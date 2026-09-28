"use client";
import { useState, useCallback } from "react";
import type { NewsletterArticle, NewsletterArticleParams } from "../types";
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
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/tech-newsletter", {
        method: "POST",
      });
      const data: ApiResponse<{
        items_fetched: number;
        items_new: number;
      }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Tech newsletter crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
