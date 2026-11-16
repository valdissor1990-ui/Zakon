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
                        out.append(bytes([int(hexpart, 16)]).decode("cp1251"))
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
            if word in ("par", "line"): out.append("\n")
            elif word == "tab": out.append("\t")
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

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--intake", type=Path, default=INTAKE)
    parser.add_argument("--evidence", type=Path, default=ROOT / "content" / "evidence")
    parser.add_argument("--output", type=Path, default=ROOT / "content" / "extracted")
    args = parser.parse_args()
    data = json.loads(args.intake.read_text(encoding="utf-8"))
    report = {"status": "review_only", "warning": "Extraction is not legal verification or approval.", "sources": []}
    for source in data.get("sources", []):
        if source.get("capture_status") != "captured":
            continue
        kind = source.get("source_type", "")
        if kind not in ("official_consolidated_text_docx", "official_consolidated_text_rtf"):
            continue
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
        source["legal_text_extraction_performed"] = True
        source["extracted_candidate_file"] = target.name
        source["extracted_candidate_sha256"] = extracted_hash
        source["article_heading_candidates"] = len(articles)
        report["sources"].append({
            "source_key": key, "source_sha256": digest, "source_bytes": len(payload),
            "extracted_file": target.name, "extracted_sha256": extracted_hash,
            "characters": len(text), "article_heading_candidates": len(articles),
            "first_headings": [row["number_heading"] for row in articles[:5]],
            "last_headings": [row["number_heading"] for row in articles[-5:]],
            "status": "extracted_candidate_not_verified"
        })
        print(f"EXTRACTED {key}: {len(text)} chars; {len(articles)} article heading candidates")
    if not report["sources"]:
        raise SystemExit("No captured DOCX/RTF sources available for extraction")
    (args.output / "extraction-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.intake.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Extraction complete. No legal records or SQLite database were changed.")

if __name__ == "__main__":
    main()
