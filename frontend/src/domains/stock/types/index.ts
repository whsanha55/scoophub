// #251 — 50/200 이동평균 추세 상태. UNKNOWN 은 일봉 200개 미만
export type TrendState = "GOLDEN" | "DEAD" | "UNKNOWN";

import type { WatchlistGroup } from "../lib/groups";

// #259 — 테마 그룹 키·라벨은 lib/groups.ts 한 곳에 둔다
export type { WatchlistGroup };

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
  // #258 — 상세 조회에만 채워지는 DB 마지막 일봉 (실시간 시세 호출 제거)
  last_candle?: LastCandle | null;
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
  // #259 — 테마 그룹 (optional)
  group?: WatchlistGroup;
}

export interface WatchlistCreateInput {
  ticker: string;
  exchange: string;
  name: string;
  memo?: string;
  // #259 — 테마 그룹 (optional)
  group?: WatchlistGroup;
}

export interface WatchlistUpdateInput {
  memo?: string;
  is_active?: boolean;
  // #259 — 테마 그룹 (optional)
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

// #258 — 최근 종가 (하루 1회 수집한 마지막 일봉)
export interface LastCandle {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
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
