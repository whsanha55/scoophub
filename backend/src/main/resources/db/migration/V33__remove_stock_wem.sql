-- V33: usstocksigma WEM 기능 제거 (#242) — 410 Gone 으로 실패하는 stock_sigma_scan 잡·테이블 정리

DELETE FROM crawl_schedule WHERE crawler = 'stock' AND job_id = 'stock_sigma_scan';
DELETE FROM crawl_config  WHERE crawler = 'stock';  -- 방어적 (seed 에 stock 행 없음, 선례 V23)
DELETE FROM crawl_logs    WHERE crawler = 'stock' AND crawler_detail = 'sigma-scan';
DROP TABLE IF EXISTS stock_weekly_expected_moves;
