#!/usr/bin/env python3
"""Regression tests for safe content database generation."""
import json
import sqlite3
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
                "registry_id": "official",
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

    def test_capture_key_can_be_distinct_from_registry_source_id(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "content" / "evidence" / "constitution__pravo_bank"
            evidence.mkdir(parents=True)
            payload = b"official text bytes"
            digest = hashlib.sha256(payload).hexdigest()
            (evidence / f"{digest}.source").write_bytes(payload)
            metadata = {
                "source_key": "constitution__pravo_bank",
                "registry_id": "pravo_law_bank",
                "requested_url": "https://pravo.gov.ru/constitution",
                "final_url": "https://pravo.gov.ru/constitution",
                "retrieved_at": "2026-11-09T12:00:00Z",
                "byte_length": len(payload),
                "sha256": digest,
                "evidence_file": f"{digest}.source",
                "legal_text_extraction_performed": False
            }
            (evidence / f"{digest}.json").write_text(json.dumps(metadata), encoding="utf-8")
            source = {
                "key": "constitution__pravo_bank",
                "registry_id": "pravo_law_bank",
                "sha256": digest,
                "official_url": metadata["requested_url"],
                "retrieved_at": metadata["retrieved_at"]
            }
            registry = {"url": "https://pravo.gov.ru/"}
            with patch.object(build_database, "ROOT", root):
                self.assertEqual(build_database.validate_source_evidence(source, registry), digest)

    def test_iso_date_validation_rejects_impossible_dates(self):
        with self.assertRaisesRegex(ValueError, "valid calendar date"):
            build_database.validate_iso_date("2026-02-30", "test_date")
        with self.assertRaisesRegex(ValueError, "YYYY-MM-DD"):
            build_database.validate_iso_date("09.11.2026", "test_date")

    def test_build_resolves_cross_reference_to_later_document(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            registry_path = root / "registry.json"
            registry_sources = [
                {"id": "official", "url": "https://publication.example.gov/", "system_id": "publication", "operator_id": "gspi"},
                {"id": "bank", "url": "https://lawbank.example.gov/", "system_id": "law-bank", "operator_id": "gspi"},
                {"id": "minjust", "url": "https://minjust.example.gov/", "system_id": "minjust", "operator_id": "minjust"}
            ]
            registry_path.write_text(json.dumps({"sources": registry_sources}), encoding="utf-8")
            records_sources = []
            source_checks = {}
            for document_id in ("doc-a", "doc-b"):
                checks = []
                for registry_source in registry_sources:
                    registry_id = registry_source["id"]
                    key = f"{document_id}__{registry_id}"
                    url = registry_source["url"] + document_id
                    payload = f"captured {key}".encode()
                    digest = hashlib.sha256(payload).hexdigest()
                    evidence = root / "content" / "evidence" / key
                    evidence.mkdir(parents=True)
                    (evidence / f"{digest}.source").write_bytes(payload)
                    retrieved_at = "2026-11-09T12:00:00Z"
                    metadata = {
                        "source_key": key,
                        "registry_id": registry_id,
                        "requested_url": url,
                        "final_url": url,
                        "retrieved_at": retrieved_at,
                        "byte_length": len(payload),
                        "sha256": digest,
                        "evidence_file": f"{digest}.source",
                        "legal_text_extraction_performed": False
                    }
                    (evidence / f"{digest}.json").write_text(json.dumps(metadata), encoding="utf-8")
                    records_sources.append({
                        "key": key,
                        "registry_id": registry_id,
                        "name": registry_id,
                        "publisher": registry_id,
                        "official_url": url,
                        "source_type": "official_test",
                        "retrieved_at": retrieved_at,
                        "sha256": digest
                    })
                    checks.append({
                        "source_key": key,
                        "status": "match",
                        "retrieved_at": retrieved_at,
                        "sha256": digest
                    })
                source_checks[document_id] = checks

            documents = []
            for document_id, title in (("doc-a", "Документ А"), ("doc-b", "Документ Б")):
                article_id = f"{document_id}-article-1"
                article_body = f"Официальный текст {title}"
                documents.append({
                    "id": document_id,
                    "title": title,
                    "document_type": "test",
                    "number": "1",
                    "adopted_at": "2020-01-01",
                    "effective_from": "2020-01-01",
                    "legal_status": "действует",
                    "verified_at": "2026-11-09",
                    "verification_status": "approved",
                    "primary_source_key": source_checks[document_id][0]["source_key"],
                    "source_revision": "test revision",
                    "text_sha256": hashlib.sha256(article_body.encode()).hexdigest(),
                    "source_checks": source_checks[document_id],
                    "articles": [{
                        "id": article_id,
                        "ordinal": 1,
                        "number": "1",
                        "title": "Статья 1",
                        "body": article_body
                    }],
                    "cross_references": []
                })
            documents[0]["cross_references"] = [{
                "from_article_id": "doc-a-article-1",
                "to_document_id": "doc-b",
                "to_article_number": "1",
                "reference_text": "статья 1 документа Б",
                "resolved_article_id": "doc-b-article-1",
                "resolution_status": "resolved"
            }]
            records_path = root / "records.json"
            records_path.write_text(json.dumps({
                "release": {
                    "version": "test-1",
                    "created_at": "2026-11-09T12:00:00Z",
                    "effective_as_of": "2026-11-09",
                    "version_code": 7
                },
                "sources": records_sources,
                "documents": documents
            }, ensure_ascii=False), encoding="utf-8")
            output = root / "output"
            with patch.object(build_database, "ROOT", root), patch.object(build_database, "REGISTRY", registry_path):
                build_database.build(records_path, output)
            with sqlite3.connect(output / "zakon-content.sqlite") as db:
                row = db.execute(
                    "SELECT to_document_id, resolved_article_id, resolution_status FROM cross_references"
                ).fetchone()
                self.assertEqual(row, ("doc-b", "doc-b-article-1", "resolved"))
                self.assertEqual(db.execute("PRAGMA foreign_key_check").fetchall(), [])

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
