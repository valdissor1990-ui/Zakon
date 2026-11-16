#!/usr/bin/env python3
"""Tests for HTTPS capture helpers (no network required for unit tests)."""
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from adapters.http_util import (
    FetchResult,
    RateLimiter,
    require_https,
    save_capture,
    sha256_hex,
    fetch_https,
)


class HttpUtilTests(unittest.TestCase):
    def test_require_https_rejects_http(self):
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            require_https("http://example.com/")

    def test_sha256_stable(self):
        self.assertEqual(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )

    def test_rate_limiter_enforces_interval(self):
        lim = RateLimiter(min_interval_sec=0.15)
        t0 = time.monotonic()
        lim.wait()
        lim.wait()
        elapsed = time.monotonic() - t0
        self.assertGreaterEqual(elapsed, 0.14)

    def test_save_capture_writes_body_and_meta(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result = FetchResult(
                url="https://pravo.gov.ru/",
                status=200,
                body=b"<html>official</html>",
                sha256=sha256_hex(b"<html>official</html>"),
                retrieved_at=1_700_000_000.0,
            )
            meta_path = save_capture(root, "pravo_law_bank", result)
            self.assertTrue(meta_path.exists())
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            self.assertEqual(meta["sha256"], result.sha256)
            self.assertEqual(meta["http_status"], 200)
            self.assertEqual(meta["source_id"], "pravo_law_bank")
            bins = list(meta_path.parent.glob("*.bin"))
            self.assertEqual(len(bins), 1)
            self.assertEqual(bins[0].read_bytes(), result.body)

    def test_fetch_https_rejects_http_before_network(self):
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            fetch_https("http://example.com/")


if __name__ == "__main__":
    unittest.main()
