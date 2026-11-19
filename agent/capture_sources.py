#!/usr/bin/env python3
"""Capture raw official-source evidence; this tool never extracts or invents legal text."""
import argparse
import hashlib
import json
import os
import re
import socket
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

try:
    from adapters.pravo import fetch_with_retries, geo_restriction_hint, is_pravo_host
    from adapters.proxy_pool import ProxyPool
except ImportError:
    from adapters.pravo import fetch_with_retries, geo_restriction_hint, is_pravo_host  # type: ignore
    from adapters.proxy_pool import ProxyPool  # type: ignore

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "content" / "sources" / "registry.json"
MAX_BYTES = 25 * 1024 * 1024

def allowed_hosts(registry: dict, registry_id: str) -> set[str]:
    source = next((item for item in registry.get("sources", []) if item.get("id") == registry_id), None)
    if source is None:
        raise ValueError(f"Source is not in the approved registry: {registry_id}")
    urls = [source.get("url", ""), source.get("access_url", "")]
    return {
        urlparse(value).netloc.lower()
        for value in urls
        if urlparse(value).scheme == "https" and urlparse(value).netloc
    }

def validate_url(url: str, hosts: set[str]) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.username or parsed.password:
        raise ValueError("Only credential-free HTTPS source URLs are allowed")
    if parsed.netloc.lower() not in hosts:
        raise ValueError(f"URL host is not allowlisted: {parsed.netloc}")

class AllowlistedRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, hosts: set[str]):
        super().__init__()
        self.hosts = hosts

    def redirect_request(self, request, response, code, message, headers, new_url):
        validate_url(new_url, self.hosts)
        return super().redirect_request(request, response, code, message, headers, new_url)

def capture(
    url: str,
    hosts: set[str],
    *,
    proxy: str | None = None,
    proxy_pool: ProxyPool | None = None,
) -> tuple[bytes, str, str]:
    """Capture a single HTTPS source. Uses stronger retries + rotation for pravo.gov.ru."""
    if is_pravo_host(url):
        print(f"Using pravo-aware fetch (rotation + timeouts) for {url}", flush=True)
        if proxy_pool and proxy_pool.has_proxies():
            print(f"  {proxy_pool.status_summary()}", flush=True)
        try:
            payload, final_url, content_type = fetch_with_retries(
                url,
                timeout=90.0,
                max_attempts=6,
                proxy=proxy,
                proxy_pool=proxy_pool,
                max_bytes=MAX_BYTES,
            )
        except Exception:
            print(geo_restriction_hint(), flush=True)
            raise
        validate_url(final_url, hosts)
        return payload, final_url, content_type

    # Non-pravo: still allow a single proxy from the pool / env
    current_proxy = None
    if proxy_pool and proxy_pool.has_proxies():
        current_proxy = proxy_pool.next()
    else:
        current_proxy = proxy or os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")

    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Zakon-Legal-Source-Capture/1.2", "Accept": "*/*"},
    )
    handlers: list = [AllowlistedRedirectHandler(hosts)]
    if current_proxy:
        handlers.insert(0, urllib.request.ProxyHandler({"https": current_proxy, "http": current_proxy}))
    opener = urllib.request.build_opener(*handlers)
    original_getaddrinfo = socket.getaddrinfo

    def ipv4_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
        return original_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)

    socket.getaddrinfo = ipv4_getaddrinfo
    try:
        last_error = None
        for attempt in range(1, 5):
            try:
                with opener.open(request, timeout=45) as response:
                    final_url = response.geturl()
                    content_type = response.headers.get("Content-Type", "application/octet-stream")
                    payload = response.read(MAX_BYTES + 1)
                if current_proxy and proxy_pool:
                    proxy_pool.mark_success(current_proxy)
                break
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last_error = error
                if current_proxy and proxy_pool:
                    proxy_pool.mark_failure(current_proxy)
                    current_proxy = proxy_pool.next()
                    # rebuild opener with next proxy
                    handlers = [AllowlistedRedirectHandler(hosts)]
                    if current_proxy:
                        handlers.insert(
                            0,
                            urllib.request.ProxyHandler(
                                {"https": current_proxy, "http": current_proxy}
                            ),
                        )
                    opener = urllib.request.build_opener(*handlers)
                if attempt == 4:
                    raise
                print(f"Retry {attempt}/4 for {url}: {error}", flush=True)
                time.sleep(attempt * 2)
        else:
            raise RuntimeError(f"Unable to capture {url}: {last_error}")
    finally:
        socket.getaddrinfo = original_getaddrinfo
    validate_url(final_url, hosts)
    if len(payload) > MAX_BYTES:
        raise ValueError(f"Source exceeds the {MAX_BYTES} byte capture limit")
    return payload, final_url, content_type

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, default=ROOT / "content" / "records" / "records.json")
    parser.add_argument("--output", type=Path, default=ROOT / "content" / "evidence")
    parser.add_argument(
        "--proxy",
        default=None,
        help="Single HTTPS proxy (http://user:pass@host:port). Also reads HTTPS_PROXY.",
    )
    parser.add_argument(
        "--proxy-file",
        default=None,
        help="File with one proxy URL per line (rotation). Also reads PROXY_LIST_FILE / PROXY_LIST.",
    )
    args = parser.parse_args()

    proxy_pool = ProxyPool.from_sources(
        single=args.proxy,
        proxy_file=args.proxy_file,
    )
    if proxy_pool.has_proxies():
        print(proxy_pool.status_summary(), flush=True)
    else:
        print(
            "No proxies configured. pravo.gov.ru captures from non-RU networks will likely time out.",
            flush=True,
        )

    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    records = json.loads(args.records.read_text(encoding="utf-8"))
    sources = records.get("sources", [])
    if not sources:
        raise SystemExit("No source captures requested in records.sources")

    captured = []
    seen_keys = set()
    for source in sources:
        key = source["key"]
        if key in seen_keys:
            raise SystemExit(f"Duplicate capture key: {key}")
        seen_keys.add(key)
        registry_id = source.get("registry_id", key)
        if not isinstance(key, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,100}", key):
            raise SystemExit("Each capture key must use lowercase letters, digits, '_' or '-' (max 101 characters)")
        hosts = allowed_hosts(registry, registry_id)
        url = source["official_url"]
        validate_url(url, hosts)
        print(f"Capturing {key} from {url}", flush=True)
        try:
            payload, final_url, content_type = capture(
                url, hosts, proxy=args.proxy, proxy_pool=proxy_pool
            )
            validate_url(final_url, hosts)
        except Exception as error:
            if is_pravo_host(url):
                print(geo_restriction_hint(), flush=True)
            print(f"FAILED {key}: {type(error).__name__}: {error}", flush=True)
            captured.append({
                "source_key": key,
                "registry_id": registry_id,
                "requested_url": url,
                "capture_status": "failed",
                "error": f"{type(error).__name__}: {error}",
            })
            continue
        print(f"Captured {key}: {len(payload)} bytes from {final_url}", flush=True)
        digest = hashlib.sha256(payload).hexdigest()
        expected = source.get("sha256", "").lower()
        if expected and digest != expected:
            raise SystemExit(
                f"{key}: captured bytes SHA-256 differs from records.json; "
                "review the source revision and update its evidence metadata manually"
            )
        retrieved_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
        directory = args.output / key
        directory.mkdir(parents=True, exist_ok=True)
        filename = f"{digest}.source"
        (directory / filename).write_bytes(payload)
        metadata = {
            "source_key": key,
            "registry_id": registry_id,
            "requested_url": url,
            "final_url": final_url,
            "retrieved_at": retrieved_at,
            "content_type": content_type,
            "byte_length": len(payload),
            "sha256": digest,
            "evidence_file": filename,
            "legal_text_extraction_performed": False,
        }
        (directory / f"{digest}.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        captured.append(metadata)

    report = {
        "captured": sum(1 for item in captured if item.get("sha256")),
        "failed": [item for item in captured if item.get("capture_status") == "failed"],
        "results": captured,
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "capture-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Captured {report['captured']} raw source file(s); failed: {len(report['failed'])}.")
    print("No legal text was parsed, rewritten, or generated.")
    return 0 if report["captured"] else 1

if __name__ == "__main__":
    raise SystemExit(main())
