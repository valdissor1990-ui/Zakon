#!/usr/bin/env python3
"""Build a SQLite release from reviewed structured records; never generates legal text."""
import argparse
import hashlib
import json
import re
import sqlite3
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RECORDS = ROOT / "content" / "records" / "records.json"
SCHEMA = ROOT / "content" / "schema" / "schema.sql"
REGISTRY = ROOT / "content" / "sources" / "registry.json"
HEX64 = re.compile(r"^[0-9a-f]{64}$")

def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def require_hash(value: str, label: str) -> None:
    if not isinstance(value, str) or not HEX64.fullmatch(value.lower()):
        raise ValueError(f"{label} must be a 64-character SHA-256 hex string")

def validate_source_evidence(source: dict, registry_source: dict) -> str:
    """Require the exact captured original and its provenance metadata before a source can enter a release."""
    key = source.get("key")
    if not isinstance(key, str) or not key:
        raise ValueError("Every source needs a non-empty key")
    digest = source.get("sha256")
    require_hash(digest, f"source {key} sha256")
    digest = digest.lower()

    allowed_hosts = {
        urlparse(value).netloc.lower()
        for value in (registry_source.get("url", ""), registry_source.get("access_url", ""))
        if urlparse(value).scheme == "https" and urlparse(value).netloc
    }
    requested_url = source.get("official_url", "")
    parsed = urlparse(requested_url)
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.netloc.lower() not in allowed_hosts:
        raise ValueError(f"Source {key}: official_url must use an allowlisted credential-free HTTPS host")

    evidence_dir = ROOT / "content" / "evidence" / key
    evidence_path = evidence_dir / f"{digest}.source"
    metadata_path = evidence_dir / f"{digest}.json"
    if not evidence_path.is_file() or not metadata_path.is_file():
        raise ValueError(
            f"Source {key}: captured original or metadata is missing; run agent/capture_sources.py "
            "and commit content/evidence before building"
        )

    payload = evidence_path.read_bytes()
    if sha256(payload) != digest:
        raise ValueError(f"Source {key}: saved original does not match its SHA-256")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    final_url = urlparse(metadata.get("final_url", ""))
    if final_url.scheme != "https" or final_url.username or final_url.password or final_url.netloc.lower() not in allowed_hosts:
        raise ValueError(f"Source {key}: captured final URL is not an allowlisted HTTPS address")
    if metadata.get("source_key") != key or metadata.get("sha256", "").lower() != digest:
        raise ValueError(f"Source {key}: capture metadata does not match the source record")
    if metadata.get("requested_url") != requested_url:
        raise ValueError(f"Source {key}: captured request URL differs from records.json")
    if metadata.get("evidence_file") != evidence_path.name or metadata.get("byte_length") != len(payload):
        raise ValueError(f"Source {key}: capture metadata does not describe the saved original")
    if metadata.get("retrieved_at") != source.get("retrieved_at"):
        raise ValueError(f"Source {key}: retrieved_at must match the captured evidence metadata")
    if metadata.get("legal_text_extraction_performed") is not False:
        raise ValueError(f"Source {key}: raw evidence metadata must confirm no automatic legal-text extraction")
    return digest


def build(records_path: Path, output_dir: Path) -> None:
    raw = records_path.read_bytes()
    data = json.loads(raw)
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    registry_sources = {s["id"]: s for s in registry["sources"]}
    source_rows = data.get("sources", [])
    documents = data.get("documents", [])
    release = data.get("release", {})
    if not documents:
        raise ValueError("No legal documents supplied; refusing to create an empty release")
    if not release.get("version") or not release.get("created_at") or not release.get("effective_as_of"):
        raise ValueError("Release version, created_at and effective_as_of are required")
    version_code = release.get("version_code")
    if isinstance(version_code, bool) or not isinstance(version_code, int) or version_code < 7:
        raise ValueError("release.version_code must be an integer >= 7 for the first SQLite release, then increase monotonically (legacy JSON versions reached 6)")
    output_dir.mkdir(parents=True, exist_ok=True)
    db_path = output_dir / "zakon-content.sqlite"
    working_db_path = output_dir / ".zakon-content.sqlite.tmp"
    if working_db_path.exists():
        working_db_path.unlink()
    db = sqlite3.connect(working_db_path)
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript(SCHEMA.read_text(encoding="utf-8"))
    db.execute("PRAGMA defer_foreign_keys=ON")
    db.execute("PRAGMA user_version=1")

    source_ids = {}
    source_hashes = {}
    for source in source_rows:
        key = source["key"]
        if key in source_ids:
            raise ValueError(f"Duplicate source key: {key}")
        if key not in registry_sources:
            raise ValueError(f"Source key is not in approved registry: {key}")
        if not source.get("retrieved_at"):
            raise ValueError(f"Source {key} is missing retrieval time")
        source_hashes[key] = validate_source_evidence(source, registry_sources[key])
        cur = db.execute(
            """INSERT INTO sources(name,publisher,official_url,source_type,retrieved_at,
               source_document_id,sha256,notes) VALUES(?,?,?,?,?,?,?,?)""",
            (source["name"], source["publisher"], source["official_url"], source["source_type"],
             source["retrieved_at"], source.get("source_document_id"), source["sha256"], source.get("notes"))
        )
        source_ids[key] = cur.lastrowid

    for doc in documents:
        doc_id = doc["id"]
        primary_key = doc["primary_source_key"]
        if primary_key not in source_ids:
            raise ValueError(f"{doc_id}: primary source is missing from sources[]")
        checks = doc.get("source_checks", [])
        checked_keys = [item.get("source_key") for item in checks]
        if len(checked_keys) != len(set(checked_keys)):
            raise ValueError(f"{doc_id}: duplicate source checks are not allowed")
        if len(set(checked_keys)) < 3:
            raise ValueError(f"{doc_id}: at least three distinct source checks are required")
        if primary_key not in checked_keys:
            raise ValueError(f"{doc_id}: primary source must be included in source_checks")
        if any(key not in source_ids or key not in registry_sources for key in checked_keys):
            raise ValueError(f"{doc_id}: a checked source is not registered or not captured")
        source_by_key = {source["key"]: source for source in source_rows}
        hosts = {urlparse(source_by_key[key]["official_url"]).netloc.lower() for key in checked_keys}
        if len(hosts) < 3:
            raise ValueError(f"{doc_id}: checks must cover three distinct source hosts")
        if any(item.get("status") != "match" for item in checks):
            raise ValueError(f"{doc_id}: source mismatch/unavailability blocks release")
        if any(not item.get("retrieved_at") for item in checks):
            raise ValueError(f"{doc_id}: every source check needs retrieved_at")
        for item in checks:
            require_hash(item.get("sha256"), f"{doc_id} source check sha256")
            if item["sha256"].lower() != source_hashes[item["source_key"]]:
                raise ValueError(f"{doc_id}: source check hash does not match the captured source record")
            if item["retrieved_at"] != next(
                source["retrieved_at"] for source in source_rows if source["key"] == item["source_key"]
            ):
                raise ValueError(f"{doc_id}: source check retrieved_at does not match the captured evidence")
        if doc.get("verification_status") != "approved":
            raise ValueError(f"{doc_id}: document must be manually approved before database build")
        articles = sorted(doc.get("articles", []), key=lambda a: int(a.get("ordinal", 0)))
        if not articles:
            raise ValueError(f"{doc_id}: document has no articles")
        seen_article_ids = set()
        for article in articles:
            article_id = article.get("id")
            article_number = article.get("number")
            body = article.get("body")
            if not article_id or article_id in seen_article_ids:
                raise ValueError(f"{doc_id}: every article needs a unique, non-empty id")
            seen_article_ids.add(article_id)
            if not article_number or not isinstance(body, str) or not body.strip():
                raise ValueError(f"{doc_id}: article {article_id} needs a number and non-empty legal text")
        text_digest = sha256("\n".join(a.get("body", "") for a in articles).encode("utf-8"))
        if doc.get("text_sha256") != text_digest:
            raise ValueError(f"{doc_id}: text_sha256 does not match article bodies")
        require_hash(doc.get("text_sha256"), f"{doc_id} text_sha256")

        cur = db.execute(
            """INSERT INTO documents(id,title,document_type,number,adopted_at,published_at,
               effective_from,effective_to,legal_status,jurisdiction,source_id,source_revision,
               verified_at,verification_status,text_sha256)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (doc_id, doc["title"], doc["document_type"], doc.get("number",""), doc.get("adopted_at"),
             doc.get("published_at"), doc.get("effective_from"), doc.get("effective_to"),
             doc["legal_status"], doc.get("jurisdiction","RU"), source_ids[primary_key],
             doc.get("source_revision"), doc.get("verified_at"), "approved", doc["text_sha256"])
        )
        for item in checks:
            db.execute(
                """INSERT INTO document_source_checks(document_id,source_id,check_status,
                   retrieved_at,source_document_id,source_sha256,comparison_notes)
                   VALUES(?,?,?,?,?,?,?)""",
                (doc_id, source_ids[item["source_key"]], item["status"], item["retrieved_at"],
                 item.get("source_document_id"), item["sha256"], item.get("comparison_notes"))
            )
        for article in articles:
            db.execute(
                """INSERT INTO articles(id,document_id,parent_id,ordinal,number,title,body,effective_from,effective_to)
                   VALUES(?,?,?,?,?,?,?,?,?)""",
                (article["id"], doc_id, article.get("parent_id"), int(article.get("ordinal",0)),
                 article["number"], article.get("title"), article["body"],
                 article.get("effective_from"), article.get("effective_to"))
            )
        for ref in doc.get("cross_references", []):
            db.execute(
                """INSERT INTO cross_references(from_article_id,to_document_id,to_article_number,
                   reference_text,resolved_article_id,resolution_status) VALUES(?,?,?,?,?,?)""",
                (ref["from_article_id"], ref.get("to_document_id"), ref["to_article_number"],
                 ref["reference_text"], ref.get("resolved_article_id"), ref["resolution_status"])
            )

    db.execute(
        "INSERT INTO content_release(version,created_at,effective_as_of,manifest_sha256,status) VALUES(?,?,?,?,?)",
        (release["version"], release["created_at"], release["effective_as_of"], sha256(raw), "draft")
    )
    db.executemany(
        "INSERT INTO content_metadata(key,value) VALUES(?,?)",
        [("pack_version", str(version_code)), ("pack_label", release["version"])]
    )
    db.commit()
    integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        db.close()
        raise ValueError(f"SQLite integrity check failed: {integrity}")
    db.close()


    manifest = {
        "schema_version": 1,
        "version_code": version_code,
        "content_version": release["version"],
        "created_at": release["created_at"],
        "effective_as_of": release["effective_as_of"],
        "database": {
            "file": db_path.name,
            "sha256": sha256(working_db_path.read_bytes())
        },
        "validation": {
            "status": "draft",
            "documents": len(documents),
            "manual_approval_required": True
        }
    }
    manifest_tmp_path = output_dir / ".manifest.json.tmp"
    manifest_tmp_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8"
    )
    working_db_path.replace(db_path)
    manifest_tmp_path.replace(output_dir / "manifest.json")
    print(f"Draft database created: {db_path}")
    print("Publication is blocked until a separate manual review approves the release.")

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, default=DEFAULT_RECORDS)
    parser.add_argument("--output", type=Path, default=ROOT / "dist" / "content")
    args = parser.parse_args()
    build(args.records, args.output)
    return 0

if __name__ == "__main__":
    main()
