"use client";

import { useEffect, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useDevtoArticles, useDevtoCrawl } from "@/domains/devto/hooks/use-devto";
import { DevtoCard } from "@/domains/devto/components/devto-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const SOURCES = ["all", "devto", "hashnode"] as const;
type Source = (typeof SOURCES)[number];

const SORTS = [
  { value: "published_at", label: "최신순" },
  { value: "positive_reactions_count", label: "인기순" },
] as const;

export default function DevtoPage() {
  const { articles, loading, fetchArticles } = useDevtoArticles();
  const { loading: crawlLoading, triggerCrawl } = useDevtoCrawl();

  const [source, setSource] = useState<Source>("all");
  const [sort, setSort] = useState("published_at");
  const [tag, setTag] = useState("");
  const debouncedTag = useDebouncedValue(tag);

  useEffect(() => {
    fetchArticles({
      tag: debouncedTag || undefined,
      source: source === "all" ? undefined : source,
      sort: sort as "published_at" | "positive_reactions_count",
      order: "desc",
      limit: 25,
    });
  }, [source, sort, debouncedTag, fetchArticles]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchArticles({
      tag: debouncedTag || undefined,
      source: source === "all" ? undefined : source,
      sort: sort as "published_at" | "positive_reactions_count",
      order: "desc",
      limit: 25,
    });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Dev.to / Hashnode</h1>
        <CrawlTriggerButton
          onClick={handleCrawl}
          loading={crawlLoading}
          label="크롤 수집"
        />
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="flex gap-1">
          {SOURCES.map((s) => (
            <Button
              key={s}
              variant={source === s ? "default" : "outline"}
              size="sm"
              onClick={() => setSource(s)}
            >
              {s === "all" ? "전체" : s}
            </Button>
          ))}
        </div>
        <div className="flex gap-1">
          {SORTS.map((s) => (
            <Button
              key={s.value}
              variant={sort === s.value ? "default" : "outline"}
              size="sm"
              onClick={() => setSort(s.value)}
            >
              {s.label}
            </Button>
          ))}
        </div>
        <Input
          className="sm:w-60"
          placeholder="태그 필터 (예: react)"
          aria-label="태그 필터"
          value={tag}
          onChange={(e) => setTag(e.target.value)}
        />
      </div>

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : articles && articles.length > 0 ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {articles.map((article) => (
            <DevtoCard key={article.id} article={article} />
          ))}
        </div>
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          아티클이 없습니다
        </div>
      )}
    </div>
  );
}
