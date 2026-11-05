#!/usr/bin/env python3
"""Validate the configured state-operated source registry; this is not legal validation."""
import json
import sys
from pathlib import Path
from urllib.parse import urlparse

DEFAULT = Path(__file__).resolve().parents[1] / "content" / "sources" / "registry.json"

def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    data = json.loads(path.read_text(encoding="utf-8"))
    sources = data.get("sources", [])
    rule = data.get("publication_rule", {})
    required = int(rule.get("minimum_distinct_sources", 3))
    if len(sources) < required:
        raise SystemExit(f"Need at least {required} registered sources")
    ids = [item.get("id") for item in sources]
    if len(set(ids)) != len(ids) or any(not value for value in ids):
        raise SystemExit("Source IDs must be present and unique")
    hosts = set()
    for item in sources:
        parsed = urlparse(item.get("url", ""))
        if parsed.scheme != "https" or not parsed.netloc:
            raise SystemExit(f"Source {item.get('id')} must use an HTTPS URL")
        if not item.get("publisher") or not item.get("authority") or not item.get("role"):
            raise SystemExit(f"Source {item.get('id')} is missing provenance metadata")
        hosts.add(parsed.netloc.lower())
    if len(hosts) < required:
        raise SystemExit("Sources must use distinct hostnames to provide independent cross-checks")
    if rule.get("mismatch_action") != "block_release":
        raise SystemExit("A source mismatch must block publication")
    if rule.get("never_generate_missing_legal_text") is not True:
        raise SystemExit("Missing legal text must never be generated")
    print(f"Source registry valid: {len(sources)} configured sources.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
