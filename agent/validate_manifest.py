#!/usr/bin/env python3
"""Basic release-manifest validation. This does not validate legal text correctness."""
import hashlib
import json
import sys
from pathlib import Path

REQUIRED = ("schema_version", "content_version", "created_at", "effective_as_of", "database", "validation")

def main() -> int:
    manifest_path = Path(sys.argv[1] if len(sys.argv) > 1 else "../content/manifest.example.json")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    missing = [key for key in REQUIRED if key not in data]
    if missing:
        raise SystemExit(f"Manifest is missing fields: {', '.join(missing)}")
    db = data["database"]
    if not isinstance(db, dict) or not db.get("file") or not db.get("sha256"):
        raise SystemExit("Manifest database.file and database.sha256 are required")
    if data["validation"].get("status") != "validated":
        raise SystemExit("Refusing publication: content validation status is not 'validated'")
    db_path = manifest_path.parent / db["file"]
    if not db_path.is_file():
        raise SystemExit(f"Database file not found: {db_path}")
    actual = hashlib.sha256(db_path.read_bytes()).hexdigest()
    if actual != db["sha256"]:
        raise SystemExit("Database SHA-256 does not match manifest")
    print("Manifest and database checksum are valid.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
