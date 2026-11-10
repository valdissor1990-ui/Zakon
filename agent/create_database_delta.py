#!/usr/bin/env python3
"""Create a deterministic, schema-compatible row delta between two legal SQLite releases.

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
REQUIRED_TABLES = set(TABLES) | {"content_metadata", "articles_fts"}


def schema_signature(db):
    """Return the complete application schema, including indexes and triggers."""
    return list(db.execute(
        """SELECT type, name, tbl_name, COALESCE(sql, '')
           FROM sqlite_master
           WHERE name NOT LIKE 'sqlite_%'
           ORDER BY type, name"""
    ))


def validate_database(db, label):
    if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
        raise ValueError(f"{label} SQLite database failed integrity_check")

    missing = REQUIRED_TABLES - {
        row[0] for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')"
        )
    }
    if missing:
        raise ValueError(f"{label} database missing tables: {sorted(missing)}")

    for table, expected_pk in TABLES.items():
        columns = list(db.execute(f"PRAGMA table_info({table})"))
        actual_pk = tuple(
            row[1] for row in sorted((row for row in columns if row[5]), key=lambda row: row[5])
        )
        if actual_pk != expected_pk:
            raise ValueError(
                f"{label} database has unsupported primary key for {table}: {actual_pk}"
            )

    fk_errors = list(db.execute("PRAGMA foreign_key_check"))
    if fk_errors:
        raise ValueError(f"{label} database has foreign-key violations: {fk_errors[:3]}")


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
            validate_database(db, label)

        old_metadata = dict(old.execute("SELECT key, value FROM content_metadata"))
        new_metadata = dict(new.execute("SELECT key, value FROM content_metadata"))
        if old_metadata.get("pack_version") != str(from_version):
            raise ValueError(
                f"Base database metadata version {old_metadata.get('pack_version')!r} "
                f"does not match requested from_version {from_version}"
            )
        if new_metadata.get("pack_version") != str(to_version):
            raise ValueError(
                f"Target database metadata version {new_metadata.get('pack_version')!r} "
                f"does not match requested to_version {to_version}"
            )
        if not old_metadata.get("pack_label") or not new_metadata.get("pack_label"):
            raise ValueError("Base and target databases must contain pack_label metadata")

        old_schema = schema_signature(old)
        new_schema = schema_signature(new)
        if old_schema != new_schema:
            raise ValueError("Base and target databases have different SQLite schemas; publish a full database")

        old_user_version = old.execute("PRAGMA user_version").fetchone()[0]
        new_user_version = new.execute("PRAGMA user_version").fetchone()[0]
        if old_user_version != new_user_version:
            raise ValueError(
                f"SQLite schema version changed ({old_user_version} -> {new_user_version}); publish a full database"
            )

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
