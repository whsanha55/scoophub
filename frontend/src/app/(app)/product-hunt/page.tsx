"use client";

import { useEffect, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useProductHunt, useProductHuntCrawl } from "@/domains/product-hunt/hooks/use-product-hunt";
import { ProductHuntCard } from "@/domains/product-hunt/components/product-hunt-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const SORT_OPTIONS = [
  { value: "votes_count" as const, label: "인기순" },
  { value: "created_at" as const, label: "최신순" },
];

type Sort = "votes_count" | "created_at";

export default function ProductHuntPage() {
  const { posts, loading, fetchPosts } = useProductHunt();
  const { loading: crawlLoading, triggerCrawl } = useProductHuntCrawl();

  const [sort, setSort] = useState<Sort>("votes_count");
  const [topic, setTopic] = useState("");
  const debouncedTopic = useDebouncedValue(topic);

  useEffect(() => {
    fetchPosts({ topic: debouncedTopic || undefined, sort, order: "desc", limit: 25 });
  }, [sort, debouncedTopic, fetchPosts]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchPosts({ topic: debouncedTopic || undefined, sort, order: "desc", limit: 25 });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Product Hunt</h1>
        <CrawlTriggerButton
          onClick={handleCrawl}
          loading={crawlLoading}
          label="크롤 수집"
        />
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="flex gap-1">
          {SORT_OPTIONS.map((opt) => (
            <Button
              key={opt.value}
              variant={sort === opt.value ? "default" : "outline"}
              size="sm"
              onClick={() => setSort(opt.value)}
            >
              {opt.label}
            </Button>
          ))}
        </div>
        <Input
          className="sm:w-60"
          placeholder="토픽 필터 (예: AI)"
          aria-label="토픽 필터"
          value={topic}
          onChange={(e) => setTopic(e.target.value)}
        />
      </div>

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : posts && posts.length > 0 ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {posts.map((post) => (
            <ProductHuntCard key={post.id} post={post} />
          ))}
        </div>
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          게시물이 없습니다
        </div>
      )}
    </div>
  );
}
