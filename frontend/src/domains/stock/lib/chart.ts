// #261 — 상세 차트 SVG 계산 util (라이브러리 없이 그린다)
import type { StockChartPoint } from "../types";

export type ChartRange = "1M" | "3M" | "1Y";

const RANGE_MONTHS: Record<ChartRange, number> = { "1M": 1, "3M": 3, "1Y": 12 };

// 마지막 일봉 기준 최근 N개월
export function sliceRange(points: StockChartPoint[], range: ChartRange): StockChartPoint[] {
  const last = points.at(-1);
  if (!last) return [];
  const from = new Date(`${last.date}T00:00:00Z`);
  from.setUTCMonth(from.getUTCMonth() - RANGE_MONTHS[range]);
  const fromDate = from.toISOString().slice(0, 10);
  return points.filter((p) => p.date >= fromDate);
}

// 같은 주간 밴드가 이어지는 구간 [start, end] (인덱스, 양끝 포함)
export function bandRuns(points: StockChartPoint[]): { start: number; end: number }[] {
  const runs: { start: number; end: number }[] = [];
  let key: string | null = null;
  points.forEach((p, i) => {
    const k = p.sigma_upper != null && p.sigma_lower != null ? `${p.sigma_upper}|${p.sigma_lower}` : null;
    if (k !== null && k === key) {
      runs[runs.length - 1].end = i;
    } else if (k !== null) {
      runs.push({ start: i, end: i });
    }
    key = k;
  });
  return runs;
}

// null 에서 끊기는 SVG path
export function linePath(
  values: (number | null)[],
  x: (i: number) => number,
  y: (v: number) => number,
): string {
  let d = "";
  let pen = false;
  values.forEach((v, i) => {
    if (v == null) {
      pen = false;
      return;
    }
    d += `${pen ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`;
    pen = true;
  });
  return d;
}

// 종가·이동평균·밴드를 모두 담는 y 범위 (위아래 5% 여백)
export function valueDomain(points: StockChartPoint[]): [number, number] {
  const values = points.flatMap((p) =>
    [p.close, p.sma50, p.sma200, p.sigma_upper, p.sigma_lower].filter((v): v is number => v != null),
  );
  if (values.length === 0) return [0, 1];
  const min = Math.min(...values);
  const max = Math.max(...values);
  const pad = (max - min || Math.abs(max) || 1) * 0.05;
  return [min - pad, max + pad];
}
