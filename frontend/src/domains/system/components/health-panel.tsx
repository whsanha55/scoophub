"use client";

import { useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { RefreshCw } from "lucide-react";
import { useHealth } from "../hooks/use-health";

export function HealthPanel() {
  const { health, loading, error, fetchHealth } = useHealth();

  useEffect(() => {
    fetchHealth();
  }, [fetchHealth]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">서비스 상태</h2>
        <Button variant="outline" size="sm" onClick={fetchHealth} disabled={loading}>
          <RefreshCw className="h-4 w-4 mr-1" />
          새로고침
        </Button>
      </div>

      {loading && !health && (
        <Skeleton className="h-24 w-full" />
      )}

      {error && (
        <p className="text-destructive text-sm">{error}</p>
      )}

      {!loading && !error && !health && (
        <p className="text-muted-foreground text-sm">헬스 정보를 불러올 수 없습니다</p>
      )}

      {health && (
        <div className="flex items-center gap-2">
          <Badge variant={health.status === "ok" ? "default" : "destructive"}>
            {health.status === "ok" ? "정상" : "오류"}
          </Badge>
        </div>
      )}
    </div>
  );
}
