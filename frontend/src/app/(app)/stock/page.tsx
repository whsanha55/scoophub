"use client";

import { useEffect } from "react";
import Link from "next/link";
import {
  useAllStockReportsMulti,
  useStockAnalyze,
  useStockSigmaCrawl,
  useStockSigmaCompute,
  useStockSync,
  useMarketStatus,
  useStockReportSend,
} from "@/domains/stock/hooks/use-stock";
import { StockReportCard } from "@/domains/stock/components/stock-report-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";

export default function StockPage() {
  const { reports, loading: reportsLoading, fetchReports } = useAllStockReportsMulti();
  const { loading: analyzeLoading, triggerAnalyze } = useStockAnalyze();
  const { loading: sigmaCrawlLoading, triggerSigmaCrawl } = useStockSigmaCrawl();
  const { loading: sigmaComputeLoading, triggerCompute } = useStockSigmaCompute();
  const { loading: syncLoading, triggerSync } = useStockSync();
  const { loading: sendLoading, error: sendError, triggerSend } =
    useStockReportSend();
  const { status: marketStatus, fetchStatus } = useMarketStatus();

  useEffect(() => {
    fetchReports(true);
    fetchStatus();
  }, [fetchReports, fetchStatus]);

  const handleAnalyze = async () => {
    await triggerAnalyze();
    await fetchReports(true);
  };

  const handleSigmaCrawl = async () => {
    await triggerSigmaCrawl();
    await fetchReports(true);
  };

  const handleSigmaCompute = async () => {
    await triggerCompute();
    await fetchReports(true);
  };

  const handleSync = async () => {
    await triggerSync();
    await fetchReports(true);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <h1 className="text-2xl font-bold">주식</h1>
        <div className="flex items-center gap-2">
          {marketStatus && (
            <Badge variant={marketStatus.is_open ? "default" : "secondary"}>
              {marketStatus.is_open ? "장 열림" : "장 닫힘"}
            </Badge>
          )}
          <CrawlTriggerButton
            onClick={handleAnalyze}
            loading={analyzeLoading}
            label="분석 실행"
          />
          <CrawlTriggerButton
            onClick={handleSigmaCrawl}
            loading={sigmaCrawlLoading}
            label="시그마 수집"
          />
          <CrawlTriggerButton
            onClick={handleSigmaCompute}
            loading={sigmaComputeLoading}
            label="Sigma 계산"
          />
          <CrawlTriggerButton
            onClick={handleSync}
            loading={syncLoading}
            label="캔들 동기화"
          />
          <CrawlTriggerButton
            onClick={() => triggerSend()}
            loading={sendLoading}
            label="리포트 발신"
          />
        </div>
      </div>

      {sendError && <p className="text-destructive text-sm">{sendError}</p>}

      {reportsLoading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-36 rounded-xl" />
          ))}
        </div>
      ) : reports.length === 0 ? (
        <div className="py-12 text-center text-muted-foreground">
          리포트가 없습니다. 시스템 관리에서 Watchlist에 종목을 추가하고 분석을 실행하세요.
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {reports.map((report) => (
            <Link
              key={report.ticker}
              href={`/stock/${report.ticker}`}
              className="block"
            >
              <StockReportCard report={report} />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
