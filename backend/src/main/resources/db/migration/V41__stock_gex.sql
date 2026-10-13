-- 행사가별 GEX 분포 (#264). 하루 1회 시그마 다음 단계에서 종목·스냅샷 날짜별로 저장한다.
-- 레벨(resistances/supports/accelerations)은 행사가 배열, strikes 는 [{strike, call_gex, put_gex, net_gex}].
CREATE TABLE stock_gex (
    id BIGSERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    snapshot_date DATE NOT NULL,
    snapshot_at TIMESTAMPTZ NOT NULL,
    oi_date DATE,
    spot DOUBLE PRECISION NOT NULL,
    net_gex DOUBLE PRECISION NOT NULL,
    gamma_flip DOUBLE PRECISION,
    resistances JSONB NOT NULL,
    supports JSONB NOT NULL,
    accelerations JSONB NOT NULL,
    strikes JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_stock_gex_daily ON stock_gex (ticker, snapshot_date);
