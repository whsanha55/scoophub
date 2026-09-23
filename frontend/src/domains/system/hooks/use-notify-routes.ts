"use client";

import { useState, useCallback } from "react";
import type {
  NotifyRoute,
  NotifyRouteCreate,
  NotifyRoutePatch,
  NotifyTestResult,
} from "../types";
import type { ApiResponse } from "@/shared/types";

export function useNotifyRoutes() {
  const [routes, setRoutes] = useState<NotifyRoute[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchRoutes = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/notify/routes");
      const data: ApiResponse<NotifyRoute[]> = await res.json();
      if (data.success && data.data) {
        setRoutes(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch notify routes");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  const createRoute = useCallback(async (body: NotifyRouteCreate) => {
    const res = await fetch("/api/notify/routes", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`발신 라우트 생성 실패 (HTTP ${res.status})`);
    const data: ApiResponse<NotifyRoute> = await res.json();
    if (data.success && data.data) {
      setRoutes((prev) => [...prev, data.data!]);
      return data.data;
    }
    throw new Error(data.error?.message || "Failed to create notify route");
  }, []);

  const patchRoute = useCallback(
    async (routeId: number, patch: NotifyRoutePatch) => {
      const res = await fetch(`/api/notify/routes/${routeId}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(patch),
      });
      if (!res.ok) throw new Error(`발신 라우트 업데이트 실패 (HTTP ${res.status})`);
      const data: ApiResponse<NotifyRoute> = await res.json();
      if (data.success && data.data) {
        setRoutes((prev) =>
          prev.map((r) => (r.id === routeId ? data.data! : r)),
        );
        return data.data;
      }
      throw new Error(data.error?.message || "Failed to update notify route");
    },
    [],
  );

  const deleteRoute = useCallback(async (routeId: number) => {
    const res = await fetch(`/api/notify/routes/${routeId}`, {
      method: "DELETE",
    });
    if (!res.ok) throw new Error(`발신 라우트 삭제 실패 (HTTP ${res.status})`);
    const data: ApiResponse<{ deleted: number }> = await res.json();
    if (data.success && data.data) {
      setRoutes((prev) => prev.filter((r) => r.id !== routeId));
      return data.data;
    }
    throw new Error(data.error?.message || "Failed to delete notify route");
  }, []);

  const testRoute = useCallback(async (routeId: number) => {
    const res = await fetch(`/api/notify/routes/${routeId}/test`, {
      method: "POST",
    });
    if (!res.ok) throw new Error(`발신 라우트 테스트 실패 (HTTP ${res.status})`);
    const data: ApiResponse<NotifyTestResult> = await res.json();
    if (data.success && data.data) {
      return data.data;
    }
    throw new Error(data.error?.message || "Failed to test notify route");
  }, []);

  return {
    routes,
    loading,
    error,
    fetchRoutes,
    createRoute,
    patchRoute,
    deleteRoute,
    testRoute,
  };
}
