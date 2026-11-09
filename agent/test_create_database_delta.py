#!/usr/bin/env python3
import sqlite3
import tempfile
import unittest
from pathlib import Path

from create_database_delta import diff

SCHEMA = """
CREATE TABLE sources(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE documents(id TEXT PRIMARY KEY, title TEXT NOT NULL, source_id INTEGER NOT NULL REFERENCES sources(id));
CREATE TABLE articles(id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id), parent_id TEXT, ordinal INTEGER NOT NULL, body TEXT NOT NULL);
CREATE TABLE document_source_checks(id INTEGER PRIMARY KEY, document_id TEXT REFERENCES documents(id));
CREATE TABLE cross_references(id INTEGER PRIMARY KEY, from_article_id TEXT REFERENCES articles(id));
CREATE TABLE legal_status_history(id INTEGER PRIMARY KEY, document_id TEXT REFERENCES documents(id));
CREATE TABLE content_release(id INTEGER PRIMARY KEY, version TEXT UNIQUE);
"""


def make_db(path, title, include_second=False):
    with sqlite3.connect(path) as db:
        db.executescript(SCHEMA)
        db.execute("INSERT INTO sources VALUES(1,'Official')")
        db.execute("INSERT INTO documents VALUES('law',?,1)", (title,))
        db.execute("INSERT INTO articles VALUES('a1','law',NULL,1,'Text one')")
        if include_second:
            db.execute("INSERT INTO articles VALUES('a2','law','a1',2,'Text two')")
        db.execute("INSERT INTO content_release VALUES(1,?)", (title,))


class DeltaTests(unittest.TestCase):
    def test_delta_contains_only_changed_rows_and_versions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old")
            make_db(new, "New", include_second=True)
            patch = diff(old, new, 7, 8)
            self.assertEqual(patch["from_version_code"], 7)
            self.assertEqual(patch["to_version_code"], 8)
            self.assertEqual(patch["operations"]["documents"]["upsert"][0]["title"], "New")
            self.assertEqual([r["id"] for r in patch["operations"]["articles"]["upsert"]], ["a2"])
            self.assertNotIn("sources", patch["operations"])

    def test_rejects_non_increasing_versions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old")
            make_db(new, "New")
            with self.assertRaises(ValueError):
                diff(old, new, 8, 8)

    def test_rejects_schema_change_and_requires_full_database(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old")
            make_db(new, "New")
            with sqlite3.connect(new) as db:
                db.execute("ALTER TABLE articles ADD COLUMN new_field TEXT")
            with self.assertRaisesRegex(ValueError, "different SQLite schemas"):
                diff(old, new, 7, 8)

    def test_rejects_foreign_key_corruption(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old, new = root / "old.db", root / "new.db"
            make_db(old, "Old")
            make_db(new, "New")
            with sqlite3.connect(new) as db:
                db.execute("INSERT INTO articles VALUES('broken','missing',NULL,2,'bad')")
            with self.assertRaisesRegex(ValueError, "foreign-key violations"):
                diff(old, new, 7, 8)


if __name__ == "__main__":
    unittest.main()
