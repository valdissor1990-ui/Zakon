#!/usr/bin/env python3
"""Regression tests for the review-only 63-FZ article-fragment candidate."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXTRACTED = ROOT / "content" / "extracted"
CANDIDATE_PATH = EXTRACTED / "advocacy_minjust_rtf.article-fragments-candidate.json"
SOURCE_PATH = EXTRACTED / "advocacy_minjust_rtf.txt"
ARTICLE_INDEX_PATH = EXTRACTED / "advocacy_minjust_rtf.article-index.json"
STRUCTURE_PATH = EXTRACTED / "advocacy_minjust_rtf.structure-candidate.json"
ANNOTATION_PATH = EXTRACTED / "advocacy_minjust_rtf.annotation-index.json"


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def source_lines():
    value = SOURCE_PATH.read_text(encoding="utf-8")
    lines = value.split("\n")
    return [line[:-1] if line.endswith("\r") else line for line in lines]


class AdvocacyLawArticleFragmentsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.candidate = load_json(CANDIDATE_PATH)
        cls.article_index = load_json(ARTICLE_INDEX_PATH)
        cls.structure = load_json(STRUCTURE_PATH)
        cls.annotation_index = load_json(ANNOTATION_PATH)
        cls.lines = source_lines()

    def test_artifact_is_explicitly_review_only(self):
        self.assertEqual(self.candidate["artifact_type"], "review_only_article_text_fragments")
        self.assertEqual(self.candidate["status"], "review_only_not_legal_record")
        self.assertEqual(self.candidate["document_id"], "ru-advocacy-law")
        self.assertEqual(len(self.candidate["articles"]), 54)
        self.assertEqual(self.candidate["source"]["extracted_sha256"],
                         self.article_index["source"]["extracted_sha256"])

    def test_article_ranges_reconstruct_every_source_line_without_gaps(self):
        reconstructed = []
        line_numbers = []
        for article in self.candidate["articles"]:
            start = article["source_line_range"]["start_inclusive"]
            end = article["source_line_range"]["end_exclusive"]
            self.assertLess(start, end)
            self.assertEqual(len(article["raw_lines"]), end - start)
            for offset, item in enumerate(article["raw_lines"]):
                expected_line = start + offset
                self.assertEqual(item["line_number"], expected_line)
                reconstructed.append(item["text"])
                line_numbers.append(expected_line)

        preamble = self.candidate["preamble_lines"]
        trailing = self.candidate["trailing_lines"]
        full_rows = preamble + [
            {"line_number": number, "text": text}
            for number, text in zip(line_numbers, reconstructed)
        ] + trailing
        full_rows.sort(key=lambda item: item["line_number"])
        self.assertEqual([row["line_number"] for row in full_rows],
                         list(range(1, len(self.lines) + 1)))
        self.assertEqual([row["text"] for row in full_rows], self.lines)
        self.assertTrue(self.candidate["validation"]["contiguous_article_ranges"])
        self.assertEqual(self.candidate["validation"]["errors"], [])

    def test_annotation_and_editorial_lines_are_separated_exactly(self):
        annotations_by_article = {}
        for item in self.annotation_index["annotations"]:
            annotations_by_article.setdefault(item["article_number"], []).append(item)

        article_index_by_number = {
            item["number"]: item for item in self.article_index["articles"]
        }
        for article in self.candidate["articles"]:
            number = article["number"]
            expected_annotations = annotations_by_article.get(number, [])
            actual_annotations = article["third_party_annotations"]
            self.assertEqual(len(actual_annotations), len(expected_annotations))
            for expected, actual in zip(expected_annotations, actual_annotations):
                self.assertEqual(actual["marker_line"], expected["marker_line"])
                self.assertEqual(actual["marker_text_raw"], expected["marker_text_raw"])
                self.assertEqual(actual["annotation_text_raw"], expected["annotation_text_raw"])

            expected_editorial_lines = article_index_by_number[number].get(
                "amendment_editorial_marker_lines", []
            )
            self.assertEqual(
                [item["line_number"] for item in article["editorial_revision_note_candidates"]],
                expected_editorial_lines,
            )

            classifications = {
                item["line_number"]: item["classification"]
                for item in article["raw_lines"]
            }
            for annotation in expected_annotations:
                self.assertEqual(classifications[annotation["marker_line"]],
                                 "third_party_annotation_marker")
                for line_number in range(annotation["annotation_line_start"],
                                         annotation["annotation_line_end"] + 1):
                    self.assertEqual(classifications[line_number],
                                     "third_party_annotation_text")
            for line_number in expected_editorial_lines:
                self.assertEqual(classifications[line_number],
                                 "editorial_revision_note_candidate")

    def test_numbered_markers_match_review_structure_candidate(self):
        expected = {
            item["article_number"]: item
            for item in self.structure["articles"]
        }
        for article in self.candidate["articles"]:
            source = expected[article["number"]]
            self.assertEqual(article["numbered_marker_candidates"], source["markers"])
            self.assertEqual(article["marker_counts"], source["marker_counts"])


if __name__ == "__main__":
    unittest.main()
