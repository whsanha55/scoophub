-- V17: stock 시그마/분석 시각 KST 재정렬 — EDT/EST 모두 미국 장 마감 후 보장 + race 회피 (#176)
-- #174 로 cron 해석이 KST가 되면서, V16 daily_sigma '30 22'가 KST 밤 10:30 = 미국 정규장 중이 되는 문제 드러남.
-- 미국 장 마감: EDT(3-11월) KST 05:00 / EST(11-3월) KST 06:00.
--   → 시그마를 KST 06:00으로 두면 두 계절 모두 마감 후 종가 확정 상태 계산.
-- 의존성: stock_analyze(_fetch_sigma_enrichment → sigma_repo.get_latest)가 저장된 시그마를 소비하므로
--   시그마(06:00) → 분석(07:00) 1시간 선행으로 race 회피. 알림 6시 → 7시.

UPDATE crawl_schedule
   SET schedules = ARRAY['0 6 * * *'],
       description = '시그마(straddle) 일일 계산 (매일 KST 06:00, 미국 장 마감 후)'
 WHERE crawler = 'stock' AND job_id = 'stock_daily_sigma';

UPDATE crawl_schedule
   SET schedules = ARRAY['0 7 * * *'],
       description = '주식 분석+발신 (매일 KST 07:00, 시그마 계산 1시간 후)'
 WHERE crawler = 'stock' AND job_id = 'stock_analyze';
