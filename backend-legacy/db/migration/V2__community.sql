-- V2: Community — 사용자 투표/랭킹 기반

-- Hacker News
CREATE TABLE community_hackernews (
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

CREATE INDEX idx_community_hackernews_fetched ON community_hackernews(fetched_at DESC);
CREATE INDEX idx_community_hackernews_hn_id ON community_hackernews(hn_id);
CREATE INDEX idx_community_hackernews_score ON community_hackernews(score DESC);

-- Reddit
CREATE TABLE community_reddit (
    id SERIAL PRIMARY KEY,
    reddit_id TEXT NOT NULL,
    title TEXT NOT NULL,
    author TEXT,
    subreddit TEXT NOT NULL,
    score INTEGER DEFAULT 0,
    upvote_ratio REAL,
    num_comments INTEGER DEFAULT 0,
    url TEXT,
    permalink TEXT NOT NULL,
    selftext TEXT,
    is_self BOOLEAN DEFAULT FALSE,
    link_flair TEXT,
    domain TEXT,
    posted_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (reddit_id)
);

CREATE INDEX idx_community_reddit_fetched ON community_reddit(fetched_at DESC);
CREATE INDEX idx_community_reddit_reddit_id ON community_reddit(reddit_id);
CREATE INDEX idx_community_reddit_subreddit ON community_reddit(subreddit);
CREATE INDEX idx_community_reddit_score ON community_reddit(score DESC);

-- Product Hunt
CREATE TABLE community_producthunt (
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

CREATE INDEX idx_community_producthunt_fetched ON community_producthunt(fetched_at DESC);
CREATE INDEX idx_community_producthunt_posted ON community_producthunt(posted_at DESC);
CREATE INDEX idx_community_producthunt_votes ON community_producthunt(votes_count DESC);

-- GitHub Trending
CREATE TABLE community_github (
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

CREATE INDEX idx_community_github_fetched ON community_github(fetched_at DESC);
CREATE INDEX idx_community_github_period ON community_github(period);
