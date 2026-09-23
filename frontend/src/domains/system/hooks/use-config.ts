"use client";

import { useState, useCallback } from "react";
import type { CrawlConfigEntry, CrawlConfigPatch } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useCrawlConfig() {
  const [configs, setConfigs] = useState<CrawlConfigEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchConfigs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/config");
      const data: ApiResponse<CrawlConfigEntry[]> = await res.json();
      if (data.success && data.data) {
        setConfigs(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch config");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  const patchConfig = useCallback(async (crawler: string, patch: CrawlConfigPatch) => {
    const res = await fetch(`/api/config/${encodeURIComponent(crawler)}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    });
    if (!res.ok) throw new Error(`설정 업데이트 실패 (HTTP ${res.status})`);
    const data: ApiResponse<CrawlConfigEntry> = await res.json();
    if (data.success && data.data) {
      setConfigs((prev) =>
        prev.map((c) => (c.crawler === crawler ? data.data! : c)),
      );
      return data.data;
    }
    throw new Error(data.error?.message || "Failed to update config");
  }, []);

  return { configs, loading, error, fetchConfigs, patchConfig };
}
