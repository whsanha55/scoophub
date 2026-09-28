"use client";
import { useState, useCallback } from "react";
import type { HackerNewsItem, HackerNewsParams } from "../types";
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
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/hacker-news", {
        method: "POST",
      });
      const data: ApiResponse<{
        items_fetched: number;
        items_new: number;
      }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Hacker News crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
