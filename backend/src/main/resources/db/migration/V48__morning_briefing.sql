-- V48: 아침 브리핑 텔레그램 발송 (#325)
-- 매일 07:30(KST, 주말 포함). 시스템 스케줄 화면에서 켜고 끄고 시각을 바꾼다.
INSERT INTO crawl_schedule (crawler, job_id, schedule_type, schedules, description) VALUES
    ('morning_briefing', 'morning_briefing', 'cron', ARRAY['30 7 * * *'], '아침 브리핑 텔레그램 발송 (매일 07:30)')
ON CONFLICT (crawler, job_id) DO NOTHING;

-- 기준값은 crawl_config 로 관리한다 (시스템 설정 화면에서 수정, 다음 발송부터 반영).
-- lookback_hours: 이 시간 안에 발행된 뉴스, 이 시간 안의 수집에서 본 HN·GitHub·Dev.to 만 후보로 삼는다
-- max_items: 묶음마다 최대 건수(높은 순)
-- news_min_importance: 뉴스 중요도, hn_min_score: HN 점수,
-- github_min_stars: GitHub 하루 스타 증가(current_period_stars), devto_min_reactions: Dev.to 반응 수
INSERT INTO crawl_config (crawler, params) VALUES
    ('morning_briefing',
     '{"lookback_hours": 24, "max_items": 5, "news_min_importance": 5, "hn_min_score": 300,
       "github_min_stars": 300, "devto_min_reactions": 50}'::jsonb)
ON CONFLICT (crawler) DO NOTHING;
