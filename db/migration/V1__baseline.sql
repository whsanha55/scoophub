-- Baseline schema for ScoopHub.
-- news_articles is dropped and recreated clean (one-time redesign).
-- All other tables use CREATE TABLE IF NOT EXISTS to preserve existing data.

-- ────────────────────────────────────────────────────────────
--  News Context (clean redesign)
-- ────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS news_articles CASCADE;

CREATE TABLE news_articles (
    id             SERIAL PRIMARY KEY,
    source         TEXT NOT NULL,
    category       TEXT,
    title          TEXT NOT NULL,          -- crawl: RSS 원문 / 영문 소스는 LLM 요약 후 한글로 덮어씀
    summary        TEXT,                   -- crawl: RSS 본문 원문 / LLM 요약 후 한글 요약으로 덮어씀
    url            TEXT UNIQUE NOT NULL,
    published_at   TIMESTAMPTZ,
    importance     SMALLINT NOT NULL DEFAULT 2,     -- LLM 1~5 / 미처리·실패 = 2
    summary_status TEXT NOT NULL DEFAULT 'pending'
        CONSTRAINT news_summary_status_check
        CHECK (summary_status IN ('pending', 'success', 'failed', 'error')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_news_published       ON news_articles(published_at DESC);
CREATE INDEX idx_news_category        ON news_articles(category);
CREATE INDEX idx_news_created         ON news_articles(created_at DESC);
CREATE INDEX idx_news_importance      ON news_articles(importance DESC);
CREATE INDEX idx_news_summary_pending ON news_articles(id) WHERE summary_status = 'pending';

-- ────────────────────────────────────────────────────────────
--  Weather Context
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS weather_snapshots (
    id SERIAL PRIMARY KEY,
    location TEXT NOT NULL DEFAULT 'seoul',
    fetched_at TIMESTAMPTZ NOT NULL,
    temperature REAL,
    feels_like REAL,
    humidity INTEGER,
    wind_speed REAL,
    wind_direction TEXT,
    condition TEXT,
    precip_mm REAL,
    rain_chance INTEGER,
    pm10 REAL,
    pm10_grade TEXT,
    pm25 REAL,
    pm25_grade TEXT,
    ozone REAL,
    uv_index REAL,
    uv_grade TEXT,
    weekly_forecast JSONB,
    raw_json JSONB
);

CREATE INDEX IF NOT EXISTS idx_weather_location_time ON weather_snapshots(location, fetched_at DESC);

-- ────────────────────────────────────────────────────────────
--  Shared Kernel (EAV metadata)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS crawler_metadata (
    id SERIAL PRIMARY KEY,
    crawler TEXT NOT NULL,
    meta_key TEXT NOT NULL,
    meta_value TEXT NOT NULL,
    deleted BOOLEAN DEFAULT FALSE NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_crawler_meta_active
    ON crawler_metadata(crawler, meta_key) WHERE deleted = FALSE;

-- ────────────────────────────────────────────────────────────
--  Crawl Logs
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS crawl_logs (
    id SERIAL PRIMARY KEY,
    crawler TEXT NOT NULL,
    status TEXT NOT NULL,
    items_fetched INTEGER DEFAULT 0,
    items_new INTEGER DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_crawl_logs_crawler ON crawl_logs(crawler, started_at DESC);

-- ────────────────────────────────────────────────────────────
--  Crawl Sources (generic, multi-crawler)
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS crawl_sources (
    id SERIAL PRIMARY KEY,
    crawler TEXT NOT NULL,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    config JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_crawl_sources_crawler_url
    ON crawl_sources(crawler, url);
CREATE INDEX IF NOT EXISTS idx_crawl_sources_crawler_active
    ON crawl_sources(crawler) WHERE active = TRUE;

-- ────────────────────────────────────────────────────────────
--  Stock Context
-- ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_watchlist (
    id SERIAL PRIMARY KEY,
    ticker TEXT NOT NULL,
    exchange TEXT NOT NULL DEFAULT 'NAS',
    name TEXT NOT NULL DEFAULT '',
    memo TEXT,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    added_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_stock_watchlist_ticker ON stock_watchlist(ticker) WHERE is_active = TRUE;

CREATE TABLE IF NOT EXISTS stock_weekly_expected_moves (
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
CREATE UNIQUE INDEX IF NOT EXISTS idx_stock_wem_ticker_week ON stock_weekly_expected_moves(ticker, week_end);
CREATE INDEX IF NOT EXISTS idx_stock_wem_ticker ON stock_weekly_expected_moves(ticker, week_start DESC);

CREATE TABLE IF NOT EXISTS stock_candles (
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
CREATE UNIQUE INDEX IF NOT EXISTS idx_stock_candles_ticker_interval_date ON stock_candles(ticker, interval, date);

CREATE TABLE IF NOT EXISTS stock_analysis_results (
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
CREATE UNIQUE INDEX IF NOT EXISTS idx_stock_analysis_ticker_timeframe ON stock_analysis_results(ticker, timeframe);
CREATE INDEX IF NOT EXISTS idx_stock_analysis_time ON stock_analysis_results(analyzed_at DESC);

CREATE TABLE IF NOT EXISTS stock_ticker_params (
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
