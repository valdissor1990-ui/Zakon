#!/usr/bin/env python3
"""Validate the configured state-operated source registry; this is not legal validation."""
import json
import sys
from pathlib import Path
from urllib.parse import urlparse

DEFAULT = Path(__file__).resolve().parents[1] / "content" / "sources" / "registry.json"


def https_host(value: str, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{label} must be an HTTPS URL")
    parsed = urlparse(value)
    try:
        port = parsed.port
    except ValueError as error:
        raise ValueError(f"{label} has an invalid port") from error
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
    ):
        raise ValueError(f"{label} must be a credential-free HTTPS URL on port 443")
    return parsed.hostname.lower()


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
        try:
            host = https_host(item.get("url", ""), f"Source {item.get('id')}")
            if item.get("access_url"):
                https_host(item["access_url"], f"Source {item.get('id')} access_url")
        except ValueError as error:
            raise SystemExit(str(error)) from error
        if not item.get("publisher") or not item.get("authority") or not item.get("role"):
            raise SystemExit(f"Source {item.get('id')} is missing provenance metadata")
        hosts.add(host)
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
