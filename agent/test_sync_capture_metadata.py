import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from sync_capture_metadata import sync

class SyncCaptureMetadataTests(unittest.TestCase):
    def fixture(self, root, *, old_capture=False):
        records_path = root / "records.json"
        evidence = root / "evidence"
        key = "source_one"
        source = {"key": key, "official_url": "https://minjust.gov.ru/source", "legal_text_extraction_performed": True, "extracted_candidate_sha256": "b" * 64}
        if old_capture:
            source.update({"capture_status": "captured", "sha256": "a" * 64,
                           "retrieved_at": "old", "evidence_file": "old.source"})
        records_path.write_text(json.dumps({"sources": [source]}), encoding="utf-8")
        payload = b"current original"
        digest = hashlib.sha256(payload).hexdigest()
        directory = evidence / key
        directory.mkdir(parents=True)
        (directory / f"{digest}.source").write_bytes(payload)
        metadata = {
            "source_key": key, "requested_url": source["official_url"],
            "final_url": source["official_url"], "retrieved_at": "2026-11-19T00:00:00Z",
            "content_type": "application/rtf", "byte_length": len(payload),
            "sha256": digest, "evidence_file": f"{digest}.source",
            "legal_text_extraction_performed": False,
        }
        (directory / f"{digest}.json").write_text(json.dumps(metadata), encoding="utf-8")
        report = root / "report.json"
        report.write_text(json.dumps({"results": [{
            **metadata, "capture_status": "captured", "registry_id": "minjust"
        }]}), encoding="utf-8")
        return records_path, report, evidence, digest

    def test_uses_only_exact_current_run_capture(self):
        with tempfile.TemporaryDirectory() as tmp:
            records, report, evidence, digest = self.fixture(Path(tmp), old_capture=True)
            captured, failed, changed = sync(records, report, evidence)
            data = json.loads(records.read_text(encoding="utf-8"))
            self.assertEqual((captured, failed, changed), (1, 0, 0))
            self.assertEqual(data["sources"][0]["sha256"], digest)
            self.assertEqual(data["sources"][0]["capture_status"], "captured")
            self.assertNotIn("legal_text_extraction_performed", data["sources"][0])
            self.assertNotIn("extracted_candidate_sha256", data["sources"][0])


    def test_preserves_extraction_only_when_source_hash_is_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            records, report, evidence, digest = self.fixture(root, old_capture=False)
            data = json.loads(records.read_text(encoding="utf-8"))
            data["sources"][0].update({
                "sha256": digest,
                "legal_text_extraction_performed": True,
                "extracted_candidate_file": "source_one.txt",
                "extracted_candidate_sha256": "c" * 64,
                "article_heading_candidates": 12,
            })
            records.write_text(json.dumps(data), encoding="utf-8")
            captured, failed, changed = sync(records, report, evidence)
            source = json.loads(records.read_text(encoding="utf-8"))["sources"][0]
            self.assertEqual((captured, failed, changed), (1, 0, 0))
            self.assertEqual(source["sha256"], digest)
            self.assertTrue(source["legal_text_extraction_performed"])
            self.assertEqual(source["extracted_candidate_sha256"], "c" * 64)

    def test_failed_current_capture_cannot_reuse_stale_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            records, report, evidence, _ = self.fixture(Path(tmp), old_capture=True)
            report.write_text(json.dumps({"results": [{
                "source_key": "source_one", "capture_status": "failed", "error": "timeout"
            }]}), encoding="utf-8")
            captured, failed, changed = sync(records, report, evidence)
            source = json.loads(records.read_text(encoding="utf-8"))["sources"][0]
            self.assertEqual((captured, failed, changed), (0, 1, 0))
            self.assertEqual(source["capture_status"], "failed")
            self.assertNotIn("sha256", source)
            self.assertNotIn("evidence_file", source)
            self.assertNotIn("legal_text_extraction_performed", source)
            self.assertNotIn("extracted_candidate_file", source)
            self.assertNotIn("extracted_candidate_sha256", source)

    def test_missing_current_result_does_not_reuse_stale_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            records, report, evidence, _ = self.fixture(Path(tmp), old_capture=True)
            report.write_text(json.dumps({"results": []}), encoding="utf-8")
            captured, failed, changed = sync(records, report, evidence)
            source = json.loads(records.read_text(encoding="utf-8"))["sources"][0]
            self.assertEqual((captured, failed, changed), (0, 1, 0))
            self.assertNotIn("sha256", source)

    def test_changed_capture_is_quarantined_and_keeps_pinned_source_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            records, report, evidence, _ = self.fixture(root, old_capture=True)
            payload = b"new bytes from the same official source"
            digest = hashlib.sha256(payload).hexdigest()
            candidate_dir = evidence / "source_one" / "candidates"
            candidate_dir.mkdir(parents=True)
            candidate_file = f"candidates/{digest}.source"
            (candidate_dir / f"{digest}.source").write_bytes(payload)
            metadata = {
                "source_key": "source_one",
                "registry_id": "minjust",
                "requested_url": "https://minjust.gov.ru/source",
                "final_url": "https://minjust.gov.ru/source",
                "retrieved_at": "2026-11-24T08:00:00Z",
                "content_type": "application/rtf",
                "byte_length": len(payload),
                "sha256": digest,
                "expected_sha256": "a" * 64,
                "evidence_file": candidate_file,
                "legal_text_extraction_performed": False,
                "capture_status": "changed",
                "candidate_review_required": True,
            }
            (candidate_dir / f"{digest}.json").write_text(json.dumps(metadata), encoding="utf-8")
            report.write_text(json.dumps({"results": [metadata]}), encoding="utf-8")

            captured, failed, changed = sync(records, report, evidence)
            source = json.loads(records.read_text(encoding="utf-8"))["sources"][0]
            self.assertEqual((captured, failed, changed), (0, 0, 1))
            self.assertEqual(source["capture_status"], "changed")
            self.assertEqual(source["sha256"], "a" * 64)
            self.assertEqual(source["retrieved_at"], "old")
            self.assertEqual(source["evidence_file"], "old.source")
            self.assertEqual(source["capture_candidate"]["sha256"], digest)
            self.assertEqual(source["capture_candidate"]["expected_sha256"], "a" * 64)
            self.assertIn("not replaced", source["capture_error"])
            self.assertNotIn("legal_text_extraction_performed", source)

    def test_rejects_report_metadata_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            records, report, evidence, _ = self.fixture(Path(tmp))
            data = json.loads(report.read_text(encoding="utf-8"))
            data["results"][0]["retrieved_at"] = "wrong"
            report.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "disagree"):
                sync(records, report, evidence)

if __name__ == "__main__":
    unittest.main()
