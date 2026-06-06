-- V13: Product Hunt 포스트 테이블

CREATE TABLE IF NOT EXISTS product_hunt (
    id SERIAL PRIMARY KEY,
    ph_id TEXT NOT NULL,
    name TEXT NOT NULL,
    tagline TEXT,
    slug TEXT NOT NULL,
    ph_url TEXT NOT NULL,
    website_url TEXT,
    votes_count INTEGER DEFAULT 0,
    comments_count INTEGER DEFAULT 0,
    topics JSONB DEFAULT '[]',
    featured_at TIMESTAMPTZ,
    posted_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (ph_id)
);

CREATE INDEX idx_product_hunt_fetched ON product_hunt(fetched_at DESC);
CREATE INDEX idx_product_hunt_posted ON product_hunt(posted_at DESC);
CREATE INDEX idx_product_hunt_votes ON product_hunt(votes_count DESC);
