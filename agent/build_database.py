#!/usr/bin/env python3
"""Build a SQLite release from reviewed structured records; never generates legal text.

Updated 2026-11-19 by owner request: publication_rule.minimum_distinct_sources
is read from registry (currently 1). Cross-checks still recommended.
"""
import argparse
import hashlib
import json
import re
import sqlite3
from pathlib import Path

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

def build(records_path: Path, output_dir: Path) -> None:
    raw = records_path.read_bytes()
    data = json.loads(raw)
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    registry_sources = {s["id"]: s for s in registry["sources"]}
    pub_rule = registry.get("publication_rule", {})
    min_sources = int(pub_rule.get("minimum_distinct_sources", 1))
    source_rows = data.get("sources", [])
    documents = data.get("documents", [])
    release = data.get("release", {})
    if not documents:
        raise ValueError("No legal documents supplied; refusing to create an empty release")
    if not release.get("version") or not release.get("created_at") or not release.get("effective_as_of"):
        raise ValueError("Release version, created_at and effective_as_of are required")

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
    for source in source_rows:
        key = source["key"]
        registry_id = source.get("registry_id", key)
        if key in source_ids:
            raise ValueError(f"Duplicate source capture key: {key}")
        if registry_id not in registry_sources:
            raise ValueError(f"Source registry_id is not in approved registry: {registry_id}")
        require_hash(source.get("sha256"), f"source {key} sha256")
        if not source.get("retrieved_at") or not source.get("official_url"):
            raise ValueError(f"Source {key} is missing retrieval time or URL")
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
        if len(set(checked_keys)) < min_sources:
            raise ValueError(f"{doc_id}: at least {min_sources} distinct source check(s) required by current policy")
        if primary_key not in checked_keys:
            raise ValueError(f"{doc_id}: primary source must be included in source_checks")
        if any(key not in source_ids for key in checked_keys):
            raise ValueError(f"{doc_id}: a checked source is not captured")
        if any(item.get("status") != "match" for item in checks):
            raise ValueError(f"{doc_id}: source mismatch/unavailability blocks release")
        if doc.get("verification_status") != "approved":
            raise ValueError(f"{doc_id}: document must be manually approved before database build")
        articles = sorted(doc.get("articles", []), key=lambda a: int(a.get("ordinal", 0)))
        if not articles:
            raise ValueError(f"{doc_id}: document has no articles")
        for article in articles:
            if not article.get("id") or not article.get("number") or not isinstance(article.get("body"), str) or not article["body"].strip():
                raise ValueError(f"{doc_id}: every article needs id, number and non-empty body")
        text_digest = sha256("\n".join(a.get("body", "") for a in articles).encode("utf-8"))
        if doc.get("text_sha256") != text_digest:
            raise ValueError(f"{doc_id}: text_sha256 does not match article bodies")

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

    db.execute(
        "INSERT INTO content_release(version,created_at,effective_as_of,manifest_sha256,status) VALUES(?,?,?,?,?)",
        (release["version"], release["created_at"], release["effective_as_of"], sha256(raw), "draft")
    )
    db.commit()
    integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        db.close()
        raise ValueError(f"SQLite integrity check failed: {integrity}")
    db.close()

    manifest = {
        "schema_version": 1,
        "content_version": release["version"],
        "created_at": release["created_at"],
        "effective_as_of": release["effective_as_of"],
        "database": {"file": db_path.name, "sha256": sha256(working_db_path.read_bytes())},
        "validation": {"status": "draft", "documents": len(documents),
                       "manual_approval_required": True}
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    working_db_path.replace(db_path)
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
