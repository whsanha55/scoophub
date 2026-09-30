"use client";
import { useState, useCallback } from "react";
import type { NewsletterArticle, NewsletterArticleParams } from "../types";
import { useCrawlTrigger } from "@/shared/hooks/use-crawl-trigger";
import type { ApiResponse } from "@/shared/types";

export function useTechNewsletter() {
  const [articles, setArticles] = useState<NewsletterArticle[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchArticles = useCallback(
    async ({
      newsletter,
      sort,
      order,
      limit,
      page,
    }: NewsletterArticleParams = {}) => {
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams();
        if (newsletter) params.set("newsletter", newsletter);
        if (sort) params.set("sort", sort);
        if (order) params.set("order", order);
        if (limit) params.set("limit", String(limit));
        if (page) params.set("page", String(page));
        const qs = params.toString();
        const res = await fetch(`/api/tech-newsletter${qs ? `?${qs}` : ""}`);
        const data: ApiResponse<NewsletterArticle[]> = await res.json();
        if (data.success && data.data) {
          setArticles(data.data);
        } else {
          setError(data.error?.message || "Failed to fetch tech newsletter articles");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    },
    []
  );

  return { articles, loading, error, fetchArticles };
}

export function useTechNewsletterCrawl() {
  return useCrawlTrigger("tech-newsletter", "Tech newsletter crawl failed");
}
