-- V11: arXiv 논문 테이블

CREATE TABLE IF NOT EXISTS arxiv_papers (
    id SERIAL PRIMARY KEY,
    arxiv_id TEXT NOT NULL,
    title TEXT NOT NULL,
    authors JSONB NOT NULL DEFAULT '[]',
    summary TEXT,
    primary_category TEXT NOT NULL,
    categories JSONB DEFAULT '[]',
    pdf_url TEXT,
    abstract_url TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    author_comment TEXT,
    journal_ref TEXT,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (arxiv_id)
);

CREATE INDEX idx_arxiv_papers_fetched ON arxiv_papers(fetched_at DESC);
CREATE INDEX idx_arxiv_papers_arxiv_id ON arxiv_papers(arxiv_id);
CREATE INDEX idx_arxiv_papers_category ON arxiv_papers(primary_category);
CREATE INDEX idx_arxiv_papers_published ON arxiv_papers(published_at DESC);
