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

def sync(records_path: Path, report_path: Path, evidence_root: Path) -> tuple[int, int, int]:
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

    captured = failed = changed = 0
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
            source.pop("capture_candidate", None)
            for field in CAPTURE_FIELDS + EXTRACTION_FIELDS:
                source.pop(field, None)
            failed += 1
            continue

        if result.get("capture_status") == "changed":
            digest = result.get("sha256")
            expected_digest = result.get("expected_sha256")
            if not isinstance(digest, str) or not HEX64.fullmatch(digest):
                raise ValueError(f"{key}: changed capture has no valid SHA-256")
            if not isinstance(expected_digest, str) or source.get("sha256") != expected_digest:
                raise ValueError(f"{key}: changed capture does not match the source's pinned SHA-256")
            if result.get("requested_url") != source.get("official_url"):
                raise ValueError(f"{key}: changed capture requested_url differs from intake URL")
            relative_file = result.get("evidence_file")
            candidate_rel = Path(relative_file) if isinstance(relative_file, str) else Path()
            if (
                candidate_rel.is_absolute()
                or candidate_rel.parts[:1] != ("candidates",)
                or ".." in candidate_rel.parts
                or candidate_rel.suffix != ".source"
            ):
                raise ValueError(f"{key}: changed capture must be stored under its candidates directory")
            directory = evidence_root / key
            payload_path = directory / candidate_rel
            metadata_path = payload_path.with_suffix(".json")
            if not payload_path.is_file() or not metadata_path.is_file():
                raise ValueError(f"{key}: changed capture candidate or sidecar is missing")
            payload = payload_path.read_bytes()
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            if hashlib.sha256(payload).hexdigest() != digest:
                raise ValueError(f"{key}: changed candidate bytes do not match their SHA-256")
            if metadata.get("capture_status") != "changed" or metadata.get("candidate_review_required") is not True:
                raise ValueError(f"{key}: candidate sidecar is not marked for manual review")
            if metadata.get("expected_sha256") != expected_digest or metadata.get("sha256") != digest:
                raise ValueError(f"{key}: candidate sidecar does not preserve the pinned and downloaded hashes")
            if metadata.get("source_key") != key or metadata.get("requested_url") != source.get("official_url"):
                raise ValueError(f"{key}: candidate sidecar source identity or URL mismatch")
            if metadata.get("evidence_file") != candidate_rel.as_posix() or metadata.get("byte_length") != len(payload):
                raise ValueError(f"{key}: candidate sidecar does not describe the saved bytes")
            for field in ("retrieved_at", "final_url", "content_type", "byte_length", "evidence_file", "sha256", "expected_sha256"):
                if metadata.get(field) != result.get(field):
                    raise ValueError(f"{key}: report and candidate sidecar disagree on {field}")

            source["capture_status"] = "changed"
            source["capture_candidate"] = {
                "sha256": digest,
                "expected_sha256": expected_digest,
                "retrieved_at": metadata["retrieved_at"],
                "final_url": metadata["final_url"],
                "content_type": metadata["content_type"],
                "byte_length": len(payload),
                "evidence_file": candidate_rel.as_posix(),
            }
            source["capture_error"] = (
                "Latest retrieved bytes differ from the pinned original. "
                "The new bytes are quarantined for manual review; the pinned original was not replaced."
            )
            for field in EXTRACTION_FIELDS:
                source.pop(field, None)
            changed += 1
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
        source.pop("capture_candidate", None)
        captured += 1

    atomic_json_write(records_path, records)
    return captured, failed, changed

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    captured, failed, changed = sync(args.records, args.report, args.evidence)
    print(f"Current-run evidence synchronized: captured={captured}, changed_candidates={changed}, failed={failed}")
    if captured == 0 and changed == 0:
        raise SystemExit("No source evidence from the current run passed verification")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
