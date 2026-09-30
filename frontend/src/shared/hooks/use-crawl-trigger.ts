"use client";

import { useCallback, useRef, useState } from "react";
import { requestCrawl } from "@/shared/lib/crawl-api";

export function useCrawlTrigger(crawler: string, fallbackMessage: string) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = useRef(false);

  const triggerCrawl = useCallback(async () => {
    if (pending.current) return;
    pending.current = true;
    setLoading(true);
    setError(null);
    try {
      await requestCrawl(crawler, fallbackMessage);
    } catch (err) {
      setError(err instanceof Error ? err.message : fallbackMessage);
    } finally {
      pending.current = false;
      setLoading(false);
    }
  }, [crawler, fallbackMessage]);

  return { loading, error, triggerCrawl };
}
