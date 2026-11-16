"""Adapter protocol: capture official pages; never synthesize legal articles."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .http_util import FetchResult, RateLimiter, fetch_https, save_capture


@dataclass(frozen=True)
class SourceSpec:
    source_id: str
    name: str
    url: str


class SourceAdapter(Protocol):
    spec: SourceSpec

    def capture(self, captures_dir: Path, limiter: RateLimiter) -> Path:
        """Fetch official URL and persist capture. Returns meta path."""
        ...


class SimpleHttpsAdapter:
    """Fetches the registry homepage/URL and stores raw bytes + metadata."""

    def __init__(self, spec: SourceSpec) -> None:
        self.spec = spec

    def capture(self, captures_dir: Path, limiter: RateLimiter) -> Path:
        result: FetchResult = fetch_https(self.spec.url, limiter=limiter)
        return save_capture(
            captures_dir,
            self.spec.source_id,
            result,
            extra_meta={"adapter": "SimpleHttpsAdapter", "source_name": self.spec.name},
        )
