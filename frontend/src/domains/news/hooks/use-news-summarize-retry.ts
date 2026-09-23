"use client";
import { useState, useCallback } from "react";
import type { ApiResponse } from "@/shared/types";

export function useNewsSummarizeRetry() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerRetry = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/news/summarize/retry", {
        method: "POST",
      });
      const data: ApiResponse<{ retried: number }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Summarize retry failed");
      }
      return data;
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerRetry };
}
