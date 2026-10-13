"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { useSigmaWeekly } from "../hooks/use-stock";
import {
  SIGMA_STATE_CLASS,
  filterWeeks,
  formatReturn,
  formatSigma,
  type SigmaWeekFilter,
} from "../lib/sigma";

const FILTERS: { key: SigmaWeekFilter; label: string }[] = [
  { key: "ALL", label: "전체" },
  { key: "UPPER", label: "≥+1σ" },
  { key: "LOWER", label: "≤−1σ" },
];

function returnClass(v: number | null) {
  if (v == null || v === 0) return "text-muted-foreground";
  return v > 0 ? "text-green-500" : "text-red-500";
}

// #262 — 주간 마감 이후 가격 변화. 관찰 기록일 뿐 신호가 아니다 (#251)
export function SigmaWeeklyTable({ ticker }: { ticker: string }) {
  const { weeks, loading, error, fetchWeeks } = useSigmaWeekly();
  const [filter, setFilter] = useState<SigmaWeekFilter>("ALL");

  useEffect(() => {
    if (ticker) fetchWeeks(ticker);
  }, [ticker, fetchWeeks]);

  const rows = filterWeeks(weeks, filter);

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-lg flex items-center justify-between">
          <span>주간 마감 이후 가격 변화</span>
          <span className="flex gap-1">
            {FILTERS.map((f) => (
              <Button
                key={f.key}
                size="xs"
                variant={f.key === filter ? "default" : "outline"}
                onClick={() => setFilter(f.key)}
                className="cursor-pointer"
              >
                {f.label}
              </Button>
            ))}
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-xs text-muted-foreground">
          마감 종가가 당시 주간 밴드에서 몇 σ 였는지와 그 뒤 5·10·20 거래일 종가 수익률입니다. 예측력은 검증되지 않았습니다.
        </p>
        {error && <p className="text-destructive text-sm">{error}</p>}
        {loading && weeks.length === 0 && <p className="text-muted-foreground text-sm">불러오는 중…</p>}
        {!loading && !error && rows.length === 0 && (
          <p className="text-muted-foreground text-sm">해당하는 주가 없습니다.</p>
        )}
        {rows.length > 0 && (
          <div className="max-h-96 overflow-auto">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-card text-xs text-muted-foreground">
                <tr className="border-b border-border">
                  <th className="py-1 text-left font-medium">마감일</th>
                  <th className="py-1 text-right font-medium">종가</th>
                  <th className="py-1 text-right font-medium">당시 σ</th>
                  <th className="py-1 text-right font-medium">+1W</th>
                  <th className="py-1 text-right font-medium">+2W</th>
                  <th className="py-1 text-right font-medium">+4W</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((w) => (
                  <tr key={w.expiry_date} className="border-b border-border/50">
                    <td className="py-1" title={`앵커 ${w.anchor_date} $${w.anchor_price.toFixed(2)} ± $${w.expected_move.toFixed(2)}`}>
                      {w.close_date}
                    </td>
                    <td className="py-1 text-right">${w.close.toFixed(2)}</td>
                    <td className={`py-1 text-right font-medium ${SIGMA_STATE_CLASS[w.sigma_state]}`}>
                      {formatSigma(w.sigma_position)}
                    </td>
                    <td className={`py-1 text-right ${returnClass(w.return1w)}`}>{formatReturn(w.return1w)}</td>
                    <td className={`py-1 text-right ${returnClass(w.return2w)}`}>{formatReturn(w.return2w)}</td>
                    <td className={`py-1 text-right ${returnClass(w.return4w)}`}>{formatReturn(w.return4w)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
