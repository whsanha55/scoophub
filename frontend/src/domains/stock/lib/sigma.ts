// #260 — 주간 σ 밴드 표시 util. 판단 신호(매수·매도)는 만들지 않는다 (#251)
import type { SigmaState, SigmaWeek } from "../types";

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
