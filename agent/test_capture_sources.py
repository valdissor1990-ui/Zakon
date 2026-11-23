import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.request import ProxyHandler

from capture_sources import (
    MAX_BYTES,
    AllowlistedRedirectHandler,
    capture,
    allowed_hosts,
    load_proxies,
    proxy_for_attempt,
    safe_error_message,
    validate_url,
)


class FakeResponse:
    def __init__(self, payload, url, content_type="application/pdf"):
        self.payload = payload
        self.url = url
        self.headers = {"Content-Type": content_type}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def geturl(self):
        return self.url

    def read(self, size=-1):
        return self.payload if size < 0 else self.payload[:size]


class FakeOpener:
    def __init__(self, response):
        self.response = response
        self.request = None

    def open(self, request, timeout=None):
        self.request = request
        self.timeout = timeout
        return self.response


class SourceCaptureSafetyTests(unittest.TestCase):
    def setUp(self):
        self.hosts = {"publication.pravo.gov.ru"}

    def test_capture_key_resolves_registered_source_id(self):
        registry = {"sources": [{
            "id": "pravo_law_bank",
            "url": "https://pravo.gov.ru/"
        }]}
        self.assertEqual(
            allowed_hosts(registry, "pravo_law_bank"),
            {"pravo.gov.ru"}
        )

    def test_accepts_registered_https_host(self):
        validate_url("https://publication.pravo.gov.ru/document", self.hosts)

    def test_rejects_http(self):
        with self.assertRaises(ValueError):
            validate_url("http://publication.pravo.gov.ru/document", self.hosts)

    def test_rejects_unregistered_host(self):
        with self.assertRaises(ValueError):
            validate_url("https://example.org/document", self.hosts)

    def test_rejects_embedded_credentials(self):
        with self.assertRaises(ValueError):
            validate_url("https://user:password@publication.pravo.gov.ru/document", self.hosts)

    def test_redirect_handler_checks_destination_before_following(self):
        handler = AllowlistedRedirectHandler(self.hosts)
        with self.assertRaises(ValueError):
            handler.redirect_request(None, None, 302, "Found", {}, "https://example.org/redirect")

    def test_capture_returns_exact_bytes_and_metadata_for_hashing(self):
        payload = b"official source bytes"
        response = FakeResponse(payload, "https://publication.pravo.gov.ru/document")
        opener = FakeOpener(response)
        with patch("capture_sources.urllib.request.build_opener", return_value=opener):
            captured, final_url, content_type = capture(
                "https://publication.pravo.gov.ru/document", self.hosts
            )
        self.assertEqual(captured, payload)
        self.assertEqual(final_url, "https://publication.pravo.gov.ru/document")
        self.assertEqual(content_type, "application/pdf")
        self.assertEqual(hashlib.sha256(captured).hexdigest(), hashlib.sha256(payload).hexdigest())
        self.assertEqual(opener.timeout, 45)

    def test_load_proxies_ignores_blank_lines_and_comments(self):
        with tempfile.TemporaryDirectory() as directory:
            proxy_file = Path(directory) / "proxies.txt"
            proxy_file.write_text(
                "# optional proxy list\\n\\nhttp://proxy-one.example:8080\\n"
                "https://user:secret@proxy-two.example:8443\\n",
                encoding="utf-8",
            )
            self.assertEqual(
                load_proxies(proxy_file),
                [
                    "http://proxy-one.example:8080",
                    "https://user:secret@proxy-two.example:8443",
                ],
            )

    def test_load_proxies_rejects_non_http_schemes(self):
        with tempfile.TemporaryDirectory() as directory:
            proxy_file = Path(directory) / "proxies.txt"
            proxy_file.write_text("socks5://proxy.example:1080\\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "HTTP\\(S\\) URL"):
                load_proxies(proxy_file)

    def test_proxy_attempts_use_each_proxy_then_direct_fallback(self):
        proxies = [
            "http://proxy-one.example:8080",
            "http://proxy-two.example:8080",
        ]
        self.assertEqual(proxy_for_attempt(proxies, 1), proxies[0])
        self.assertEqual(proxy_for_attempt(proxies, 2), proxies[1])
        self.assertIsNone(proxy_for_attempt(proxies, 3))

    def test_error_message_redacts_proxy_credentials_and_host(self):
        proxy = "http://user:secret@proxy.example:8080"
        message = safe_error_message(RuntimeError(f"connection failed via {proxy}"), [proxy])
        self.assertNotIn("secret", message)
        self.assertNotIn("proxy.example", message)
        self.assertIn("[redacted]", message)

    def test_capture_installs_proxy_handler_when_proxy_is_configured(self):
        response = FakeResponse(b"official bytes", "https://publication.pravo.gov.ru/document")
        opener = FakeOpener(response)
        with patch("capture_sources.urllib.request.build_opener", return_value=opener) as build_opener:
            capture(
                "https://publication.pravo.gov.ru/document",
                self.hosts,
                proxies=["http://proxy.example:8080"],
            )
        self.assertTrue(any(isinstance(handler, ProxyHandler) for handler in build_opener.call_args.args))

    def test_capture_rejects_payload_over_size_limit(self):
        payload = b"x" * (MAX_BYTES + 1)
        response = FakeResponse(payload, "https://publication.pravo.gov.ru/document")
        with patch("capture_sources.urllib.request.build_opener", return_value=FakeOpener(response)):
            with self.assertRaisesRegex(ValueError, "exceeds"):
                capture("https://publication.pravo.gov.ru/document", self.hosts)

    def test_capture_rejects_redirect_to_non_allowlisted_host(self):
        response = FakeResponse(b"data", "https://example.org/redirected")
        with patch("capture_sources.urllib.request.build_opener", return_value=FakeOpener(response)):
            with self.assertRaisesRegex(ValueError, "not allowlisted"):
                capture("https://publication.pravo.gov.ru/document", self.hosts)


if __name__ == "__main__":
    unittest.main()
