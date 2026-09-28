"use client";

import { useEffect, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useTechNewsletter, useTechNewsletterCrawl } from "@/domains/tech-newsletter/hooks/use-tech-newsletter";
import { NewsletterCard } from "@/domains/tech-newsletter/components/newsletter-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const SORT_OPTIONS = [
  { value: "published_at", label: "최신순" },
  { value: "fetched_at", label: "수집순" },
] as const;

type SortValue = (typeof SORT_OPTIONS)[number]["value"];

export default function TechNewsletterPage() {
  const { articles, loading, fetchArticles } = useTechNewsletter();
  const { loading: crawlLoading, triggerCrawl } = useTechNewsletterCrawl();

  const [sort, setSort] = useState<SortValue>("published_at");
  const [newsletter, setNewsletter] = useState("");
  const debouncedNewsletter = useDebouncedValue(newsletter);

  useEffect(() => {
    fetchArticles({
      newsletter: debouncedNewsletter || undefined,
      sort,
      order: "desc",
      limit: 25,
    });
  }, [sort, debouncedNewsletter, fetchArticles]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchArticles({
      newsletter: debouncedNewsletter || undefined,
      sort,
      order: "desc",
      limit: 25,
    });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Tech Newsletter</h1>
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
          placeholder="뉴스레터 필터"
          aria-label="뉴스레터 필터"
          value={newsletter}
          onChange={(e) => setNewsletter(e.target.value)}
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
            <NewsletterCard key={article.id} article={article} />
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
