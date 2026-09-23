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
import { ArrowLeft, RefreshCw, Info, Flame } from "lucide-react";

function signalStyle(signal: string): { variant: "default" | "outline" | "secondary"; className: string } {
  const s = signal.toUpperCase();
  if (s === "STRONG_BUY") return { variant: "default", className: "bg-green-500 hover:bg-green-500 text-white border-green-500" };
  if (s === "BUY") return { variant: "outline", className: "text-green-500 border-green-500" };
  if (s === "STRONG_SELL") return { variant: "default", className: "bg-red-500 hover:bg-red-500 text-white border-red-500" };
  if (s === "SELL") return { variant: "outline", className: "text-red-500 border-red-500" };
  return { variant: "secondary", className: "text-yellow-500" };
}

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
  const sigma = detail.sigma;

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
          {(() => {
            const st = signalStyle(report.technical.signal);
            return (
              <Badge variant={st.variant} className={`text-xs ${st.className}`}>
                {report.technical.signal}
              </Badge>
            );
          })()}
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
              <span className="text-muted-foreground">시그널</span>
              {(() => {
                const st = signalStyle(report.technical.signal);
                return (
                  <p className={`font-semibold ${st.className}`}>
                    {report.technical.signal}
                  </p>
                );
              })()}
            </div>
            <div>
              <span className="text-muted-foreground">종합 점수</span>
              <p className="font-semibold">{report.technical.total_score.toFixed(1)}</p>
            </div>
            <div>
              <span className="text-muted-foreground">신뢰도</span>
              <div className="h-2 mt-1 rounded-full bg-muted overflow-hidden">
                <div
                  className={`h-full ${report.technical.confidence >= 70 ? "bg-green-500" : report.technical.confidence >= 40 ? "bg-yellow-500" : "bg-red-500"}`}
                  style={{ width: `${Math.min(Math.max(report.technical.confidence, 0), 100)}%` }}
                />
              </div>
              <p className="text-xs mt-0.5 font-medium">{report.technical.confidence.toFixed(1)}%</p>
            </div>
            <div>
              <span className="text-muted-foreground">시장 국면</span>
              <p className="font-semibold capitalize">{report.technical.market_regime}</p>
            </div>
          </div>

          {/* Technical Scores */}
          <div>
            <p className="text-sm font-medium mb-2">기술적 지표 점수</p>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 text-sm">
              {Object.entries(report.technical.technical_scores).map(([key, value]) => (
                <IndicatorCell key={key} k={key} value={Number(value)} />
              ))}
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

      {/* Sigma Overview */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-lg">Sigma 분석</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
            <div>
              <span className="text-muted-foreground">위치</span>
              <p className="font-semibold capitalize">{report.sigma.sigma_position}</p>
            </div>
            <div>
              <span className="text-muted-foreground">시그널</span>
              <p className="font-semibold capitalize">{report.sigma.sigma_signal}</p>
            </div>
            <div>
              <span className="text-muted-foreground">신뢰도</span>
              <p className="font-semibold">{(report.sigma.sigma_confidence * 100).toFixed(0)}%</p>
            </div>
            <div>
              <span className="text-muted-foreground">예상 변동폭</span>
              <p className="font-semibold">{report.sigma.expected_move_pct.toFixed(1)}%</p>
            </div>
            <div>
              <span className="text-muted-foreground">예상 상한</span>
              <p className="font-semibold">${report.sigma.expected_move_high.toFixed(2)}</p>
            </div>
            <div>
              <span className="text-muted-foreground">예상 하한</span>
              <p className="font-semibold">${report.sigma.expected_move_low.toFixed(2)}</p>
            </div>
            <div>
              <span className="text-muted-foreground">출처</span>
              <p className="font-semibold">{report.sigma.source}</p>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* #57 — 액션러블 레벨 (optional, 레거시 호환) */}
      {(report.actionable_levels || report.hit_rate != null) && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-lg">액션러블 레벨</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
              {report.actionable_levels?.target_price != null && (
                <div>
                  <span className="text-muted-foreground">목표가</span>
                  <p className="font-semibold">${report.actionable_levels.target_price.toFixed(2)}</p>
                </div>
              )}
              {report.actionable_levels?.buy_zone != null && (
                <div>
                  <span className="text-muted-foreground">매수가</span>
                  <p className="font-semibold text-green-500">${report.actionable_levels.buy_zone.toFixed(2)}</p>
                </div>
              )}
              {report.actionable_levels?.stop_loss != null && (
                <div>
                  <span className="text-muted-foreground">손절가</span>
                  <p className="font-semibold text-red-500">${report.actionable_levels.stop_loss.toFixed(2)}</p>
                </div>
              )}
              {report.actionable_levels?.momentum_fire && (
                <div>
                  <span className="text-muted-foreground">불타기</span>
                  <p className="font-semibold text-orange-500 inline-flex items-center gap-1"><Flame className="h-3 w-3" aria-hidden="true" />진입</p>
                </div>
              )}
              {report.hit_rate != null && (
                <div>
                  <span className="text-muted-foreground">히트레이트</span>
                  <p className="font-semibold">{(report.hit_rate > 1 ? report.hit_rate : report.hit_rate * 100).toFixed(0)}%</p>
                </div>
              )}
            </div>
          </CardContent>
        </Card>
      )}

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

      {/* #82 — WEM (detail.sigma의 expected_move) */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-lg">주간 예상 움직임 (WEM)</CardTitle>
        </CardHeader>
        <CardContent>
          {!sigma && <p className="text-muted-foreground text-sm">WEM 데이터가 없습니다.</p>}
          {sigma && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
              <div>
                <span className="text-muted-foreground">예상 상한</span>
                <p className="font-semibold text-green-500">${sigma.expected_move_high.toFixed(2)}</p>
              </div>
              <div>
                <span className="text-muted-foreground">예상 하한</span>
                <p className="font-semibold text-red-500">${sigma.expected_move_low.toFixed(2)}</p>
              </div>
              <div>
                <span className="text-muted-foreground">예상 변동폭</span>
                <p className="font-semibold">{sigma.expected_move_pct.toFixed(1)}%</p>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* 분석 (detail 자체가 분석 리포트) */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-lg">분석</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2 text-sm">
            <p>
              <span className="text-muted-foreground">시그널: </span>
              {(() => {
                const st = signalStyle(report.technical.signal);
                return (
                  <span className={`font-semibold ${st.className}`}>
                    {report.technical.signal}
                  </span>
                );
              })()}
            </p>
            <p>
              <span className="text-muted-foreground">종합 점수: </span>
              <span className="font-semibold">{report.technical.total_score}</span>
            </p>
            {report.data_date && (
              <p className="text-xs text-muted-foreground">기준일: {report.data_date}</p>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Meta */}
      <div className="text-xs text-muted-foreground">
        데이터 기준일: {report.data_date}
      </div>
    </div>
  );
}
