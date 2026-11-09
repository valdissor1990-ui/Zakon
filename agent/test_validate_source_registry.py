#!/usr/bin/env python3
"""Tests for the official legal-source registry policy."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_source_registry import validate_registry


class SourceRegistryPolicyTests(unittest.TestCase):
    def setUp(self):
        self.registry = {
            "sources": [
                {"id": "a", "url": "https://a.gov.ru/", "publisher": "State A",
                 "authority": "official", "role": "publication", "state_operated": True},
                {"id": "b", "url": "https://b.gov.ru/", "publisher": "State B",
                 "authority": "official", "role": "consolidated", "state_operated": True},
                {"id": "c", "url": "https://c.gov.ru/", "publisher": "State C",
                 "authority": "official", "role": "cross-check", "state_operated": True},
            ],
            "publication_rule": {
                "minimum_distinct_sources": 3,
                "minimum_state_operated_sources": 3,
                "mismatch_action": "block_release",
                "never_generate_missing_legal_text": True,
            },
        }

    def test_accepts_three_distinct_state_operated_sources(self):
        validate_registry(self.registry)

    def test_rejects_source_not_declared_state_operated(self):
        self.registry["sources"][2]["state_operated"] = False
        with self.assertRaisesRegex(ValueError, "not explicitly marked"):
            validate_registry(self.registry)

    def test_rejects_duplicate_hostnames(self):
        self.registry["sources"][2]["url"] = "https://a.gov.ru/other"
        with self.assertRaisesRegex(SystemExit, "distinct hostnames"):
            validate_registry(self.registry)

    def test_rejects_policy_that_allows_generated_text(self):
        self.registry["publication_rule"]["never_generate_missing_legal_text"] = False
        with self.assertRaisesRegex(SystemExit, "never be generated"):
            validate_registry(self.registry)


if __name__ == "__main__":
    unittest.main()
