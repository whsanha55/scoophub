-- V5: stock_sigma — options chain IV 기반 일간/주간 시그마 저장
CREATE TABLE IF NOT EXISTS stock_sigma (
    id          SERIAL PRIMARY KEY,
    ticker      VARCHAR(20)  NOT NULL,
    sigma_type  VARCHAR(10)  NOT NULL DEFAULT 'daily',   -- daily | weekly
    current_price  DOUBLE PRECISION NOT NULL DEFAULT 0,
    atm_iv      DOUBLE PRECISION NOT NULL DEFAULT 0,
    expiry_date DATE,
    dte         INT,
    daily_sigma       DOUBLE PRECISION NOT NULL DEFAULT 0,
    daily_sigma_pct   DOUBLE PRECISION NOT NULL DEFAULT 0,
    expected_move_high DOUBLE PRECISION NOT NULL DEFAULT 0,
    expected_move_low  DOUBLE PRECISION NOT NULL DEFAULT 0,
    expected_move_pct  DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 동일 ticker/type/expiry 조합은 upsert
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_sigma_ticker_type_expiry
    ON stock_sigma (ticker, sigma_type, COALESCE(expiry_date, '1970-01-01'::date));

-- 조회용: 최근 데이터 우선
CREATE INDEX IF NOT EXISTS ix_stock_sigma_ticker_type_created
    ON stock_sigma (ticker, sigma_type, created_at DESC);
