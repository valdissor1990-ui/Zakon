#!/usr/bin/env python3
"""Fetch approved registry sources and store hashed captures. No legal text parsing."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from adapters.base import SimpleHttpsAdapter, SourceSpec
from adapters.http_util import RateLimiter

REGISTRY = ROOT / "content" / "sources" / "registry.json"
CAPTURES = ROOT / "content" / "records" / "captures"


def load_specs() -> list[SourceSpec]:
    data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    out: list[SourceSpec] = []
    for s in data.get("sources", []):
        out.append(
            SourceSpec(
                source_id=s["id"],
                name=s["name"],
                url=s.get("access_url") or s["url"],
            )
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="all", help="source id or 'all'")
    parser.add_argument("--interval", type=float, default=2.0, help="min seconds between requests")
    parser.add_argument("--captures", type=Path, default=CAPTURES)
    args = parser.parse_args()

    specs = load_specs()
    if args.source != "all":
        specs = [s for s in specs if s.source_id == args.source]
        if not specs:
            print(f"Unknown source: {args.source}", file=sys.stderr)
            return 2

    limiter = RateLimiter(min_interval_sec=args.interval)
    ok = 0
    for spec in specs:
        adapter = SimpleHttpsAdapter(spec)
        try:
            meta = adapter.capture(args.captures, limiter)
            print(f"OK {spec.source_id} -> {meta}")
            ok += 1
        except Exception as e:
            print(f"FAIL {spec.source_id}: {e}", file=sys.stderr)
    print(f"Captured {ok}/{len(specs)}")
    return 0 if ok == len(specs) else 1


if __name__ == "__main__":
    raise SystemExit(main())
