"use client";
import { useState, useCallback } from "react";
import type { DevtoArticle, DevtoArticleParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useDevtoArticles() {
  const [articles, setArticles] = useState<DevtoArticle[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchArticles = useCallback(
    async (params?: DevtoArticleParams) => {
      setLoading(true);
      setError(null);
      try {
        const sp = new URLSearchParams();
        if (params?.tag) sp.set("tag", params.tag);
        if (params?.source) sp.set("source", params.source);
        if (params?.sort) sp.set("sort", params.sort);
        if (params?.order) sp.set("order", params.order);
        if (params?.limit) sp.set("limit", String(params.limit));
        if (params?.page) sp.set("page", String(params.page));
        const qs = sp.toString();
        const res = await fetch(`/api/devto-hashnode${qs ? `?${qs}` : ""}`);
        const data: ApiResponse<DevtoArticle[]> = await res.json();
        if (data.success && data.data) {
          setArticles(data.data);
        } else {
          setError(data.error?.message || "Failed to fetch articles");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    },
    []
  );

  return { articles, loading, error, fetchArticles };
}

export function useDevtoCrawl() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/devto-hashnode", {
        method: "POST",
      });
      const data: ApiResponse<{
        items_fetched: number;
        items_new: number;
      }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
