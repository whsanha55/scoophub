-- 코드 참조가 없는 테이블과 중복 인덱스 정리 (#222). Keep historical migrations immutable.
DROP TABLE IF EXISTS crawl_sources;
DROP TABLE IF EXISTS stock_ticker_params;
-- uq_stock_sigma_daily (ticker, expiry_date, snapshot_date) 와 같은 컬럼 구성
DROP INDEX IF EXISTS ix_stock_sigma_ticker_expiry_snapshot_date;
