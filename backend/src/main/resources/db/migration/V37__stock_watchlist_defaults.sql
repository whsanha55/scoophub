-- V37: 기본 관심종목 확대 — 주요 지수 ETF, 섹터·테마 ETF, 대형 인기 종목
-- 같은 티커의 활성 행이 있으면 건너뛴다 (idx_stock_watchlist_ticker).

INSERT INTO stock_watchlist (ticker, exchange, name, is_active, "group")
VALUES
    ('SPY',  'NYE', 'SPDR S&P 500 ETF',              TRUE, 'market'),
    ('DIA',  'NYE', 'SPDR Dow Jones Industrial ETF', TRUE, 'market'),
    ('IWM',  'NYE', 'iShares Russell 2000 ETF',      TRUE, 'market'),

    ('XLF',  'NYE', 'Financial Select',              TRUE, 'sector'),
    ('XLY',  'NYE', 'Consumer Discretionary Select', TRUE, 'sector'),
    ('XLI',  'NYE', 'Industrial Select',             TRUE, 'sector'),
    ('XLC',  'NYE', 'Communication Services Select', TRUE, 'sector'),
    ('XLP',  'NYE', 'Consumer Staples Select',       TRUE, 'sector'),
    ('XLU',  'NYE', 'Utilities Select',              TRUE, 'sector'),
    ('XLB',  'NYE', 'Materials Select',              TRUE, 'sector'),
    ('XLRE', 'NYE', 'Real Estate Select',            TRUE, 'sector'),
    ('SMH',  'NAS', 'VanEck Semiconductor ETF',      TRUE, 'sector'),
    ('ARKK', 'NYE', 'ARK Innovation ETF',            TRUE, 'sector'),

    ('AMZN', 'NAS', 'Amazon',                        TRUE, 'individual'),
    ('GOOGL','NAS', 'Alphabet',                      TRUE, 'individual'),
    ('META', 'NAS', 'Meta Platforms',                TRUE, 'individual'),
    ('AVGO', 'NAS', 'Broadcom',                      TRUE, 'individual'),
    ('AMD',  'NAS', 'Advanced Micro Devices',        TRUE, 'individual'),
    ('NFLX', 'NAS', 'Netflix',                       TRUE, 'individual'),
    ('PLTR', 'NAS', 'Palantir',                      TRUE, 'individual'),
    ('TSM',  'NYE', 'Taiwan Semiconductor ADR',      TRUE, 'individual'),
    ('COIN', 'NAS', 'Coinbase',                      TRUE, 'individual'),
    ('MSTR', 'NAS', 'Strategy',                      TRUE, 'individual'),
    ('UBER', 'NYE', 'Uber',                          TRUE, 'individual'),
    ('ORCL', 'NYE', 'Oracle',                        TRUE, 'individual'),
    ('CRM',  'NYE', 'Salesforce',                    TRUE, 'individual'),
    ('INTC', 'NAS', 'Intel',                         TRUE, 'individual')
ON CONFLICT DO NOTHING;
