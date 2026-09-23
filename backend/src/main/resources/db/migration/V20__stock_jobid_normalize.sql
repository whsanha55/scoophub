-- V20: stock job_id 네이밍 통일 — 하이픈 → 언더스코어 (#184)
-- stock_sync / stock_daily_sigma (언더스코어) 와 일관되게.
-- scheduler.py 의 APScheduler job_id 와 crawl_schedule PK 동기화 필수.

UPDATE crawl_schedule
   SET job_id = 'stock_sigma_scan'
 WHERE crawler = 'stock' AND job_id = 'stock-sigma-scan';
