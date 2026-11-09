package ru.zakon.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class LegalDocument(
    val id: String,
    val title: String,
    val documentType: String,
    val number: String,
    val legalStatus: String,
    val verificationStatus: String
)

data class LegalArticle(
    val id: String,
    val documentId: String,
    val number: String,
    val title: String?,
    val body: String
)

data class SearchHit(
    val articleId: String,
    val documentTitle: String,
    val articleNumber: String,
    val articleTitle: String?,
    val preview: String
)

data class PackImportResult(
    val version: Int,
    val label: String,
    val documentsUpserted: Int,
    val articlesUpserted: Int
)

class LegalStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "zakon-local.db", null, 8) {

    private val appContext = context.applicationContext
    private val metaPrefs = appContext.getSharedPreferences("zakon_content_meta", Context.MODE_PRIVATE)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
        seedFromAssetsOrFallback(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS articles_fts")
            db.execSQL(
                "CREATE VIRTUAL TABLE articles_fts USING fts4(article_id UNINDEXED, document_title, article_number, article_title, body, tokenize=unicode61)"
            )
            db.execSQL(
                """INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
                SELECT a.id,d.title,a.number,COALESCE(a.title,''),a.body FROM articles a JOIN documents d ON d.id=a.document_id""".trimIndent()
            )
        }
        if (oldVersion < 7) {
            seedFromAssetsOrFallback(db)
        }
        if (oldVersion < 8) {
            db.execSQL("DELETE FROM articles WHERE document_id IN ('koap','const')")
            db.execSQL("DELETE FROM documents WHERE id IN ('koap','const')")
            seedFromAssetsOrFallback(db)
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS documents (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, document_type TEXT NOT NULL DEFAULT '',
            number TEXT NOT NULL DEFAULT '', legal_status TEXT NOT NULL DEFAULT 'не проверен',
            verification_status TEXT NOT NULL DEFAULT 'unverified')"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS articles (
            id TEXT PRIMARY KEY, document_id TEXT NOT NULL, ordinal INTEGER NOT NULL DEFAULT 0,
            number TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_articles_doc ON articles(document_id, ordinal)")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts4(article_id UNINDEXED, document_title, article_number, article_title, body, tokenize=unicode61)"
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS articles_fts_insert AFTER INSERT ON articles BEGIN
            INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
            SELECT NEW.id,d.title,NEW.number,COALESCE(NEW.title,''),NEW.body FROM documents d WHERE d.id=NEW.document_id;
        END"""
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS articles_fts_delete AFTER DELETE ON articles BEGIN
            DELETE FROM articles_fts WHERE article_id=OLD.id;
        END"""
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS articles_fts_update AFTER UPDATE ON articles BEGIN
            DELETE FROM articles_fts WHERE article_id=OLD.id;
            INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
            SELECT NEW.id,d.title,NEW.number,COALESCE(NEW.title,''),NEW.body FROM documents d WHERE d.id=NEW.document_id;
        END"""
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS documents_fts_title_update AFTER UPDATE OF title ON documents BEGIN
            DELETE FROM articles_fts WHERE article_id IN (SELECT id FROM articles WHERE document_id=NEW.id);
            INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
            SELECT a.id,NEW.title,a.number,COALESCE(a.title,''),a.body FROM articles a WHERE a.document_id=NEW.id;
        END"""
        )
    }

    private fun seedFromAssetsOrFallback(db: SQLiteDatabase) {
        val loaded = tryLoadAssetPack(db)
        SeedData.populate(db)
    }

    private fun tryLoadAssetPack(db: SQLiteDatabase): Boolean {
        return try {
            val am = appContext.assets
            val names = am.list("")?.toList().orEmpty()
            val b64Name = names.firstOrNull { it == "content_pack_v7.b64" }
            val gzName = names.firstOrNull { it == "content_pack_v7.json.gz" }
            val jsonName = names.firstOrNull { it == "content_pack_v7.json" }
            val json = when {
                b64Name != null -> {
                    val b64 = am.open(b64Name).use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                            .replace("\\s".toRegex(), "")
                    }
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    GZIPInputStream(bytes.inputStream()).use { gz ->
                        BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).readText()
                    }
                }
                gzName != null -> {
                    am.open(gzName).use { input ->
                        GZIPInputStream(input).use { gz ->
                            BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).readText()
                        }
                    }
                }
                jsonName != null -> {
                    am.open(jsonName).use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    }
                }
                else -> return false
            }
            val result = importPackJsonInto(db, json, requireNewer = false)
            result.articlesUpserted > 0
        } catch (_: Exception) {
            false
        }
    }

    fun installedPackVersion(): Int = metaPrefs.getInt("pack_version", 5)

    fun installedPackLabel(): String = metaPrefs.getString("pack_label", "встроенная") ?: "встроенная"

    private fun setInstalledPack(version: Int, label: String) {
        metaPrefs.edit().putInt("pack_version", version).putString("pack_label", label).apply()
    }

    fun getDocuments(): List<LegalDocument> = readableDatabase.rawQuery(
        "SELECT id,title,document_type,number,legal_status,verification_status FROM documents ORDER BY title", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                LegalDocument(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
            )
        }
    }

    fun getDocument(id: String): LegalDocument? = readableDatabase.rawQuery(
        "SELECT id,title,document_type,number,legal_status,verification_status FROM documents WHERE id=?", arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) null
        else LegalDocument(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
    }

    fun getArticles(documentId: String): List<LegalArticle> = readableDatabase.rawQuery(
        "SELECT id,document_id,number,title,body FROM articles WHERE document_id=? ORDER BY ordinal", arrayOf(documentId)
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                LegalArticle(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            )
        }
    }

    fun getArticle(id: String): LegalArticle? = readableDatabase.rawQuery(
        "SELECT id,document_id,number,title,body FROM articles WHERE id=?", arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) null
        else LegalArticle(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
    }

    fun search(query: String): List<SearchHit> {
        val db = readableDatabase
        try {
            return db.rawQuery(
                """SELECT a.id,d.title,a.number,a.title,substr(a.body,1,240)
                FROM articles_fts f JOIN articles a ON a.id=f.article_id JOIN documents d ON d.id=a.document_id
                WHERE articles_fts MATCH ? ORDER BY d.title,a.ordinal LIMIT 100""".trimIndent(),
                arrayOf(query)
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
                    )
                }
            }
        } catch (_: SQLiteException) {
            val pattern = "%$query%"
            return db.rawQuery(
                """SELECT a.id,d.title,a.number,a.title,substr(a.body,1,240)
                FROM articles a JOIN documents d ON d.id=a.document_id
                WHERE a.number LIKE ? OR COALESCE(a.title,'') LIKE ? OR a.body LIKE ? OR d.title LIKE ?
                ORDER BY d.title,a.ordinal LIMIT 100""".trimIndent(),
                arrayOf(pattern, pattern, pattern, pattern)
            ).use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
                    )
                }
            }
        }
    }

    fun importPackJson(json: String, requireNewer: Boolean = false): PackImportResult {
        val db = writableDatabase
        return importPackJsonInto(db, json, requireNewer)
    }

    private fun importPackJsonInto(db: SQLiteDatabase, json: String, requireNewer: Boolean): PackImportResult {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        val label = root.optString("label", "pack")
        if (requireNewer && version > 0 && version <= installedPackVersion()) {
            return PackImportResult(installedPackVersion(), installedPackLabel(), 0, 0)
        }

        val docs = root.optJSONArray("documents") ?: JSONArray()
        val arts = root.optJSONArray("articles") ?: JSONArray()
        var docCount = 0
        var artCount = 0

        db.beginTransaction()
        try {
            for (i in 0 until docs.length()) {
                val o = docs.getJSONObject(i)
                val id = o.getString("id")
                db.execSQL(
                    """INSERT OR REPLACE INTO documents(id,title,document_type,number,legal_status,verification_status)
                    VALUES(?,?,?,?,?,?)""".trimIndent(),
                    arrayOf(
                        id,
                        o.optString("title", id),
                        o.optString("document_type", "code"),
                        o.optString("number", ""),
                        o.optString("legal_status", "справочный текст"),
                        o.optString("verification_status", "pack")
                    )
                )
                docCount++
            }
            for (i in 0 until arts.length()) {
                val o = arts.getJSONObject(i)
                val id = o.getString("id")
                val documentId = o.getString("document_id")
                db.rawQuery("SELECT 1 FROM documents WHERE id=? LIMIT 1", arrayOf(documentId)).use { c ->
                    if (!c.moveToFirst()) {
                        db.execSQL(
                            "INSERT OR REPLACE INTO documents(id,title,document_type,number,legal_status,verification_status) VALUES(?,?,?,?,?,?)",
                            arrayOf(documentId, documentId, "code", "", "справочный текст", "pack")
                        )
                    }
                }
                db.execSQL(
                    """INSERT OR REPLACE INTO articles(id,document_id,ordinal,number,title,body)
                    VALUES(?,?,?,?,?,?)""".trimIndent(),
                    arrayOf(
                        id,
                        documentId,
                        o.optInt("ordinal", i),
                        o.optString("number", ""),
                        if (o.isNull("title")) null else o.optString("title"),
                        o.optString("body", "")
                    )
                )
                artCount++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (version > 0) setInstalledPack(version, label)
        return PackImportResult(version, label, docCount, artCount)
    }
}
