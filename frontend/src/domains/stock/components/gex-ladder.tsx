"use client";

import { Fragment, useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import type { ApiResponse } from "@/shared/types";

// GET /api/stock/gex/{ticker} 응답 (#264). GEX 단위는 현재가 1% 변동당 달러
interface GexStrike {
  strike: number;
  call_gex: number;
  put_gex: number;
  net_gex: number;
}

interface GexData {
  ticker: string;
  snapshot_date: string;
  snapshot_at: string;
  oi_date: string | null;
  spot: number;
  net_gex: number;
  gamma_flip: number | null;
  resistances: number[];
  supports: number[];
  accelerations: number[];
  strikes: GexStrike[];
}

function fmtGex(v: number) {
  const abs = Math.abs(v);
  const sign = v < 0 ? "−" : "+";
  if (abs >= 1e9) return `${sign}${(abs / 1e9).toFixed(1)}B`;
  if (abs >= 1e6) return `${sign}${(abs / 1e6).toFixed(1)}M`;
  if (abs >= 1e3) return `${sign}${(abs / 1e3).toFixed(1)}K`;
  return `${sign}${abs.toFixed(0)}`;
}

function fmtLevels(levels: number[]) {
  return levels.length ? levels.map((l) => `$${l.toFixed(2)}`).join(" · ") : "—";
}

export function GexLadder({ ticker }: { ticker: string }) {
  const [gex, setGex] = useState<GexData | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/stock/gex/${encodeURIComponent(ticker)}`)
      .then((res) => res.json() as Promise<ApiResponse<GexData | null>>)
      .then((data) => {
        if (!cancelled) setGex(data.success ? (data.data ?? null) : null);
      })
      .catch(() => {
        if (!cancelled) setGex(null);
      })
      .finally(() => {
        if (!cancelled) setLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [ticker]);

  if (!loaded) return null;

  if (!gex || gex.strikes.length === 0) {
    return (
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-lg">행사가별 GEX 분포</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">GEX 데이터가 없습니다.</p>
        </CardContent>
      </Card>
    );
  }

  const isLong = gex.net_gex >= 0;
  const maxAbs = Math.max(...gex.strikes.map((s) => Math.abs(s.net_gex)), 1);
  const rows = [...gex.strikes].sort((a, b) => b.strike - a.strike);
  // 현재가는 바로 아래 행사가 앞에 표시한다
  const spotIndex = rows.findIndex((s) => s.strike <= gex.spot);
  const levelTag = (strike: number) =>
    gex.resistances.includes(strike)
      ? "저항"
      : gex.supports.includes(strike)
        ? "지지"
        : gex.accelerations.includes(strike)
          ? "가속"
          : null;
  const spotRow = (
    <div className="flex items-center gap-2 py-1 text-xs font-semibold text-primary">
      <span className="w-16 text-right">${gex.spot.toFixed(2)}</span>
      <div className="h-px flex-1 bg-primary" />
      <span>현재가</span>
    </div>
  );

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-lg flex flex-wrap items-center justify-between gap-2">
          <span>행사가별 GEX 분포</span>
          <Badge variant={isLong ? "secondary" : "destructive"} className="text-xs">
            Dealer {isLong ? "long" : "short"} gamma · Net {fmtGex(gex.net_gex)}
          </Badge>
        </CardTitle>
        <p className="text-xs text-muted-foreground">
          {isLong
            ? "딜러 헤지가 가격 움직임을 되돌리는 쪽이라 변동이 눌릴 수 있습니다."
            : "딜러 헤지가 가격 방향을 따라가 지지보다 이탈이 빨라질 수 있습니다."}
        </p>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="max-h-96 overflow-y-auto pr-1">
          {rows.map((s, i) => {
            const tag = levelTag(s.strike);
            const width = `${(Math.abs(s.net_gex) / maxAbs) * 50}%`;
            return (
              <Fragment key={s.strike}>
                {i === spotIndex && spotRow}
                <div className="flex items-center gap-2 text-xs">
                  <span className="w-16 shrink-0 text-right tabular-nums">{s.strike.toFixed(2)}</span>
                  <div className="relative h-3 flex-1">
                    <div className="absolute inset-y-0 left-1/2 w-px bg-border" />
                    <div
                      className={`absolute inset-y-0 ${s.net_gex >= 0 ? "left-1/2 bg-green-500/70" : "right-1/2 bg-red-500/70"}`}
                      style={{ width }}
                    />
                  </div>
                  <span className="w-14 shrink-0 text-right tabular-nums">{fmtGex(s.net_gex)}</span>
                  <span className="w-8 shrink-0 text-muted-foreground">{tag ?? ""}</span>
                </div>
              </Fragment>
            );
          })}
          {spotIndex === -1 && spotRow}
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
          <div>
            <span className="text-muted-foreground">저항 (현재가 위 +GEX)</span>
            <p className="font-semibold">{fmtLevels(gex.resistances)}</p>
          </div>
          <div>
            <span className="text-muted-foreground">지지 (현재가 아래 +GEX)</span>
            <p className="font-semibold">{fmtLevels(gex.supports)}</p>
          </div>
          <div>
            <span className="text-muted-foreground">가속 (−GEX)</span>
            <p className="font-semibold">{fmtLevels(gex.accelerations)}</p>
          </div>
          <div>
            <span className="text-muted-foreground">Gamma flip</span>
            <p className="font-semibold">{gex.gamma_flip != null ? `$${gex.gamma_flip.toFixed(2)}` : "—"}</p>
          </div>
        </div>

        <p className="text-xs text-muted-foreground">
          45일 이내 전체 만기, 현재가 ±10% 행사가 기준 · 1% 변동당 달러 · OI {gex.oi_date ?? "—"} (하루 1회 확정)
        </p>
      </CardContent>
    </Card>
  );
}
