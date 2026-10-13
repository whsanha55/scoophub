"use client";

import { useEffect, useMemo, useState, type MouseEvent } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useStockChart } from "../hooks/use-stock";
import { bandRuns, linePath, sliceRange, valueDomain, type ChartRange } from "../lib/chart";
import { formatSigma } from "../lib/sigma";
import type { StockChartPoint } from "../types";

const W = 800;
const H = 320;
const PAD = { top: 12, right: 12, bottom: 24, left: 56 };
const RANGES: ChartRange[] = ["1M", "3M", "1Y"];

// #261 — 종가 + SMA50/200 + 주간 ±1σ 음영 + 밴드 돌파·교차 마커 (SVG 직접)
export function StockChart({ ticker }: { ticker: string }) {
  const { chart, loading, error, fetchChart } = useStockChart();
  const [range, setRange] = useState<ChartRange>("3M");
  const [hover, setHover] = useState<number | null>(null);

  useEffect(() => {
    if (ticker) fetchChart(ticker);
  }, [ticker, fetchChart]);

  const points = useMemo(() => sliceRange(chart?.points ?? [], range), [chart, range]);

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-lg flex items-center justify-between">
          <span>주가와 주간 σ</span>
          <span className="flex gap-1">
            {RANGES.map((r) => (
              <Button
                key={r}
                size="xs"
                variant={r === range ? "default" : "outline"}
                onClick={() => setRange(r)}
                className="cursor-pointer"
              >
                {r}
              </Button>
            ))}
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {loading && !chart && <Skeleton className="h-64 w-full rounded-lg" />}
        {error && <p className="text-destructive text-sm">{error}</p>}
        {chart && points.length < 2 && <p className="text-muted-foreground text-sm">차트를 그릴 일봉이 없습니다.</p>}
        {points.length >= 2 && <ChartSvg points={points} hover={hover} onHover={setHover} />}
        <Legend />
      </CardContent>
    </Card>
  );
}

function ChartSvg({
  points,
  hover,
  onHover,
}: {
  points: StockChartPoint[];
  hover: number | null;
  onHover: (i: number | null) => void;
}) {
  const [min, max] = valueDomain(points);
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const step = innerW / (points.length - 1);
  const x = (i: number) => PAD.left + i * step;
  const y = (v: number) => PAD.top + ((max - v) / (max - min)) * innerH;
  const ticks = [0, 1, 2, 3].map((k) => min + ((max - min) * k) / 3);
  const dateTicks = [0, Math.floor((points.length - 1) / 2), points.length - 1];
  const hovered = hover != null ? points[hover] : null;

  const onMove = (e: MouseEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * W;
    const i = Math.round((px - PAD.left) / step);
    onHover(Math.min(points.length - 1, Math.max(0, i)));
  };

  return (
    <div className="relative">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="w-full h-auto select-none"
        onMouseMove={onMove}
        onMouseLeave={() => onHover(null)}
        role="img"
        aria-label="종가, 50·200일 이동평균, 주간 ±1σ 밴드 차트"
      >
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className="stroke-border" strokeWidth={1} />
            <text x={PAD.left - 6} y={y(t) + 4} textAnchor="end" className="fill-muted-foreground text-[11px]">
              {t.toFixed(t >= 100 ? 0 : 2)}
            </text>
          </g>
        ))}
        {dateTicks.map((i) => (
          <text
            key={i}
            x={x(i)}
            y={H - 6}
            textAnchor={i === 0 ? "start" : i === points.length - 1 ? "end" : "middle"}
            className="fill-muted-foreground text-[11px]"
          >
            {points[i].date}
          </text>
        ))}

        {/* 주간 ±1σ 음영 — 주마다 앵커가 바뀌어 구간별 사각형 */}
        {bandRuns(points).map(({ start, end }) => {
          const p = points[start];
          const x0 = x(start) - (start > 0 ? step / 2 : 0);
          const x1 = x(end) + (end < points.length - 1 ? step / 2 : 0);
          return (
            <rect
              key={start}
              x={x0}
              y={y(p.sigma_upper as number)}
              width={Math.max(x1 - x0, 1)}
              height={Math.max(y(p.sigma_lower as number) - y(p.sigma_upper as number), 1)}
              className="fill-sky-500/15 stroke-sky-500/40"
              strokeWidth={1}
            />
          );
        })}

        <path d={linePath(points.map((p) => p.sma200), x, y)} fill="none" className="stroke-violet-500" strokeWidth={1.5} />
        <path d={linePath(points.map((p) => p.sma50), x, y)} fill="none" className="stroke-amber-500" strokeWidth={1.5} />
        <path d={linePath(points.map((p) => p.close), x, y)} fill="none" className="stroke-foreground" strokeWidth={1.75} />

        {/* 밴드 돌파·이탈 원 마커 */}
        {points.map((p, i) =>
          p.sigma_state === "UPPER_BREAK" || p.sigma_state === "LOWER_BREAK" ? (
            <circle
              key={`b${p.date}`}
              cx={x(i)}
              cy={y(p.close)}
              r={4}
              className={p.sigma_state === "UPPER_BREAK" ? "fill-green-500" : "fill-red-500"}
            />
          ) : null,
        )}

        {/* 골든·데드크로스 — 세로선 + 라벨 */}
        {points.map((p, i) =>
          p.cross ? (
            <g key={`c${p.date}`}>
              <line
                x1={x(i)}
                x2={x(i)}
                y1={PAD.top}
                y2={H - PAD.bottom}
                className={p.cross === "GOLDEN" ? "stroke-green-500" : "stroke-red-500"}
                strokeDasharray="4 3"
                strokeWidth={1}
              />
              <text
                x={x(i) + 3}
                y={PAD.top + 10}
                className={`text-[11px] ${p.cross === "GOLDEN" ? "fill-green-500" : "fill-red-500"}`}
              >
                {p.cross === "GOLDEN" ? "골든" : "데드"}
              </text>
            </g>
          ) : null,
        )}

        {hovered && hover != null && (
          <line
            x1={x(hover)}
            x2={x(hover)}
            y1={PAD.top}
            y2={H - PAD.bottom}
            className="stroke-muted-foreground"
            strokeWidth={1}
          />
        )}
      </svg>
      {hovered && (
        <div className="pointer-events-none absolute top-1 right-2 rounded-md border border-border bg-popover px-2 py-1 text-xs text-popover-foreground shadow-sm">
          <p className="font-medium">{hovered.date}</p>
          <p>종가 ${hovered.close.toFixed(2)}</p>
          {hovered.sigma_lower != null && hovered.sigma_upper != null && (
            <p>
              밴드 ${hovered.sigma_lower.toFixed(2)} ~ ${hovered.sigma_upper.toFixed(2)}
            </p>
          )}
          {hovered.sigma_position != null && <p>σ 위치 {formatSigma(hovered.sigma_position)}</p>}
          {hovered.sma50 != null && <p>50일 ${hovered.sma50.toFixed(2)}</p>}
          {hovered.sma200 != null && <p>200일 ${hovered.sma200.toFixed(2)}</p>}
        </div>
      )}
    </div>
  );
}

function Legend() {
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
      <span className="flex items-center gap-1"><i className="inline-block h-0.5 w-4 bg-foreground" />종가</span>
      <span className="flex items-center gap-1"><i className="inline-block h-0.5 w-4 bg-amber-500" />50일선</span>
      <span className="flex items-center gap-1"><i className="inline-block h-0.5 w-4 bg-violet-500" />200일선</span>
      <span className="flex items-center gap-1"><i className="inline-block h-3 w-4 bg-sky-500/15 border border-sky-500/40" />주간 ±1σ (당시 밴드)</span>
      <span className="flex items-center gap-1"><i className="inline-block h-2 w-2 rounded-full bg-green-500" />상단 돌파</span>
      <span className="flex items-center gap-1"><i className="inline-block h-2 w-2 rounded-full bg-red-500" />하단 이탈</span>
    </div>
  );
}
