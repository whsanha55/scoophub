"use client";

import { useState, useCallback } from "react";
import type { NotifyLog, NotifyLogsParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useNotifyLog() {
  const [logs, setLogs] = useState<NotifyLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchLogs = useCallback(async (params?: NotifyLogsParams) => {
    setLoading(true);
    setError(null);
    try {
      const searchParams = new URLSearchParams();
      if (params?.route_id !== undefined) searchParams.set("route_id", String(params.route_id));
      if (params?.status) searchParams.set("status", params.status);
      if (params?.limit) searchParams.set("limit", String(params.limit));
      const qs = searchParams.toString();
      const res = await fetch(`/api/notify/log${qs ? `?${qs}` : ""}`);
      const data: ApiResponse<NotifyLog[]> = await res.json();
      if (data.success && data.data) {
        setLogs(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch notify logs");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { logs, loading, error, fetchLogs };
}
