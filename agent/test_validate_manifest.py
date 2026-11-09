#!/usr/bin/env python3
"""Regression tests for approved content release manifests."""
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

VALIDATOR = Path(__file__).resolve().parent / "validate_manifest.py"


class ManifestValidationTests(unittest.TestCase):
    def _fixture(self, root: Path):
        database = root / "zakon-content.sqlite"
        with sqlite3.connect(database) as db:
            db.executescript("""
                CREATE TABLE documents(id TEXT PRIMARY KEY, title TEXT NOT NULL);
                CREATE TABLE articles(id TEXT PRIMARY KEY, document_id TEXT, body TEXT);
                CREATE VIRTUAL TABLE articles_fts USING fts4(article_id, body);
                INSERT INTO documents VALUES('doc', 'Тестовый документ');
                INSERT INTO articles VALUES('article', 'doc', 'Тестовая статья');
            """)
        manifest = {
            "schema_version": 1,
            "version_code": 1,
            "content_version": "1.0.0",
            "created_at": "2026-11-09T00:00:00Z",
            "effective_as_of": "2026-11-09",
            "database": {
                "file": database.name,
                "sha256": hashlib.sha256(database.read_bytes()).hexdigest(),
                "download_url": "https://example.org/zakon-content.sqlite",
            },
            "validation": {"status": "validated"},
        }
        path = root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path, manifest

    def test_accepts_valid_manifest_and_sqlite(self):
        with tempfile.TemporaryDirectory() as temporary:
            path, _ = self._fixture(Path(temporary))
            result = subprocess.run(
                [sys.executable, str(VALIDATOR), str(path)],
                capture_output=True, text=True, check=False
            )
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def test_rejects_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            path, manifest = self._fixture(Path(temporary))
            manifest["database"]["sha256"] = "0" * 64
            path.write_text(json.dumps(manifest), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(VALIDATOR), str(path)],
                capture_output=True, text=True, check=False
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SHA-256", result.stderr)


if __name__ == "__main__":
    unittest.main()
