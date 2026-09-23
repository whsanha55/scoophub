"use client";
import { useState, useCallback } from "react";
import type { YouTubeVideo, YouTubeVideoParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useYouTubeTrending() {
  const [videos, setVideos] = useState<YouTubeVideo[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchVideos = useCallback(
    async ({ channel, sort, order, limit, page }: YouTubeVideoParams = {}) => {
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams();
        if (channel) params.set("channel", channel);
        if (sort) params.set("sort", sort);
        if (order) params.set("order", order);
        if (limit) params.set("limit", String(limit));
        if (page) params.set("page", String(page));
        const qs = params.toString();
        const res = await fetch(`/api/youtube-trending${qs ? `?${qs}` : ""}`);
        const data: ApiResponse<YouTubeVideo[]> = await res.json();
        if (data.success && data.data) {
          setVideos(data.data);
        } else {
          setError(data.error?.message || "Failed to fetch YouTube trending videos");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    },
    []
  );

  return { videos, loading, error, fetchVideos };
}

export function useYouTubeTrendingCrawl() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/youtube-trending", {
        method: "POST",
      });
      const data: ApiResponse<{
        items_fetched: number;
        items_new: number;
      }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "YouTube trending crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
