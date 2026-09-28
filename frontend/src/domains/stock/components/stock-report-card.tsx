"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { TrendingUp, TrendingDown, Minus, Flame } from "lucide-react";
import type { StockReportSummarized } from "../types";

function signalStyle(signal: string): { variant: "default" | "outline" | "secondary"; className: string } {
  const s = signal.toUpperCase();
  if (s === "STRONG_BUY") return { variant: "default", className: "bg-green-500 hover:bg-green-500 text-white border-green-500" };
  if (s === "BUY") return { variant: "outline", className: "text-green-500 border-green-500" };
  if (s === "STRONG_SELL") return { variant: "default", className: "bg-red-500 hover:bg-red-500 text-white border-red-500" };
  if (s === "SELL") return { variant: "outline", className: "text-red-500 border-red-500" };
  return { variant: "secondary", className: "text-yellow-500" };
}

function ChangeIcon({ change }: { change: number }) {
  if (change > 0) return <TrendingUp className="h-4 w-4 text-green-500" />;
  if (change < 0) return <TrendingDown className="h-4 w-4 text-red-500" />;
  return <Minus className="h-4 w-4 text-muted-foreground" />;
}

export function StockReportCard({ report }: { report: StockReportSummarized }) {
  const changeColor =
    report.change > 0
      ? "text-green-500"
      : report.change < 0
        ? "text-red-500"
        : "text-muted-foreground";

  return (
    <Card className="transition-all duration-200 hover:shadow-md">
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base font-semibold flex items-center gap-2">
            {report.ticker}
            <span className="text-xs text-muted-foreground font-normal">
              {report.exchange}
            </span>
          </CardTitle>
          <div className="flex items-center gap-1 flex-wrap justify-end">
            {(() => {
              const st = signalStyle(report.signal);
              return <Badge variant={st.variant} className={`text-xs ${st.className}`}>{report.signal}</Badge>;
            })()}
            {report.signal_1w && <MiniSignal label="1W" signal={report.signal_1w} />}
            {report.signal_1m && <MiniSignal label="1M" signal={report.signal_1m} />}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="flex items-end gap-2">
          <span className="text-2xl font-bold">${report.price.toFixed(2)}</span>
          <span className={`flex items-center gap-1 text-sm font-medium ${changeColor}`}>
            <ChangeIcon change={report.change} />
            {report.change > 0 ? "+" : ""}
            {report.change.toFixed(2)} ({report.change_rate.toFixed(2)}%)
          </span>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs text-muted-foreground">
          <div>
            Score: <span className="text-foreground font-medium">{report.total_score.toFixed(1)}</span>
          </div>
          <div>
            Confidence: <span className="text-foreground font-medium">{report.confidence.toFixed(1)}%</span>
          </div>
          <div>
            Regime: <span className="text-foreground font-medium capitalize">{report.market_regime}</span>
          </div>
          <div>
            1σ Move: <span className="text-foreground font-medium">{report.expected_move_pct.toFixed(1)}%</span>
          </div>
        </div>
        {(report.actionable_levels || report.hit_rate != null) && (
          <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground pt-1 border-t">
            {report.actionable_levels?.target_price != null && (
              <span>목표가: <span className="text-foreground font-medium">${report.actionable_levels.target_price.toFixed(2)}</span></span>
            )}
            {report.actionable_levels?.buy_zone != null && (
              <span>매수가: <span className="text-foreground font-medium text-green-500">${report.actionable_levels.buy_zone.toFixed(2)}</span></span>
            )}
            {report.actionable_levels?.stop_loss != null && (
              <span>손절가: <span className="text-foreground font-medium text-red-500">${report.actionable_levels.stop_loss.toFixed(2)}</span></span>
            )}
            {report.actionable_levels?.momentum_fire && (
              <span className="inline-flex items-center gap-0.5">불타기: <Flame className="h-3 w-3 text-orange-500" aria-hidden="true" /><span className="text-orange-500 font-medium">진입</span></span>
            )}
            {report.hit_rate != null && (
              <span>히트레이트: <span className="text-foreground font-medium">{(report.hit_rate > 1 ? report.hit_rate : report.hit_rate * 100).toFixed(0)}%</span></span>
            )}
          </div>
        )}
        {report.is_stale && (
          <Badge variant="secondary" className="text-xs">
            Stale data
          </Badge>
        )}
      </CardContent>
    </Card>
  );
}

function MiniSignal({ label, signal }: { label: string; signal: string }) {
  const s = signal.toUpperCase();
  const dot = s === "BUY" || s === "STRONG_BUY" ? "bg-green-500" : s === "SELL" || s === "STRONG_SELL" ? "bg-red-500" : "bg-yellow-500";
  return (
    <span className="inline-flex items-center gap-0.5 text-[10px] text-muted-foreground" title={`${label} ${signal}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${dot}`} />
      {label}
    </span>
  );
}
