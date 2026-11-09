#!/usr/bin/env python3
"""Regression tests for safe content database generation."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_database


class BuildDatabaseSafetyTests(unittest.TestCase):
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
