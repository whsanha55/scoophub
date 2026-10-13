import { Badge } from "@/components/ui/badge";
import type { SigmaBand } from "../types";
import { SIGMA_STATE_CLASS, SIGMA_STATE_LABEL, formatSigma } from "../lib/sigma";

// #260 — 주간 σ 밴드 위치 배지. 툴팁에 밴드·앵커·만기
export function SigmaBadge({ sigma }: { sigma: SigmaBand }) {
  return (
    <Badge
      variant="outline"
      className={`text-xs ${SIGMA_STATE_CLASS[sigma.state]}`}
      title={`주간 밴드 $${sigma.lower.toFixed(2)} ~ $${sigma.upper.toFixed(2)} · 앵커 ${sigma.anchor_date} $${sigma.anchor_price.toFixed(2)} · 만기 ${sigma.expiry_date}`}
    >
      {formatSigma(sigma.position)}
      <span className="font-normal opacity-70">{SIGMA_STATE_LABEL[sigma.state]}</span>
    </Badge>
  );
}
