// #251 — 50/200 이동평균 추세 상태. UNKNOWN 은 일봉 200개 미만
export type TrendState = "GOLDEN" | "DEAD" | "UNKNOWN";

// #57 — watchlist 계층 분류 (백엔드 #147 연동, optional)
export type WatchlistGroup = "market" | "sector" | "individual";

export interface StockReport {
  ticker: string;
  exchange: string;
  price: number;
  change: number;
  change_rate: number;
  technical: {
    trend: TrendState;
    trend_since: string | null; // 마지막 골든·데드크로스 일자
    technical_details: Record<string, number>;
  };
  data_date: string;
  is_stale: boolean;
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
  trend: TrendState;
  trend_since: string | null;
  data_date: string;
  is_stale: boolean;
  group?: string | null;
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

// #44 — 분석 / 실시간 quote
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

export interface StockAnalysis {
  ticker: string;
  signal?: string;
  score?: number;
  summary?: string;
  recommendation?: string;
  data_date?: string;
  [key: string]: unknown;
}
