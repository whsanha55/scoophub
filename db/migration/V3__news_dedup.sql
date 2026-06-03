-- Deduplication support: normalized URL for exact-match dedup.
ALTER TABLE news_articles ADD COLUMN IF NOT EXISTS normalized_url TEXT;

-- Backfill existing rows (fallback to raw url).
UPDATE news_articles SET normalized_url = url WHERE normalized_url IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_news_normalized_url ON news_articles(normalized_url);
