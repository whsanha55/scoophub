"use client";

import { useEffect, useState } from "react";
import { useHackerNews, useHackerNewsCrawl } from "@/domains/hacker-news/hooks/use-hacker-news";
import { HackerNewsCard } from "@/domains/hacker-news/components/hacker-news-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";

const SORT_OPTIONS = [
  { value: "points" as const, label: "포인트순" },
  { value: "created_at" as const, label: "최신순" },
];

type Sort = "points" | "created_at";

export default function HackerNewsPage() {
  const { items, loading, fetchItems } = useHackerNews();
  const { loading: crawlLoading, triggerCrawl } = useHackerNewsCrawl();

  const [sort, setSort] = useState<Sort>("points");

  useEffect(() => {
    fetchItems({ sort, order: "desc", limit: 25 });
  }, [sort, fetchItems]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchItems({ sort, order: "desc", limit: 25 });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Hacker News</h1>
        <CrawlTriggerButton
          onClick={handleCrawl}
          loading={crawlLoading}
          label="크롤 수집"
        />
      </div>

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

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : items && items.length > 0 ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {items.map((item) => (
            <HackerNewsCard key={item.id} item={item} />
          ))}
        </div>
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          아이템이 없습니다
        </div>
      )}
    </div>
  );
}
