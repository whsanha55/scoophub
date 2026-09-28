-- 반도체 ETF SOXX 섹터층 추가 (#153)
INSERT INTO stock_watchlist (ticker, exchange, name, memo, is_active, "group")
VALUES ('SOXX', 'NYE', 'iShares Semiconductor', 'sector', TRUE, 'sector');
