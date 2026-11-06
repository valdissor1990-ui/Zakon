package ru.zakon.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException

data class LegalDocument(val id: String, val title: String, val documentType: String, val number: String, val legalStatus: String, val verificationStatus: String)
data class LegalArticle(val id: String, val documentId: String, val number: String, val title: String?, val body: String)
data class SearchHit(val articleId: String, val documentTitle: String, val articleNumber: String, val articleTitle: String?, val preview: String)

class LegalStore(context: Context) : SQLiteOpenHelper(context, "zakon-local.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE documents (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, document_type TEXT NOT NULL DEFAULT '',
            number TEXT NOT NULL DEFAULT '', legal_status TEXT NOT NULL DEFAULT 'не проверен',
            verification_status TEXT NOT NULL DEFAULT 'unverified')""")
        db.execSQL("""CREATE TABLE articles (
            id TEXT PRIMARY KEY, document_id TEXT NOT NULL, ordinal INTEGER NOT NULL DEFAULT 0,
            number TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX idx_articles_doc ON articles(document_id, ordinal)")
        db.execSQL("CREATE VIRTUAL TABLE articles_fts USING fts4(article_id UNINDEXED, document_title, article_number, article_title, body, tokenize=unicode61)")
        db.execSQL("""CREATE TRIGGER articles_fts_insert AFTER INSERT ON articles BEGIN
            INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
            SELECT NEW.id,d.title,NEW.number,COALESCE(NEW.title,''),NEW.body FROM documents d WHERE d.id=NEW.document_id;
        END""")
        db.execSQL("""CREATE TRIGGER articles_fts_delete AFTER DELETE ON articles BEGIN
            DELETE FROM articles_fts WHERE article_id=OLD.id;
        END""")
        db.execSQL("""CREATE TRIGGER articles_fts_update AFTER UPDATE ON articles BEGIN
            DELETE FROM articles_fts WHERE article_id=OLD.id;
            INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
            SELECT NEW.id,d.title,NEW.number,COALESCE(NEW.title,''),NEW.body FROM documents d WHERE d.id=NEW.document_id;
        END""")
        db.execSQL("""CREATE TRIGGER documents_fts_title_update AFTER UPDATE OF title ON documents BEGIN
            DELETE FROM articles_fts WHERE article_id IN (SELECT id FROM articles WHERE document_id=NEW.id);
            INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
            SELECT a.id,NEW.title,a.number,COALESCE(a.title,''),a.body FROM articles a WHERE a.document_id=NEW.id;
        END""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS articles_fts")
            db.execSQL("CREATE VIRTUAL TABLE articles_fts USING fts4(article_id UNINDEXED, document_title, article_number, article_title, body, tokenize=unicode61)")
            db.execSQL("""
                INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body)
                SELECT a.id,d.title,a.number,COALESCE(a.title,''),a.body
                FROM articles a JOIN documents d ON d.id=a.document_id
            """.trimIndent())
        }
    }
    fun getDocuments(): List<LegalDocument> = readableDatabase.rawQuery(
        "SELECT id,title,document_type,number,legal_status,verification_status FROM documents ORDER BY title", null
    ).use { c -> buildList {
        while (c.moveToNext()) add(LegalDocument(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)))
    } }
    fun getDocument(id: String): LegalDocument? = readableDatabase.rawQuery(
        "SELECT id,title,document_type,number,legal_status,verification_status FROM documents WHERE id=?", arrayOf(id)
    ).use { c -> if (!c.moveToFirst()) null else LegalDocument(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)) }
    fun getArticles(documentId: String): List<LegalArticle> = readableDatabase.rawQuery(
        "SELECT id,document_id,number,title,body FROM articles WHERE document_id=? ORDER BY ordinal", arrayOf(documentId)
    ).use { c -> buildList {
        while (c.moveToNext()) add(LegalArticle(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)))
    } }
    fun getArticle(id: String): LegalArticle? = readableDatabase.rawQuery(
        "SELECT id,document_id,number,title,body FROM articles WHERE id=?", arrayOf(id)
    ).use { c -> if (!c.moveToFirst()) null else LegalArticle(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)) }
    fun search(query: String): List<SearchHit> {
        val db = readableDatabase
        try {
            return db.rawQuery("""
                SELECT a.id,d.title,a.number,a.title,substr(a.body,1,240)
                FROM articles_fts f
                JOIN articles a ON a.id=f.article_id
                JOIN documents d ON d.id=a.document_id
                WHERE articles_fts MATCH ?
                ORDER BY d.title,a.ordinal LIMIT 100
            """.trimIndent(), arrayOf(query)).use { c -> buildList {
                while (c.moveToNext()) add(SearchHit(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)))
            } }
        } catch (_: SQLiteException) {
            val pattern = "%$query%"
            return db.rawQuery("""
                SELECT a.id,d.title,a.number,a.title,substr(a.body,1,240)
                FROM articles a JOIN documents d ON d.id=a.document_id
                WHERE a.number LIKE ? OR COALESCE(a.title,'') LIKE ? OR a.body LIKE ? OR d.title LIKE ?
                ORDER BY d.title,a.ordinal LIMIT 100
            """.trimIndent(), arrayOf(pattern,pattern,pattern,pattern)).use { c -> buildList {
                while (c.moveToNext()) add(SearchHit(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)))
            } }
        }
    }
}
