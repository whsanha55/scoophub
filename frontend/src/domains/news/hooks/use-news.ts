"use client";

import { useState, useCallback, useRef } from "react";
import type { NewsArticle, NewsListParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useNews() {
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestIdRef = useRef(0);
  const [total, setTotal] = useState(0);

  const fetchNews = useCallback(async (params?: NewsListParams) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    try {
      const sp = new URLSearchParams();
      if (params?.minutes) sp.set("minutes", String(params.minutes));
      if (params?.from) sp.set("from", params.from);
      if (params?.to) sp.set("to", params.to);
      if (params?.category) sp.set("category", params.category);
      if (params?.min_importance) sp.set("min_importance", String(params.min_importance));
      if (params?.limit) sp.set("limit", String(params.limit));
      if (params?.page) sp.set("page", String(params.page));
      if (params?.symbol) sp.set("symbol", params.symbol);

      const res = await fetch(`/api/news?${sp.toString()}`);
      const data: ApiResponse<NewsArticle[]> = await res.json();
      if (requestId !== requestIdRef.current) return;
      if (data.success && data.data) {
        setArticles(data.data);
        setTotal(data.meta?.total ?? 0);
      } else {
        setError(data.error?.message || "Failed to fetch news");
      }
    } catch (err) {
      if (requestId !== requestIdRef.current) return;
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      if (requestId === requestIdRef.current) setLoading(false);
    }
  }, []);

  return { articles, loading, error, total, fetchNews };
}

export function useNewsArticle() {
  const [article, setArticle] = useState<NewsArticle | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestIdRef = useRef(0);

  const fetchArticle = useCallback(async (id: number) => {
    const requestId = ++requestIdRef.current;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/news/${id}`);
      const data: ApiResponse<NewsArticle> = await res.json();
      if (requestId !== requestIdRef.current) return;
      if (data.success && data.data) {
        setArticle(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch article");
      }
    } catch (err) {
      if (requestId !== requestIdRef.current) return;
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      if (requestId === requestIdRef.current) setLoading(false);
    }
  }, []);

  // 기사 전환 시 이전 본문 잔류 방지용 reset.
  const resetArticle = useCallback(() => {
    requestIdRef.current++;
    setArticle(null);
    setError(null);
  }, []);

  return { article, loading, error, fetchArticle, resetArticle };
}
