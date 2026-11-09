#!/usr/bin/env python3
"""Validate the staged legal-act catalog; catalog entries are not published legal text."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "content" / "catalog" / "core-federal-acts.json"
REGISTRY = ROOT / "content" / "sources" / "registry.json"


def validate_catalog(catalog: dict, registry: dict) -> None:
    documents = catalog.get("documents", [])
    source_ids = {item.get("id") for item in registry.get("sources", [])}
    ids = [item.get("id") for item in documents]
    if not documents or len(ids) != len(set(ids)) or any(not value for value in ids):
        raise ValueError("Catalog document IDs must be present, unique and non-empty")
    catalog_sources = catalog.get("sources", [])
    for source_id in catalog_sources:
        if source_id not in source_ids:
            raise ValueError(f"Catalog references an unknown source: {source_id}")
    registry_by_id = {item["id"]: item for item in registry.get("sources", [])}
    if len({registry_by_id[key].get("system_id") for key in catalog_sources}) < 3:
        raise ValueError("Catalog must include at least three state information systems")
    if len({registry_by_id[key].get("operator_id") for key in catalog_sources}) < 2:
        raise ValueError("Catalog must include at least two state operators")
    for item in documents:
        if item.get("content_status") != "not_imported":
            raise ValueError(f"{item.get('id')}: catalog metadata must not imply imported legal text")
        if item.get("verification_status") != "not_started":
            raise ValueError(f"{item.get('id')}: unverified catalog item must remain not_started")
        if not item.get("title") or not item.get("document_type") or not item.get("source_search_terms"):
            raise ValueError(f"{item.get('id')}: title, type and source search terms are required")
        if not isinstance(item.get("priority"), int) or item["priority"] not in (1, 2, 3):
            raise ValueError(f"{item.get('id')}: priority must be 1, 2 or 3")


def main() -> int:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    validate_catalog(catalog, registry)
    print(f"Legal act catalog valid: {len(catalog['documents'])} items; no legal text is marked as imported.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        raise SystemExit(str(error))
