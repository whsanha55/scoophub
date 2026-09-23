"use client";

import { useState, useCallback } from "react";
import type { KalBonusItem } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useKalBonus() {
  const [months, setMonths] = useState<string[] | null>(null);
  const [items, setItems] = useState<KalBonusItem[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 월 목록만 로드 (GET /api/kal-bonus 파라미터 없 → meta.months)
  const fetchMonths = useCallback(async (): Promise<string[]> => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/kal-bonus");
      const data: ApiResponse<KalBonusItem[]> = await res.json();
      const ms = data.meta?.months ?? [];
      setMonths(ms);
      return ms;
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
      return [];
    } finally {
      setLoading(false);
    }
  }, []);

  // 특정 월 데이터 로드 (GET /api/kal-bonus?month=YYYYMM)
  const fetchByMonth = useCallback(async (ym: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/kal-bonus?month=${ym}`);
      const data: ApiResponse<KalBonusItem[]> = await res.json();
      if (data.success && data.data) {
        setItems(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch KAL bonus");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { months, items, loading, error, fetchMonths, fetchByMonth };
}

export function useKalBonusCrawl() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/kal-bonus", { method: "POST" });
      const data: ApiResponse<{ items_fetched?: number; items_new?: number }> =
        await res.json();
      if (!data.success) {
        setError(data.error?.message || "KAL bonus crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
