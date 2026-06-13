-- V8: Generic crawl_data — "크롤 → 최신 응답 저장 → 최신 조회" 패턴 통합 캐시 테이블
-- 신규 크롤 도메인 추가 시 DDL 없이 INSERT만으로 확장 가능.

CREATE TABLE crawl_data (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category    TEXT        NOT NULL,         -- 대분류: kal / community / feed / weather / crawl_run
    purpose     TEXT        NOT NULL,         -- 세부: bonus_seat / hackernews / rss / snapshot ...
    key         TEXT        NOT NULL,         -- 자연키(중복판별 + upsert 기준)
    date_at     TIMESTAMPTZ NOT NULL,         -- 데이터 기준시각(크롤 fetched_at)
    response    JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT crawl_data_uniq UNIQUE (category, purpose, key)
);

CREATE INDEX ix_crawl_data_latest ON crawl_data (category, purpose, date_at DESC);
CREATE INDEX ix_crawl_data_resp   ON crawl_data USING GIN (response jsonb_path_ops);
