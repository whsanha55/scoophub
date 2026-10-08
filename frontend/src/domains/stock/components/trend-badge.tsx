import { Badge } from "@/components/ui/badge";
import type { TrendState } from "../types";

// #251 — 50/200 이동평균 추세 상태 배지. since 는 마지막 교차일
const STYLE: Record<TrendState, { label: string; className: string }> = {
  GOLDEN: { label: "골든크로스", className: "text-green-500 border-green-500" },
  DEAD: { label: "데드크로스", className: "text-red-500 border-red-500" },
  UNKNOWN: { label: "판단 불가", className: "text-muted-foreground" },
};

export function TrendBadge({ trend, since }: { trend: TrendState; since: string | null }) {
  const st = STYLE[trend] ?? STYLE.UNKNOWN;
  return (
    <Badge variant="outline" className={`text-xs ${st.className}`} title="50일선과 200일선 교차 상태">
      {st.label}
      {since && <span className="ml-1 font-normal opacity-70">{since}~</span>}
    </Badge>
  );
}
