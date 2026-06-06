-- V10: Hacker News 테이블

CREATE TABLE IF NOT EXISTS hacker_news (
    id SERIAL PRIMARY KEY,
    hn_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    url TEXT,
    by_user TEXT NOT NULL,
    score INTEGER DEFAULT 0,
    descendants INTEGER DEFAULT 0,
    item_type TEXT NOT NULL DEFAULT 'story',
    body_text TEXT,
    posted_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (hn_id)
);

CREATE INDEX idx_hacker_news_fetched ON hacker_news(fetched_at DESC);
CREATE INDEX idx_hacker_news_hn_id ON hacker_news(hn_id);
CREATE INDEX idx_hacker_news_score ON hacker_news(score DESC);
