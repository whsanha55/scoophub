-- V36: 분석 결과 일별 이력
-- stock_analysis_results 는 (ticker, timeframe) 최신 1행만 유지해 과거 신호를 사후 검증할 수 없었다.
-- 같은 거래일(ET)에 다시 분석하면 그날 행을 갱신한다.

CREATE TABLE stock_analysis_history (
    id               BIGSERIAL PRIMARY KEY,
    ticker           TEXT             NOT NULL,
    timeframe        TEXT             NOT NULL,
    trade_date       DATE             NOT NULL,
    signal           TEXT             NOT NULL,
    total_score      DOUBLE PRECISION NOT NULL,
    confidence       DOUBLE PRECISION NOT NULL,
    market_regime    TEXT             NOT NULL,
    price            DOUBLE PRECISION NOT NULL,
    change_rate      DOUBLE PRECISION NOT NULL,
    technical_scores JSONB            NOT NULL DEFAULT '{}'::jsonb,
    analyzed_at      TIMESTAMPTZ      NOT NULL
);
CREATE UNIQUE INDEX uq_stock_analysis_history_daily ON stock_analysis_history (ticker, timeframe, trade_date);
