"use client";

import { useState, useCallback } from "react";
import type { CrawlLog, CrawlLogsParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useCrawlLogs() {
  const [logs, setLogs] = useState<CrawlLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchLogs = useCallback(async (params?: CrawlLogsParams) => {
    setLoading(true);
    setError(null);
    try {
      const searchParams = new URLSearchParams();
      if (params?.crawler) searchParams.set("crawler", params.crawler);
      if (params?.crawler_detail) searchParams.set("crawler_detail", params.crawler_detail);
      if (params?.limit) searchParams.set("limit", String(params.limit));
      const qs = searchParams.toString();
      const res = await fetch(`/api/crawl-logs${qs ? `?${qs}` : ""}`);
      const data: ApiResponse<CrawlLog[]> = await res.json();
      if (data.success && data.data) {
        setLogs(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch crawl logs");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { logs, loading, error, fetchLogs };
}
