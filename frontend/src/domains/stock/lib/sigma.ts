// #260 — 주간 σ 밴드 표시 util. 판단 신호(매수·매도)는 만들지 않는다 (#251)
import type { SigmaState } from "../types";

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
