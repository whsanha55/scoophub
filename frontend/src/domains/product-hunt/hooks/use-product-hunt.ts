"use client";
import { useState, useCallback } from "react";
import type { ProductHuntPost, ProductHuntParams } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useProductHunt() {
  const [posts, setPosts] = useState<ProductHuntPost[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchPosts = useCallback(
    async ({
      topic,
      sort,
      order,
      limit,
      page,
    }: ProductHuntParams = {}) => {
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams();
        if (topic) params.set("topic", topic);
        if (sort) params.set("sort", sort);
        if (order) params.set("order", order);
        if (limit) params.set("limit", String(limit));
        if (page) params.set("page", String(page));
        const qs = params.toString();
        const res = await fetch(`/api/product-hunt${qs ? `?${qs}` : ""}`);
        const data: ApiResponse<ProductHuntPost[]> = await res.json();
        if (data.success && data.data) {
          setPosts(data.data);
        } else {
          setError(data.error?.message || "Failed to fetch Product Hunt posts");
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setLoading(false);
      }
    },
    []
  );

  return { posts, loading, error, fetchPosts };
}

export function useProductHuntCrawl() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/product-hunt", {
        method: "POST",
      });
      const data: ApiResponse<{
        items_fetched: number;
        items_new: number;
      }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Product Hunt crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
