// #57 — 리포트 분석 기간 (백엔드 #147 연동)
export type Timeframe = "1D" | "1W" | "1M";

// #59 — 액션러블 레벨 (백엔드 #149 확정 스키마 정합)
export interface ActionableLevels {
  target_price?: number | null; // 목표가 (+1σ)
  buy_zone?: number | null; // 매수 구간 (-1σ or BB 하단)
  stop_loss?: number | null; // 손절가 (price - 1.5×ATR)
  momentum_fire?: boolean; // 불타기 진입
}

// #57 — watchlist 계층 분류 (백엔드 #147 연동, optional)
export type WatchlistGroup = "market" | "sector" | "individual";

export interface StockReport {
  ticker: string;
  exchange: string;
  price: number;
  change: number;
  change_rate: number;
  technical: {
    signal: string;
    total_score: number;
    confidence: number;
    market_regime: string;
    technical_scores: Record<string, number>;
    technical_details: Record<string, number>;
  };
  sigma: {
    sigma_position: string;
    sigma_signal: string;
    sigma_confidence: number;
    expected_move_pct: number;
    expected_move_high: number;
    expected_move_low: number;
    source: string;
    weekly_moves: unknown[];
  };
  data_date: string;
  is_stale: boolean;
  // #59 — 액션러블 레벨/히트레이트/그룹 (백엔드 #149 확정 스키마 정합)
  actionable_levels?: ActionableLevels | null;
  hit_rate?: number | null;
  group?: string | null;
  // #82 — 단일 fetch 통합: 실시간 quote (백엔드 #168 정합)
  quote?: StockQuote | null;
}

export interface StockReportSummarized {
  ticker: string;
  exchange: string;
  price: number;
  change: number;
  change_rate: number;
  signal: string;
  total_score: number;
  confidence: number;
  market_regime: string;
  sigma_position: string;
  sigma_signal: string;
  sigma_confidence: number;
  expected_move_pct: number;
  data_date: string;
  is_stale: boolean;
  // #59 — 액션러블 레벨/히트레이트/그룹 (백엔드 #149 확정 스키마 정합)
  actionable_levels?: ActionableLevels | null;
  hit_rate?: number | null;
  group?: string | null;
  // #84 — 다기간 시그널 (1W/1M, 다기간 fetch 후 병합)
  signal_1w?: string | null;
  signal_1m?: string | null;
}

export interface SigmaData {
  ticker: string;
  current_price: number;
  expiry_date: string;
  atm_strike: number;
  atm_call: number;
  atm_put: number;
  expected_move: number;
  expected_move_pct: number;
  snapshot_date: string;
  snapshot_at: string;
  source: string;
  total_call_volume: number;
  total_put_volume: number;
  put_call_volume_ratio: number;
  atm_call_volume: number;
  atm_put_volume: number;
  created_at: string;
}

export interface WatchlistItem {
  id: string;
  ticker: string;
  exchange: string;
  name: string;
  memo: string;
  added_at: string;
  is_active: boolean;
  // #57 — 계층 분류 (백엔드 #147 연동, optional)
  group?: WatchlistGroup;
}

export interface WatchlistCreateInput {
  ticker: string;
  exchange: string;
  name: string;
  memo?: string;
  // #57 — 계층 분류 (optional)
  group?: WatchlistGroup;
}

export interface WatchlistUpdateInput {
  memo?: string;
  is_active?: boolean;
  // #57 — 계층 분류 (optional)
  group?: WatchlistGroup;
}

export interface MarketStatus {
  is_open: boolean;
  is_weekday: boolean;
  current_utc: string;
}

export interface StockAnalyzeResult {
  total: number;
  ok: number;
  errors: number;
  results: { ticker: string; status: string; detail?: string }[];
}

// #44 — 분석 / 실시간 quote / WEM
export interface StockQuote {
  ticker: string;
  price: number;
  change: number;
  change_rate: number;
  volume?: number;
  high?: number;
  low?: number;
  open?: number;
  prev_close?: number;
  timestamp?: string;
  source?: string;
}

export interface StockWem {
  ticker: string;
  expiry_date?: string;
  expected_move_high: number;
  expected_move_low: number;
  expected_move_pct: number;
  expected_move?: number;
  source?: string;
  updated_at?: string;
}

export interface StockAnalysis {
  ticker: string;
  signal?: string;
  score?: number;
  summary?: string;
  recommendation?: string;
  data_date?: string;
  [key: string]: unknown;
}
