import hashlib
import unittest
from unittest.mock import patch

from capture_sources import (
    MAX_BYTES,
    AllowlistedRedirectHandler,
    capture,
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
        self.assertEqual(opener.timeout, 30)

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
