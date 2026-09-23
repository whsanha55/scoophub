"use client";

import { useEffect, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useArxiv, useArxivCrawl } from "@/domains/arxiv/hooks/use-arxiv";
import { ArxivCard } from "@/domains/arxiv/components/arxiv-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const SORT_OPTIONS = [
  { value: "published_at" as const, label: "최신순" },
  { value: "fetched_at" as const, label: "수집순" },
];

type Sort = "published_at" | "fetched_at";

export default function ArxivPage() {
  const { papers, loading, fetchPapers } = useArxiv();
  const { loading: crawlLoading, triggerCrawl } = useArxivCrawl();

  const [sort, setSort] = useState<Sort>("published_at");
  const [category, setCategory] = useState("");
  const debouncedCategory = useDebouncedValue(category);

  useEffect(() => {
    fetchPapers({ category: debouncedCategory || undefined, sort, order: "desc", limit: 25 });
  }, [sort, debouncedCategory, fetchPapers]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchPapers({ category: debouncedCategory || undefined, sort, order: "desc", limit: 25 });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">arXiv Papers</h1>
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
          placeholder="카테고리 필터 (예: cs.AI)"
          aria-label="카테고리 필터"
          value={category}
          onChange={(e) => setCategory(e.target.value)}
        />
      </div>

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : papers && papers.length > 0 ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {papers.map((paper) => (
            <ArxivCard key={paper.id} paper={paper} />
          ))}
        </div>
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          논문이 없습니다
        </div>
      )}
    </div>
  );
}
