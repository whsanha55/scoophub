CREATE TABLE news_article (
    id BIGINT PRIMARY KEY,
    source TEXT NOT NULL,
    headline TEXT NOT NULL,
    summary TEXT,
    content TEXT,
    author TEXT,
    url TEXT,
    symbols TEXT[] NOT NULL DEFAULT '{}',
    published_at TIMESTAMPTZ NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'filtered', 'pushed', 'skipped', 'failed')),
    attempts SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decision_reason TEXT,
    decided_at TIMESTAMPTZ,
    importance SMALLINT CHECK (importance BETWEEN 1 AND 5),
    category TEXT,
    summary_ko TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_news_article_pending ON news_article(published_at, id) WHERE status = 'pending';
CREATE INDEX idx_news_article_published ON news_article(published_at DESC, id DESC);
CREATE INDEX idx_news_article_symbols ON news_article USING GIN (symbols);
