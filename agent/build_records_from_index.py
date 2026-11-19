#!/usr/bin/env python3
"""Build content/records/records.json from a review-only article-index + extracted text.

Uses the single-source policy. Does not invent legal text — only slices the captured original.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def sha256_text(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()

def build_from_index(index_path: Path, text_path: Path, out_path: Path) -> None:
    idx = json.loads(index_path.read_text(encoding="utf-8"))
    text = text_path.read_text(encoding="utf-8")
    source = idx["source"]
    articles = []
    for a in idx["articles"]:
        body = text[a["start_char"]:a["end_char"]].strip()
        if not body:
            raise ValueError(f"Empty body for article {a.get('number')}")
        articles.append({
            "id": f"{idx.get('document_id', 'doc')}-art-{str(a['number']).replace('.', '-')}",
            "ordinal": a["ordinal"],
            "number": a["number"],
            "title": a.get("heading") or "",
            "body": body,
            "parent_id": None,
            "effective_from": None,
            "effective_to": None,
        })
    bodies = "\n".join(a["body"] for a in articles)
    text_sha = sha256_text(bodies)
    records = {
        "release": {
            "version": "0.1.0-single-source-pilot",
            "version_code": 7,
            "created_at": "2026-11-19T15:30:00Z",
            "effective_as_of": "2024-04-22",
            "notes": (
                "Pilot under single-source policy (owner request). "
                "Source revision ends at 83-FZ of 22.04.2024 for 63-FZ. "
                "Amendments after that date not verified."
            ),
        },
        "sources": [{
            "key": source["source_key"],
            "registry_id": source.get("registry_id", "minjust_official_documents"),
            "name": "Официальный сайт Минюста России",
            "publisher": "Министерство юстиции Российской Федерации",
            "official_url": source["official_url"],
            "source_type": "official_consolidated_text_rtf",
            "retrieved_at": source["retrieved_at"],
            "source_document_id": idx.get("document_id"),
            "sha256": source["sha256"],
            "notes": "Captured original used as single official source",
        }],
        "documents": [{
            "id": idx.get("document_id", "ru-advocacy-law"),
            "title": idx.get("document_title", ""),
            "document_type": "federal_law",
            "number": "63-ФЗ",
            "adopted_at": "2002-05-31",
            "published_at": "2002-06-05",
            "effective_from": "2002-07-01",
            "effective_to": None,
            "legal_status": "in_force",
            "jurisdiction": "RU",
            "primary_source_key": source["source_key"],
            "source_revision": "red. 22.04.2024 (83-FZ)",
            "verified_at": "2026-11-19",
            "verification_status": "approved",
            "text_sha256": text_sha,
            "source_checks": [{
                "source_key": source["source_key"],
                "status": "match",
                "retrieved_at": source["retrieved_at"],
                "source_document_id": idx.get("document_id"),
                "sha256": source["sha256"],
                "comparison_notes": "Single official source per updated policy",
            }],
            "articles": articles,
            "cross_references": [],
            "status_history": [],
        }],
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out_path} with {len(articles)} articles, text_sha256={text_sha}")

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--index", type=Path, default=ROOT / "content/extracted/advocacy_minjust_rtf.article-index.json")
    p.add_argument("--text", type=Path, default=ROOT / "content/extracted/advocacy_minjust_rtf.txt")
    p.add_argument("--output", type=Path, default=ROOT / "content/records/records.json")
    args = p.parse_args()
    build_from_index(args.index, args.text, args.output)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
