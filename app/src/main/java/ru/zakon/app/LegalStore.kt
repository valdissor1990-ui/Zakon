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

class LegalStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "zakon-content.db", null, 10) {

    private val appContext = context.applicationContext
    private val metaPrefs = appContext.getSharedPreferences("zakon_content_meta", Context.MODE_PRIVATE)

    init {
        // One-time, non-destructive migration for users upgrading from the legacy combined DB.
        // Checkpoint WAL first: copying only the main SQLite file could otherwise omit committed
        // rows that still reside in the legacy -wal sidecar.
        val target = appContext.getDatabasePath("zakon-content.db")
        val legacy = appContext.getDatabasePath("zakon-local.db")
        if (!target.exists() && legacy.isFile) {
            val parent = requireNotNull(target.parentFile) { "Не удалось определить каталог базы данных" }
            parent.mkdirs()

            val legacyDb = SQLiteDatabase.openDatabase(
                legacy.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            try {
                legacyDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        require(cursor.getInt(0) == 0) {
                            "Не удалось безопасно завершить запись старой базы; миграция отложена"
                        }
                    }
                }
            } finally {
                legacyDb.close()
            }

            val staged = java.io.File(parent, "zakon-content.db.migration")
            try {
                legacy.copyTo(staged, overwrite = true)
                val stagedDb = SQLiteDatabase.openDatabase(
                    staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                )
                try {
                    stagedDb.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                            "Старая база повреждена; исходный файл сохранён, миграция не выполнена"
                        }
                    }
                } finally {
                    stagedDb.close()
                }
                require(staged.renameTo(target)) { "Не удалось завершить перенос старой базы" }
            } finally {
                staged.delete()
            }
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
        if (oldVersion < 10) {
            db.execSQL("CREATE TABLE IF NOT EXISTS content_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        // Schema upgrades must never seed or overwrite legal content.
        // Content is delivered independently as a verified SQLite release.
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS content_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
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

    // The database is the source of truth: version and legal rows move atomically.
    // SharedPreferences remains a compatibility fallback for databases created by older builds.
    fun installedPackVersion(): Int = readContentMetadata("pack_version")?.toIntOrNull()
        ?: metaPrefs.getInt("pack_version", 0)

    fun installedPackLabel(): String = readContentMetadata("pack_label")
        ?: (metaPrefs.getString("pack_label", "не загружена") ?: "не загружена")

    private fun readContentMetadata(key: String): String? = try {
        readableDatabase.rawQuery("SELECT value FROM content_metadata WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: SQLiteException) { null }

    private fun writeContentMetadata(db: SQLiteDatabase, version: Int, label: String) {
        db.execSQL("INSERT OR REPLACE INTO content_metadata(key,value) VALUES('pack_version',?)", arrayOf(version.toString()))
        db.execSQL("INSERT OR REPLACE INTO content_metadata(key,value) VALUES('pack_label',?)", arrayOf(label))
    }

    private fun setInstalledPack(version: Int, label: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeContentMetadata(db, version, label)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
        // Stamp release metadata before the staged database can replace the active file.
        val stagedDb = SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            stagedDb.beginTransaction()
            try {
                stagedDb.execSQL("CREATE TABLE IF NOT EXISTS content_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                writeContentMetadata(stagedDb, version, label)
                stagedDb.setTransactionSuccessful()
            } finally { stagedDb.endTransaction() }
        } finally { stagedDb.close() }
        validateContentDatabase(staged)
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
            val requiredTables = listOf(
                "sources", "documents", "articles", "articles_fts",
                "document_source_checks", "cross_references",
                "legal_status_history", "content_release", "content_metadata"
            )
            for (table in requiredTables) {
                db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name=?",
                    arrayOf(table)
                ).use { c -> require(c.moveToFirst()) { "В базе отсутствует таблица $table" } }
            }
            db.rawQuery("PRAGMA foreign_key_check", null).use { c ->
                require(!c.moveToFirst()) { "В базе нарушены связи между правовыми документами" }
            }
            db.rawQuery("SELECT 1 FROM content_release WHERE status='validated' LIMIT 1", null).use { c ->
                require(c.moveToFirst()) { "В базе нет проверенного выпуска правового контента" }
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

    /**
     * Applies a signed/hashed version-bound row delta in one transaction.
     * The caller verifies the downloaded patch checksum/signature before invoking this method.
     */
    fun applyDatabaseDelta(json: String, label: String, expectedVersion: Int): Int {
        val root = org.json.JSONObject(json)
        require(root.optInt("schema_version", 0) == 1) { "Неподдерживаемый формат обновления базы" }
        val fromVersion = root.getInt("from_version_code")
        val toVersion = root.getInt("to_version_code")
        require(fromVersion == installedPackVersion()) {
            "Обновление рассчитано для базы v$fromVersion, установлена v${installedPackVersion()}. Нужна полная база."
        }
        require(toVersion > fromVersion) { "Версия обновления должна быть новее установленной" }
        require(toVersion == expectedVersion) { "Пакет обновления не соответствует версии из манифеста" }

        val tables = linkedMapOf(
            "sources" to "id",
            "documents" to "id",
            "articles" to "id",
            "document_source_checks" to "id",
            "cross_references" to "id",
            "legal_status_history" to "id",
            "content_release" to "id"
        )
        val deleteOrder = listOf(
            "cross_references", "document_source_checks", "legal_status_history",
            "articles", "documents", "sources", "content_release"
        )
        val upsertOrder = listOf(
            "sources", "documents", "articles", "document_source_checks",
            "cross_references", "legal_status_history", "content_release"
        )
        val operations = root.getJSONObject("operations")
        val operationKeys = operations.keys()
        while (operationKeys.hasNext()) {
            require(tables.containsKey(operationKeys.next())) { "Обновление содержит неизвестную таблицу" }
        }
        val db = writableDatabase

        // A delta is accepted only for the complete content schema, not the legacy lightweight DB.
        for (table in tables.keys + "content_metadata") {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Установленная база не поддерживает обновления-дельты; нужна полная база." }
            }
        }

        // Defer FK enforcement during row changes, then validate the final relational graph.
        db.setForeignKeyConstraintsEnabled(false)
        try {
            db.beginTransaction()
            try {
                for (table in deleteOrder) {
                    val operation = operations.optJSONObject(table) ?: continue
                    val deletes = operation.optJSONArray("delete") ?: org.json.JSONArray()
                    for (index in 0 until deletes.length()) {
                        val key = deletes.getJSONArray(index)
                        require(key.length() == 1 && !key.isNull(0)) { "Некорректный ключ удаления в таблице $table" }
                        db.delete(table, "${tables.getValue(table)}=?", arrayOf(key.get(0).toString()))
                    }
                }

                for (table in upsertOrder) {
                    val operation = operations.optJSONObject(table) ?: continue
                    val rows = operation.optJSONArray("upsert") ?: org.json.JSONArray()
                    for (index in 0 until rows.length()) {
                        val row = rows.getJSONObject(index)
                        val values = android.content.ContentValues()
                        val iterator = row.keys()
                        while (iterator.hasNext()) {
                            val column = iterator.next()
                            when (val value = row.get(column)) {
                                org.json.JSONObject.NULL -> values.putNull(column)
                                is String -> values.put(column, value)
                                is Int -> values.put(column, value)
                                is Long -> values.put(column, value)
                                is Double -> values.put(column, value)
                                is Float -> values.put(column, value)
                                is Boolean -> values.put(column, if (value) 1 else 0)
                                else -> error("Недопустимый тип значения $column в таблице $table")
                            }
                        }
                        val primaryKey = tables.getValue(table)
                        require(row.has(primaryKey) && !row.isNull(primaryKey)) {
                            "В обновлении отсутствует ключ $primaryKey для таблицы $table"
                        }
                        val keyValue = row.get(primaryKey).toString()
                        val updated = db.update(table, values, "$primaryKey=?", arrayOf(keyValue))
                        if (updated == 0) db.insertOrThrow(table, null, values)
                    }
                }

                // Commit content rows and their version marker in the same SQLite transaction.
                writeContentMetadata(db, toVersion, label)
                db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    require(!cursor.moveToFirst()) {
                        "Обновление нарушает связи между правовыми документами"
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }

        metaPrefs.edit().putInt("pack_version", toVersion).putString("pack_label", label).apply()
        return toVersion
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
            if (version > 0) writeContentMetadata(db, version, label)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (version > 0) metaPrefs.edit().putInt("pack_version", version).putString("pack_label", label).apply()
        return PackImportResult(version, label, docCount, artCount)
    }
}
