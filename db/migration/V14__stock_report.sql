-- V14: Stock 일간 분석 리포트 시스템 개편 (#147)
-- 1. stock_watchlist.group 컬럼 — market/sector/individual 계층 분류
-- 2. 시장층(^IXIC, ^NDX, QQQ) + 섹터층(XLK, XLV, XLE) 시드
-- 3. notify_routes: stock/daily-report 라우트 (발신 경로)

-- ────────────────────────────────────────────────────────────────
-- 1. stock_watchlist.group
-- ────────────────────────────────────────────────────────────────
ALTER TABLE stock_watchlist
    ADD COLUMN IF NOT EXISTS "group" TEXT NOT NULL DEFAULT 'individual';

-- 개별 종목(group=individual)은 유니크 티커 유지. 시장/섹터층은 동일 티커 없으므로
-- 기존 idx_stock_watchlist_ticker(is_active=TRUE) 그대로 유효.

-- group 체크 제약 (허용값 고정)
ALTER TABLE stock_watchlist
    DROP CONSTRAINT IF EXISTS stock_watchlist_group_chk;
ALTER TABLE stock_watchlist
    ADD CONSTRAINT stock_watchlist_group_chk
    CHECK ("group" IN ('market', 'sector', 'individual'));

-- ────────────────────────────────────────────────────────────────
-- 2. 시장층 / 섹터층 시드 (group 분류)
--    중복 INSERT 방지: 동일 ticker 활성 행이 있으면 스킵
-- ────────────────────────────────────────────────────────────────
INSERT INTO stock_watchlist (ticker, exchange, name, memo, is_active, "group")
VALUES
    -- 시장층 (broad index/ETF)
    ('^IXIC', 'NAS', 'NASDAQ Composite',   'market', TRUE, 'market'),
    ('^NDX',  'NAS', 'NASDAQ 100',         'market', TRUE, 'market'),
    ('QQQ',   'NAS', 'Invesco QQQ Trust',  'market', TRUE, 'market'),
    -- 섹터층 (sector SPDR ETF) — NYSE Arca 상장
    ('XLK',   'NYE', 'Technology Select',  'sector', TRUE, 'sector'),
    ('XLV',   'NYE', 'Health Care Select', 'sector', TRUE, 'sector'),
    ('XLE',   'NYE', 'Energy Select',      'sector', TRUE, 'sector')
ON CONFLICT DO NOTHING;

-- ────────────────────────────────────────────────────────────────
-- 3. notify_routes: stock/daily-report 발신 라우트
--    topic_id/topic_name = 운영자가 환경에 맞게 채워 발신 (별도 토픽).
--    category='stock' 라우트가 없으면 provisioner 가 자동 생성 가능하나,
--    명시 시드로 발신 경로 보장.
-- ────────────────────────────────────────────────────────────────
INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled)
VALUES ('stock', 'daily-report', 'telegram', '', NULL, '', TRUE)
ON CONFLICT (category, purpose, channel) DO NOTHING;
