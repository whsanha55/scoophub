-- V13: notify — 크롤 완료 발신 라우팅 + 발신 이력 (#129)
-- 도메인 제안서 Notify 단계 정식 구현.
-- crawl_schedule(V11)/crawl_config(V12)와 동일 철학: DB 단일 진실 + 관리 API.

-- ────────────────────────────────────────────────────────────────
-- notify_routes: (category, purpose) → 채널·토픽 매핑
-- ────────────────────────────────────────────────────────────────
-- category/purpose = ''(빈문자열) → wildcard. 정확 매칭 우선, 없으면 '' 폴백.
-- topic_id NULL + topic_name 있으면 발신 시점에 토픽 자동 생성(createForumTopic).
CREATE TABLE notify_routes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category    TEXT NOT NULL DEFAULT '',   -- news/weather/stock/community/feed/kal_bonus. ''=wildcard
    purpose     TEXT NOT NULL DEFAULT '',   -- rss/snapshot/sigma-scan/... ''. ''=wildcard
    channel     TEXT NOT NULL DEFAULT 'telegram',
    chat_id     TEXT NOT NULL,              -- 텔레그램 슈퍼그룹 chat_id
    topic_id    BIGINT,                     -- forum topic thread_id. NULL=미생성(자동 생성 대상)
    topic_name  TEXT NOT NULL DEFAULT '',   -- 토픽 자동생성용 이름. ''=수동(자동생성 안 함)
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notify_routes_channel_chk CHECK (channel IN ('telegram', 'discord', 'email'))
);

-- 동일 라우팅 중복 방지 (wildcard '' 포함)
CREATE UNIQUE INDEX ux_notify_routes_routing
    ON notify_routes (category, purpose, channel);

-- 활성 라우트 조회용
CREATE INDEX ix_notify_routes_enabled
    ON notify_routes (channel) WHERE enabled = TRUE;

-- ────────────────────────────────────────────────────────────────
-- notify_log: 발신 이력 + 중복 발신 방지
-- ────────────────────────────────────────────────────────────────
-- payload_key = 발신 단위 논리키 (예: 'news:rss' / 'weather:snapshot').
-- 동일 (route_id, payload_key) 재발신 차단.
CREATE TABLE notify_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    route_id    BIGINT NOT NULL REFERENCES notify_routes(id) ON DELETE CASCADE,
    payload_key TEXT NOT NULL DEFAULT '',
    status      TEXT NOT NULL,              -- 'success' | 'error'
    error       TEXT,                       -- 실패 시 메시지
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notify_log_status_chk CHECK (status IN ('success', 'error'))
);

-- 중복 방지: 동일 route+payload는 한 번만
CREATE UNIQUE INDEX ux_notify_log_dedup ON notify_log (route_id, payload_key);
-- 최근 발신/장애 추적
CREATE INDEX ix_notify_log_recent ON notify_log (route_id, sent_at DESC);
