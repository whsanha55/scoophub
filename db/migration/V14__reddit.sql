-- V14: Reddit 게시물 테이블

CREATE TABLE IF NOT EXISTS reddit_posts (
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

CREATE INDEX idx_reddit_posts_fetched ON reddit_posts(fetched_at DESC);
CREATE INDEX idx_reddit_posts_reddit_id ON reddit_posts(reddit_id);
CREATE INDEX idx_reddit_posts_subreddit ON reddit_posts(subreddit);
CREATE INDEX idx_reddit_posts_score ON reddit_posts(score DESC);
