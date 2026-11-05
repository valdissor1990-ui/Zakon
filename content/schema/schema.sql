PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS content_release (
    id INTEGER PRIMARY KEY,
    version TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    effective_as_of TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('draft','validated','published','withdrawn'))
);

CREATE TABLE IF NOT EXISTS sources (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    publisher TEXT NOT NULL,
    official_url TEXT NOT NULL,
    source_type TEXT NOT NULL,
    retrieved_at TEXT NOT NULL,
    source_document_id TEXT,
    sha256 TEXT NOT NULL,
    notes TEXT
);

CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    document_type TEXT NOT NULL,
    number TEXT,
    adopted_at TEXT,
    published_at TEXT,
    effective_from TEXT,
    effective_to TEXT,
    legal_status TEXT NOT NULL,
    jurisdiction TEXT NOT NULL DEFAULT 'RU',
    source_id INTEGER NOT NULL REFERENCES sources(id),
    source_revision TEXT,
    verified_at TEXT,
    verification_status TEXT NOT NULL CHECK (verification_status IN ('unverified','single_source','cross_checked','approved','rejected')),
    text_sha256 TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS articles (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    parent_id TEXT REFERENCES articles(id),
    ordinal INTEGER NOT NULL,
    number TEXT NOT NULL,
    title TEXT,
    body TEXT NOT NULL,
    effective_from TEXT,
    effective_to TEXT,
    UNIQUE(document_id, number, effective_from)
);

CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts5(
    article_id UNINDEXED,
    document_title,
    article_number,
    article_title,
    body,
    tokenize = 'unicode61'
);

CREATE TABLE IF NOT EXISTS cross_references (
    id INTEGER PRIMARY KEY,
    from_article_id TEXT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    to_document_id TEXT REFERENCES documents(id),
    to_article_number TEXT NOT NULL,
    reference_text TEXT NOT NULL,
    resolved_article_id TEXT REFERENCES articles(id),
    resolution_status TEXT NOT NULL CHECK (resolution_status IN ('resolved','ambiguous','unresolved'))
);

CREATE TABLE IF NOT EXISTS legal_status_history (
    id INTEGER PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    valid_from TEXT,
    valid_to TEXT,
    source_id INTEGER REFERENCES sources(id),
    recorded_at TEXT NOT NULL,
    explanation TEXT
);

CREATE INDEX IF NOT EXISTS idx_articles_document_ordinal ON articles(document_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(legal_status, verification_status);
CREATE INDEX IF NOT EXISTS idx_crossrefs_source ON cross_references(from_article_id);
