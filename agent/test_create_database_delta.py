#!/usr/bin/env python3
import sqlite3
import tempfile
import unittest
from pathlib import Path

from create_database_delta import TABLES, diff

SCHEMA = """
CREATE TABLE content_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE sources(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE documents(id TEXT PRIMARY KEY, title TEXT NOT NULL, source_id INTEGER NOT NULL REFERENCES sources(id));
CREATE TABLE articles(id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id), parent_id TEXT REFERENCES articles(id), ordinal INTEGER NOT NULL, body TEXT NOT NULL);
CREATE TABLE document_source_checks(id INTEGER PRIMARY KEY, document_id TEXT REFERENCES documents(id));
CREATE TABLE cross_references(id INTEGER PRIMARY KEY, from_article_id TEXT REFERENCES articles(id));
CREATE TABLE legal_status_history(id INTEGER PRIMARY KEY, document_id TEXT REFERENCES documents(id));
CREATE TABLE content_release(id INTEGER PRIMARY KEY, version TEXT UNIQUE);
CREATE VIRTUAL TABLE articles_fts USING fts4(article_id UNINDEXED, body);
CREATE TRIGGER articles_fts_insert AFTER INSERT ON articles BEGIN
    INSERT INTO articles_fts(article_id, body) VALUES(NEW.id, NEW.body);
END;
CREATE TRIGGER articles_fts_delete AFTER DELETE ON articles BEGIN
    DELETE FROM articles_fts WHERE article_id=OLD.id;
END;
CREATE TRIGGER articles_fts_update AFTER UPDATE ON articles BEGIN
    DELETE FROM articles_fts WHERE article_id=OLD.id;
    INSERT INTO articles_fts(article_id, body) VALUES(NEW.id, NEW.body);
END;
"""


def make_db(path, title, version, include_second=False):
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA foreign_keys=ON")
        db.executescript(SCHEMA)
        db.execute("INSERT INTO content_metadata VALUES('pack_version',?)", (str(version),))
        db.execute("INSERT INTO content_metadata VALUES('pack_label',?)", (title,))
        db.execute("INSERT INTO sources VALUES(1,'Official')")
        db.execute("INSERT INTO documents VALUES('law',?,1)", (title,))
        db.execute("INSERT INTO articles VALUES('a1','law',NULL,1,'Text one')")
        if include_second:
            db.execute("INSERT INTO articles VALUES('a2','law','a1',2,'Text two')")
        db.execute("INSERT INTO content_release VALUES(1,?)", (title,))


def apply_patch(db, patch):
    delete_order = (
        "cross_references", "document_source_checks", "legal_status_history",
        "articles", "documents", "sources", "content_release"
    )
    upsert_order = (
        "sources", "documents", "articles", "document_source_checks",
        "cross_references", "legal_status_history", "content_release"
    )
    db.execute("PRAGMA foreign_keys=OFF")
    with db:
        for table in delete_order:
            change = patch["operations"].get(table, {})
            for key in change.get("delete", []):
                db.execute(f"DELETE FROM {table} WHERE id=?", key)
        for table in upsert_order:
            change = patch["operations"].get(table, {})
            for row in change.get("upsert", []):
                columns = list(row)
                values = [row[column] for column in columns]
                placeholders = ",".join("?" for _ in columns)
                db.execute(
                    f"INSERT INTO {table}({','.join(columns)}) VALUES({placeholders}) "
                    f"ON CONFLICT(id) DO UPDATE SET " +
                    ",".join(f"{column}=excluded.{column}" for column in columns if column != "id"),
                    values,
                )
        db.execute("UPDATE content_metadata SET value=? WHERE key='pack_version'",
                   (str(patch["to_version_code"]),))
        db.execute("PRAGMA foreign_key_check")


def rows(db, table):
    return db.execute(f"SELECT * FROM {table} ORDER BY 1").fetchall()


class DeltaTests(unittest.TestCase):
    def test_delta_contains_only_changed_rows_and_versions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old", 7)
            make_db(new, "New", 8, include_second=True)
            patch = diff(old, new, 7, 8)
            self.assertEqual(patch["from_version_code"], 7)
            self.assertEqual(patch["to_version_code"], 8)
            self.assertEqual(patch["operations"]["documents"]["upsert"][0]["title"], "New")
            self.assertEqual([r["id"] for r in patch["operations"]["articles"]["upsert"]], ["a2"])
            self.assertNotIn("sources", patch["operations"])

    def test_delta_round_trip_reproduces_target_rows(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old", 7, include_second=True)
            make_db(new, "New", 8)
            with sqlite3.connect(old) as db:
                db.execute("INSERT INTO articles VALUES('a3','law','a2',3,'Text three')")
                db.execute("INSERT INTO cross_references VALUES(1,'a3')")
                db.execute("INSERT INTO document_source_checks VALUES(1,'law')")
                db.execute("INSERT INTO legal_status_history VALUES(1,'law')")
            with sqlite3.connect(new) as db:
                db.execute("INSERT INTO cross_references VALUES(1,'a1')")
                db.execute("INSERT INTO document_source_checks VALUES(1,'law')")
                db.execute("INSERT INTO legal_status_history VALUES(1,'law')")
            patch = diff(old, new, 7, 8)
            with sqlite3.connect(old) as actual, sqlite3.connect(new) as expected:
                apply_patch(actual, patch)
                for table in TABLES:
                    self.assertEqual(rows(actual, table), rows(expected, table), table)
                self.assertEqual(
                    dict(actual.execute("SELECT key,value FROM content_metadata")),
                    dict(expected.execute("SELECT key,value FROM content_metadata"))
                    | {"pack_version": "8"},
                )
                self.assertEqual(
                    actual.execute("SELECT article_id,body FROM articles_fts ORDER BY article_id").fetchall(),
                    expected.execute("SELECT article_id,body FROM articles_fts ORDER BY article_id").fetchall(),
                )
                self.assertEqual(actual.execute("PRAGMA foreign_key_check").fetchall(), [])

    def test_rejects_non_increasing_versions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old", 8)
            make_db(new, "New", 8)
            with self.assertRaises(ValueError):
                diff(old, new, 8, 8)

    def test_rejects_database_metadata_version_mismatch(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old", 6)
            make_db(new, "New", 8)
            with self.assertRaisesRegex(ValueError, "metadata version"):
                diff(old, new, 7, 8)

    def test_rejects_schema_change_and_requires_full_database(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old", 7)
            make_db(new, "New", 8)
            with sqlite3.connect(new) as db:
                db.execute("ALTER TABLE articles ADD COLUMN new_field TEXT")
            with self.assertRaisesRegex(ValueError, "different SQLite schemas"):
                diff(old, new, 7, 8)

    def test_rejects_foreign_key_corruption(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old", 7)
            make_db(new, "New", 8)
            with sqlite3.connect(new) as db:
                db.execute("PRAGMA foreign_keys=OFF")
                db.execute("INSERT INTO articles VALUES('broken','missing',NULL,2,'bad')")
            with self.assertRaisesRegex(ValueError, "foreign-key violations"):
                diff(old, new, 7, 8)


if __name__ == "__main__":
    unittest.main()
