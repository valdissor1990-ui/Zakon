import unittest

from capture_sources import AllowlistedRedirectHandler, validate_url


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


if __name__ == "__main__":
    unittest.main()
