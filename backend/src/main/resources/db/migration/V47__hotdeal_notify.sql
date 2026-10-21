-- V47: 핫딜 추천 Telegram 알림 (#271, HOTDEAL_PLAN 9절)
-- 발송 결과·중복 방지는 notify_log 가 맡고, 여기에는 항목별 발송 시각만 남겨 하루 상한과 재발송 방지에 쓴다.
ALTER TABLE hotdeal_feed_item ADD COLUMN notified_at TIMESTAMPTZ;
CREATE INDEX idx_hotdeal_feed_item_notified_at ON hotdeal_feed_item (notified_at) WHERE notified_at IS NOT NULL;

-- 알림 설정은 crawl_config 로 관리한다 (시스템 관리 화면에서 수정). 배포 직후 알림이 몰리지 않게 기본은 꺼 둔다.
-- score_threshold: 웹 추천 임계치(3.0)보다 높게, daily_limit: 하루 상한(timezone 기준),
-- quiet_start~quiet_end: 조용한 시간(자정을 넘길 수 있음), max_age_hours: 이보다 오래된 딜은 보내지 않는다,
-- web_url: 메시지에 붙일 웹 피드 주소 (비우면 생략)
INSERT INTO crawl_config (crawler, params) VALUES
    ('hotdeal_notify',
     '{"enabled": false, "score_threshold": 5.0, "daily_limit": 5, "quiet_start": "23:00", "quiet_end": "08:00",
       "timezone": "Asia/Seoul", "max_age_hours": 6, "web_url": ""}'::jsonb)
ON CONFLICT (crawler) DO NOTHING;
