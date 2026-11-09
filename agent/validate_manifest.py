#!/usr/bin/env python3
"""Validate a manually approved content release and its SQLite payload."""
import hashlib
import json
import sqlite3
import sys
from pathlib import Path
from urllib.parse import urlparse

REQUIRED = ("schema_version", "version_code", "content_version", "created_at",
            "effective_as_of", "database", "validation")


def main() -> int:
    manifest_path = Path(sys.argv[1] if len(sys.argv) > 1 else "../content/manifest.example.json")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    missing = [key for key in REQUIRED if key not in data]
    if missing:
        raise SystemExit(f"Manifest is missing fields: {', '.join(missing)}")
    if data["schema_version"] != 1:
        raise SystemExit("Unsupported manifest schema_version")
    if not isinstance(data["version_code"], int) or data["version_code"] < 1:
        raise SystemExit("version_code must be a positive integer")
    db_info = data["database"]
    if not isinstance(db_info, dict) or not db_info.get("file") or not db_info.get("sha256"):
        raise SystemExit("Manifest database.file and database.sha256 are required")
    download_url = db_info.get("download_url")
    parsed = urlparse(download_url or "")
    if parsed.scheme != "https" or not parsed.netloc:
        raise SystemExit("Manifest database.download_url must be an HTTPS URL")
    if data["validation"].get("status") != "validated":
        raise SystemExit("Refusing publication: content validation status is not 'validated'")

    filename = Path(db_info["file"])
    if filename.name != str(filename):
        raise SystemExit("Database file must be a filename inside the manifest directory")
    db_path = manifest_path.parent / filename
    if not db_path.is_file():
        raise SystemExit(f"Database file not found: {db_path}")
    actual = hashlib.sha256(db_path.read_bytes()).hexdigest()
    if actual != db_info["sha256"]:
        raise SystemExit("Database SHA-256 does not match manifest")

    with sqlite3.connect(f"file:{db_path}?mode=ro", uri=True) as db:
        integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise SystemExit(f"SQLite integrity check failed: {integrity}")
        tables = {row[0] for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view')"
        )}
        missing_tables = {"documents", "articles", "articles_fts"} - tables
        if missing_tables:
            raise SystemExit(f"SQLite database is missing tables: {', '.join(sorted(missing_tables))}")
        documents = db.execute("SELECT count(*) FROM documents").fetchone()[0]
        articles = db.execute("SELECT count(*) FROM articles").fetchone()[0]
        if documents < 1 or articles < 1:
            raise SystemExit("Refusing to publish an empty legal database")

    print(f"Manifest and SQLite database are valid: {documents} documents, {articles} articles.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
