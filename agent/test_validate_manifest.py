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
                CREATE TABLE content_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE VIRTUAL TABLE articles_fts USING fts4(article_id, body);
                INSERT INTO content_metadata VALUES('pack_version', '7');
                INSERT INTO content_metadata VALUES('pack_label', '1.0.0');
                INSERT INTO documents VALUES('doc', 'Тестовый документ');
                INSERT INTO articles VALUES('article', 'doc', 'Тестовая статья');
            """)
        private_key = root / "test-private.pem"
        public_key = root / "test-public.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(private_key)],
            check=True, capture_output=True
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
            check=True, capture_output=True
        )
        manifest = {
            "schema_version": 1,
            "version_code": 7,
            "content_version": "1.0.0",
            "created_at": "2026-11-09T00:00:00Z",
            "effective_as_of": "2026-11-09",
            "database": {
                "file": database.name,
                "sha256": hashlib.sha256(database.read_bytes()).hexdigest(),
                "download_url": "https://example.org/zakon-content.sqlite",
            },
            "signature": {
                "algorithm": "SHA256withRSA",
                "file": "manifest.sig",
                "download_url": "https://example.org/manifest.sig",
            },
            "validation": {"status": "validated"},
        }
        path = root / "manifest.json"
        self._write_and_sign(path, manifest, private_key)
        return path, manifest, public_key, private_key

    def _write_and_sign(self, path, manifest, private_key):
        path.write_text(json.dumps(manifest), encoding="utf-8")
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(private_key),
             "-out", str(path.parent / "manifest.sig"), str(path)],
            check=True, capture_output=True
        )

    def _validate(self, path, public_key):
        return subprocess.run(
            [sys.executable, str(VALIDATOR), str(path), str(public_key)],
            capture_output=True, text=True, check=False
        )

    def test_accepts_valid_manifest_and_sqlite(self):
        with tempfile.TemporaryDirectory() as temporary:
            path, _, public_key, _ = self._fixture(Path(temporary))
            result = self._validate(path, public_key)
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def test_accepts_version_bound_delta(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, manifest, public_key, private_key = self._fixture(root)
            patch = {"schema_version": 1, "from_version_code": 7, "to_version_code": 8, "operations": {}}
            delta_path = root / "zakon-content.delta.json"
            delta_path.write_text(json.dumps(patch), encoding="utf-8")
            manifest["version_code"] = 8
            with sqlite3.connect(root / "zakon-content.sqlite") as db:
                db.execute("UPDATE content_metadata SET value='8' WHERE key='pack_version'")
            manifest["database"]["sha256"] = hashlib.sha256((root / "zakon-content.sqlite").read_bytes()).hexdigest()
            manifest["delta"] = {
                "file": delta_path.name,
                "sha256": hashlib.sha256(delta_path.read_bytes()).hexdigest(),
                "from_version_code": 7,
                "to_version_code": 8,
                "download_url": "https://example.org/zakon-content.delta.json"
            }
            self._write_and_sign(path, manifest, private_key)
            result = self._validate(path, public_key)
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def test_rejects_delta_for_wrong_target_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, manifest, public_key, private_key = self._fixture(root)
            patch = {"schema_version": 1, "from_version_code": 7, "to_version_code": 8, "operations": {}}
            delta_path = root / "zakon-content.delta.json"
            delta_path.write_text(json.dumps(patch), encoding="utf-8")
            manifest["version_code"] = 8
            manifest["delta"] = {
                "file": delta_path.name,
                "sha256": hashlib.sha256(delta_path.read_bytes()).hexdigest(),
                "from_version_code": 7,
                "to_version_code": 7,
                "download_url": "https://example.org/zakon-content.delta.json"
            }
            self._write_and_sign(path, manifest, private_key)
            result = self._validate(path, public_key)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("target version", result.stderr)

    def test_rejects_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, manifest, public_key, private_key = self._fixture(root)
            manifest["database"]["sha256"] = "0" * 64
            self._write_and_sign(path, manifest, private_key)
            result = self._validate(path, public_key)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SHA-256", result.stderr)

    def test_rejects_tampered_manifest_signature(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, manifest, public_key, _ = self._fixture(root)
            manifest["content_version"] = "tampered"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            result = self._validate(path, public_key)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("signature", (result.stderr + result.stdout).lower())


if __name__ == "__main__":
    unittest.main()
