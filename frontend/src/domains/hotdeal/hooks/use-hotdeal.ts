"use client";

import { useCallback, useRef, useState } from "react";
import type { ApiResponse } from "@/shared/types";
import { useCrawlTrigger } from "@/shared/hooks/use-crawl-trigger";
import { applyFeedback } from "../lib/feed";
import type {
  FeedFilter,
  FeedSort,
  FeedbackKind,
  HotdealFeedItem,
  HotdealProfile,
  ProfileKind,
} from "../types";

const PAGE_SIZE = 20;

async function request<T>(url: string, init: RequestInit, fallback: string): Promise<T> {
  const res = await fetch(url, init);
  const body = (await res.json().catch(() => null)) as
    | (Partial<ApiResponse<T>> & { detail?: unknown })
    | null;
  if (!res.ok || body?.success !== true) {
    const message = body?.error?.message
      || (typeof body?.detail === "string" ? body.detail : null)
      || `${fallback} (HTTP ${res.status})`;
    throw new Error(message);
  }
  return body.data as T;
}

const jsonInit = (method: string, body?: unknown): RequestInit => ({
  method,
  headers: body === undefined ? undefined : { "Content-Type": "application/json" },
  body: body === undefined ? undefined : JSON.stringify(body),
});

export function useHotdealFeed() {
  const [items, setItems] = useState<HotdealFeedItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pageRef = useRef(1);

  // page 1 이면 목록을 새로 채우고, 이후 페이지는 뒤에 붙인다 (더 보기).
  const fetchFeed = useCallback(async (sort: FeedSort, filter: FeedFilter, page = 1) => {
    pageRef.current = page;
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ sort, filter, limit: String(PAGE_SIZE), page: String(page) });
      const res = await fetch(`/api/hotdeal/feed?${params}`);
      const body: ApiResponse<HotdealFeedItem[]> = await res.json();
      if (!res.ok || !body.success || !body.data) {
        throw new Error(body.error?.message || `핫딜 피드 조회 실패 (HTTP ${res.status})`);
      }
      const data = body.data;
      setItems((prev) => (page === 1 ? data : [...prev, ...data]));
      setTotal(body.meta.total ?? data.length);
    } catch (err) {
      setError(err instanceof Error ? err.message : "핫딜 피드 조회 실패");
    } finally {
      setLoading(false);
    }
  }, []);

  // 피드백은 화면에 먼저 반영하고, 실패하면 이전 목록으로 되돌린다.
  const changeFeedback = useCallback(
    async (id: number, feedback: FeedbackKind | null, filter: FeedFilter) => {
      const snapshot = items;
      const next = applyFeedback(snapshot, id, feedback, filter);
      const removed = next.length < snapshot.length;
      setItems(next);
      if (removed) setTotal((t) => Math.max(0, t - 1));
      try {
        await request(
          `/api/hotdeal/feed/${id}/feedback`,
          feedback === null ? jsonInit("DELETE") : jsonInit("PUT", { kind: feedback }),
          "피드백 저장 실패",
        );
      } catch (err) {
        setItems(snapshot);
        if (removed) setTotal((t) => t + 1);
        setError(err instanceof Error ? err.message : "피드백 저장 실패");
      }
    },
    [items],
  );

  const loadMore = useCallback(
    (sort: FeedSort, filter: FeedFilter) => fetchFeed(sort, filter, pageRef.current + 1),
    [fetchFeed],
  );

  return { items, total, loading, error, fetchFeed, loadMore, changeFeedback };
}

export function useHotdealProfiles() {
  const [profiles, setProfiles] = useState<HotdealProfile[]>([]);
  const [error, setError] = useState<string | null>(null);

  const fetchProfiles = useCallback(async () => {
    try {
      setProfiles(await request<HotdealProfile[]>("/api/hotdeal/profiles", {}, "관심 항목 조회 실패"));
    } catch (err) {
      setError(err instanceof Error ? err.message : "관심 항목 조회 실패");
    }
  }, []);

  // 추가·삭제하면 서버가 즉시 재평가한다. 성공하면 true.
  const addProfile = useCallback(async (kind: ProfileKind, value: string): Promise<boolean> => {
    setError(null);
    try {
      const created = await request<HotdealProfile>(
        "/api/hotdeal/profiles",
        jsonInit("POST", { kind, value }),
        "관심 항목 추가 실패",
      );
      setProfiles((prev) => [...prev, created]);
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : "관심 항목 추가 실패");
      return false;
    }
  }, []);

  const deleteProfile = useCallback(async (id: number): Promise<boolean> => {
    setError(null);
    try {
      await request(`/api/hotdeal/profiles/${id}`, jsonInit("DELETE"), "관심 항목 삭제 실패");
      setProfiles((prev) => prev.filter((p) => p.id !== id));
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : "관심 항목 삭제 실패");
      return false;
    }
  }, []);

  const changeWeight = useCallback(async (id: number, seedWeight: number): Promise<boolean> => {
    setError(null);
    try {
      const updated = await request<HotdealProfile>(
        `/api/hotdeal/profiles/${id}`,
        jsonInit("PATCH", { seed_weight: seedWeight }),
        "가중치 변경 실패",
      );
      setProfiles((prev) => prev.map((p) => (p.id === id ? updated : p)));
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : "가중치 변경 실패");
      return false;
    }
  }, []);

  // 피드백으로 쌓인 학습값만 0 으로 되돌린다. 피드백 상태는 유지된다.
  const resetLearned = useCallback(async (): Promise<boolean> => {
    setError(null);
    try {
      setProfiles(
        await request<HotdealProfile[]>("/api/hotdeal/profiles/reset-learned", jsonInit("POST"), "학습값 초기화 실패"),
      );
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : "학습값 초기화 실패");
      return false;
    }
  }, []);

  return { profiles, error, fetchProfiles, addProfile, deleteProfile, changeWeight, resetLearned };
}

export function useHotdealCrawl() {
  return useCrawlTrigger("hotdeal-ppomppu", "뽐뿌 크롤 실패");
}
