package ru.zakon.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegalStoreMigrationTest {
    private val context by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }
    private lateinit var legacyFile: File
    private lateinit var contentFile: File

    @Before
    fun prepare() {
        context.deleteDatabase("zakon-local.db")
        context.deleteDatabase("zakon-content.db")
        context.deleteSharedPreferences("zakon_content_meta")
        legacyFile = context.getDatabasePath("zakon-local.db")
        contentFile = context.getDatabasePath("zakon-content.db")
        legacyFile.parentFile?.mkdirs()
        createLegacyVersionEightDatabase(legacyFile)
    }

    @After
    fun cleanup() {
        context.deleteDatabase("zakon-local.db")
        context.deleteDatabase("zakon-content.db")
        context.deleteSharedPreferences("zakon_content_meta")
    }

    @Test
    fun legacyDatabaseIsCopiedUpgradedAndPreserved() {
        val store = LegalStore(context)
        try {
            val documents = store.getDocuments()
            assertEquals(1, documents.size)
            assertEquals("legacy-doc", documents.single().id)
            assertEquals("Сохранённый документ", documents.single().title)

            val articles = store.getArticles("legacy-doc")
            assertEquals(1, articles.size)
            assertEquals("legacy-article", articles.single().id)
            assertEquals("Текст старой статьи должен сохраниться", articles.single().body)

            assertTrue("New content DB must exist", contentFile.isFile)
            assertTrue("Legacy DB must not be deleted", legacyFile.isFile)
            assertEquals(8, SQLiteDatabase.openDatabase(
                legacyFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { it.version })
            assertEquals(10, SQLiteDatabase.openDatabase(
                contentFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { it.version })

            val searchHits = store.search("старой")
            assertTrue("FTS content must remain searchable after migration", searchHits.any {
                it.articleId == "legacy-article"
            })
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidReplacementDoesNotDamageMigratedDatabaseOrLegacyCopy() {
        val store = LegalStore(context)
        val invalid = File(context.cacheDir, "invalid-legal-content.sqlite")
        try {
            assertNotNull(store.getDocument("legacy-doc"))
            invalid.writeText("not a sqlite database")
            var rejected = false
            try {
                store.installDatabaseFile(invalid, 7, "invalid-test")
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Invalid SQLite replacement must be rejected", rejected)
            assertNotNull("Current content DB must remain readable", store.getDocument("legacy-doc"))
            assertTrue("Legacy DB must remain available for recovery", legacyFile.isFile)
            assertTrue("Current DB must remain available", contentFile.isFile)
        } finally {
            invalid.delete()
            store.close()
        }
    }

    private fun createLegacyVersionEightDatabase(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                """CREATE TABLE documents (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    document_type TEXT NOT NULL DEFAULT '',
                    number TEXT NOT NULL DEFAULT '',
                    legal_status TEXT NOT NULL DEFAULT 'не проверен',
                    verification_status TEXT NOT NULL DEFAULT 'unverified'
                )"""
            )
            db.execSQL(
                """CREATE TABLE articles (
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL DEFAULT 0,
                    number TEXT NOT NULL,
                    title TEXT,
                    body TEXT NOT NULL,
                    FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
                )"""
            )
            db.execSQL(
                """CREATE VIRTUAL TABLE articles_fts USING fts4(
                    article_id UNINDEXED, document_title, article_number,
                    article_title, body, tokenize=unicode61
                )"""
            )
            db.execSQL(
                "INSERT INTO documents(id,title,document_type,number,legal_status,verification_status) VALUES(?,?,?,?,?,?)",
                arrayOf("legacy-doc", "Сохранённый документ", "code", "1", "действует", "approved")
            )
            db.execSQL(
                "INSERT INTO articles(id,document_id,ordinal,number,title,body) VALUES(?,?,?,?,?,?)",
                arrayOf("legacy-article", "legacy-doc", 1, "1", "Старая статья", "Текст старой статьи должен сохраниться")
            )
            db.execSQL(
                "INSERT INTO articles_fts(article_id,document_title,article_number,article_title,body) VALUES(?,?,?,?,?)",
                arrayOf("legacy-article", "Сохранённый документ", "1", "Старая статья", "Текст старой статьи должен сохраниться")
            )
            db.version = 8
        } finally {
            db.close()
        }
    }
}
