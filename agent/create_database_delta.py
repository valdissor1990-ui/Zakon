#!/usr/bin/env python3
"""Create a deterministic, version-bound row delta between two legal SQLite releases.

The patch contains only changed rows, not legal-text synthesis. The Android client
applies it in one SQLite transaction and falls back to the full database when it
cannot safely apply the patch.
"""
import argparse
import hashlib
import json
import sqlite3
from pathlib import Path

TABLES = {
    "sources": ("id",),
    "documents": ("id",),
    "articles": ("id",),
    "document_source_checks": ("id",),
    "cross_references": ("id",),
    "legal_status_history": ("id",),
    "content_release": ("id",),
}
UPSERT_ORDER = list(TABLES)
DELETE_ORDER = [
    "cross_references", "document_source_checks", "legal_status_history",
    "articles", "documents", "sources", "content_release",
]

def rows_by_key(db, table, pk):
    db.row_factory = sqlite3.Row
    rows = {}
    for row in db.execute(f"SELECT * FROM {table}"):
        item = dict(row)
        key = tuple(item[column] for column in pk)
        rows[key] = item
    return rows

def diff(old_path: Path, new_path: Path, from_version: int, to_version: int) -> dict:
    if from_version <= 0 or to_version <= from_version:
        raise ValueError("Delta versions must be positive and strictly increasing")
    old = sqlite3.connect(f"file:{old_path}?mode=ro", uri=True)
    new = sqlite3.connect(f"file:{new_path}?mode=ro", uri=True)
    try:
        for label, db in (("base", old), ("target", new)):
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise ValueError(f"{label} SQLite database failed integrity_check")
            existing = {r[0] for r in db.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )}
            missing = set(TABLES) - existing
            if missing:
                raise ValueError(f"{label} database missing tables: {sorted(missing)}")
        operations = {}
        for table, pk in TABLES.items():
            before = rows_by_key(old, table, pk)
            after = rows_by_key(new, table, pk)
            deleted_keys = list(before.keys() - after.keys())
            if table == "articles":
                by_id = {row["id"]: row for row in before.values()}
                def depth(article_id, seen=None):
                    seen = seen or set()
                    if article_id in seen:
                        raise ValueError("Cycle detected in article parent hierarchy")
                    parent = by_id[article_id].get("parent_id")
                    return 0 if not parent or parent not in by_id else 1 + depth(parent, seen | {article_id})
                deleted_keys.sort(key=lambda key: depth(key[0]), reverse=True)
            deletes = [list(key) for key in deleted_keys]
            changed = [
                after[key] for key in after
                if key not in before or before[key] != after[key]
            ]
            if table == "articles":
                changed.sort(key=lambda row: (row.get("ordinal", 0), row["id"]))
            if deletes or changed:
                operations[table] = {"delete": deletes, "upsert": changed}
        return {
            "schema_version": 1,
            "from_version_code": from_version,
            "to_version_code": to_version,
            "operations": operations,
        }
    finally:
        old.close()
        new.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--from-version", type=int, required=True)
    parser.add_argument("--to-version", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    patch = diff(args.base, args.target, args.from_version, args.to_version)
    encoded = (json.dumps(patch, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(encoded)
    print(f"Delta created: {len(encoded)} bytes; sha256={hashlib.sha256(encoded).hexdigest()}")

if __name__ == "__main__":
    main()
