"use client";

import { useState, useCallback } from "react";
import type { CrawlSchedule, CrawlSchedulePatch } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useSchedules() {
  const [schedules, setSchedules] = useState<CrawlSchedule[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchSchedules = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/schedules");
      const data: ApiResponse<CrawlSchedule[]> = await res.json();
      if (data.success && data.data) {
        setSchedules(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch schedules");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  const patchSchedule = useCallback(
    async (crawler: string, jobId: string, patch: CrawlSchedulePatch) => {
      const res = await fetch(
        `/api/schedules/${encodeURIComponent(crawler)}/${encodeURIComponent(jobId)}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(patch),
        },
      );
      if (!res.ok) throw new Error(`스케줄 업데이트 실패 (HTTP ${res.status})`);
      const data: ApiResponse<CrawlSchedule> = await res.json();
      if (data.success && data.data) {
        setSchedules((prev) =>
          prev.map((s) =>
            s.crawler === crawler && s.job_id === jobId ? data.data! : s,
          ),
        );
        return data.data;
      }
      throw new Error(data.error?.message || "Failed to update schedule");
    },
    [],
  );

  return { schedules, loading, error, fetchSchedules, patchSchedule };
}
