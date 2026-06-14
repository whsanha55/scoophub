-- V11: crawl_schedule — 크롤 주기 DB 동적 관리 (#124)
-- crawl_sources 선례(DB 단일 진실 + 관리 API)를 스케줄에 동일 적용.
-- yaml schedule 키 제거 → 본 테이블이 단일 seed 소스.

CREATE TABLE crawl_schedule (
    crawler          TEXT NOT NULL,                 -- news, hacker_news, arxiv, stock, ...
    job_id           TEXT NOT NULL,                 -- news_crawler, stock_sync, stock-sigma-scan, ...
    schedule_type    TEXT NOT NULL,                 -- 'cron' | 'interval'
    schedules        TEXT[] NOT NULL DEFAULT '{}',  -- cron expr 배열 (다중 지원, OrTrigger 결합)
    schedule_minutes INTEGER,                       -- minutes (schedule_type='interval')
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    description      TEXT NOT NULL DEFAULT '',
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (crawler, job_id),
    CONSTRAINT crawl_schedule_type_chk CHECK (schedule_type IN ('cron', 'interval'))
);

-- seed: interval (3)
INSERT INTO crawl_schedule (crawler, job_id, schedule_type, schedule_minutes, description) VALUES
    ('news',   'news_crawler',   'interval', 15, '뉴스 RSS 크롤'),
    ('weather','weather_crawler','interval', 30, '날씨 스냅샷 크롤'),
    ('stock',  'stock_sync',     'interval', 60, '관심종목 캔들 동기화')
ON CONFLICT (crawler, job_id) DO NOTHING;

-- seed: cron (12)
INSERT INTO crawl_schedule (crawler, job_id, schedule_type, schedules, description) VALUES
    ('stock',            'stock_daily_sigma',         'cron', ARRAY['30 22 * * 2-6'], '시그마(straddle) 일일 계산 (화-토 22:30, 하드코딩 이관)'),
    ('stock',            'stock-sigma-scan',          'cron', ARRAY['0 3 * * 1'],     '시그마 크롤 스캔 (월 03:00)'),
    ('stock',            'stock_analyze',             'cron', ARRAY['0 6 * * 2-6'],   '주식 분석 실행 (화-토 06:00)'),
    ('github_trending',  'github_trending_crawler',   'cron', ARRAY['0 9 * * *'],     'GitHub 트렌딩 크롤'),
    ('hacker_news',      'hacker_news_crawler',       'cron', ARRAY['0 */4 * * *'],   'Hacker News 크롤'),
    ('arxiv',            'arxiv_crawler',             'cron', ARRAY['0 10 * * *'],    'arXiv 논문 크롤'),
    ('product_hunt',     'product_hunt_crawler',      'cron', ARRAY['0 11 * * *'],    'Product Hunt 크롤'),
    ('reddit',           'reddit_crawler',            'cron', ARRAY['0 */4 * * *'],   'Reddit 크롤'),
    ('youtube_trending', 'youtube_trending_crawler',  'cron', ARRAY['0 */6 * * *'],   'YouTube 트렌딩 크롤'),
    ('devto_hashnode',   'devto_hashnode_crawler',    'cron', ARRAY['0 */4 * * *'],   'Dev.to/Hashnode 크롤'),
    ('tech_newsletter',  'tech_newsletter_crawler',   'cron', ARRAY['0 */4 * * *'],   '기술 뉴스레터 RSS 크롤'),
    ('kal_bonus',        'kal_bonus_crawler',         'cron', ARRAY['0 7 * * *'],     'KAL 보너스 좌석 크롤 (KST 07:00)')
ON CONFLICT (crawler, job_id) DO NOTHING;
