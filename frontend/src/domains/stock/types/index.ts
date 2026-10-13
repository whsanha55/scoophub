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
  // #260 — 주간 σ 밴드 위치. 적용 밴드가 없으면 null
  sigma?: SigmaBand | null;
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
  sigma?: SigmaBand | null;
}

// #260 — 주간 σ 밴드. 앵커 = 주간 만기를 연 스냅샷 종가, 1σ = expected move
export type SigmaState = "NORMAL" | "UPPER_BREAK" | "LOWER_BREAK";

export interface SigmaBand {
  position: number; // (종가 - 앵커) / expected_move
  state: SigmaState;
  upper: number;
  lower: number;
  anchor_price: number;
  anchor_date: string; // 앵커 스냅샷 거래일
  expiry_date: string; // 주간 마감(만기)
  expected_move: number;
  expected_move_pct: number;
  as_of: string; // σ 위치를 잰 일봉 일자
}

// #261 — 상세 차트 (최근 1년 일봉). 밴드가 없는 날은 sigma_* 가 null
export interface StockChartPoint {
  date: string;
  close: number;
  sma50: number | null;
  sma200: number | null;
  sigma_upper: number | null;
  sigma_lower: number | null;
  sigma_anchor: number | null;
  sigma_position: number | null;
  sigma_state: SigmaState | null;
  cross: "GOLDEN" | "DEAD" | null; // 그날 50/200 교차로 바뀐 추세
}

export interface StockChart {
  ticker: string;
  points: StockChartPoint[];
}

// #262 — 주간 마감 후 가격 변화. return_nw 는 마감 종가 대비 5N 거래일 뒤 수익률(%), 아직 없으면 null
export interface SigmaWeek {
  anchor_date: string;
  anchor_price: number;
  expected_move: number;
  expiry_date: string;
  close_date: string;
  close: number;
  sigma_position: number;
  sigma_state: SigmaState;
  return1w: number | null;
  return2w: number | null;
  return4w: number | null;
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
