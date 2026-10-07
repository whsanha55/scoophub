-- 90일 경과 로그·뉴스 정리 잡과 burst 조회 인덱스 (#220).

-- hasRecentBurst: payload_key LIKE 'news:burst:%' 조건을 순차 스캔하던 것을 부분 인덱스로 대체
CREATE INDEX ix_notify_log_burst ON notify_log (sent_at DESC)
    WHERE payload_key LIKE 'news:burst:%' AND status = 'success';

INSERT INTO crawl_schedule (crawler, job_id, schedule_type, schedules, description) VALUES
    ('system', 'data_retention', 'cron', ARRAY['0 4 * * *'], '90일 경과 crawl_logs·notify_log·news_article 정리 (매일 04:00)')
ON CONFLICT (crawler, job_id) DO NOTHING;
