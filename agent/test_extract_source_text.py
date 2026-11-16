import io
import unittest
import zipfile
from xml.etree import ElementTree as ET

from extract_source_text import article_index, docx_text, rtf_text


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

    def test_article_heading_candidates(self):
        text = "Статья 1. Общие положения\nТекст\nСтатья 2. Права\nТекст"
        rows = article_index(text)
        self.assertEqual([row["number_heading"] for row in rows], ["Статья 1", "Статья 2"])


if __name__ == "__main__":
    unittest.main()
