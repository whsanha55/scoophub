"use client";
import { useState, useCallback } from "react";
import type { ArxivPaper, ArxivPaperParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useArxiv() {
  const [papers, setPapers] = useState<ArxivPaper[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchPapers = useCallback(
    async ({
      category,
      search,
      sort,
      order,
      limit,
      page,
    }: ArxivPaperParams = {}) => {
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams();
        if (category) params.set("category", category);
        if (search) params.set("search", search);
        if (sort) params.set("sort", sort);
        if (order) params.set("order", order);
        if (limit) params.set("limit", String(limit));
        if (page) params.set("page", String(page));
        const qs = params.toString();
        const res = await fetch(`/api/arxiv${qs ? `?${qs}` : ""}`);
        const data: ApiResponse<ArxivPaper[]> = await res.json();
        if (data.success && data.data) {
          setPapers(data.data);
        } else {
          setError(data.error?.message || "Failed to fetch arXiv papers");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    },
    []
  );

  return { papers, loading, error, fetchPapers };
}

export function useArxivCrawl() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/arxiv", {
        method: "POST",
      });
      const data: ApiResponse<{
        items_fetched: number;
        items_new: number;
      }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "arXiv crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
