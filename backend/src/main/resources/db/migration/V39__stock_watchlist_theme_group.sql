-- V39: 관심종목 테마 그룹 (#259)
-- market/sector/individual 3단계를 테마 그룹으로 바꾼다. 라벨·정렬은 프론트 domains/stock/lib/groups.ts.
-- individual 은 신규 추가·미분류 종목의 기본값으로 남긴다.

ALTER TABLE stock_watchlist DROP CONSTRAINT IF EXISTS stock_watchlist_group_chk;

UPDATE stock_watchlist SET "group" = 'etf_index'
 WHERE ticker IN ('SPY', 'QQQ', 'DIA', 'IWM') OR "group" = 'market';

UPDATE stock_watchlist SET "group" = 'etf_sector'
 WHERE ticker IN ('XLK', 'XLV', 'XLE', 'XLF', 'XLY', 'XLI', 'XLC', 'XLP', 'XLU', 'XLB', 'XLRE',
                  'SOXX', 'SMH', 'ARKK')
    OR "group" = 'sector';

UPDATE stock_watchlist SET "group" = 'semiconductor'
 WHERE ticker IN ('NVDA', 'AMD', 'AVGO', 'TSM', 'INTC', 'QCOM', 'TXN', 'ADI', 'ARM', 'MRVL', 'MU',
                  'NXPI', 'MCHP', 'MPWR', 'ALAB', 'SNDK', 'ON', 'GFS');

UPDATE stock_watchlist SET "group" = 'semi_equipment'
 WHERE ticker IN ('ASML', 'AMAT', 'LRCX', 'KLAC', 'TER', 'CDNS', 'SNPS');

UPDATE stock_watchlist SET "group" = 'ai_infra'
 WHERE ticker IN ('CRWV', 'NBIS', 'CSCO', 'LITE', 'STX', 'WDC');

UPDATE stock_watchlist SET "group" = 'bigtech_software'
 WHERE ticker IN ('AAPL', 'MSFT', 'GOOGL', 'GOOG', 'META', 'ORCL', 'CRM', 'ADBE', 'ADSK', 'INTU',
                  'WDAY', 'ROP', 'PLTR', 'CRWD', 'PANW', 'FTNT', 'ZS', 'DDOG', 'TEAM', 'MDB',
                  'APP', 'TRI', 'ADP', 'PAYX', 'CTSH', 'VRSK');

UPDATE stock_watchlist SET "group" = 'internet_consumer'
 WHERE ticker IN ('AMZN', 'TSLA', 'NFLX', 'ABNB', 'BKNG', 'DASH', 'MELI', 'PDD', 'SHOP', 'UBER',
                  'PYPL', 'COIN', 'MSTR', 'CMCSA', 'TMUS', 'CHTR', 'WBD', 'EA', 'TTWO',
                  'COST', 'WMT', 'PEP', 'KDP', 'MNST', 'MDLZ', 'KHC', 'CCEP', 'SBUX', 'MAR',
                  'ORLY', 'ROST', 'LULU', 'DLTR');

UPDATE stock_watchlist SET "group" = 'healthcare'
 WHERE ticker IN ('AMGN', 'GILD', 'VRTX', 'REGN', 'ALNY', 'ISRG', 'IDXX', 'DXCM', 'GEHC', 'AZN',
                  'BIIB');

UPDATE stock_watchlist SET "group" = 'industrial_energy'
 WHERE ticker IN ('HON', 'HONA', 'AXON', 'CPRT', 'CSX', 'CTAS', 'FAST', 'FER', 'ODFL', 'PCAR',
                  'LIN', 'SPCX', 'CEG', 'AEP', 'EXC', 'XEL', 'BKR', 'FANG');

ALTER TABLE stock_watchlist
    ADD CONSTRAINT stock_watchlist_group_chk
    CHECK ("group" IN ('etf_index', 'etf_sector', 'semiconductor', 'semi_equipment', 'ai_infra',
                       'bigtech_software', 'internet_consumer', 'healthcare', 'industrial_energy',
                       'individual'));
