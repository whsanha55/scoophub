import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { StockReportSummarized } from "../types";
import { SIGMA_STATE_CLASS, formatSigma, sigmaBoard } from "../lib/sigma";

const TOP_N = 5;

// #263 — /stock 상단 σ 보드. 요약 리포트의 sigma 로만 계산한다
export function SigmaBoard({ reports }: { reports: StockReportSummarized[] }) {
  const board = sigmaBoard(reports, TOP_N);
  if (board.topMove.length === 0) return null;

  return (
    <div className="grid gap-3 sm:grid-cols-3">
      <BoardColumn
        title="+1σ 상단 돌파"
        empty="없음"
        items={board.upper}
        value={(r) => formatSigma(r.sigma?.position ?? 0)}
      />
      <BoardColumn
        title="−1σ 하단 이탈"
        empty="없음"
        items={board.lower}
        value={(r) => formatSigma(r.sigma?.position ?? 0)}
      />
      <BoardColumn
        title={`Expected move 상위 ${TOP_N}`}
        empty="없음"
        items={board.topMove}
        value={(r) => `±${(r.sigma?.expected_move_pct ?? 0).toFixed(1)}%`}
      />
    </div>
  );
}

function BoardColumn({
  title,
  empty,
  items,
  value,
}: {
  title: string;
  empty: string;
  items: StockReportSummarized[];
  value: (r: StockReportSummarized) => string;
}) {
  return (
    <Card size="sm">
      <CardHeader className="pb-1">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          {title} <span className="font-normal">({items.length})</span>
        </CardTitle>
      </CardHeader>
      <CardContent>
        {items.length === 0 ? (
          <p className="text-sm text-muted-foreground">{empty}</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {items.map((r) => (
              <li key={r.ticker}>
                <Link href={`/stock/${r.ticker}`} className="flex items-center justify-between hover:underline">
                  <span className="font-semibold">{r.ticker}</span>
                  <span className={r.sigma ? SIGMA_STATE_CLASS[r.sigma.state] : ""}>{value(r)}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
