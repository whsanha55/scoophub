-- V3: Feed — RSS/API → 아티클 저장

-- News
CREATE TABLE feed_news (
    id SERIAL PRIMARY KEY,
    source TEXT NOT NULL,
    category TEXT,
    title TEXT NOT NULL,
    summary TEXT,
    url TEXT UNIQUE NOT NULL,
    normalized_url TEXT,
    published_at TIMESTAMPTZ,
    importance SMALLINT NOT NULL DEFAULT 2,
    summary_status TEXT NOT NULL DEFAULT 'pending'
        CONSTRAINT feed_news_summary_status_check
        CHECK (summary_status IN ('pending', 'success', 'failed', 'error')),
    duplicated BOOLEAN DEFAULT FALSE NOT NULL,
    duplicated_news_id INTEGER REFERENCES feed_news(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feed_news_published ON feed_news(published_at DESC);
CREATE INDEX idx_feed_news_category ON feed_news(category);
CREATE INDEX idx_feed_news_created ON feed_news(created_at DESC);
CREATE INDEX idx_feed_news_importance ON feed_news(importance DESC);
CREATE INDEX idx_feed_news_summary_incomplete ON feed_news(id) WHERE summary_status <> 'success';
CREATE UNIQUE INDEX idx_feed_news_normalized_url ON feed_news(normalized_url);
CREATE INDEX idx_feed_news_dedup ON feed_news(duplicated) WHERE duplicated = FALSE;

-- Tech Newsletter
CREATE TABLE feed_newsletter (
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

CREATE INDEX idx_feed_newsletter_fetched ON feed_newsletter(fetched_at DESC);
CREATE INDEX idx_feed_newsletter_source ON feed_newsletter(source);
CREATE INDEX idx_feed_newsletter_published ON feed_newsletter(published_at DESC);
CREATE UNIQUE INDEX idx_feed_newsletter_url ON feed_newsletter(url);

-- Dev.to / Hashnode
CREATE TABLE feed_devblog (
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

CREATE INDEX idx_feed_devblog_fetched ON feed_devblog(fetched_at DESC);
CREATE INDEX idx_feed_devblog_article_id ON feed_devblog(article_id);
CREATE INDEX idx_feed_devblog_source ON feed_devblog(source);
CREATE INDEX idx_feed_devblog_reactions ON feed_devblog(reactions_count DESC);

-- arXiv
CREATE TABLE feed_arxiv (
    id SERIAL PRIMARY KEY,
    arxiv_id TEXT NOT NULL,
    title TEXT NOT NULL,
    authors JSONB NOT NULL DEFAULT '[]',
    summary TEXT,
    primary_category TEXT NOT NULL,
    categories JSONB DEFAULT '[]',
    pdf_url TEXT,
    abstract_url TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    author_comment TEXT,
    journal_ref TEXT,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (arxiv_id)
);

CREATE INDEX idx_feed_arxiv_fetched ON feed_arxiv(fetched_at DESC);
CREATE INDEX idx_feed_arxiv_arxiv_id ON feed_arxiv(arxiv_id);
CREATE INDEX idx_feed_arxiv_category ON feed_arxiv(primary_category);
CREATE INDEX idx_feed_arxiv_published ON feed_arxiv(published_at DESC);

-- YouTube Trending
CREATE TABLE feed_youtube (
    id SERIAL PRIMARY KEY,
    video_id TEXT NOT NULL,
    title TEXT NOT NULL,
    channel_title TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    description TEXT,
    category_id TEXT,
    published_at TIMESTAMPTZ NOT NULL,
    view_count INTEGER DEFAULT 0,
    like_count INTEGER DEFAULT 0,
    comment_count INTEGER DEFAULT 0,
    duration TEXT,
    thumbnail_url TEXT,
    region_code TEXT NOT NULL DEFAULT 'KR',
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (video_id, region_code)
);

CREATE INDEX idx_feed_youtube_fetched ON feed_youtube(fetched_at DESC);
CREATE INDEX idx_feed_youtube_video_id ON feed_youtube(video_id);
CREATE INDEX idx_feed_youtube_region ON feed_youtube(region_code);
CREATE INDEX idx_feed_youtube_views ON feed_youtube(view_count DESC);
