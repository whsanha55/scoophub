"use client";
import { useState, useCallback } from "react";
import type { GitHubTrendingRepo, GitHubTrendingParams } from "../types";
import { useCrawlTrigger } from "@/shared/hooks/use-crawl-trigger";
import type { ApiResponse } from "@/shared/types";

export function useGitHubTrending() {
  const [repos, setRepos] = useState<GitHubTrendingRepo[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchTrending = useCallback(
    async ({ period, language, limit }: GitHubTrendingParams = {}) => {
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams();
        if (period) params.set("period", period);
        if (language) params.set("language", language);
        if (limit) params.set("limit", String(limit));
        const qs = params.toString();
        const res = await fetch(`/api/github-trending${qs ? `?${qs}` : ""}`);
        const data: ApiResponse<GitHubTrendingRepo[]> = await res.json();
        if (data.success && data.data) {
          setRepos(data.data);
        } else {
          setError(data.error?.message || "Failed to fetch GitHub trending");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    },
    []
  );

  return { repos, loading, error, fetchTrending };
}

export function useGitHubTrendingCrawl() {
  return useCrawlTrigger("github-trending", "GitHub trending crawl failed");
}
