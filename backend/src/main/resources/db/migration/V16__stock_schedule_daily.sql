-- V16: stock 분석/시그마 스케줄 요일 제거 — 매일 실행 (#172)
-- APScheduler 가 cron 요일을 0=월 기준으로 해석 → V11 의도 '2-6'(표준 cron 0=일 기준 화~토)이
-- 실제론 수~일이 되어 미국 영업일인 월·화 자동 분석+발신 누락.
-- 발신은 날짜별 payload_key dedup로 1회라 매일 실행해도 중복 없음 → 요일 제거(매일).
-- V11 seed(ON CONFLICT DO NOTHING)는 신규 환경에만 들어가고 V16 UPDATE 로 최종 '*'.

UPDATE crawl_schedule
   SET schedules = ARRAY['30 22 * * *'],
       description = '시그마(straddle) 일일 계산 (매일 22:30)'
 WHERE crawler = 'stock' AND job_id = 'stock_daily_sigma';

UPDATE crawl_schedule
   SET schedules = ARRAY['0 6 * * *'],
       description = '주식 분석 실행 (매일 06:00)'
 WHERE crawler = 'stock' AND job_id = 'stock_analyze';
