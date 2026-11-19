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
            captured, failed = sync(records, report, evidence)
            data = json.loads(records.read_text(encoding="utf-8"))
            self.assertEqual((captured, failed), (1, 0))
            self.assertEqual(data["sources"][0]["sha256"], digest)
            self.assertEqual(data["sources"][0]["capture_status"], "captured")
            self.assertTrue(data["sources"][0]["legal_text_extraction_performed"])
            self.assertEqual(data["sources"][0]["extracted_candidate_sha256"], "b" * 64)

    def test_failed_current_capture_cannot_reuse_stale_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            records, report, evidence, _ = self.fixture(Path(tmp), old_capture=True)
            report.write_text(json.dumps({"results": [{
                "source_key": "source_one", "capture_status": "failed", "error": "timeout"
            }]}), encoding="utf-8")
            captured, failed = sync(records, report, evidence)
            source = json.loads(records.read_text(encoding="utf-8"))["sources"][0]
            self.assertEqual((captured, failed), (0, 1))
            self.assertEqual(source["capture_status"], "failed")
            self.assertNotIn("sha256", source)
            self.assertNotIn("evidence_file", source)

    def test_missing_current_result_does_not_reuse_stale_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            records, report, evidence, _ = self.fixture(Path(tmp), old_capture=True)
            report.write_text(json.dumps({"results": []}), encoding="utf-8")
            captured, failed = sync(records, report, evidence)
            source = json.loads(records.read_text(encoding="utf-8"))["sources"][0]
            self.assertEqual((captured, failed), (0, 1))
            self.assertNotIn("sha256", source)

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
