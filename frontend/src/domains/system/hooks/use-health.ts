"use client";

import { useState, useCallback } from "react";
import type { HealthData } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useHealth() {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchHealth = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/health");
      const data: ApiResponse<HealthData> = await res.json();
      if (data.success && data.data) {
        setHealth(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch health");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { health, loading, error, fetchHealth };
}
