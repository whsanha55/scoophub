"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import type { SigmaData } from "../types";

interface SigmaPanelProps {
  sigma: SigmaData;
}

export function SigmaPanel({ sigma }: SigmaPanelProps) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-lg flex items-center justify-between">
          <span>{sigma.ticker} Sigma</span>
          <Badge variant="outline" className="text-xs">
            {sigma.source}
          </Badge>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <span className="text-muted-foreground">현재가</span>
            <p className="font-semibold">${sigma.current_price.toFixed(2)}</p>
          </div>
          <div>
            <span className="text-muted-foreground">ATM Strike</span>
            <p className="font-semibold">${sigma.atm_strike.toFixed(2)}</p>
          </div>
          <div>
            <span className="text-muted-foreground">Expected Move</span>
            <p className="font-semibold">${sigma.expected_move.toFixed(2)} ({sigma.expected_move_pct.toFixed(1)}%)</p>
          </div>
          <div>
            <span className="text-muted-foreground">Expiry</span>
            <p className="font-semibold">{sigma.expiry_date}</p>
          </div>
          <div>
            <span className="text-muted-foreground">ATM Call</span>
            <p className="font-semibold">${sigma.atm_call.toFixed(2)}</p>
          </div>
          <div>
            <span className="text-muted-foreground">ATM Put</span>
            <p className="font-semibold">${sigma.atm_put.toFixed(2)}</p>
          </div>
          <div>
            <span className="text-muted-foreground">Call Vol</span>
            <p className="font-semibold">{sigma.total_call_volume.toLocaleString()}</p>
          </div>
          <div>
            <span className="text-muted-foreground">Put Vol</span>
            <p className="font-semibold">{sigma.total_put_volume.toLocaleString()}</p>
          </div>
          <div>
            <span className="text-muted-foreground">P/C Ratio</span>
            <p className="font-semibold">{sigma.put_call_volume_ratio.toFixed(2)}</p>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
