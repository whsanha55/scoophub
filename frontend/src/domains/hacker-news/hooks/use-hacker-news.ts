"use client";
import { useState, useCallback } from "react";
import type { HackerNewsItem, HackerNewsParams } from "../types";
import { useCrawlTrigger } from "@/shared/hooks/use-crawl-trigger";
import type { ApiResponse } from "@/shared/types";

export function useHackerNews() {
  const [items, setItems] = useState<HackerNewsItem[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchItems = useCallback(
    async ({ type, sort, order, limit, page }: HackerNewsParams = {}) => {
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams();
        if (type) params.set("type", type);
        if (sort) params.set("sort", sort);
        if (order) params.set("order", order);
        if (limit) params.set("limit", String(limit));
        if (page) params.set("page", String(page));
        const qs = params.toString();
        const res = await fetch(`/api/hacker-news${qs ? `?${qs}` : ""}`);
        const data: ApiResponse<HackerNewsItem[]> = await res.json();
        if (data.success && data.data) {
          setItems(data.data);
        } else {
          setError(data.error?.message || "Failed to fetch Hacker News items");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    },
    []
  );

  return { items, loading, error, fetchItems };
}

export function useHackerNewsCrawl() {
  return useCrawlTrigger("hacker-news", "Hacker News crawl failed");
}
