-- V12: crawl_config — 크롤러 도메인 파라미터 DB 동적 관리 (#127)
-- crawl_schedule(V11, 주기)과 동일 철학을 도메인 파라미터(categories, subreddits, feeds 등)로 확장.
-- yaml 도메인 파라미터 → 본 테이블이 단일 진실 소스. 관리 API(PATCH)로 런타임 갱신.

-- 찌꺼기 정리: crawler_metadata(V1)는 EAV 패턴(meta_key/meta_value TEXT)으로 JSONB 부적합.
-- 프로덕션 사용처 0(tests/conftest, tests/test_database 참조만). #127에서 DROP 결정.
-- (V10 NO-GO 주석에는 남겨두었으나, 본 이슈에서 명시적 폐기.)
DROP TABLE IF EXISTS crawler_metadata;

CREATE TABLE crawl_config (
    crawler    TEXT PRIMARY KEY,                     -- news, arxiv, reddit, ...
    params     JSONB NOT NULL DEFAULT '{}'::jsonb,   -- 도메인 파라미터 (categories, feeds 등)
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- seed: yaml config/settings.yaml 현재값 → JSONB (9종 도메인 파라미터)
-- 이관 대상(A 그룹)만. B(런타임 튜닝: source_timeout/retry), D(앱 전역)는 yaml 유지.
-- 예외1: news.dedup_window_hours — news scheduler가 LLM dedup 로직으로 직접 소비하므로 포함.
-- 예외2: 시크릿(api_key/developer_token/client_id/client_secret) — yaml 현재값이 전부 빈 문자열(비활성).
--        crawler 생성자 호환을 위해 params에 포함(빈값). 평문 위험 0.
--        #127은 C(시크릿)를 별도 이관 예정 → 추후 env/secrets manager 이관 시 해당 키 제거.
INSERT INTO crawl_config (crawler, params) VALUES
    ('news',
     '{"max_lookback_hours": 24, "dedup_window_hours": 24}'::jsonb),
    ('github_trending',
     '{"since": "daily", "language": null, "max_repos": 25}'::jsonb),
    ('hacker_news',
     '{"max_items": 100, "min_score": 50, "story_types": ["top", "best"]}'::jsonb),
    ('arxiv',
     '{"categories": ["cs.AI", "cs.LG", "cs.CL", "stat.ML"], "max_results_per_category": 25}'::jsonb),
    ('product_hunt',
     '{"developer_token": "", "max_posts": 30}'::jsonb),
    ('reddit',
     '{"client_id": "", "client_secret": "", "user_agent": "scoophub/1.0 by whsanha55", "subreddits": ["programming", "python", "javascript", "MachineLearning", "webdev", "datascience"], "listing_type": "hot", "max_posts_per_subreddit": 25, "min_score": 50}'::jsonb),
    ('youtube_trending',
     '{"api_key": "", "region_codes": ["KR", "US"], "max_results_per_region": 50}'::jsonb),
    ('devto_hashnode',
     '{"tags": ["python", "javascript", "webdev", "tutorial", "beginners"], "max_articles_per_tag": 30}'::jsonb),
    ('tech_newsletter',
     '{"feeds": [{"url": "https://tldr.tech/api/rss/tech", "source": "TLDR Tech"}, {"url": "https://tldr.tech/api/rss/ai", "source": "TLDR AI"}, {"url": "https://techcrunch.com/feed/", "source": "TechCrunch"}, {"url": "https://www.theverge.com/rss/tech/index.xml", "source": "The Verge"}]}'::jsonb)
ON CONFLICT (crawler) DO NOTHING;
