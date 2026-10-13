import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { SigmaBand } from "../types";
import { SIGMA_STATE_LABEL, formatSigma } from "../lib/sigma";
import { SigmaBadge } from "./sigma-badge";

// #260 — 주간 σ 밴드 위치. 앵커 = 주간 만기를 연 스냅샷 종가, 1σ = ATM 스트래들 expected move
export function SigmaBandCard({ sigma }: { sigma: SigmaBand | null | undefined }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-lg flex items-center justify-between">
          <span>주간 σ 밴드</span>
          {sigma && <SigmaBadge sigma={sigma} />}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {!sigma ? (
          <p className="text-muted-foreground text-sm">적용할 주간 시그마 스냅샷이 없습니다.</p>
        ) : (
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
            <div>
              <span className="text-muted-foreground">밴드 위치 ({sigma.as_of})</span>
              <p className="font-semibold">
                {formatSigma(sigma.position)} · {SIGMA_STATE_LABEL[sigma.state]}
              </p>
            </div>
            <div>
              <span className="text-muted-foreground">−1σ ~ +1σ</span>
              <p className="font-semibold">
                ${sigma.lower.toFixed(2)} ~ ${sigma.upper.toFixed(2)}
              </p>
            </div>
            <div>
              <span className="text-muted-foreground">앵커 ({sigma.anchor_date})</span>
              <p className="font-semibold">${sigma.anchor_price.toFixed(2)}</p>
            </div>
            <div>
              <span className="text-muted-foreground">Expected move (만기 {sigma.expiry_date})</span>
              <p className="font-semibold">
                ±${sigma.expected_move.toFixed(2)} ({sigma.expected_move_pct.toFixed(1)}%)
              </p>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
