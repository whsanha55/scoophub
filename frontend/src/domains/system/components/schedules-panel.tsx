"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Input } from "@/components/ui/input";
import { RefreshCw } from "lucide-react";
import { useSchedules } from "../hooks/use-schedules";

function describeSchedule(s: { interval_seconds: number | null; cron_expr: string | null }) {
  if (s.cron_expr) return `cron: ${s.cron_expr}`;
  if (s.interval_seconds != null) return `${s.interval_seconds}s`;
  return "-";
}

export function SchedulesPanel() {
  const { schedules, loading, error, fetchSchedules, patchSchedule } = useSchedules();
  const [editing, setEditing] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    fetchSchedules();
  }, [fetchSchedules]);

  const key = (crawler: string, jobId: string) => `${crawler}/${jobId}`;

  const handleToggle = async (crawler: string, jobId: string, next: boolean) => {
    setSaving(key(crawler, jobId));
    setActionError(null);
    try {
      await patchSchedule(crawler, jobId, { is_active: next });
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "스케줄 업데이트 실패");
    } finally {
      setSaving(null);
    }
  };

  const handleSaveInterval = async (crawler: string, jobId: string) => {
    const raw = editing[key(crawler, jobId)];
    if (!raw) return;
    const n = Number(raw);
    if (Number.isNaN(n) || n <= 0) {
      setActionError("주기는 양수(초)여야 합니다");
      return;
    }
    setSaving(key(crawler, jobId));
    setActionError(null);
    try {
      // cron 기반 스케줄의 cron_expr을 사이드 이펙트로 지우지 않도록
      // interval만 부분 patch. (UI에 cron 편집 입력란은 없음)
      await patchSchedule(crawler, jobId, { interval_seconds: n });
      setEditing((prev) => {
        const next = { ...prev };
        delete next[key(crawler, jobId)];
        return next;
      });
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "주기 저장 실패");
    } finally {
      setSaving(null);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">크롤 스케줄</h2>
        <Button variant="outline" size="sm" onClick={fetchSchedules} disabled={loading}>
          <RefreshCw className="h-4 w-4 mr-1" />
          새로고침
        </Button>
      </div>

      {loading && schedules.length === 0 && (
        <div className="space-y-2">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      )}

      {error && <p className="text-destructive text-sm">{error}</p>}

      {actionError && <p className="text-destructive text-sm">{actionError}</p>}

      {!loading && !error && schedules.length === 0 && (
        <p className="text-muted-foreground text-sm">스케줄이 없습니다.</p>
      )}

      <div className="grid gap-3">
        {schedules.map((s) => {
          const k = key(s.crawler, s.job_id);
          const isSaving = saving === k;
          return (
            <Card key={k}>
              <CardHeader className="pb-2">
                <CardTitle className="text-base flex items-center justify-between">
                  <span className="flex items-center gap-2">
                    <span>{s.crawler}</span>
                    <Badge variant="outline" className="text-xs">{s.job_id}</Badge>
                  </span>
                  <Badge variant={s.is_active ? "default" : "secondary"}>
                    {s.is_active ? "활성" : "비활성"}
                  </Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3 text-sm">
                <div className="grid grid-cols-2 gap-2 text-muted-foreground">
                  <div>
                    현재 주기:{" "}
                    <span className="text-foreground font-medium">
                      {describeSchedule(s)}
                    </span>
                  </div>
                  <div>마지막 실행: {s.last_run_at ?? "-"}</div>
                  <div>다음 실행: {s.next_run_at ?? "-"}</div>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    size="sm"
                    variant={s.is_active ? "destructive" : "default"}
                    disabled={isSaving}
                    onClick={() => handleToggle(s.crawler, s.job_id, !s.is_active)}
                    className="cursor-pointer transition-colors duration-200"
                  >
                    {s.is_active ? "비활성화" : "활성화"}
                  </Button>

                  <Input
                    className="h-8 w-32"
                    inputMode="numeric"
                    placeholder="주기(초)"
                    value={editing[k] ?? ""}
                    onChange={(e) =>
                      setEditing((prev) => ({ ...prev, [k]: e.target.value }))
                    }
                  />
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={isSaving || !editing[k]}
                    onClick={() => handleSaveInterval(s.crawler, s.job_id)}
                    className="cursor-pointer transition-colors duration-200"
                  >
                    주기 저장
                  </Button>
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
