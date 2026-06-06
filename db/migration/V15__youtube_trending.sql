-- V15: YouTube Trending 동영상 테이블

CREATE TABLE IF NOT EXISTS youtube_trending (
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

CREATE INDEX idx_youtube_trending_fetched ON youtube_trending(fetched_at DESC);
CREATE INDEX idx_youtube_trending_video_id ON youtube_trending(video_id);
CREATE INDEX idx_youtube_trending_region ON youtube_trending(region_code);
CREATE INDEX idx_youtube_trending_views ON youtube_trending(view_count DESC);
