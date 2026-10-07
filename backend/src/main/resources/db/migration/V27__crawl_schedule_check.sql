-- crawl_schedule 에 잘못된 주기가 저장되면 기동 시 잡 등록이 실패한다 (#214).
ALTER TABLE crawl_schedule
    ADD CONSTRAINT crawl_schedule_trigger_chk CHECK (
        (schedule_type = 'interval' AND schedule_minutes > 0)
        OR (schedule_type = 'cron' AND cardinality(schedules) > 0)
    );
