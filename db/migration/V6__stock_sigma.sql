-- V5: stock_sigma — ATM straddle 기반 만기별 예상 변동폭 + volume flow
CREATE TABLE IF NOT EXISTS stock_sigma (
    id BIGSERIAL PRIMARY KEY,

    ticker      VARCHAR(20) NOT NULL,
    expiry_date DATE        NOT NULL,

    snapshot_date DATE        NOT NULL,   -- ET 거래일 기준 (snapshot_at→ET 변환 후 date)
    snapshot_at   TIMESTAMPTZ NOT NULL,

    current_price DOUBLE PRECISION NOT NULL,   -- 장중 spot

    atm_strike DOUBLE PRECISION NOT NULL,
    atm_call   DOUBLE PRECISION NOT NULL,      -- mid 우선, 없으면 lastPrice
    atm_put    DOUBLE PRECISION NOT NULL,

    expected_move     DOUBLE PRECISION NOT NULL,   -- atm_call + atm_put
    expected_move_pct DOUBLE PRECISION NOT NULL,   -- expected_move / current_price * 100

    -- 만기별 전체 합산 → flow/sentiment
    total_call_volume     BIGINT NOT NULL DEFAULT 0,
    total_put_volume      BIGINT NOT NULL DEFAULT 0,
    put_call_volume_ratio DOUBLE PRECISION,        -- put/call, call=0이면 NULL

    -- ATM 레그 → straddle 가격 신뢰도
    atm_call_volume BIGINT NOT NULL DEFAULT 0,
    atm_put_volume  BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 일별 1 row → 재실행 upsert
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_sigma_daily
    ON stock_sigma (ticker, expiry_date, snapshot_date);

CREATE INDEX IF NOT EXISTS ix_stock_sigma_ticker_snapshot_date
    ON stock_sigma (ticker, snapshot_date DESC);

CREATE INDEX IF NOT EXISTS ix_stock_sigma_ticker_expiry_snapshot_date
    ON stock_sigma (ticker, expiry_date, snapshot_date DESC);

CREATE INDEX IF NOT EXISTS ix_stock_sigma_snapshot_date
    ON stock_sigma (snapshot_date DESC);
