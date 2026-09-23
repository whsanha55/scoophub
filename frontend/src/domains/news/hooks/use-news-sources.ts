"use client";

import { useState, useCallback } from "react";
import type { NewsSource, NewsSourceCreateParams, NewsSourceUpdateParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useNewsSources() {
  const [sources, setSources] = useState<NewsSource[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchSources = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/news/sources");
      const data: ApiResponse<NewsSource[]> = await res.json();
      if (data.success && data.data) {
        setSources(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch sources");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  const addSource = useCallback(async (params: NewsSourceCreateParams) => {
    setError(null);
    try {
      const res = await fetch("/api/news/sources", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(params),
      });
      const data: ApiResponse<NewsSource> = await res.json();
      if (data.success && data.data) {
        setSources((prev) => (prev ? [...prev, data.data!] : [data.data!]));
        return data.data;
      } else {
        setError(data.error?.message || "Failed to add source");
        return null;
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Unknown error";
      setError(msg);
      return null;
    }
  }, []);

  const updateSource = useCallback(async (id: number, params: NewsSourceUpdateParams) => {
    setError(null);
    try {
      const res = await fetch(`/api/news/sources/${id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(params),
      });
      const data: ApiResponse<NewsSource> = await res.json();
      if (data.success && data.data) {
        setSources((prev) =>
          prev ? prev.map((s) => (s.id === id ? data.data! : s)) : prev
        );
        return data.data;
      } else {
        setError(data.error?.message || "Failed to update source");
        return null;
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Unknown error";
      setError(msg);
      return null;
    }
  }, []);

  const deleteSource = useCallback(async (id: number) => {
    setError(null);
    try {
      const res = await fetch(`/api/news/sources/${id}`, { method: "DELETE" });
      const data: ApiResponse<null> = await res.json();
      if (data.success) {
        setSources((prev) => (prev ? prev.filter((s) => s.id !== id) : prev));
        return true;
      } else {
        setError(data.error?.message || "Failed to delete source");
        return false;
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Unknown error";
      setError(msg);
      return false;
    }
  }, []);

  return { sources, loading, error, fetchSources, addSource, updateSource, deleteSource };
}
