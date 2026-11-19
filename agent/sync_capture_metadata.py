#!/usr/bin/env python3
"""Synchronize intake metadata only from the exact capture results produced in this run."""
import argparse
import hashlib
import json
import re
from pathlib import Path

HEX64 = re.compile(r"^[0-9a-f]{64}$")
SAFE_KEY = re.compile(r"^[a-z0-9][a-z0-9_-]{0,100}$")
CAPTURE_FIELDS = (
    "sha256", "retrieved_at", "final_url", "content_type",
    "byte_length", "evidence_file",
)
EXTRACTION_FIELDS = (
    "legal_text_extraction_performed", "extracted_candidate_file",
    "extracted_candidate_sha256", "article_heading_candidates",
)

def atomic_json_write(path: Path, data: dict) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)

def sync(records_path: Path, report_path: Path, evidence_root: Path) -> tuple[int, int]:
    records = json.loads(records_path.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    results = report.get("results")
    if not isinstance(results, list):
        raise ValueError("Capture report must contain a results array")

    by_key = {}
    for result in results:
        key = result.get("source_key")
        if not isinstance(key, str) or not SAFE_KEY.fullmatch(key):
            raise ValueError("Capture report contains an invalid source_key")
        if key in by_key:
            raise ValueError(f"Capture report contains duplicate result for {key}")
        by_key[key] = result

    captured = failed = 0
    sources = records.get("sources", [])
    if not isinstance(sources, list) or not sources:
        raise ValueError("Intake records must contain a non-empty sources array")

    for source in sources:
        key = source.get("key")
        if not isinstance(key, str) or not SAFE_KEY.fullmatch(key):
            raise ValueError("Intake source contains an invalid key")
        result = by_key.get(key)
        if result is None:
            source["capture_status"] = "failed"
            source["capture_error"] = "No result for this source in the current capture run"
            for field in CAPTURE_FIELDS + EXTRACTION_FIELDS:
                source.pop(field, None)
            failed += 1
            continue

        if result.get("capture_status") == "failed":
            source["capture_status"] = "failed"
            source["capture_error"] = result.get("error", "Capture failed")
            for field in CAPTURE_FIELDS + EXTRACTION_FIELDS:
                source.pop(field, None)
            failed += 1
            continue

        digest = result.get("sha256")
        if not isinstance(digest, str) or not HEX64.fullmatch(digest):
            raise ValueError(f"{key}: current capture result has no valid SHA-256")
        expected_url = source.get("official_url")
        if result.get("requested_url") != expected_url:
            raise ValueError(f"{key}: capture report requested_url differs from intake URL")

        directory = evidence_root / key
        payload_path = directory / f"{digest}.source"
        metadata_path = directory / f"{digest}.json"
        if not payload_path.is_file() or not metadata_path.is_file():
            raise ValueError(f"{key}: current capture's exact evidence files are missing")

        payload = payload_path.read_bytes()
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        actual_digest = hashlib.sha256(payload).hexdigest()
        if actual_digest != digest:
            raise ValueError(f"{key}: saved original does not match current capture SHA-256")
        if metadata.get("sha256") != digest or metadata.get("byte_length") != len(payload):
            raise ValueError(f"{key}: metadata does not describe the current captured original")
        if metadata.get("source_key") != key:
            raise ValueError(f"{key}: metadata source identity mismatch")
        if metadata.get("requested_url") != expected_url:
            raise ValueError(f"{key}: metadata requested URL mismatch")
        for field in ("retrieved_at", "final_url", "content_type", "byte_length", "evidence_file"):
            if metadata.get(field) != result.get(field):
                raise ValueError(f"{key}: report and evidence metadata disagree on {field}")

        if source.get("sha256") != digest:
            for field in EXTRACTION_FIELDS:
                source.pop(field, None)

        source.update({
            "capture_status": "captured",
            "sha256": digest,
            "retrieved_at": metadata["retrieved_at"],
            "final_url": metadata["final_url"],
            "content_type": metadata["content_type"],
            "byte_length": len(payload),
            "evidence_file": metadata["evidence_file"],
        })
        source.pop("capture_error", None)
        captured += 1

    atomic_json_write(records_path, records)
    return captured, failed

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    captured, failed = sync(args.records, args.report, args.evidence)
    print(f"Current-run evidence synchronized: captured={captured}, failed={failed}")
    if captured == 0:
        raise SystemExit("No source evidence from the current run passed verification")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
