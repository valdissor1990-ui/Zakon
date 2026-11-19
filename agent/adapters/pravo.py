#!/usr/bin/env python3
"""Helpers for pravo.gov.ru and publication.pravo.gov.ru.

Important operational constraint (2026):
  The integrated bank endpoint https://pravo.gov.ru/proxy/ips/ is known to be
  IP-restricted / heavily filtered for non-Russian networks. GitHub Actions
  runners outside RU typically see timeouts. Official publication portal
  (publication.pravo.gov.ru) can also be slow or blocked from the same networks.

Recommended capture strategy:
  1. Prefer Minjust hosted originals when available (already allowlisted).
  2. For pravo endpoints run capture from a Russian exit (VPS / residential proxy)
     using HTTP_PROXY / HTTPS_PROXY or --proxy.
  3. Never invent or reconstruct legal text when the source is unreachable.
"""
from __future__ import annotations

import os
import re
import socket
import time
import urllib.error
import urllib.request
from typing import Optional
from urllib.parse import quote, urlparse

# Document numbers in the IPS system are numeric (nd=...)
IPS_DOC_RE = re.compile(r"nd=(\d+)")

# Official publication EO numbers look like 0001202404220011
EO_NUMBER_RE = re.compile(r"\b0001\d{12}\b")


def ips_docbody_url(nd: str | int) -> str:
    """Build consolidated-text URL for the integrated law bank."""
    return f"https://pravo.gov.ru/proxy/ips/?docbody=&nd={int(nd)}"


def ips_doc_itself_url(nd: str | int, page: int = 1, rdk: int = 0) -> str:
    """Build alternative IPS view URL."""
    return (
        f"https://pravo.gov.ru/proxy/ips/?doc_itself=&nd={int(nd)}"
        f"&page={page}&rdk={rdk}"
    )


def publication_document_url(eo_number: str) -> str:
    """Build official publication page URL by EO number."""
    eo = eo_number.strip()
    if not EO_NUMBER_RE.fullmatch(eo):
        raise ValueError(f"Unexpected EO number format: {eo}")
    return f"https://publication.pravo.gov.ru/document/{eo}"


def publication_api_search_url(
    number: str | None = None,
    number_search_type: int = 0,
    page: int = 1,
    page_size: int = 20,
) -> str:
    """Build a search URL against the official publication API (when reachable)."""
    base = "https://publication.pravo.gov.ru/api/Document/Get"
    params = [f"Page={page}", f"PageSize={page_size}"]
    if number:
        params.append(f"NumberSearchType={number_search_type}")
        params.append(f"Number={quote(number)}")
    return base + "?" + "&".join(params)


def extract_nd_from_url(url: str) -> Optional[str]:
    m = IPS_DOC_RE.search(url)
    return m.group(1) if m else None


def is_pravo_host(url: str) -> bool:
    host = urlparse(url).netloc.lower()
    return host.endswith("pravo.gov.ru") or host == "pravo.gov.ru"


def fetch_with_retries(
    url: str,
    *,
    timeout: float = 60.0,
    max_attempts: int = 5,
    user_agent: str = "Zakon-Legal-Source-Capture/1.1 (pravo-adapter)",
    proxy: str | None = None,
    force_ipv4: bool = True,
    max_bytes: int = 25 * 1024 * 1024,
) -> tuple[bytes, str, str]:
    """HTTPS fetch with longer timeout, exponential backoff and optional proxy.

    Returns (payload, final_url, content_type).
    Raises the last error after all attempts fail.
    """
    proxy = proxy or os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
    handlers: list = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"https": proxy, "http": proxy}))
    opener = urllib.request.build_opener(*handlers)
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": user_agent,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.5",
        },
    )

    original_getaddrinfo = socket.getaddrinfo

    def ipv4_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
        return original_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)

    if force_ipv4:
        socket.getaddrinfo = ipv4_getaddrinfo

    last_error: Exception | None = None
    try:
        for attempt in range(1, max_attempts + 1):
            try:
                with opener.open(request, timeout=timeout) as response:
                    final_url = response.geturl()
                    content_type = response.headers.get(
                        "Content-Type", "application/octet-stream"
                    )
                    payload = response.read(max_bytes + 1)
                if len(payload) > max_bytes:
                    raise ValueError(f"Response exceeds {max_bytes} byte limit")
                return payload, final_url, content_type
            except (urllib.error.URLError, TimeoutError, OSError, ValueError) as err:
                last_error = err
                if attempt == max_attempts:
                    break
                sleep_s = min(2 ** attempt, 30)
                print(
                    f"pravo fetch retry {attempt}/{max_attempts} for {url}: {err}; "
                    f"sleep {sleep_s}s",
                    flush=True,
                )
                time.sleep(sleep_s)
    finally:
        if force_ipv4:
            socket.getaddrinfo = original_getaddrinfo

    raise RuntimeError(f"Unable to fetch {url} after {max_attempts} attempts: {last_error}")


def geo_restriction_hint() -> str:
    return (
        "pravo.gov.ru (especially /proxy/ips/) is frequently unreachable from "
        "non-Russian IP ranges. Run capture from a Russian VPS or set HTTPS_PROXY "
        "to a Russian exit node. Minjust originals remain the preferred primary source "
        "when pravo is blocked."
    )
