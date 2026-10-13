"use client";

import { useEffect } from "react";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { useAllStockReports } from "../hooks/use-stock";
import { SIGMA_STATE_CLASS, formatSigma, groupPeers } from "../lib/sigma";
import { groupLabel } from "../lib/groups";

// #263 — 상세 하단: 같은 관심종목 그룹(테마) 종목의 주간 σ 위치 칩
export function SigmaPeerChips({ ticker, group }: { ticker: string; group: string | null | undefined }) {
  const { reports, fetchReports } = useAllStockReports();

  useEffect(() => {
    if (group) fetchReports(true);
  }, [group, fetchReports]);

  const peers = groupPeers(reports, ticker, group);
  if (!group || peers.length === 0) return null;

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-lg">같은 그룹 종목 σ 위치 <span className="text-sm font-normal text-muted-foreground">({groupLabel(group)})</span></CardTitle>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        {peers.map((r) => (
          <Link key={r.ticker} href={`/stock/${r.ticker}`}>
            <Badge variant="outline" className={`cursor-pointer text-xs ${r.sigma ? SIGMA_STATE_CLASS[r.sigma.state] : ""}`}>
              <span className="font-semibold text-foreground">{r.ticker}</span>
              {r.sigma && formatSigma(r.sigma.position)}
            </Badge>
          </Link>
        ))}
      </CardContent>
    </Card>
  );
}
