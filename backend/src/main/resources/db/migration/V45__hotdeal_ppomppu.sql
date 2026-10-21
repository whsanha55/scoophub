-- V45: 뽐뿌 RSS 핫딜 수집 스케줄 (#271)
-- RSS 는 최근 15건만 주므로 글이 몰리는 시간에도 놓치지 않게 15분 주기로 수집한다.
INSERT INTO crawl_schedule (crawler, job_id, schedule_type, schedule_minutes, description) VALUES
    ('hotdeal_ppomppu', 'hotdeal_ppomppu_crawler', 'interval', 15, '뽐뿌 핫딜 RSS 크롤')
ON CONFLICT (crawler, job_id) DO NOTHING;
