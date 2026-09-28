-- V19: stock_analyze 고아 행 삭제 (#184)
-- V17 에서 stock_analyze 잡을 stock_daily_sigma 파이프라인으로 흡수(enabled=false)했으나
-- crawl_schedule 행은 남아 있었고, scheduler.py 에는 등록 자체가 안 되어 토글이 무효인 죽은 행.
-- schedules_router 에서 enabled 를 바꿔도 대응 잡이 없으므로 혼란만 유발 → 삭제.

DELETE FROM crawl_schedule
 WHERE crawler = 'stock' AND job_id = 'stock_analyze';
