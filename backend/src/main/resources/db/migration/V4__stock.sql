-- V4: Stock — 기존 스키마 그대로

CREATE TABLE stock_watchlist (
    id SERIAL PRIMARY KEY,
    ticker TEXT NOT NULL,
    exchange TEXT NOT NULL DEFAULT 'NAS',
    name TEXT NOT NULL DEFAULT '',
    memo TEXT,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    added_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);
CREATE UNIQUE INDEX idx_stock_watchlist_ticker ON stock_watchlist(ticker) WHERE is_active = TRUE;

CREATE TABLE stock_weekly_expected_moves (
    id SERIAL PRIMARY KEY,
    ticker TEXT NOT NULL,
    week_start DATE NOT NULL,
    week_end DATE NOT NULL,
    expected_move_high REAL NOT NULL DEFAULT 0,
    expected_move_low REAL NOT NULL DEFAULT 0,
    expected_move_pct REAL NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);
CREATE UNIQUE INDEX idx_stock_wem_ticker_week ON stock_weekly_expected_moves(ticker, week_end);
CREATE INDEX idx_stock_wem_ticker ON stock_weekly_expected_moves(ticker, week_start DESC);

CREATE TABLE stock_candles (
    id SERIAL PRIMARY KEY,
    ticker TEXT NOT NULL,
    interval TEXT NOT NULL DEFAULT '1D',
    date DATE NOT NULL,
    open REAL NOT NULL,
    high REAL NOT NULL,
    low REAL NOT NULL,
    close REAL NOT NULL,
    volume REAL NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX idx_stock_candles_ticker_interval_date ON stock_candles(ticker, interval, date);

CREATE TABLE stock_analysis_results (
    id SERIAL PRIMARY KEY,
    ticker TEXT NOT NULL,
    exchange TEXT NOT NULL DEFAULT 'NAS',
    timeframe TEXT NOT NULL DEFAULT '1D',
    signal TEXT NOT NULL,
    total_score REAL NOT NULL DEFAULT 0,
    confidence REAL NOT NULL DEFAULT 0,
    market_regime TEXT NOT NULL DEFAULT 'RANGING',
    price REAL NOT NULL DEFAULT 0,
    change REAL NOT NULL DEFAULT 0,
    change_rate REAL NOT NULL DEFAULT 0,
    technical_scores JSONB DEFAULT '{}',
    technical_details JSONB DEFAULT '{}',
    analyzed_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);
CREATE UNIQUE INDEX idx_stock_analysis_ticker_timeframe ON stock_analysis_results(ticker, timeframe);
CREATE INDEX idx_stock_analysis_time ON stock_analysis_results(analyzed_at DESC);

CREATE TABLE stock_ticker_params (
    id SERIAL PRIMARY KEY,
    ticker TEXT NOT NULL UNIQUE,
    weights JSONB DEFAULT '{}',
    entry_threshold REAL,
    exit_threshold REAL,
    position_size_pct REAL,
    in_sample_sharpe REAL,
    in_sample_sortino REAL,
    out_sample_sharpe REAL,
    out_sample_sortino REAL,
    is_adopted BOOLEAN DEFAULT FALSE NOT NULL,
    tuned_at TIMESTAMPTZ
);

CREATE TABLE stock_sigma (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    expiry_date DATE NOT NULL,
    snapshot_date DATE NOT NULL,
    snapshot_at TIMESTAMPTZ NOT NULL,
    current_price DOUBLE PRECISION NOT NULL,
    atm_strike DOUBLE PRECISION NOT NULL,
    atm_call DOUBLE PRECISION NOT NULL,
    atm_put DOUBLE PRECISION NOT NULL,
    expected_move DOUBLE PRECISION NOT NULL,
    expected_move_pct DOUBLE PRECISION NOT NULL,
    total_call_volume BIGINT NOT NULL DEFAULT 0,
    total_put_volume BIGINT NOT NULL DEFAULT 0,
    put_call_volume_ratio DOUBLE PRECISION,
    atm_call_volume BIGINT NOT NULL DEFAULT 0,
    atm_put_volume BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_stock_sigma_daily ON stock_sigma (ticker, expiry_date, snapshot_date);
CREATE INDEX ix_stock_sigma_ticker_snapshot_date ON stock_sigma (ticker, snapshot_date DESC);
CREATE INDEX ix_stock_sigma_ticker_expiry_snapshot_date ON stock_sigma (ticker, expiry_date, snapshot_date DESC);
CREATE INDEX ix_stock_sigma_snapshot_date ON stock_sigma (snapshot_date DESC);
