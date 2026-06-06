-- V9: GitHub Trending 리포지토리 테이블

CREATE TABLE IF NOT EXISTS github_trending_repos (
    id SERIAL PRIMARY KEY,
    fullname TEXT NOT NULL,
    author TEXT NOT NULL,
    name TEXT NOT NULL,
    url TEXT UNIQUE NOT NULL,
    description TEXT,
    language TEXT,
    stars INTEGER DEFAULT 0,
    forks INTEGER DEFAULT 0,
    current_period_stars INTEGER DEFAULT 0,
    period TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_github_trending_fetched ON github_trending_repos(fetched_at DESC);
CREATE INDEX idx_github_trending_period ON github_trending_repos(period);
