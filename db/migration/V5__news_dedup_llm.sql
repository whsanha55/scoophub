-- LLM 기반 중복 검사를 위한 컬럼 추가
ALTER TABLE news_articles
  ADD COLUMN IF NOT EXISTS duplicated BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE news_articles
  ADD COLUMN IF NOT EXISTS duplicated_news_id INTEGER REFERENCES news_articles(id);

-- 요약 대상 조회 성능 향상 (duplicated=false 만 요약)
CREATE INDEX idx_news_dedup
  ON news_articles(duplicated)
  WHERE duplicated = FALSE;
