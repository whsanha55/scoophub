# shared/database.py
from __future__ import annotations

import logging
from typing import Any

import asyncpg

logger = logging.getLogger(__name__)

SCHEMA_SQL = """
-- News Context
CREATE TABLE IF NOT EXISTS news_articles (
    id SERIAL PRIMARY KEY,
    source TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    url TEXT UNIQUE NOT NULL,
    published_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL,
    category TEXT,
    importance TEXT
);

CREATE INDEX IF NOT EXISTS idx_news_published ON news_articles(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_news_category ON news_articles(category);
CREATE INDEX IF NOT EXISTS idx_news_fetched ON news_articles(fetched_at DESC);

-- Weather Context
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

-- Shared Kernel (EAV metadata)
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

-- Crawl Logs
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

-- Crawl Sources (generic, multi-crawler)
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

-- Add summary column for LLM-generated summaries
ALTER TABLE news_articles ADD COLUMN IF NOT EXISTS summary TEXT DEFAULT NULL;

-- Partial index for pending summarization
CREATE INDEX IF NOT EXISTS idx_news_summary_pending ON news_articles(id) WHERE summary IS NULL;

-- Stock Context
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
"""


class Database:
    def __init__(self, database_url: str):
        self._database_url = database_url
        self._pool: asyncpg.Pool | None = None

    @property
    async def pool(self) -> asyncpg.Pool:
        if self._pool is None:
            self._pool = await asyncpg.create_pool(self._database_url, min_size=2, max_size=10)
        return self._pool

    async def initialize(self) -> None:
        pool = await self.pool
        async with pool.acquire() as conn:
            await conn.execute(SCHEMA_SQL)
        logger.info("Database schema initialized")

    async def close(self) -> None:
        if self._pool:
            await self._pool.close()
            self._pool = None

    async def execute(self, query: str, *args: Any) -> str:
        pool = await self.pool
        return await pool.execute(query, *args)

    async def fetch(self, query: str, *args: Any) -> list[asyncpg.Record]:
        pool = await self.pool
        return await pool.fetch(query, *args)

    async def fetchrow(self, query: str, *args: Any) -> asyncpg.Record | None:
        pool = await self.pool
        return await pool.fetchrow(query, *args)

    async def fetchval(self, query: str, *args: Any) -> Any:
        pool = await self.pool
        return await pool.fetchval(query, *args)
