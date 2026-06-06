-- V29: RSS Universal — 피드 관리 및 엔트리 테이블

CREATE TABLE IF NOT EXISTS rss_feed (
    id SERIAL PRIMARY KEY,
    url TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    site_url TEXT,
    last_fetched_at TIMESTAMPTZ,
    last_etag TEXT,
    last_modified TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    poll_interval_minutes INTEGER DEFAULT 60,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rss_entry (
    id SERIAL PRIMARY KEY,
    feed_id INTEGER NOT NULL REFERENCES rss_feed(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    summary TEXT,
    author TEXT,
    published_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_rss_entry_fetched ON rss_entry(fetched_at DESC);
CREATE INDEX idx_rss_entry_feed ON rss_entry(feed_id);
CREATE INDEX idx_rss_entry_published ON rss_entry(published_at DESC);
CREATE UNIQUE INDEX idx_rss_entry_url ON rss_entry(url);
