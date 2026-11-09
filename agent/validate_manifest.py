#!/usr/bin/env python3
"""Validate a manually approved content release and its SQLite payload."""
import hashlib
import json
import sqlite3
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

REQUIRED = ("schema_version", "version_code", "content_version", "created_at",
            "effective_as_of", "database", "validation")


def main() -> int:
    manifest_path = Path(sys.argv[1] if len(sys.argv) > 1 else "../content/manifest.example.json")
    default_key = Path(__file__).resolve().parents[1] / "content" / "signing" / "public.pem"
    public_key_path = Path(sys.argv[2]) if len(sys.argv) > 2 else default_key
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    missing = [key for key in REQUIRED if key not in data]
    if missing:
        raise SystemExit(f"Manifest is missing fields: {', '.join(missing)}")
    if data["schema_version"] != 1:
        raise SystemExit("Unsupported manifest schema_version")
    if not isinstance(data["version_code"], int) or data["version_code"] < 7:
        raise SystemExit("version_code must be >= 7 for migration from legacy JSON packs")
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
        missing_tables = {"documents", "articles", "articles_fts", "content_metadata", "content_release"} - tables
        if missing_tables:
            raise SystemExit(f"SQLite database is missing tables: {', '.join(sorted(missing_tables))}")
        documents = db.execute("SELECT count(*) FROM documents").fetchone()[0]
        articles = db.execute("SELECT count(*) FROM articles").fetchone()[0]
        if documents < 1 or articles < 1:
            raise SystemExit("Refusing to publish an empty legal database")
        metadata = dict(db.execute("SELECT key,value FROM content_metadata"))
        if metadata.get("pack_version") != str(data["version_code"]):
            raise SystemExit("SQLite content_metadata version does not match manifest version_code")
        if not metadata.get("pack_label"):
            raise SystemExit("SQLite content_metadata is missing pack_label")
        release_row = db.execute(
            "SELECT status FROM content_release WHERE version=?",
            (data["content_version"],)
        ).fetchone()
        if release_row is None or release_row[0] != "validated":
            raise SystemExit(
                "SQLite content_release must contain this content_version with status='validated'"
            )

    delta_info = data.get("delta")
    if delta_info is not None:
        if not isinstance(delta_info, dict):
            raise SystemExit("Manifest delta must be an object")
        delta_file = delta_info.get("file")
        delta_hash = delta_info.get("sha256")
        delta_url = urlparse(delta_info.get("download_url", ""))
        if not delta_file or Path(delta_file).name != str(delta_file):
            raise SystemExit("Delta file must be a filename inside the manifest directory")
        if delta_url.scheme != "https" or not delta_url.netloc:
            raise SystemExit("Manifest delta.download_url must be an HTTPS URL")
        delta_path = manifest_path.parent / delta_file
        if not delta_path.is_file():
            raise SystemExit(f"Delta file not found: {delta_path}")
        if hashlib.sha256(delta_path.read_bytes()).hexdigest() != delta_hash:
            raise SystemExit("Delta SHA-256 does not match manifest")
        patch = json.loads(delta_path.read_text(encoding="utf-8"))
        if patch.get("schema_version") != 1:
            raise SystemExit("Unsupported delta schema_version")
        from_code = patch.get("from_version_code")
        to_code = patch.get("to_version_code")
        if not isinstance(from_code, int) or from_code <= 0 or from_code >= data["version_code"]:
            raise SystemExit("Delta base version must be positive and lower than target version")
        if from_code != delta_info.get("from_version_code"):
            raise SystemExit("Delta base version does not match manifest")
        if to_code != data["version_code"] or delta_info.get("to_version_code") != data["version_code"]:
            raise SystemExit("Delta target version does not match manifest version_code")
        operations = patch.get("operations")
        allowed_tables = {"sources", "documents", "articles", "document_source_checks",
                           "cross_references", "legal_status_history", "content_release"}
        if not isinstance(operations, dict) or not set(operations).issubset(allowed_tables):
            raise SystemExit("Delta operations contain an invalid table")
        for table, change in operations.items():
            if not isinstance(change, dict) or not isinstance(change.get("delete", []), list) or not isinstance(change.get("upsert", []), list):
                raise SystemExit(f"Delta operations for {table} have an invalid shape")

    signature_info = data.get("signature")
    if data["validation"].get("status") == "validated":
        if not isinstance(signature_info, dict):
            raise SystemExit("A validated manifest must contain a publisher signature")
        if signature_info.get("algorithm") != "SHA256withRSA":
            raise SystemExit("Unsupported publisher signature algorithm")
        signature_file = signature_info.get("file", "manifest.sig")
        if Path(signature_file).name != str(signature_file):
            raise SystemExit("Signature file must be inside the manifest directory")
        signature_url = urlparse(signature_info.get("download_url", ""))
        if signature_url.scheme != "https" or not signature_url.netloc:
            raise SystemExit("Manifest signature.download_url must be an HTTPS URL")
        signature_path = manifest_path.parent / signature_file
        if not signature_path.is_file():
            raise SystemExit(f"Manifest signature file not found: {signature_path}")
        if not public_key_path.is_file():
            raise SystemExit(f"Publisher public key not found: {public_key_path}")
        verification = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(public_key_path),
             "-signature", str(signature_path), str(manifest_path)],
            capture_output=True, text=True, check=False
        )
        if verification.returncode != 0:
            raise SystemExit("Publisher signature verification failed: " + verification.stderr.strip())

    print(f"Manifest and SQLite database are valid: {documents} documents, {articles} articles.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
