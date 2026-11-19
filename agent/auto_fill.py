#!/usr/bin/env python3
"""Fully automatic fill pipeline for official Minjust-captured texts.

Steps:
  1. Capture sources listed in content/intake/source-requests.json (HTTPS allowlist)
  2. Sync capture metadata
  3. Extract DOCX/RTF → content/extracted/*.txt
  4. Auto-build article-index for each extracted consolidated text
  5. Merge into content/records/records.json (single-source, auto-approved)
  6. Optionally build draft SQLite

Never invents legal text. Uses only bytes captured from official URLs.
pravo.gov.ru requires PROXY_LIST / --proxy-file (Russian exits); without proxies
only Minjust sources that respond from the runner network are filled.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INTAKE = ROOT / "content" / "intake" / "source-requests.json"
EXTRACTED = ROOT / "content" / "extracted"
RECORDS = ROOT / "content" / "records" / "records.json"
EVIDENCE = ROOT / "content" / "evidence"

# Map source_key → document metadata for auto records
DOCUMENT_MAP = {
    "advocacy_minjust_rtf": {
        "id": "ru-advocacy-law",
        "title": "Федеральный закон от 31.05.2002 № 63-ФЗ «Об адвокатской деятельности и адвокатуре в Российской Федерации»",
        "document_type": "federal_law",
        "number": "63-ФЗ",
        "adopted_at": "2002-05-31",
        "published_at": "2002-06-05",
        "effective_from": "2002-07-01",
        "source_revision": "red. per Minjust capture",
    },
    "constitution_minjust_rtf": {
        "id": "ru-constitution",
        "title": "Конституция Российской Федерации",
        "document_type": "constitution",
        "number": "",
        "adopted_at": "1993-12-12",
        "published_at": "1993-12-25",
        "effective_from": "1993-12-25",
        "source_revision": "per Minjust RTF capture",
    },
    "uk_minjust_docx": {
        "id": "ru-criminal-code",
        "title": "Уголовный кодекс Российской Федерации",
        "document_type": "code",
        "number": "63-ФЗ",
        "adopted_at": "1996-06-13",
        "published_at": "1996-06-18",
        "effective_from": "1997-01-01",
        "source_revision": "Minjust DOCX (file date may lag current law)",
    },
    "gpk_minjust_docx": {
        "id": "ru-civil-procedure-code",
        "title": "Гражданский процессуальный кодекс Российской Федерации",
        "document_type": "code",
        "number": "138-ФЗ",
        "adopted_at": "2002-11-14",
        "published_at": "2002-11-20",
        "effective_from": "2003-02-01",
        "source_revision": "Minjust DOCX (file date may lag current law)",
    },
    "koap_minjust_docx": {
        "id": "ru-administrative-offences-code",
        "title": "Кодекс Российской Федерации об административных правонарушениях",
        "document_type": "code",
        "number": "195-ФЗ",
        "adopted_at": "2001-12-30",
        "published_at": "2002-01-07",
        "effective_from": "2002-07-01",
        "source_revision": "Minjust DOCX (file date may lag current law)",
    },
    "kas_minjust_docx": {
        "id": "ru-code-of-administrative-judicial-procedure",
        "title": "Кодекс административного судопроизводства Российской Федерации",
        "document_type": "code",
        "number": "21-ФЗ",
        "adopted_at": "2015-03-08",
        "published_at": "2015-03-09",
        "effective_from": "2015-09-15",
        "source_revision": "Minjust DOCX (file date may lag current law)",
    },
}


def run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    print("+", " ".join(cmd), flush=True)
    return subprocess.run(cmd, cwd=ROOT, check=check)


def sha256_text(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def load_intake() -> dict:
    return json.loads(INTAKE.read_text(encoding="utf-8"))


def step_capture(proxy_file: str | None) -> None:
    # capture_sources expects a JSON with sources[]; intake has that shape
    cmd = [
        sys.executable,
        "agent/capture_sources.py",
        "--records",
        str(INTAKE),
        "--output",
        str(EVIDENCE),
    ]
    if proxy_file:
        cmd.extend(["--proxy-file", proxy_file])
    # Do not fail the whole pipeline if some sources (pravo) fail
    run(cmd, check=False)
    report = EVIDENCE / "capture-report.json"
    if report.is_file():
        run([
            sys.executable,
            "agent/sync_capture_metadata.py",
            "--records",
            str(INTAKE),
            "--report",
            str(report),
            "--evidence",
            str(EVIDENCE),
        ], check=False)


def step_extract() -> None:
    run([sys.executable, "agent/extract_source_text.py"], check=False)


def step_index() -> list[str]:
    intake = load_intake()
    indexed: list[str] = []
    for source in intake.get("sources", []):
        key = source.get("key")
        if key not in DOCUMENT_MAP:
            continue
        if source.get("capture_status") != "captured":
            continue
        if not source.get("legal_text_extraction_performed"):
            continue
        text_name = source.get("extracted_candidate_file")
        if not text_name:
            continue
        text_path = EXTRACTED / text_name
        if not text_path.is_file():
            continue
        meta = DOCUMENT_MAP[key]
        out = EXTRACTED / f"{key}.article-index.json"
        cmd = [
            sys.executable,
            "agent/auto_index_articles.py",
            "--text", str(text_path),
            "--document-id", meta["id"],
            "--document-title", meta["title"],
            "--source-key", key,
            "--official-url", source["official_url"],
            "--sha256", source["sha256"],
            "--retrieved-at", source.get("retrieved_at") or "",
            "--byte-length", str(source.get("byte_length") or 0),
            "--registry-id", source.get("registry_id", "minjust_official_documents"),
            "--output", str(out),
        ]
        try:
            run(cmd)
            indexed.append(key)
        except subprocess.CalledProcessError as err:
            print(f"INDEX SKIP {key}: {err}", flush=True)
    return indexed


def build_document_from_index(index_path: Path, text_path: Path, meta: dict, source: dict) -> dict:
    idx = json.loads(index_path.read_text(encoding="utf-8"))
    text = text_path.read_text(encoding="utf-8")
    articles = []
    for a in idx["articles"]:
        body = text[a["start_char"]:a["end_char"]].strip()
        if not body:
            raise ValueError(f"Empty body for article {a.get('number')} in {meta['id']}")
        articles.append({
            "id": f"{meta['id']}-art-{str(a['number']).replace('.', '-')}",
            "ordinal": a["ordinal"],
            "number": a["number"],
            "title": a.get("heading") or "",
            "body": body,
            "parent_id": None,
            "effective_from": None,
            "effective_to": None,
        })
    bodies = "\n".join(x["body"] for x in articles)
    text_sha = sha256_text(bodies)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    return {
        "id": meta["id"],
        "title": meta["title"],
        "document_type": meta["document_type"],
        "number": meta.get("number", ""),
        "adopted_at": meta.get("adopted_at"),
        "published_at": meta.get("published_at"),
        "effective_from": meta.get("effective_from"),
        "effective_to": None,
        "legal_status": "in_force",
        "jurisdiction": "RU",
        "primary_source_key": source["key"],
        "source_revision": meta.get("source_revision", "per capture"),
        "verified_at": now[:10],
        "verification_status": "approved",
        "text_sha256": text_sha,
        "source_checks": [{
            "source_key": source["key"],
            "status": "match",
            "retrieved_at": source.get("retrieved_at") or now,
            "source_document_id": meta["id"],
            "sha256": source["sha256"],
            "comparison_notes": (
                "Auto-filled from single official Minjust capture "
                "(single-source policy). Revision currency not independently verified."
            ),
        }],
        "articles": articles,
        "cross_references": [],
        "status_history": [],
    }


def step_records(indexed_keys: list[str]) -> int:
    intake = load_intake()
    sources_by_key = {s["key"]: s for s in intake.get("sources", [])}
    existing = {"release": {}, "sources": [], "documents": []}
    if RECORDS.is_file():
        try:
            existing = json.loads(RECORDS.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            pass

    docs_by_id = {d["id"]: d for d in existing.get("documents", []) if isinstance(d, dict)}
    sources_out = {s["key"]: s for s in existing.get("sources", []) if isinstance(s, dict) and "key" in s}

    for key in indexed_keys:
        source = sources_by_key.get(key)
        if not source:
            continue
        meta = DOCUMENT_MAP[key]
        index_path = EXTRACTED / f"{key}.article-index.json"
        text_path = EXTRACTED / source["extracted_candidate_file"]
        if not index_path.is_file() or not text_path.is_file():
            continue
        doc = build_document_from_index(index_path, text_path, meta, source)
        docs_by_id[doc["id"]] = doc
        sources_out[key] = {
            "key": key,
            "registry_id": source.get("registry_id", "minjust_official_documents"),
            "name": source.get("name") or f"Minjust {key}",
            "publisher": source.get("publisher") or "Министерство юстиции Российской Федерации",
            "official_url": source["official_url"],
            "source_type": source.get("source_type", "official_consolidated_text"),
            "retrieved_at": source.get("retrieved_at"),
            "source_document_id": meta["id"],
            "sha256": source["sha256"],
            "notes": "Auto-captured official original; used under single-source policy",
        }

    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    documents = list(docs_by_id.values())
    records = {
        "release": {
            "version": existing.get("release", {}).get("version") or "0.2.0-auto",
            "version_code": int(existing.get("release", {}).get("version_code") or 8),
            "created_at": now,
            "effective_as_of": now[:10],
            "notes": (
                "Auto-filled from official Minjust captures (single-source policy). "
                "Some DOCX files may lag the current amendment state; "
                "pravo cross-check requires Russian proxy."
            ),
        },
        "sources": list(sources_out.values()),
        "documents": documents,
    }
    RECORDS.parent.mkdir(parents=True, exist_ok=True)
    RECORDS.write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"records.json: {len(documents)} document(s), {len(sources_out)} source(s)")
    return len(documents)


def step_database() -> None:
    if not RECORDS.is_file():
        print("No records.json — skip database")
        return
    run([
        sys.executable,
        "agent/build_database.py",
        "--records", str(RECORDS),
        "--output", str(ROOT / "dist" / "content"),
    ], check=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy-file", default=None)
    parser.add_argument("--skip-capture", action="store_true")
    parser.add_argument("--skip-database", action="store_true")
    args = parser.parse_args()

    if not args.skip_capture:
        step_capture(args.proxy_file)
    step_extract()
    indexed = step_index()
    n = step_records(indexed)
    if not args.skip_database and n > 0:
        step_database()
    print(f"AUTO-FILL DONE: indexed={len(indexed)}, documents_in_records={n}")
    return 0 if n > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
