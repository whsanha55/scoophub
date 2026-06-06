-- V8: crawl_logs에 crawler_detail 컬럼 추가 + 기존 데이터 마이그레이션

-- crawler_detail 컬럼 추가
ALTER TABLE crawl_logs ADD COLUMN IF NOT EXISTS crawler_detail TEXT NOT NULL DEFAULT '';

-- 기존 stock_sigma 데이터를 stock + crawler_detail='sigma-scan'으로 마이그레이션
UPDATE crawl_logs SET crawler = 'stock', crawler_detail = 'sigma-scan' WHERE crawler = 'stock_sigma';

-- 기존 news/weather 데이터에 crawler_detail 값 세팅
UPDATE crawl_logs SET crawler_detail = 'rss' WHERE crawler = 'news';
UPDATE crawl_logs SET crawler_detail = 'forecast' WHERE crawler = 'weather';

-- 인덱스 변경: crawler + crawler_detail + started_at 복합 인덱스로 교체
DROP INDEX IF EXISTS idx_crawl_logs_crawler;
CREATE INDEX idx_crawl_logs_crawler ON crawl_logs (crawler, crawler_detail, started_at DESC);
