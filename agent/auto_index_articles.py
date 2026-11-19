#!/usr/bin/env python3
"""Build review/auto article-index from extracted official text.

Slices the captured original only — never invents legal wording.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARTICLE_RE = re.compile(
    r"(?m)^[ \t]*(Статья[ \t]+(?:\d+(?:\.\d+)*|[0-9]+(?:\s*[¹²³⁴⁵⁶⁷⁸⁹⁰])?))"
    r"(?:[ \t]*[.\u2014:\u2013-]?[ \t]*(.*))?$",
    re.IGNORECASE,
)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build_index(
    *,
    text_path: Path,
    document_id: str,
    document_title: str,
    source_meta: dict,
    output_path: Path,
) -> int:
    text = text_path.read_text(encoding="utf-8")
    matches = list(ARTICLE_RE.finditer(text))
    if len(matches) < 2:
        raise SystemExit(
            f"{text_path}: too few article headings ({len(matches)}); refusing auto-index"
        )

    articles = []
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        number_heading = match.group(1).strip()
        number = number_heading.split(None, 1)[1].strip() if " " in number_heading else number_heading
        heading = (match.group(2) or "").strip()
        body = text[match.start():end].strip()
        articles.append({
            "ordinal": i + 1,
            "number": number,
            "heading": heading,
            "start_char": match.start(),
            "end_char": end,
            "source_excerpt_characters": len(body),
            "numbered_item_line_candidates": 0,
            "lettered_item_line_candidates": 0,
            "third_party_annotation_marker_lines": [],
            "amendment_editorial_marker_lines": [],
        })

    index = {
        "schema_version": 1,
        "artifact_type": "auto_article_boundary_index",
        "document_id": document_id,
        "document_title": document_title,
        "status": "auto_from_official_capture",
        "source": {
            "source_key": source_meta.get("key") or source_meta.get("source_key"),
            "registry_id": source_meta.get("registry_id", "minjust_official_documents"),
            "official_url": source_meta.get("official_url"),
            "retrieved_at": source_meta.get("retrieved_at"),
            "byte_length": source_meta.get("byte_length"),
            "sha256": source_meta.get("sha256"),
            "extracted_file": text_path.name,
            "extracted_sha256": sha256_text(text),
        },
        "extracted_text": {
            "characters": len(text),
            "article_heading_candidates": len(articles),
            "annotation_marker_count": 0,
        },
        "limitations": [
            "Boundaries detected automatically from Статья N headings in the captured official text.",
            "Does not certify completeness of amendments after the source revision date.",
            "ConsultantPlus-style notes, if present in the source, are retained in article bodies.",
        ],
        "annotation_markers": [],
        "articles": articles,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Indexed {document_id}: {len(articles)} articles → {output_path}")
    return len(articles)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--text", type=Path, required=True)
    parser.add_argument("--document-id", required=True)
    parser.add_argument("--document-title", required=True)
    parser.add_argument("--source-key", required=True)
    parser.add_argument("--official-url", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--retrieved-at", default="")
    parser.add_argument("--byte-length", type=int, default=0)
    parser.add_argument("--registry-id", default="minjust_official_documents")
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Default: content/extracted/{source_key}.article-index.json",
    )
    args = parser.parse_args()
    out = args.output or (ROOT / "content" / "extracted" / f"{args.source_key}.article-index.json")
    meta = {
        "key": args.source_key,
        "registry_id": args.registry_id,
        "official_url": args.official_url,
        "sha256": args.sha256,
        "retrieved_at": args.retrieved_at,
        "byte_length": args.byte_length,
    }
    build_index(
        text_path=args.text,
        document_id=args.document_id,
        document_title=args.document_title,
        source_meta=meta,
        output_path=out,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
