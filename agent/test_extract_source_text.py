import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from extract_source_text import article_index, docx_text, refresh_review_index, rtf_text


class SourceExtractionTests(unittest.TestCase):
    def test_rtf_unicode_and_paragraphs(self):
        source = (
            r"{\rtf1\ansi\ansicpg1251\uc1 "
            r"\u1057?\u1090?\u1072?\u1090?\u1100?\u1103? 1.\par "
            r"Текст нормы\par}"
        ).encode("cp1251")
        result = rtf_text(source)
        self.assertIn("Статья 1.", result)
        self.assertIn("Текст нормы", result)

    def test_rtf_ignores_font_table(self):
        source = br"{\rtf1{\fonttbl{\f0 Arial;}}Visible text\par}"
        result = rtf_text(source)
        self.assertIn("Visible text", result)
        self.assertNotIn("Arial", result)

    def test_rtf_table_rows_preserve_article_heading_boundaries(self):
        source = (
            r"{\rtf1\ansi\ansicpg1251 "
            r"Статья 15. Внесение сведений\cell\row "
            r"Статья 19. Страхование риска\cell\row}"
        ).encode("cp1251")
        rows = article_index(rtf_text(source))
        self.assertEqual(
            [row["number_heading"] for row in rows],
            ["Статья 15", "Статья 19"],
        )

    def test_docx_paragraph_extraction(self):
        ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        root = ET.Element(f"{{{ns}}}document")
        body = ET.SubElement(root, f"{{{ns}}}body")
        paragraph = ET.SubElement(body, f"{{{ns}}}p")
        run = ET.SubElement(paragraph, f"{{{ns}}}r")
        node = ET.SubElement(run, f"{{{ns}}}t")
        node.text = "Статья 1. Общие положения"
        xml = ET.tostring(root, encoding="utf-8", xml_declaration=True)
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("word/document.xml", xml)
        self.assertIn("Статья 1", docx_text(stream.getvalue()))


    def test_review_index_refreshes_capture_timestamp_without_changing_text(self):
        text = "Статья 1. Общие положения\nТекст\nСтатья 2. Права\nНорма\n"
        rows = article_index(text)
        extracted_hash = "b" * 64
        source = {
            "key": "test_source",
            "registry_id": "minjust_official_documents",
            "official_url": "https://minjust.gov.ru/test.rtf",
            "retrieved_at": "2026-11-18T08:00:00Z",
            "byte_length": 123,
            "sha256": "a" * 64,
        }
        index_data = {
            "artifact_type": "review_only_article_boundary_index",
            "status": "review_only_not_legal_record",
            "source": {
                "sha256": source["sha256"],
                "extracted_sha256": extracted_hash,
                "retrieved_at": "2026-11-17T08:00:00Z",
            },
            "extracted_text": {},
            "annotation_markers": [{"marker": "note"}],
            "articles": [
                {
                    "number": row["number_heading"].replace("Статья ", ""),
                    "start_char": row["start_char"],
                    "end_char": rows[i + 1]["start_char"] if i + 1 < len(rows) else len(text),
                }
                for i, row in enumerate(rows)
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review-index.json"
            path.write_text(json.dumps(index_data), encoding="utf-8")
            refresh_review_index(path, source, "test_source.txt", extracted_hash, text, rows)
            updated = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(updated["source"]["retrieved_at"], source["retrieved_at"])
        self.assertEqual(updated["source"]["byte_length"], 123)
        self.assertEqual(updated["source"]["extracted_file"], "test_source.txt")
        self.assertEqual(updated["extracted_text"]["characters"], len(text))
        self.assertEqual(updated["extracted_text"]["annotation_marker_count"], 1)

    def test_review_index_refuses_changed_extracted_text(self):
        text = "Статья 1. Общие положения\nТекст\n"
        rows = article_index(text)
        source = {
            "key": "test_source",
            "registry_id": "minjust_official_documents",
            "official_url": "https://minjust.gov.ru/test.rtf",
            "retrieved_at": "2026-11-18T08:00:00Z",
            "byte_length": 123,
            "sha256": "a" * 64,
        }
        index_data = {
            "artifact_type": "review_only_article_boundary_index",
            "status": "review_only_not_legal_record",
            "source": {"sha256": source["sha256"], "extracted_sha256": "c" * 64},
            "articles": [{
                "number": "1",
                "start_char": rows[0]["start_char"],
                "end_char": len(text),
            }],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review-index.json"
            path.write_text(json.dumps(index_data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "extracted text changed"):
                refresh_review_index(path, source, "test_source.txt", "b" * 64, text, rows)

    def test_article_heading_candidates(self):
        text = "Статья 1. Общие положения\nТекст\nСтатья 2. Права\nТекст"
        rows = article_index(text)
        self.assertEqual([row["number_heading"] for row in rows], ["Статья 1", "Статья 2"])

    def test_article_heading_candidates_include_decimal_numbers(self):
        text = (
            "Статья 6.1. Адвокатский запрос\nТекст\n"
            "Статья 17.1. Представления органов юстиции\nТекст"
        )
        rows = article_index(text)
        self.assertEqual(
            [row["number_heading"] for row in rows],
            ["Статья 6.1", "Статья 17.1"],
        )


if __name__ == "__main__":
    unittest.main()
