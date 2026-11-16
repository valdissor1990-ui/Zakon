#!/usr/bin/env python3
"""Merge document shells + article shards into content/updates/pack.json."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UPDATES = ROOT / "content" / "updates"
SHARDS = UPDATES / "shards"

def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))

def main() -> int:
    docs = load_json(SHARDS / "documents.json")
    articles = []
    for shard in sorted(SHARDS.glob("articles_*.json")):
        part = load_json(shard)
        if not isinstance(part, list):
            raise SystemExit(f"shard must be a list: {shard}")
        articles.extend(part)
    seen = set()
    unique = []
    for a in articles:
        if a["id"] in seen:
            continue
        seen.add(a["id"])
        unique.append(a)
    pack = {
        "version": 8,
        "label": "2026-11-16 seed export: Constitution + KoAP + catalog",
        "documents": docs,
        "articles": sorted(unique, key=lambda a: (a["document_id"], a.get("ordinal", 0), a["id"])),
    }
    raw = json.dumps(pack, ensure_ascii=False, indent=2) + "\n"
    out = UPDATES / "pack.json"
    out.write_text(raw, encoding="utf-8")
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    manifest = {
        "version": pack["version"],
        "label": pack["label"],
        "pack_url": "https://raw.githubusercontent.com/valdissor1990-ui/Zakon/main/content/updates/pack.json",
        "sha256": digest,
        "note": "Справочный пакет (seed). Не полная официальная база.",
    }
    (UPDATES / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"pack: {len(pack['documents'])} docs, {len(pack['articles'])} articles")
    print(f"sha256: {digest}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
