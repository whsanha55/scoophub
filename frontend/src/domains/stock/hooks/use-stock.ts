"use client";

import { useState, useCallback } from "react";
import type {
  StockReport,
  StockReportSummarized,
  WatchlistItem,
  WatchlistCreateInput,
  WatchlistUpdateInput,
  MarketStatus,
  StockAnalyzeResult,
  StockAnalysis,
} from "../types";
import type { ApiResponse } from "@/shared/types";

export function useStockReports() {
  const [reports, setReports] = useState<StockReport[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchReports = useCallback(async (tickers?: string) => {
    setLoading(true);
    setError(null);
    try {
      const sp = new URLSearchParams();
      if (tickers) sp.set("tickers", tickers);
      const res = await fetch(`/api/stock/report?${sp.toString()}`);
      const data: ApiResponse<StockReport[]> = await res.json();
      if (data.success && data.data) {
        setReports(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch reports");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { reports, loading, error, fetchReports };
}

export function useAllStockReports() {
  const [reports, setReports] = useState<StockReportSummarized[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchReports = useCallback(async (summarize = true) => {
    setLoading(true);
    setError(null);
    try {
      const sp = new URLSearchParams();
      sp.set("summarize", String(summarize));
      const res = await fetch(`/api/stock/report/all?${sp.toString()}`);
      const data: ApiResponse<StockReportSummarized[]> = await res.json();
      if (data.success && data.data) {
        setReports(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch reports");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { reports, loading, error, fetchReports };
}

export function useWatchlist() {
  const [items, setItems] = useState<WatchlistItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchWatchlist = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/stock/watchlist");
      const data: ApiResponse<WatchlistItem[]> = await res.json();
      if (data.success && data.data) {
        setItems(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch watchlist");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  const addItem = useCallback(async (input: WatchlistCreateInput) => {
    const res = await fetch("/api/stock/watchlist", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    });
    const data: ApiResponse<WatchlistItem> = await res.json();
    if (data.success && data.data) {
      setItems((prev) => [...prev, data.data!]);
      return data.data;
    }
    throw new Error(data.error?.message || "Failed to add item");
  }, []);

  const updateItem = useCallback(async (id: string, input: WatchlistUpdateInput) => {
    const res = await fetch(`/api/stock/watchlist/${id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    });
    const data: ApiResponse<WatchlistItem> = await res.json();
    if (data.success && data.data) {
      setItems((prev) => prev.map((i) => (i.id === id ? data.data! : i)));
      return data.data;
    }
    throw new Error(data.error?.message || "Failed to update item");
  }, []);

  const deleteItem = useCallback(async (id: string) => {
    const res = await fetch(`/api/stock/watchlist/${id}`, { method: "DELETE" });
    const data: ApiResponse<{ deleted: number }> = await res.json();
    if (data.success) {
      setItems((prev) => prev.filter((i) => i.id !== id));
      return true;
    }
    throw new Error(data.error?.message || "Failed to delete item");
  }, []);

  return { items, loading, error, fetchWatchlist, addItem, updateItem, deleteItem };
}

export function useMarketStatus() {
  const [status, setStatus] = useState<MarketStatus | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchStatus = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/stock/market-status");
      const data: ApiResponse<MarketStatus> = await res.json();
      if (data.success && data.data) {
        setStatus(data.data);
      }
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  }, []);

  return { status, loading, fetchStatus };
}

export function useStockAnalyze() {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<StockAnalyzeResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const triggerAnalyze = useCallback(async (tickers?: string) => {
    setLoading(true);
    setError(null);
    try {
      const sp = new URLSearchParams();
      if (tickers) sp.set("tickers", tickers);
      const res = await fetch(`/api/crawling/stock/analyze?${sp.toString()}`, { method: "POST" });
      const data: ApiResponse<StockAnalyzeResult> = await res.json();
      if (data.success && data.data) {
        setResult(data.data);
      } else {
        setError(data.error?.message || "Analyze failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, result, error, triggerAnalyze };
}

export function useStockSigmaCompute() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCompute = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/stock/sigma/compute", { method: "POST" });
      const data: ApiResponse<{ computed: number }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Sigma compute failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCompute };
}

export function useStockSync() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerSync = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/stock/sync", { method: "POST" });
      const data: ApiResponse<{ synced: number }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Sync failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerSync };
}

// #82 — 단일 fetch 통합: 티커 상세 (리포트 + quote)
export function useStockDetail() {
  const [detail, setDetail] = useState<StockReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchDetail = useCallback(async (ticker: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/stock/detail/${encodeURIComponent(ticker)}`);
      const data: ApiResponse<StockReport> = await res.json();
      if (data.success && data.data) {
        setDetail(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch detail");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { detail, loading, error, fetchDetail };
}

// #44 — 분석 결과 (전체)
export function useStockAnalysis() {
  const [items, setItems] = useState<StockAnalysis[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchAnalysis = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/stock/analysis");
      const data: ApiResponse<StockAnalysis[]> = await res.json();
      if (data.success && data.data) {
        setItems(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch analysis");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { items, loading, error, fetchAnalysis };
}

// #57 — 리포트 온디맨드 발신 (super 전용 — UI에서 게이트)
export function useStockReportSend() {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<{ sent?: number } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const triggerSend = useCallback(async (tickers?: string) => {
    setLoading(true);
    setError(null);
    try {
      const sp = new URLSearchParams();
      if (tickers) sp.set("tickers", tickers);
      const res = await fetch(`/api/stock/report/send?${sp.toString()}`, { method: "POST" });
      const data: ApiResponse<{ sent?: number }> = await res.json();
      if (data.success) {
        setResult(data.data ?? null);
      } else {
        setError(data.error?.message || "Send failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, result, error, triggerSend };
}
