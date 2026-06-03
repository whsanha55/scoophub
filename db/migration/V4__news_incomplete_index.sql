-- Summarizer now targets all not-yet-succeeded rows (pending/failed/error),
-- so align the partial index predicate.
DROP INDEX IF EXISTS idx_news_summary_pending;
CREATE INDEX IF NOT EXISTS idx_news_summary_incomplete
    ON news_articles(id) WHERE summary_status <> 'success';
