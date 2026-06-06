-- V24: Tech Newsletter 테이블

CREATE TABLE IF NOT EXISTS tech_newsletter (
    id SERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    source TEXT NOT NULL,
    summary TEXT,
    author TEXT,
    category TEXT,
    published_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_tech_newsletter_fetched ON tech_newsletter(fetched_at DESC);
CREATE INDEX idx_tech_newsletter_source ON tech_newsletter(source);
CREATE INDEX idx_tech_newsletter_published ON tech_newsletter(published_at DESC);
CREATE UNIQUE INDEX idx_tech_newsletter_url ON tech_newsletter(url);
