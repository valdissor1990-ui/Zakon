package ru.zakon.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

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

class LegalStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "zakon-content.db", null, 9) {

    private val appContext = context.applicationContext
    private val metaPrefs = appContext.getSharedPreferences("zakon_content_meta", Context.MODE_PRIVATE)

    init {
        // One-time, non-destructive migration for users upgrading from the legacy combined DB.
        val target = appContext.getDatabasePath("zakon-content.db")
        val legacy = appContext.getDatabasePath("zakon-local.db")
        if (!target.exists() && legacy.isFile) {
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = false)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
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
        // Schema upgrades must never seed or overwrite legal content.
        // Content is delivered independently as a verified SQLite release.
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

    fun installedPackVersion(): Int = metaPrefs.getInt("pack_version", 0)

    fun installedPackLabel(): String = metaPrefs.getString("pack_label", "не загружена") ?: "не загружена"

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

    /**
     * Atomically installs a prebuilt content SQLite database.
     * The caller must verify the downloaded SHA-256 before calling this method.
     */
    fun installDatabaseFile(source: java.io.File, version: Int, label: String): Int {
        require(source.isFile && source.length() > 0L) { "Файл базы пуст или отсутствует" }
        validateContentDatabase(source)

        val target = appContext.getDatabasePath("zakon-content.db")
        val staged = java.io.File(target.parentFile, "zakon-content.db.staged")
        val backup = java.io.File(target.parentFile, "zakon-content.db.backup")
        target.parentFile?.mkdirs()
        source.copyTo(staged, overwrite = true)
        close()
        try {
            java.io.File(target.path + "-wal").delete()
            java.io.File(target.path + "-shm").delete()
            backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                error("Не удалось сохранить предыдущую базу")
            }
            if (!staged.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Не удалось установить новую базу")
            }
            validateContentDatabase(target)
            metaPrefs.edit().putInt("pack_version", version)
                .putString("pack_label", label).commit()
            backup.delete()
            return version
        } catch (failure: Exception) {
            target.delete()
            if (backup.exists()) backup.renameTo(target)
            throw failure
        } finally {
            staged.delete()
        }
    }

    private fun validateContentDatabase(file: java.io.File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else "no result"
            }
            require(integrity == "ok") { "Проверка целостности SQLite не пройдена: $integrity" }
            for (table in listOf("documents", "articles", "articles_fts")) {
                db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name=?",
                    arrayOf(table)
                ).use { c -> require(c.moveToFirst()) { "В базе отсутствует таблица $table" } }
            }
            val requiredDocumentColumns = setOf("id", "title", "document_type", "number", "legal_status", "verification_status")
            val actualDocumentColumns = db.rawQuery("PRAGMA table_info(documents)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            require(actualDocumentColumns.containsAll(requiredDocumentColumns)) {
                "Несовместимая структура таблицы documents"
            }
            val requiredArticleColumns = setOf("id", "document_id", "ordinal", "number", "title", "body")
            val actualArticleColumns = db.rawQuery("PRAGMA table_info(articles)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            require(actualArticleColumns.containsAll(requiredArticleColumns)) {
                "Несовместимая структура таблицы articles"
            }
        } finally {
            db.close()
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
                val values = arrayOf(
                    o.optString("title", id),
                    o.optString("document_type", "code"),
                    o.optString("number", ""),
                    o.optString("legal_status", "справочный текст"),
                    o.optString("verification_status", "pack")
                )
                val changed = db.update(
                    "documents",
                    android.content.ContentValues().apply {
                        put("title", values[0]); put("document_type", values[1])
                        put("number", values[2]); put("legal_status", values[3])
                        put("verification_status", values[4])
                    },
                    "id=?",
                    arrayOf(id)
                )
                if (changed == 0) db.execSQL(
                    "INSERT INTO documents(id,title,document_type,number,legal_status,verification_status) VALUES(?,?,?,?,?,?)",
                    arrayOf(id, *values)
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
                val articleValues = android.content.ContentValues().apply {
                    put("document_id", documentId)
                    put("ordinal", o.optInt("ordinal", i))
                    put("number", o.optString("number", ""))
                    if (o.isNull("title")) putNull("title") else put("title", o.optString("title"))
                    put("body", o.optString("body", ""))
                }
                val changed = db.update("articles", articleValues, "id=?", arrayOf(id))
                if (changed == 0) {
                    articleValues.put("id", id)
                    db.insertOrThrow("articles", null, articleValues)
                }
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
