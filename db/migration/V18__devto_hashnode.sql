-- V18: Dev.to / Hashnode 아티클 테이블

CREATE TABLE IF NOT EXISTS devto_hashnode (
    id SERIAL PRIMARY KEY,
    article_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    author TEXT,
    description TEXT,
    reactions_count INTEGER DEFAULT 0,
    comments_count INTEGER DEFAULT 0,
    reading_time INTEGER,
    tags JSONB DEFAULT '[]',
    source TEXT NOT NULL DEFAULT 'devto',
    published_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (article_id)
);

CREATE INDEX idx_devto_hashnode_fetched ON devto_hashnode(fetched_at DESC);
CREATE INDEX idx_devto_hashnode_article_id ON devto_hashnode(article_id);
CREATE INDEX idx_devto_hashnode_source ON devto_hashnode(source);
CREATE INDEX idx_devto_hashnode_reactions ON devto_hashnode(reactions_count DESC);
