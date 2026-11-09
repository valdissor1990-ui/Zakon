import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate_catalog import validate_catalog


class LegalCatalogTests(unittest.TestCase):
    def setUp(self):
        self.registry = {"sources": [
            {"id": "official", "system_id": "publication", "operator_id": "gspi"},
            {"id": "bank", "system_id": "law-bank", "operator_id": "gspi"},
            {"id": "minjust", "system_id": "minjust", "operator_id": "minjust"}
        ]}
        self.catalog = {
            "sources": ["official", "bank", "minjust"],
            "documents": [{
                "id": "ru-test",
                "title": "Тестовый акт",
                "document_type": "code",
                "priority": 1,
                "content_status": "not_imported",
                "verification_status": "not_started",
                "metadata_status": "provisional",
                "source_search_terms": "поиск тестового акта"
            }]
        }

    def test_accepts_unimported_catalog_metadata(self):
        validate_catalog(self.catalog, self.registry)

    def test_verified_metadata_requires_official_reference_url(self):
        self.catalog["documents"][0]["metadata_status"] = "source_reference_verified"
        with self.assertRaisesRegex(ValueError, "HTTPS official reference"):
            validate_catalog(self.catalog, self.registry)

    def test_rejects_catalog_that_claims_unverified_text_is_imported(self):
        self.catalog["documents"][0]["content_status"] = "imported"
        with self.assertRaisesRegex(ValueError, "must not imply"):
            validate_catalog(self.catalog, self.registry)

    def test_rejects_unknown_source(self):
        self.catalog["sources"][0] = "unknown"
        with self.assertRaisesRegex(ValueError, "unknown source"):
            validate_catalog(self.catalog, self.registry)


if __name__ == "__main__":
    unittest.main()
