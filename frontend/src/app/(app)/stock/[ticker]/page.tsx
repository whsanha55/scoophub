"use client";

import { useEffect } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { useStockDetail } from "@/domains/stock/hooks/use-stock";
import { getIndicatorMeta } from "@/domains/stock/lib/indicators";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import { ArrowLeft, RefreshCw, Info } from "lucide-react";
import { TrendBadge } from "@/domains/stock/components/trend-badge";

// #82 — 지표 label + 느낌표 툴팁 셀
function IndicatorCell({ k, value }: { k: string; value: number }) {
  const meta = getIndicatorMeta(k);
  const label = meta?.label ?? k;
  return (
    <div className="rounded-lg border border-border p-2">
      <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
        {label}
        {meta && (
          <Tooltip>
            <TooltipTrigger
              render={
                <button type="button" aria-label={`${label} 설명`} className="cursor-help">
                  <Info className="h-3 w-3 opacity-60" />
                </button>
              }
            />
            <TooltipContent>{meta.desc}</TooltipContent>
          </Tooltip>
        )}
      </span>
      <p className="font-semibold">{value.toFixed(typeof value === "number" && Math.abs(value) <= 2 ? 1 : 2)}</p>
    </div>
  );
}

// SMA200 은 일봉 200개 미만이면 0 으로 온다
function fmtSma(v: number | undefined) {
  return v ? `$${v.toFixed(2)}` : "-";
}

export default function StockDetailPage() {
  const params = useParams<{ ticker: string }>();
  const ticker = params.ticker as string;

  const { detail, loading, error, fetchDetail } = useStockDetail();

  useEffect(() => {
    if (ticker) {
      fetchDetail(ticker);
    }
  }, [ticker, fetchDetail]);

  if (loading && !detail) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-48" />
        <Skeleton className="h-64 rounded-xl" />
        <Skeleton className="h-48 rounded-xl" />
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="space-y-4">
        <Link href="/stock">
          <Button variant="ghost" size="sm" className="cursor-pointer transition-colors duration-200">
            <ArrowLeft className="mr-1 h-4 w-4" />
            돌아가기
          </Button>
        </Link>
        <p className="text-muted-foreground">
          {error ? error : "리포트를 불러올 수 없습니다."}
        </p>
      </div>
    );
  }

  const report = detail;
  const quote = detail.quote ?? null;

  const changeColor =
    report.change > 0
      ? "text-green-500"
      : report.change < 0
        ? "text-red-500"
        : "text-muted-foreground";

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <Link href="/stock">
          <Button variant="ghost" size="sm" className="cursor-pointer transition-colors duration-200">
            <ArrowLeft className="mr-1 h-4 w-4" />
            돌아가기
          </Button>
        </Link>
        <Button
          size="sm"
          variant="outline"
          disabled={loading}
          onClick={() => fetchDetail(ticker)}
          className="cursor-pointer transition-colors duration-200"
        >
          <RefreshCw className={`h-4 w-4 mr-1 ${loading ? "animate-spin" : ""}`} />
          새로고침
        </Button>
      </div>

      {/* Header */}
      <div className="flex items-end justify-between">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold">{report.ticker}</h1>
            <Badge variant="outline" className="text-xs">
              {report.exchange}
            </Badge>
          </div>
          <div className="flex items-end gap-2 mt-1">
            <span className="text-3xl font-bold">${report.price.toFixed(2)}</span>
            <span className={`text-sm font-medium ${changeColor}`}>
              {report.change > 0 ? "+" : ""}
              {report.change.toFixed(2)} ({report.change_rate.toFixed(2)}%)
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <TrendBadge trend={report.technical.trend} since={report.technical.trend_since} />
          {report.is_stale && (
            <Badge variant="secondary" className="text-xs">Stale data</Badge>
          )}
        </div>
      </div>

      {/* Technical Overview */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-lg">기술적 분석</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
            <div>
              <span className="text-muted-foreground">추세 (50/200일선)</span>
              <div className="mt-1"><TrendBadge trend={report.technical.trend} since={report.technical.trend_since} /></div>
            </div>
            <div>
              <span className="text-muted-foreground">50일선</span>
              <p className="font-semibold">{fmtSma(report.technical.technical_details.sma50)}</p>
            </div>
            <div>
              <span className="text-muted-foreground">200일선</span>
              <p className="font-semibold">{fmtSma(report.technical.technical_details.sma200)}</p>
            </div>
          </div>

          {/* Technical Details */}
          <div>
            <p className="text-sm font-medium mb-2">기술적 상세</p>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 text-sm">
              {Object.entries(report.technical.technical_details)
                .filter(([, v]) => typeof v === "number")
                .map(([key, value]) => (
                  <IndicatorCell key={key} k={key} value={value as number} />
                ))}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* #82 — 실시간 Quote (detail.quote) */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-lg">실시간 주가</CardTitle>
        </CardHeader>
        <CardContent>
          {!quote && <p className="text-muted-foreground text-sm">실시간 quote를 불러올 수 없습니다.</p>}
          {quote && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
              <div>
                <span className="text-muted-foreground">현재가</span>
                <p className="font-semibold">${quote.price.toFixed(2)}</p>
              </div>
              <div>
                <span className="text-muted-foreground">변동</span>
                <p
                  className={`font-semibold ${
                    quote.change > 0
                      ? "text-green-500"
                      : quote.change < 0
                        ? "text-red-500"
                        : "text-muted-foreground"
                  }`}
                >
                  {quote.change > 0 ? "+" : ""}
                  {quote.change.toFixed(2)} ({quote.change_rate.toFixed(2)}%)
                </p>
              </div>
              {quote.volume != null && (
                <div>
                  <span className="text-muted-foreground">거래량</span>
                  <p className="font-semibold">{quote.volume.toLocaleString()}</p>
                </div>
              )}
              {quote.timestamp && (
                <div>
                  <span className="text-muted-foreground">시각</span>
                  <p className="font-semibold">{quote.timestamp.replace("T", " ").slice(0, 19)}</p>
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Meta */}
      <div className="text-xs text-muted-foreground">
        데이터 기준일: {report.data_date}
      </div>
    </div>
  );
}
