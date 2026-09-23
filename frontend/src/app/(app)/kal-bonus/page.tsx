"use client";

import { useEffect, useMemo, useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { useKalBonus, useKalBonusCrawl } from "@/domains/kal-bonus/hooks/use-kal-bonus";
import { KalBonusRouteCard } from "@/domains/kal-bonus/components/kal-bonus-card";

function fmtMonth(yyyymm: string): string {
  if (yyyymm.length !== 6) return yyyymm;
  return `${yyyymm.slice(0, 4)}년 ${Number(yyyymm.slice(4, 6))}월`;
}

export default function KalBonusPage() {
  const { months, items, loading, error, fetchMonths, fetchByMonth } = useKalBonus();
  const { loading: crawlLoading, error: crawlError, triggerCrawl } = useKalBonusCrawl();
  const [activeMonth, setActiveMonth] = useState<string | null>(null);

  // 첫 로딩: 월 목록 → 최신 월(오름차순 끝) 데이터
  useEffect(() => {
    (async () => {
      const ms = await fetchMonths();
      if (ms.length > 0) {
        const latest = ms[ms.length - 1];
        setActiveMonth(latest);
        await fetchByMonth(latest);
      }
    })();
    // ponytail: 최초 1회 로드. fetchMonths/fetchByMonth는 useCallback 안정.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSelectMonth = async (ym: string) => {
    setActiveMonth(ym);
    await fetchByMonth(ym);
  };

  // 활성 월 카드 — arrival 오름차순
  const routes = useMemo(
    () =>
      (items ?? [])
        .filter((it) => it.key.startsWith(activeMonth ?? ""))
        .slice()
        .sort((a, b) => (a.parsed?.arrival ?? "").localeCompare(b.parsed?.arrival ?? "")),
    [items, activeMonth],
  );

  // 현재 월 items 중 가장 최근 크롤 시각
  const lastCrawledAt = useMemo(() => {
    const latest = (items ?? [])
      .map((it) => it.updated_at)
      .filter(Boolean)
      .sort()
      .at(-1);
    if (!latest) return null;
    return new Date(latest).toLocaleString("ko-KR", {
      timeZone: "Asia/Seoul",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  }, [items]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <h1 className="text-2xl font-bold">대한항공 마일리지 좌석</h1>
          {lastCrawledAt && (
            <span className="text-xs text-muted-foreground">
              마지막 크롤: {lastCrawledAt}
            </span>
          )}
        </div>
        <CrawlTriggerButton onClick={triggerCrawl} loading={crawlLoading} label="수동 크롤" />
      </div>

      {/* 범례 */}
      <div className="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <span className="text-green-500 font-bold">●</span> 좌석 있음
        </span>
        <span className="flex items-center gap-1">
          <span className="opacity-40">○</span> 매진
        </span>
        <span className="flex items-center gap-1">
          <span>-</span> 해당 없음
        </span>
      </div>

      {crawlError && <p className="text-destructive text-sm">{crawlError}</p>}
      {error && <p className="text-destructive text-sm">{error}</p>}

      {/* 월 탭 (가로 스크롤) */}
      {!loading && months && months.length > 0 && (
        <div className="overflow-x-auto -mx-1 px-1">
          <div className="flex gap-2 w-max pb-1">
            {months.map((ym) => {
              const active = ym === activeMonth;
              return (
                <Button
                  key={ym}
                  variant={active ? "default" : "secondary"}
                  size="sm"
                  onClick={() => handleSelectMonth(ym)}
                  className="cursor-pointer whitespace-nowrap"
                >
                  {fmtMonth(ym)}
                </Button>
              );
            })}
          </div>
        </div>
      )}

      {loading && (
        <div className="grid gap-3 md:grid-cols-2">
          <Skeleton className="h-40 rounded-xl" />
          <Skeleton className="h-40 rounded-xl" />
        </div>
      )}

      {!loading && !error && routes.length === 0 && (
        <p className="text-muted-foreground text-sm">데이터가 없습니다.</p>
      )}

      {!loading && routes.length > 0 && (
        <div className="grid gap-3 md:grid-cols-2">
          {routes.map((item) => (
            <KalBonusRouteCard key={item.key} item={item} />
          ))}
        </div>
      )}
    </div>
  );
}
