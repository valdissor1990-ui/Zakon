"""HTTPS fetch with rate limiting, retries, and SHA-256 capture."""
from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


@dataclass(frozen=True)
class FetchResult:
    url: str
    status: int
    body: bytes
    sha256: str
    retrieved_at: float  # unix epoch


class RateLimiter:
    """Minimum interval between requests (per process)."""

    def __init__(self, min_interval_sec: float = 1.5) -> None:
        self.min_interval_sec = min_interval_sec
        self._last: float = 0.0

    def wait(self) -> None:
        now = time.monotonic()
        delay = self.min_interval_sec - (now - self._last)
        if delay > 0:
            time.sleep(delay)
        self._last = time.monotonic()


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require_https(url: str) -> None:
    if not url.startswith("https://"):
        raise ValueError(f"Only HTTPS allowed: {url}")


def fetch_https(
    url: str,
    *,
    limiter: Optional[RateLimiter] = None,
    timeout_sec: float = 30.0,
    max_retries: int = 3,
    user_agent: str = "ZakonContentAgent/0.1 (+offline legal reference builder)",
) -> FetchResult:
    """GET url over HTTPS with exponential backoff. Does not parse HTML."""
    require_https(url)
    last_err: Exception | None = None
    for attempt in range(max_retries):
        if limiter is not None:
            limiter.wait()
        req = urllib.request.Request(
            url,
            headers={"User-Agent": user_agent, "Accept": "*/*"},
            method="GET",
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
                status = getattr(resp, "status", 200) or 200
                body = resp.read()
                return FetchResult(
                    url=url,
                    status=int(status),
                    body=body,
                    sha256=sha256_hex(body),
                    retrieved_at=time.time(),
                )
        except urllib.error.HTTPError as e:
            last_err = e
            if e.code in (429, 500, 502, 503, 504) and attempt + 1 < max_retries:
                time.sleep(2 ** attempt)
                continue
            raise
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last_err = e
            if attempt + 1 < max_retries:
                time.sleep(2 ** attempt)
                continue
            raise
    raise RuntimeError(f"fetch failed after retries: {last_err}")


def save_capture(
    captures_dir: Path,
    source_id: str,
    result: FetchResult,
    *,
    extra_meta: Optional[dict] = None,
) -> Path:
    """Write body + sidecar meta JSON. Returns path to .meta.json."""
    folder = captures_dir / source_id
    folder.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(result.retrieved_at))
    base = folder / f"{stamp}_{result.sha256[:12]}"
    bin_path = base.with_suffix(".bin")
    meta_path = base.with_suffix(".meta.json")
    bin_path.write_bytes(result.body)
    meta = {
        "source_id": source_id,
        "url": result.url,
        "http_status": result.status,
        "sha256": result.sha256,
        "retrieved_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(result.retrieved_at)),
        "bytes": len(result.body),
    }
    if extra_meta:
        meta.update(extra_meta)
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return meta_path
