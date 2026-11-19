#!/usr/bin/env python3
"""Simple proxy pool with round-robin / fail-over rotation.

Sources of proxies (in order of precedence):
  1. Explicit list passed to ProxyPool(...)
  2. --proxy-file / PROXY_LIST_FILE  (one proxy URL per line)
  3. PROXY_LIST env  (comma or whitespace separated)
  4. Single HTTPS_PROXY / HTTP_PROXY / --proxy

Proxy URL formats supported:
  http://host:port
  http://user:pass@host:port
  socks5://user:pass@host:port   (requires PySocks if used with urllib)

Failed proxies are marked and skipped for a cool-down period so a dead
exit does not burn every retry.
"""
from __future__ import annotations

import os
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Optional
from urllib.parse import urlparse


@dataclass
class ProxyEntry:
    url: str
    failures: int = 0
    last_failure_at: float = 0.0
    last_success_at: float = 0.0

    def is_cooling_down(self, cooldown_s: float) -> bool:
        if self.failures == 0:
            return False
        return (time.time() - self.last_failure_at) < cooldown_s


@dataclass
class ProxyPool:
    proxies: list[ProxyEntry] = field(default_factory=list)
    cooldown_s: float = 120.0
    max_failures_before_long_cooldown: int = 3
    long_cooldown_s: float = 600.0
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    _index: int = 0

    @classmethod
    def from_sources(
        cls,
        *,
        explicit: Optional[Iterable[str]] = None,
        proxy_file: Optional[str | Path] = None,
        env_list: Optional[str] = None,
        single: Optional[str] = None,
        cooldown_s: float = 120.0,
    ) -> "ProxyPool":
        urls: list[str] = []

        if explicit:
            urls.extend(_normalize_list(explicit))

        file_path = proxy_file or os.environ.get("PROXY_LIST_FILE")
        if file_path:
            path = Path(file_path)
            if path.is_file():
                lines = path.read_text(encoding="utf-8").splitlines()
                urls.extend(_normalize_list(lines))

        env_raw = env_list if env_list is not None else os.environ.get("PROXY_LIST", "")
        if env_raw.strip():
            # allow comma or whitespace separation
            parts = re_split_proxies(env_raw)
            urls.extend(_normalize_list(parts))

        single = single or os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
        if single and single.strip():
            urls.append(single.strip())

        # de-dupe preserving order
        seen: set[str] = set()
        unique: list[str] = []
        for u in urls:
            if u not in seen:
                seen.add(u)
                unique.append(u)

        return cls(
            proxies=[ProxyEntry(url=u) for u in unique],
            cooldown_s=cooldown_s,
        )

    def __len__(self) -> int:
        return len(self.proxies)

    def has_proxies(self) -> bool:
        return bool(self.proxies)

    def next(self) -> Optional[str]:
        """Return next usable proxy URL, or None if pool is empty / all cooling."""
        with self._lock:
            if not self.proxies:
                return None
            n = len(self.proxies)
            for _ in range(n):
                entry = self.proxies[self._index % n]
                self._index = (self._index + 1) % n
                cooldown = (
                    self.long_cooldown_s
                    if entry.failures >= self.max_failures_before_long_cooldown
                    else self.cooldown_s
                )
                if not entry.is_cooling_down(cooldown):
                    return entry.url
            # all cooling — still return the least-recently-failed one
            best = min(self.proxies, key=lambda e: e.last_failure_at or 0.0)
            return best.url

    def mark_success(self, proxy_url: str) -> None:
        with self._lock:
            for entry in self.proxies:
                if entry.url == proxy_url:
                    entry.failures = 0
                    entry.last_success_at = time.time()
                    return

    def mark_failure(self, proxy_url: str) -> None:
        with self._lock:
            for entry in self.proxies:
                if entry.url == proxy_url:
                    entry.failures += 1
                    entry.last_failure_at = time.time()
                    return

    def status_summary(self) -> str:
        with self._lock:
            if not self.proxies:
                return "proxy pool: empty"
            parts = []
            for e in self.proxies:
                host = _safe_host(e.url)
                parts.append(f"{host}(fail={e.failures})")
            return "proxy pool: " + ", ".join(parts)


def re_split_proxies(raw: str) -> list[str]:
    import re
    return [p for p in re.split(r"[,\s]+", raw.strip()) if p]


def _normalize_list(items: Iterable[str]) -> list[str]:
    out: list[str] = []
    for item in items:
        s = (item or "").strip()
        if not s or s.startswith("#"):
            continue
        # basic sanity: must look like a URL with scheme
        if "://" not in s:
            # bare host:port → assume http
            s = "http://" + s
        parsed = urlparse(s)
        if parsed.scheme not in ("http", "https", "socks5", "socks4", "socks5h"):
            continue
        if not parsed.hostname:
            continue
        out.append(s)
    return out


def _safe_host(proxy_url: str) -> str:
    try:
        p = urlparse(proxy_url)
        return p.hostname or proxy_url
    except Exception:
        return "?"
