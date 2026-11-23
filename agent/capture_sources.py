#!/usr/bin/env python3
"""Capture raw official-source evidence; this tool never extracts or invents legal text."""
import argparse
import hashlib
import json
import re
import socket
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

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


def load_proxies(path: Path | None) -> list[str]:
    """Read optional HTTP(S) proxy URLs without printing or storing credentials."""
    if path is None:
        return []
    proxies = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        value = raw_line.strip()
        if not value or value.startswith("#"):
            continue
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError(f"Proxy line {line_number} must be an HTTP(S) URL with a host")
        try:
            parsed.port
        except ValueError as error:
            raise ValueError(f"Proxy line {line_number} has an invalid port") from error
        proxies.append(value)
    return proxies


def proxy_for_attempt(proxies: list[str], attempt: int) -> str | None:
    """Use each configured proxy once, then fall back to a direct request."""
    return proxies[attempt - 1] if 1 <= attempt <= len(proxies) else None


def safe_error_message(error: Exception, proxies: list[str]) -> str:
    """Remove proxy credentials and endpoints from messages written to logs/reports."""
    message = str(error)
    for proxy in proxies:
        parsed = urlparse(proxy)
        for secret_part in (proxy, parsed.netloc, parsed.hostname, parsed.username, parsed.password):
            if secret_part:
                message = message.replace(secret_part, "[redacted]")
    return message


class AllowlistedRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, hosts: set[str]):
        super().__init__()
        self.hosts = hosts

    def redirect_request(self, request, response, code, message, headers, new_url):
        validate_url(new_url, self.hosts)
        return super().redirect_request(request, response, code, message, headers, new_url)

def capture(url: str, hosts: set[str], proxies: list[str] | None = None) -> tuple[bytes, str, str]:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "Zakon-Legal-Source-Capture/1.0", "Accept": "*/*"},
    )
    proxies = proxies or []
    # Try configured proxies once each, then retain bounded direct retries.
    attempts = max(3, min(7, len(proxies) + 1))
    # Some hosted runners prefer IPv6 routes that are unavailable to selected
    # government hosts. Force IPv4 only for this bounded HTTPS request.
    original_getaddrinfo = socket.getaddrinfo

    def ipv4_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
        return original_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)

    socket.getaddrinfo = ipv4_getaddrinfo
    try:
        last_error = None
        for attempt in range(1, attempts + 1):
            proxy = proxy_for_attempt(proxies, attempt)
            handlers = [AllowlistedRedirectHandler(hosts)]
            if proxy:
                handlers.insert(
                    0,
                    urllib.request.ProxyHandler({"https": proxy, "http": proxy}),
                )
            opener = urllib.request.build_opener(*handlers)
            try:
                with opener.open(request, timeout=45) as response:
                    final_url = response.geturl()
                    content_type = response.headers.get("Content-Type", "application/octet-stream")
                    payload = response.read(MAX_BYTES + 1)
                break
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last_error = error
                if attempt == attempts:
                    raise
                print(
                    f"Retry {attempt}/{attempts} for {url}: "
                    f"{safe_error_message(error, proxies)}",
                    flush=True,
                )
                time.sleep(min(attempt * 2, 10))
        else:
            raise RuntimeError(
                f"Unable to capture {url}: {safe_error_message(last_error, proxies)}"
            )
    finally:
        socket.getaddrinfo = original_getaddrinfo
    # Do not rely solely on the redirect handler: validate the final response URL too,
    # including custom openers or transports that do not invoke HTTPRedirectHandler.
    validate_url(final_url, hosts)
    if len(payload) > MAX_BYTES:
        raise ValueError(f"Source exceeds the {MAX_BYTES} byte capture limit")
    return payload, final_url, content_type

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, default=ROOT / "content" / "records" / "records.json")
    parser.add_argument("--output", type=Path, default=ROOT / "content" / "evidence")
    parser.add_argument(
        "--proxy-file",
        type=Path,
        default=None,
        help="Optional file with one HTTP(S) proxy URL per line; credentials are never logged.",
    )
    args = parser.parse_args()
    proxies = load_proxies(args.proxy_file)
    if proxies:
        print(f"Optional proxy rotation configured: {len(proxies)} endpoint(s).", flush=True)
    else:
        print("No proxies configured; using direct HTTPS requests.", flush=True)

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
            payload, final_url, content_type = capture(url, hosts, proxies=proxies)
            validate_url(final_url, hosts)
        except Exception as error:
            message = safe_error_message(error, proxies)
            print(f"FAILED {key}: {type(error).__name__}: {message}", flush=True)
            captured.append({"source_key": key, "registry_id": registry_id, "requested_url": url,
                             "capture_status": "failed", "error": f"{type(error).__name__}: {message}"})
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

    report = {"captured": sum(1 for item in captured if item.get("sha256")),
              "failed": [item for item in captured if item.get("capture_status") == "failed"],
              "results": captured}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "capture-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Captured {report['captured']} raw source file(s); failed: {len(report['failed'])}.")
    print("No legal text was parsed, rewritten, or generated.")
    return 0 if report["captured"] else 1

if __name__ == "__main__":
    raise SystemExit(main())
