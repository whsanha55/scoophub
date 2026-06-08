-- V1: System — crawl_logs, crawl_sources, crawler_metadata

CREATE TABLE crawl_logs (
    id SERIAL PRIMARY KEY,
    crawler TEXT NOT NULL,
    crawler_detail TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL,
    items_fetched INTEGER DEFAULT 0,
    items_new INTEGER DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_crawl_logs_crawler ON crawl_logs (crawler, crawler_detail, started_at DESC);

CREATE TABLE crawl_sources (
    id SERIAL PRIMARY KEY,
    crawler TEXT NOT NULL,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    config JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE UNIQUE INDEX idx_crawl_sources_crawler_url ON crawl_sources(crawler, url);
CREATE INDEX idx_crawl_sources_crawler_active ON crawl_sources(crawler) WHERE active = TRUE;

CREATE TABLE crawler_metadata (
    id SERIAL PRIMARY KEY,
    crawler TEXT NOT NULL,
    meta_key TEXT NOT NULL,
    meta_value TEXT NOT NULL,
    deleted BOOLEAN DEFAULT FALSE NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE UNIQUE INDEX idx_crawler_meta_active ON crawler_metadata(crawler, meta_key) WHERE deleted = FALSE;
