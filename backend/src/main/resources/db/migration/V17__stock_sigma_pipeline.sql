-- V17: stock 시그마→분석 파이프라인 통합 (#176)
-- stock_analyze 잡을 stock_daily_sigma 안으로 흡수: 시그마 계산 완료 직후 분석+발신을 호출(코드 순서 보장).
-- cron 2개를 시각차로 race 회피하던 V16/초안 방식 대신, 데이터 의존성을 파이프라인으로 명시.
-- 미국 장 마감: EDT(3-11월) KST 05:00 / EST(11-3월) KST 06:00 → KST 06:00 시작이 두 계절 모두 마감 후.
-- 06:05 시작(EST 마감 06:00 + 5분, 종가 반영 여유) → 시그마 계산(수분) → 직후 분석+발신.

UPDATE crawl_schedule
   SET schedules = ARRAY['5 6 * * *'],
       description = '시그마(straddle) 계산 후 분석+발신 파이프라인 (매일 KST 06:05, 미국 장 마감 후)'
 WHERE crawler = 'stock' AND job_id = 'stock_daily_sigma';

UPDATE crawl_schedule
   SET enabled = false,
       description = 'stock_daily_sigma 파이프라인으로 흡수 — 별도 실행 불필요 (#176)'
 WHERE crawler = 'stock' AND job_id = 'stock_analyze';
