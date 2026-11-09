#!/usr/bin/env python3
"""Regression tests for safe content database generation."""
import json
import sys
import tempfile
import unittest
import hashlib
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_database


class BuildDatabaseSafetyTests(unittest.TestCase):
    def test_source_evidence_requires_real_original_and_matching_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "content" / "evidence" / "official"
            evidence.mkdir(parents=True)
            payload = b"Captured official source bytes"
            digest = hashlib.sha256(payload).hexdigest()
            (evidence / f"{digest}.source").write_bytes(payload)
            metadata = {
                "source_key": "official",
                "requested_url": "https://example.gov/law",
                "final_url": "https://example.gov/law",
                "retrieved_at": "2026-11-09T12:00:00Z",
                "byte_length": len(payload),
                "sha256": digest,
                "evidence_file": f"{digest}.source",
                "legal_text_extraction_performed": False
            }
            (evidence / f"{digest}.json").write_text(json.dumps(metadata), encoding="utf-8")
            source = {
                "key": "official",
                "sha256": digest,
                "official_url": metadata["requested_url"],
                "retrieved_at": metadata["retrieved_at"]
            }
            registry = {"url": "https://example.gov/"}
            with patch.object(build_database, "ROOT", root):
                self.assertEqual(build_database.validate_source_evidence(source, registry), digest)
                (evidence / f"{digest}.source").write_bytes(b"tampered")
                with self.assertRaisesRegex(ValueError, "does not match its SHA-256"):
                    build_database.validate_source_evidence(source, registry)

    def test_source_evidence_rejects_missing_capture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = {
                "key": "official",
                "sha256": hashlib.sha256(b"source").hexdigest(),
                "official_url": "https://example.gov/law",
                "retrieved_at": "2026-11-09T12:00:00Z"
            }
            with patch.object(build_database, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "captured original or metadata is missing"):
                    build_database.validate_source_evidence(source, {"url": "https://example.gov/"})

    def test_boolean_is_not_a_valid_release_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            records = root / "records.json"
            records.write_text(json.dumps({
                "release": {
                    "version": "test",
                    "created_at": "2026-11-09T00:00:00Z",
                    "effective_as_of": "2026-11-09",
                    "version_code": True
                },
                "sources": [],
                "documents": [{"id": "doc"}]
            }), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "version_code must be an integer"):
                build_database.build(records, root / "output")

    def test_failed_validation_preserves_previous_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            records = root / "records.json"
            output = root / "output"
            output.mkdir()
            database = output / "zakon-content.sqlite"
            previous_release = b"previous-valid-release"
            database.write_bytes(previous_release)

            records.write_text(
                json.dumps({
                    "release": {
                        "version": "test-1",
                        "created_at": "2026-11-06T00:00:00Z",
                        "effective_as_of": "2026-11-06",
                        "version_code": 7
                    },
                    "sources": [],
                    "documents": [{
                        "id": "test-document",
                        "title": "Тестовый документ",
                        "primary_source_key": "missing-source"
                    }]
                }, ensure_ascii=False),
                encoding="utf-8"
            )

            with self.assertRaisesRegex(ValueError, "primary source is missing"):
                build_database.build(records, output)

            self.assertEqual(
                database.read_bytes(),
                previous_release,
                "A failed build must not overwrite the previous content database"
            )


if __name__ == "__main__":
    unittest.main()
