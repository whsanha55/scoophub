"use client";

import { useEffect, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useGitHubTrending, useGitHubTrendingCrawl } from "@/domains/github/hooks/use-github-trending";
import { GitHubTrendingCard } from "@/domains/github/components/github-trending-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const PERIODS = ["daily", "weekly", "monthly"] as const;
type Period = (typeof PERIODS)[number];

export default function GitHubTrendingPage() {
  const { repos, loading, fetchTrending } = useGitHubTrending();
  const { loading: crawlLoading, triggerCrawl } = useGitHubTrendingCrawl();

  const [period, setPeriod] = useState<Period>("daily");
  const [language, setLanguage] = useState("");
  const debouncedLanguage = useDebouncedValue(language);

  useEffect(() => {
    fetchTrending({ period, language: debouncedLanguage || undefined, limit: 25 });
  }, [period, debouncedLanguage, fetchTrending]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchTrending({ period, language: debouncedLanguage || undefined, limit: 25 });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">GitHub Trending</h1>
        <CrawlTriggerButton
          onClick={handleCrawl}
          loading={crawlLoading}
          label="크롤 수집"
        />
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="flex gap-1">
          {PERIODS.map((p) => (
            <Button
              key={p}
              variant={period === p ? "default" : "outline"}
              size="sm"
              onClick={() => setPeriod(p)}
            >
              {p}
            </Button>
          ))}
        </div>
        <Input
          className="sm:w-60"
          placeholder="언어 필터 (예: python)"
          aria-label="언어 필터"
          value={language}
          onChange={(e) => setLanguage(e.target.value)}
        />
      </div>

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : repos && repos.length > 0 ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {repos.map((repo) => (
            <GitHubTrendingCard key={repo.id} repo={repo} />
          ))}
        </div>
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          트렌딩 리포지토리가 없습니다
        </div>
      )}
    </div>
  );
}
