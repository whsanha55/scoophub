// #260 — 주간 σ 밴드 표시 util. 판단 신호(매수·매도)는 만들지 않는다 (#251)
import type { SigmaState, SigmaWeek, StockReportSummarized } from "../types";

export const SIGMA_STATE_LABEL: Record<SigmaState, string> = {
  NORMAL: "정상",
  UPPER_BREAK: "상단 돌파",
  LOWER_BREAK: "하단 이탈",
};

// 상단 돌파 초록, 하단 이탈 빨강 — 목록 등락 색과 맞춘다
export const SIGMA_STATE_CLASS: Record<SigmaState, string> = {
  NORMAL: "text-muted-foreground",
  UPPER_BREAK: "text-green-500 border-green-500",
  LOWER_BREAK: "text-red-500 border-red-500",
};

export function formatSigma(position: number): string {
  const sign = position > 0 ? "+" : position < 0 ? "−" : "";
  return `${sign}${Math.abs(position).toFixed(2)}σ`;
}

// #262 — 주간 마감 표 필터. 당시 σ 가 +1 이상(상단 돌파) / −1 이하(하단 이탈)인 주만
export type SigmaWeekFilter = "ALL" | "UPPER" | "LOWER";

export function filterWeeks(weeks: SigmaWeek[], filter: SigmaWeekFilter): SigmaWeek[] {
  if (filter === "UPPER") return weeks.filter((w) => w.sigma_state === "UPPER_BREAK");
  if (filter === "LOWER") return weeks.filter((w) => w.sigma_state === "LOWER_BREAK");
  return weeks;
}

export function formatReturn(v: number | null): string {
  if (v == null) return "-";
  return `${v > 0 ? "+" : ""}${v.toFixed(2)}%`;
}

// #263 — σ 보드: 상단 돌파(σ 큰 순) / 하단 이탈(σ 작은 순) / expected move % 상위 N
export interface SigmaBoardData {
  upper: StockReportSummarized[];
  lower: StockReportSummarized[];
  topMove: StockReportSummarized[];
}

export function sigmaBoard(reports: StockReportSummarized[], topN: number): SigmaBoardData {
  const withSigma = reports.filter((r) => r.sigma);
  const pos = (r: StockReportSummarized) => r.sigma?.position ?? 0;
  const move = (r: StockReportSummarized) => r.sigma?.expected_move_pct ?? 0;
  return {
    upper: withSigma.filter((r) => r.sigma?.state === "UPPER_BREAK").sort((a, b) => pos(b) - pos(a)),
    lower: withSigma.filter((r) => r.sigma?.state === "LOWER_BREAK").sort((a, b) => pos(a) - pos(b)),
    topMove: [...withSigma].sort((a, b) => move(b) - move(a)).slice(0, topN),
  };
}

// #263 — 상세 하단: 같은 관심종목 그룹의 다른 종목 (σ 있는 것만, σ 큰 순)
export function groupPeers(
  reports: StockReportSummarized[],
  ticker: string,
  group: string | null | undefined,
): StockReportSummarized[] {
  if (!group) return [];
  return reports
    .filter((r) => r.group === group && r.ticker !== ticker && r.sigma)
    .sort((a, b) => (b.sigma?.position ?? 0) - (a.sigma?.position ?? 0));
}
