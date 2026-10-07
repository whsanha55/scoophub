-- V34: stock_daily_sigma 를 미국 거래일 다음 날(KST 화-토)에만 실행
-- 매일 실행하면 KST 일·월 06:30(ET 토·일 17:30) 회차가 금요일 데이터를 주말 snapshot_date 로 다시 저장했다.
-- 그렇게 쌓인 주말 행은 금요일 값의 복사본이라 삭제한다.

UPDATE crawl_schedule
   SET schedules = ARRAY['30 6 * * 2-6'],
       description = '캔들 동기화 → 시그마(straddle) → 분석+발신 파이프라인 (KST 화-토 06:30, 미국 장 마감 후 버퍼)'
 WHERE crawler = 'stock' AND job_id = 'stock_daily_sigma';

DELETE FROM stock_sigma WHERE EXTRACT(ISODOW FROM snapshot_date) IN (6, 7);
