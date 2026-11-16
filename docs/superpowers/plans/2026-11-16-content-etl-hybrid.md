# Content ETL Hybrid + Client Import Implementation Plan

> **For agentic workers:** TDD, ponytail, SOURCE_POLICY hard rules.

**Goal:** Reliable offline legal content pipeline without inventing norms; deliver updates into the Android client with integrity checks.
**Architecture:** Python agent fetches/hashes official pages into `content/records/`; cross-check ≥3 registry hosts; `build_database.py` builds SQLite only from approved records. Android imports pack/SQLite with mandatory SHA-256 and atomic swap.
**Tech Stack:** Python 3, urllib, SQLite; Kotlin, existing LegalStore/ContentUpdate.
**Spec:** User backlog 2026-11-16; docs/SOURCE_POLICY.md

## Task 1 — ETL HTTP utility + rate limit (tests first)
- Files: `agent/adapters/http_util.py`, `agent/test_http_util.py`
- Rate limit, exponential backoff, SHA-256 of body, refuse non-HTTPS
- Commit after green tests

## Task 2 — Source adapters skeleton
- Files: `agent/adapters/base.py`, `agent/fetch_and_record.py`
- Each adapter: fetch registry URL → save `content/records/captures/<source_id>/` + `.meta.json`
- No parsing of legal articles yet (no invented text)
- CLI: `python fetch_and_record.py --source all`

## Task 3 — Wire README
- Update `agent/README.md`

## Task 4 — Android importer hardening (next increment)
- Atomic DB replace; progress UI
- Optional: import SQLite release from GitHub Releases

## Task 5 — Cabinet / release / Kremlin image (later)
- Verify cabinet persistence; release signing; local Kremlin drawable

## Failure modes
- Official portals block bots → captures empty; release blocked (correct)
- Partial match → manual_review
- Seed remains справочный until approved records exist
