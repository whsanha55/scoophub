-- V18: stock_daily_sigma cron 06:05 → 06:30 (미국 장 마감 후 버퍼 확보, #180)
-- EDT(3-11월): 미국 장마감 ET 16:00 = KST 05:00 → 06:30이면 마감 후 1h30m (quote/옵션체인 갱신 여유).
-- EST(11-3월): 마감 KST 06:00 → 06:30이면 마감 후 30m.
-- 기존 06:05는 EST엔 적절했으나 EDT 구간에서 yfinance 갱신 지연 시 전일 종가 반영 위험.

UPDATE crawl_schedule
   SET schedules = ARRAY['30 6 * * *'],
       description = '시그마(straddle) 계산 후 분석+발신 파이프라인 (매일 KST 06:30, 미국 장 마감 후 버퍼)'
 WHERE crawler = 'stock' AND job_id = 'stock_daily_sigma';
