-- V35: 캔들 동기화를 stock_daily_sigma 파이프라인 첫 단계로 흡수
-- 일봉은 장 마감 후 하루 한 번만 바뀌어 매시간 동기화는 같은 데이터를 다시 받았다.
-- 분석이 DB 캔들을 읽으므로 동기화 → 분석 순서를 시각차가 아닌 코드로 보장한다.

UPDATE crawl_schedule
   SET enabled = false,
       description = 'stock_daily_sigma 파이프라인으로 흡수 — 별도 실행 불필요'
 WHERE crawler = 'stock' AND job_id = 'stock_sync';
