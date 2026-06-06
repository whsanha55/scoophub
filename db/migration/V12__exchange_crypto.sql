-- V12: 암호화폐 시세 테이블

CREATE TABLE IF NOT EXISTS exchange_crypto (
    id SERIAL PRIMARY KEY,
    coin_id TEXT NOT NULL,
    symbol TEXT NOT NULL,
    name TEXT NOT NULL,
    current_price REAL,
    market_cap REAL,
    market_cap_rank INTEGER,
    total_volume REAL,
    price_change_percentage_24h REAL,
    high_24h REAL,
    low_24h REAL,
    circulating_supply REAL,
    vs_currency TEXT NOT NULL DEFAULT 'krw',
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (coin_id, vs_currency)
);

CREATE INDEX idx_exchange_crypto_fetched ON exchange_crypto(fetched_at DESC);
CREATE INDEX idx_exchange_crypto_coin_id ON exchange_crypto(coin_id);
CREATE INDEX idx_exchange_crypto_rank ON exchange_crypto(market_cap_rank);
CREATE INDEX idx_exchange_crypto_currency ON exchange_crypto(vs_currency);
