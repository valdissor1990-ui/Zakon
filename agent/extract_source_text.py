#!/usr/bin/env python3
"""Extract text from captured DOCX/RTF originals into review-only candidates.

This tool does not validate legal currency, rewrite legal wording, or publish records.
"""
from __future__ import annotations
import argparse
import hashlib
import html
import json
import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
INTAKE = ROOT / "content" / "intake" / "source-requests.json"
NS = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}

EXTRACTABLE_TEXT_SOURCE_TYPES = frozenset({
    "official_consolidated_text_docx",
    "official_consolidated_text_rtf",
    "official_historical_text_rtf",
})

REVIEW_DOCUMENT_METADATA = {
    "advocacy_minjust_rtf": {
        "document_id": "ru-advocacy-law",
        "document_title": "Федеральный закон от 31.05.2002 № 63-ФЗ «Об адвокатской деятельности и адвокатуре в Российской Федерации»",
    },
    "advocacy_minjust_rtf_2017": {
        "document_id": "ru-advocacy-law",
        "document_title": "Федеральный закон от 31.05.2002 № 63-ФЗ «Об адвокатской деятельности и адвокатуре в Российской Федерации»",
    },
    "constitution_minjust_rtf": {
        "document_id": "ru-constitution-1993",
        "document_title": "Конституция Российской Федерации",
    },
    "uk_minjust_docx": {
        "document_id": "ru-criminal-code",
        "document_title": "Уголовный кодекс Российской Федерации",
    },
    "gpk_minjust_docx": {
        "document_id": "ru-civil-procedure-code",
        "document_title": "Гражданский процессуальный кодекс Российской Федерации",
    },
    "koap_minjust_docx": {
        "document_id": "ru-code-of-administrative-offences",
        "document_title": "Кодекс Российской Федерации об административных правонарушениях",
    },
    "kas_minjust_docx": {
        "document_title": "Кодекс административного судопроизводства Российской Федерации",
    },
}


def is_extractable_text_source(source: dict) -> bool:
    """Return whether a captured official text source can produce a review-only extraction."""
    return (
        source.get("capture_status") == "captured"
        and source.get("source_type") in EXTRACTABLE_TEXT_SOURCE_TYPES
    )
ARTICLE_RE = re.compile(
    r"(?m)^[ \t]*(Статья[ \t]+(?:\d+(?:\.\d+)*|[0-9]+(?:\s*[¹²³⁴⁵⁶⁷⁸⁹⁰])?))"
    r"(?:[ \t]*[.\u2014:\u2013-]?[ \t]*(.*))?$", re.IGNORECASE
)

def docx_text(payload: bytes) -> str:
    from io import BytesIO
    with zipfile.ZipFile(BytesIO(payload)) as archive:
        xml = ET.fromstring(archive.read("word/document.xml"))
    paragraphs = []
    for paragraph in xml.findall(".//w:p", NS):
        parts = []
        for node in paragraph.iter():
            tag = node.tag.rsplit("}", 1)[-1]
            if tag == "t" and node.text:
                parts.append(node.text)
            elif tag == "tab":
                parts.append("\t")
            elif tag in ("br", "cr"):
                parts.append("\n")
        paragraphs.append("".join(parts))
    return "\n".join(paragraphs)

def rtf_text(payload: bytes) -> str:
    # Conservative RTF text reader: handles groups, destinations, CP1251 hex,
    # Unicode escapes and common paragraph controls without modifying wording.
    source = payload.decode("latin-1")
    out, stack = [], []
    skip_destinations = {
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
        "header", "headerl", "headerr", "footer", "footerl", "footerr",
        "filetbl", "listtable", "listoverridetable", "generator", "xmlnstbl",
    }
    i, depth, skip_depth, uc_skip = 0, 0, None, 1
    fallback = 0
    codepage = "cp1252"
    while i < len(source):
        ch = source[i]
        if ch == "{":
            stack.append((skip_depth, uc_skip))
            depth += 1
            i += 1
            continue
        if ch == "}":
            if stack:
                skip_depth, uc_skip = stack.pop()
            depth = max(0, depth - 1)
            i += 1
            continue
        if ch != "\\":
            if skip_depth is None:
                if fallback:
                    fallback -= 1
                elif ch not in "\r\n":
                    if ord(ch) >= 128:
                        out.append(ch.encode("latin-1").decode(codepage, "replace"))
                    else:
                        out.append(ch)
            i += 1
            continue
        i += 1
        if i >= len(source):
            break
        c = source[i]
        if c in "\\{}":
            if skip_depth is None:
                if fallback:
                    fallback -= 1
                else:
                    out.append(c)
            i += 1
            continue
        if c == "'":
            hexpart = source[i+1:i+3]
            i += 3
            if skip_depth is None:
                if fallback:
                    fallback -= 1
                else:
                    try:
                        out.append(bytes([int(hexpart, 16)]).decode(codepage))
                    except (ValueError, UnicodeDecodeError):
                        out.append(bytes([int(hexpart, 16)]).decode("cp1252", "replace"))
            continue
        if not c.isalpha():
            i += 1
            if c == "*" and depth:
                skip_depth = depth
            elif skip_depth is None:
                if c == "~": out.append("\u00a0")
                elif c == "_": out.append("\u2011")
                elif c == "-": out.append("\u00ad")
            continue
        start = i
        while i < len(source) and source[i].isalpha():
            i += 1
        word = source[start:i]
        sign = 1
        if i < len(source) and source[i] == "-":
            sign = -1
            i += 1
        nstart = i
        while i < len(source) and source[i].isdigit():
            i += 1
        number = sign * int(source[nstart:i]) if i > nstart else None
        if i < len(source) and source[i] == " ":
            i += 1
        if word in skip_destinations and depth:
            skip_depth = depth
        if word == "ansicpg" and number is not None:
            codepage = f"cp{number}"
        elif word == "uc" and number is not None:
            uc_skip = max(0, min(number, 16))
        elif word == "u" and number is not None and skip_depth is None:
            out.append(chr(number if number >= 0 else number + 65536))
            fallback = uc_skip
        elif skip_depth is None:
            if word in ("par", "line", "row", "page", "sect"): out.append("\n")
            elif word in ("tab", "cell"): out.append("\t")
            elif word in ("emdash",): out.append("\u2014")
            elif word in ("endash",): out.append("\u2013")
            elif word == "bullet": out.append("\u2022")
    text = "".join(out).replace("\x00", "")
    # RTF sources occasionally declare cp1251 but contain literal bytes.
    return re.sub(r"[ \t]+\n", "\n", text)

def article_index(text: str):
    matches = list(ARTICLE_RE.finditer(text))
    rows = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        body = text[match.start():end].strip()
        rows.append({
            "number_heading": match.group(1).strip(),
            "heading_tail": (match.group(2) or "").strip(),
            "start_char": match.start(),
            "end_char": end,
            "body_characters": len(body),
        })
    return rows


def refresh_review_index(
    index_path: Path,
    source: dict,
    extracted_file: str,
    extracted_hash: str,
    text: str,
    articles: list[dict],
) -> None:
    """Refresh provenance for an existing review-only index without rewriting legal content."""
    if not index_path.is_file():
        return
    index_data = json.loads(index_path.read_text(encoding="utf-8"))
    if index_data.get("artifact_type") != "review_only_article_boundary_index":
        raise ValueError(f"{index_path}: unsupported review index artifact type")
    if index_data.get("status") != "review_only_not_legal_record":
        raise ValueError(f"{index_path}: review index must remain non-publishable")
    index_source = index_data.get("source")
    if not isinstance(index_source, dict):
        raise ValueError(f"{index_path}: source provenance is missing")
    if index_source.get("sha256") != source.get("sha256"):
        raise ValueError(f"{index_path}: source SHA-256 changed; rebuild and review the index")
    if index_source.get("extracted_sha256") != extracted_hash:
        raise ValueError(f"{index_path}: extracted text changed; rebuild and review article boundaries")

    indexed_articles = index_data.get("articles")
    if not isinstance(indexed_articles, list) or len(indexed_articles) != len(articles):
        raise ValueError(f"{index_path}: article count differs from the current extraction")
    for indexed, current in zip(indexed_articles, articles):
        current_number = current["number_heading"].split(" ", 1)[1].strip()
        if (
            indexed.get("number") != current_number
            or indexed.get("start_char") != current["start_char"]
            or indexed.get("end_char") != current["end_char"]
        ):
            raise ValueError(
                f"{index_path}: article boundaries changed at ordinal {current.get('ordinal')}; "
                "manual index regeneration is required"
            )

    index_source.update({
        "source_key": source["key"],
        "registry_id": source.get("registry_id"),
        "official_url": source.get("official_url"),
        "retrieved_at": source.get("retrieved_at"),
        "byte_length": source.get("byte_length"),
        "sha256": source.get("sha256"),
        "extracted_file": extracted_file,
        "extracted_sha256": extracted_hash,
    })
    extracted_meta = index_data.setdefault("extracted_text", {})
    extracted_meta["characters"] = len(text)
    extracted_meta["article_heading_candidates"] = len(articles)
    extracted_meta["annotation_marker_count"] = len(index_data.get("annotation_markers", []))
    index_path.write_text(
        json.dumps(index_data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def refresh_annotation_index(
    index_path: Path,
    source: dict,
    extracted_file: str,
    extracted_hash: str,
    text: str,
) -> None:
    """Refresh provenance for third-party notes without changing their wording."""
    if not index_path.is_file():
        return
    index_data = json.loads(index_path.read_text(encoding="utf-8"))
    if index_data.get("artifact_type") != "review_only_third_party_annotation_index":
        raise ValueError(f"{index_path}: unsupported annotation index artifact type")
    if index_data.get("status") != "review_only_not_legal_record":
        raise ValueError(f"{index_path}: annotation index must remain non-publishable")

    index_source = index_data.get("source")
    if not isinstance(index_source, dict):
        raise ValueError(f"{index_path}: source provenance is missing")
    if index_source.get("sha256") != source.get("sha256"):
        raise ValueError(f"{index_path}: source SHA-256 changed; rebuild and review annotations")
    if index_source.get("extracted_sha256") != extracted_hash:
        raise ValueError(f"{index_path}: extracted text changed; rebuild and review annotations")

    annotations = index_data.get("annotations")
    if not isinstance(annotations, list) or index_data.get("annotation_count") != len(annotations):
        raise ValueError(f"{index_path}: annotation count is inconsistent")
    lines = text.splitlines()
    for annotation in annotations:
        marker_line = annotation.get("marker_line")
        start_line = annotation.get("annotation_line_start")
        end_line = annotation.get("annotation_line_end")
        if not all(isinstance(value, int) for value in (marker_line, start_line, end_line)):
            raise ValueError(f"{index_path}: annotation line coordinates are invalid")
        if marker_line < 1 or start_line < 1 or end_line < start_line or end_line > len(lines):
            raise ValueError(f"{index_path}: annotation line coordinates are out of range")
        if lines[marker_line - 1] != annotation.get("marker_text_raw"):
            raise ValueError(f"{index_path}: third-party marker changed at line {marker_line}")
        current_text = "\n".join(lines[start_line - 1:end_line])
        if current_text != annotation.get("annotation_text_raw"):
            raise ValueError(f"{index_path}: annotation text changed at lines {start_line}-{end_line}")

    index_source.update({
        "source_key": source["key"],
        "registry_id": source.get("registry_id"),
        "official_url": source.get("official_url"),
        "retrieved_at": source.get("retrieved_at"),
        "byte_length": source.get("byte_length"),
        "sha256": source.get("sha256"),
        "extracted_file": extracted_file,
        "extracted_sha256": extracted_hash,
    })
    index_path.write_text(
        json.dumps(index_data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def refresh_article_fragments_candidate(
    candidate_path: Path,
    source: dict,
    extracted_file: str,
    extracted_hash: str,
    text: str,
) -> None:
    """Refresh provenance for the custom 63-FZ line-fragment candidate without rewriting its text."""
    if not candidate_path.is_file():
        return
    candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
    if candidate.get("artifact_type") != "review_only_article_text_fragments":
        raise ValueError(f"{candidate_path}: unsupported article-fragments artifact type")
    if candidate.get("status") != "review_only_not_legal_record":
        raise ValueError(f"{candidate_path}: article fragments must remain non-publishable")

    candidate_source = candidate.get("source")
    if not isinstance(candidate_source, dict):
        raise ValueError(f"{candidate_path}: source provenance is missing")
    if candidate_source.get("sha256") != source.get("sha256"):
        raise ValueError(f"{candidate_path}: source SHA-256 changed; rebuild and review article fragments")
    if candidate_source.get("extracted_sha256") != extracted_hash:
        raise ValueError(f"{candidate_path}: extracted text changed; rebuild and review article fragments")

    lines = text.split("\n")
    lines = [line[:-1] if line.endswith("\r") else line for line in lines]
    rows = list(candidate.get("preamble_lines", []))
    articles = candidate.get("articles")
    if not isinstance(articles, list) or candidate.get("extraction", {}).get("article_count") != len(articles):
        raise ValueError(f"{candidate_path}: article count is inconsistent")
    for article in articles:
        line_range = article.get("source_line_range", {})
        start = line_range.get("start_inclusive")
        end = line_range.get("end_exclusive")
        raw_lines = article.get("raw_lines")
        if not isinstance(start, int) or not isinstance(end, int) or not isinstance(raw_lines, list):
            raise ValueError(f"{candidate_path}: article line range is invalid")
        if end <= start or len(raw_lines) != end - start:
            raise ValueError(f"{candidate_path}: article {article.get('number')} line range is inconsistent")
        for offset, item in enumerate(raw_lines):
            expected_line = start + offset
            if item.get("line_number") != expected_line:
                raise ValueError(f"{candidate_path}: article {article.get('number')} line numbering changed")
            rows.append(item)
    rows.extend(candidate.get("trailing_lines", []))

    by_number = {}
    for item in rows:
        number = item.get("line_number")
        if not isinstance(number, int) or number in by_number:
            raise ValueError(f"{candidate_path}: source line numbers are missing or duplicated")
        by_number[number] = item.get("text")
    if sorted(by_number) != list(range(1, len(lines) + 1)):
        raise ValueError(f"{candidate_path}: article fragments no longer cover every extracted source line")
    for number, current_line in enumerate(lines, start=1):
        if by_number[number] != current_line:
            raise ValueError(f"{candidate_path}: source line mismatch at line {number}")

    candidate_source.update({
        "source_key": source["key"],
        "registry_id": source.get("registry_id"),
        "official_url": source.get("official_url"),
        "retrieved_at": source.get("retrieved_at"),
        "byte_length": source.get("byte_length"),
        "sha256": source.get("sha256"),
        "extracted_file": extracted_file,
        "extracted_sha256": extracted_hash,
    })
    candidate["extraction"]["extracted_line_count"] = len(lines)
    candidate["validation"] = {"contiguous_article_ranges": True, "errors": []}
    candidate_path.write_text(
        json.dumps(candidate, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def build_structure_candidate(
    source: dict,
    extracted_file: str,
    extracted_hash: str,
    text: str,
    articles: list[dict],
) -> dict:
    """Build a line-level review index of numbering markers; do not infer legal hierarchy."""
    lines = text.splitlines()
    article_entries = []
    totals = {
        "numeric_dot_candidate": 0,
        "numeric_parenthesis_candidate": 0,
        "cyrillic_letter_parenthesis_candidate": 0,
    }

    for article in articles:
        start_line = text.count("\n", 0, article["start_char"]) + 1
        end_line = text.count("\n", 0, article["end_char"]) + 1
        segment = lines[start_line - 1:end_line - 1]
        markers = []

        for offset, line in enumerate(segment):
            marker = None
            kind = None
            match = re.match(r"^\s*(\d+(?:\.\d+)*)\.\s+", line)
            if match:
                marker = match.group(1) + "."
                kind = "numeric_dot_candidate"
            else:
                match = re.match(r"^\s*(\d+(?:\.\d+)*)\)\s+", line)
                if match:
                    marker = match.group(1) + ")"
                    kind = "numeric_parenthesis_candidate"
                else:
                    match = re.match(r"^\s*([а-яё])\)\s+", line, re.IGNORECASE)
                    if match:
                        marker = match.group(1) + ")"
                        kind = "cyrillic_letter_parenthesis_candidate"

            if kind:
                markers.append({
                    "ordinal": len(markers) + 1,
                    "kind": kind,
                    "marker": marker,
                    "line_number": start_line + offset,
                })
                totals[kind] += 1

        article_number = article["number_heading"].split(" ", 1)[1].strip()
        article_entries.append({
            "article_number": article_number,
            "article_heading": article.get("heading_tail", ""),
            "source_line_range": {"start": start_line, "end_exclusive": end_line},
            "marker_counts": {
                "numeric_dot": sum(1 for item in markers if item["kind"] == "numeric_dot_candidate"),
                "numeric_parenthesis": sum(1 for item in markers if item["kind"] == "numeric_parenthesis_candidate"),
                "cyrillic_letter_parenthesis": sum(1 for item in markers if item["kind"] == "cyrillic_letter_parenthesis_candidate"),
            },
            "markers": markers,
        })

    identity = REVIEW_DOCUMENT_METADATA.get(source.get("key"), {})
    document_id = source.get("document_id") or identity.get("document_id") or source.get("key")
    document_title = (
        source.get("document_title")
        or identity.get("document_title")
        or source.get("name")
        or source.get("key")
    )

    return {
        "schema_version": 1,
        "artifact_type": "review_only_structure_marker_index",
        "status": "review_only_not_legal_record",
        "document_id": document_id,
        "document_title": document_title,
        "source": {
            "source_key": source["key"],
            "registry_id": source.get("registry_id"),
            "official_url": source.get("official_url"),
            "retrieved_at": source.get("retrieved_at"),
            "byte_length": source.get("byte_length"),
            "sha256": source.get("sha256"),
            "extracted_file": extracted_file,
            "extracted_sha256": extracted_hash,
        },
        "article_count": len(article_entries),
        "totals": {
            **totals,
            "numeric_marker_total": totals["numeric_dot_candidate"] + totals["numeric_parenthesis_candidate"],
        },
        "articles": article_entries,
        "review_notes": [
            "Markers are line-level candidates only. Their legal role and parent-child hierarchy have not been manually approved.",
            "Numeric dot markers may represent parts; numeric parenthesis markers may represent subitems. This index does not assert parent-child relationships.",
            "A broad single-letter-plus-dot heuristic can mistake abbreviations such as «П.» in explanatory notes for list markers. The conservative Cyrillic-letter-plus-parenthesis detector is used here.",
            "No normative text is copied into this index. Consult the exact source line in the extracted text.",
            "Third-party annotations and editorial revision notes remain separate from the normative text and are not approved by this index.",
        ],
    }


def write_json(path: Path, value: dict) -> None:
    """Write a UTF-8 JSON artifact with a real newline terminator."""
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--intake", type=Path, default=INTAKE)
    parser.add_argument("--evidence", type=Path, default=ROOT / "content" / "evidence")
    parser.add_argument("--output", type=Path, default=ROOT / "content" / "extracted")
    args = parser.parse_args()
    data = json.loads(args.intake.read_text(encoding="utf-8"))
    report = {
        "status": "review_only",
        "warning": "Extraction is not legal verification or approval.",
        "sources": [],
        "skipped_sources": [],
    }
    for source in data.get("sources", []):
        if not is_extractable_text_source(source):
            report["skipped_sources"].append({
                "key": source.get("key"),
                "capture_status": source.get("capture_status"),
                "source_type": source.get("source_type"),
            })
            continue
        kind = source.get("source_type", "")
        key = source["key"]
        path = args.evidence / key / source["evidence_file"]
        payload = path.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        if digest != source.get("sha256"):
            raise SystemExit(f"{key}: source checksum mismatch")
        if len(payload) != source.get("byte_length"):
            raise SystemExit(f"{key}: source byte length mismatch")
        text = docx_text(payload) if kind.endswith("_docx") else rtf_text(payload)
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        text = "\n".join(line.rstrip() for line in text.splitlines()).strip() + "\n"
        articles = article_index(text)
        if len(text) < 1000:
            raise SystemExit(f"{key}: suspiciously short extraction ({len(text)} chars)")
        if len(articles) < 2:
            raise SystemExit(f"{key}: too few article headings detected ({len(articles)}); manual format review required")
        args.output.mkdir(parents=True, exist_ok=True)
        target = args.output / f"{key}.txt"
        target.write_text(text, encoding="utf-8")
        extracted_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
        review_index_path = args.output / f"{key}.article-index.json"
        refresh_review_index(review_index_path, source, target.name, extracted_hash, text, articles)
        annotation_index_path = args.output / f"{key}.annotation-index.json"
        refresh_annotation_index(annotation_index_path, source, target.name, extracted_hash, text)
        fragments_path = args.output / f"{key}.article-fragments-candidate.json"
        refresh_article_fragments_candidate(fragments_path, source, target.name, extracted_hash, text)
        structure_data = build_structure_candidate(source, target.name, extracted_hash, text, articles)
        structure_path = args.output / f"{key}.structure-candidate.json"
        write_json(structure_path, structure_data)
        source["legal_text_extraction_performed"] = True
        source["extracted_candidate_file"] = target.name
        source["extracted_candidate_sha256"] = extracted_hash
        source["article_heading_candidates"] = len(articles)
        report["sources"].append({
            "source_key": key, "source_sha256": digest, "source_bytes": len(payload),
            "retrieved_at": source.get("retrieved_at"), "official_url": source.get("official_url"),
            "extracted_file": target.name, "extracted_sha256": extracted_hash,
            "characters": len(text), "article_heading_candidates": len(articles),
            "first_headings": [row["number_heading"] for row in articles[:5]],
            "last_headings": [row["number_heading"] for row in articles[-5:]],
            "status": "extracted_candidate_not_verified",
            "review_role": (
                "historical_revision_candidate"
                if kind == "official_historical_text_rtf"
                else "consolidated_text_candidate"
            )
        })
        print(f"EXTRACTED {key}: {len(text)} chars; {len(articles)} article heading candidates")
    args.output.mkdir(parents=True, exist_ok=True)
    if not report["sources"]:
        report["status"] = "blocked_no_extractable_sources"
        report["blocked_reason"] = (
            "No exact captured DOCX/RTF originals are available. Changed candidates and failed "
            "captures remain review-only; no text extraction or legal-record update was performed."
        )
        write_json(args.output / "extraction-report.json", report)
        print(
            "No captured DOCX/RTF sources available; extraction skipped and recorded. "
            "Capture failures remain visible in capture-report.json."
        )
        return 0
    write_json(args.output / "extraction-report.json", report)
    write_json(args.intake, data)
    print("Extraction complete. No legal records or SQLite database were changed.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
