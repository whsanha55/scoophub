"use client";

import { useEffect, useState, useCallback } from "react";
import { useNews, useNewsArticle, useNewsCrawl } from "@/domains/news/hooks/use-news";
import { useNewsSummarizeRetry } from "@/domains/news/hooks/use-news-summarize-retry";
import { NewsCard } from "@/domains/news/components/news-card";
import { NewsDetail } from "@/domains/news/components/news-detail";
import { NewsFilters } from "@/domains/news/components/news-filters";
import { NewsPagination } from "@/domains/news/components/news-pagination";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import type { NewsArticle } from "@/domains/news/types";

const PAGE_SIZE = 20;

function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export default function NewsPage() {
  const { articles, loading, total, fetchNews } = useNews();
  const { article: selectedArticle, fetchArticle, resetArticle } = useNewsArticle();
  const { loading: crawlLoading, triggerCrawl } = useNewsCrawl();
  const { loading: retryLoading, triggerRetry } = useNewsSummarizeRetry();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [category, setCategory] = useState<string | null>(null);
  const [minImportance, setMinImportance] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [dateFrom, setDateFrom] = useState(today);
  const [dateTo, setDateTo] = useState(today);

  const totalPages = Math.ceil(total / PAGE_SIZE);

  // 기사 전환/닫기 시 이전 본문 잔류 방지(fetch 완료 전 stale 본문 노출 차단).
  const selectArticle = useCallback(
    (id: number | null) => {
      resetArticle();
      setSelectedId(id);
    },
    [resetArticle],
  );

  const loadNews = useCallback(() => {
    fetchNews({
      from: `${dateFrom}T00:00:00`,
      to: `${dateTo}T23:59:59`,
      category: category ?? undefined,
      min_importance: minImportance ?? undefined,
      limit: PAGE_SIZE,
      page,
    });
  }, [fetchNews, dateFrom, dateTo, category, minImportance, page]);

  useEffect(() => {
    loadNews();
  }, [loadNews]);

  useEffect(() => {
    if (selectedId) {
      fetchArticle(selectedId);
    }
  }, [selectedId, fetchArticle]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await loadNews();
  };

  const handleRetry = async () => {
    await triggerRetry();
    await loadNews();
  };

  const handlePageChange = (newPage: number) => {
    setPage(newPage);
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  if (selectedId && selectedArticle) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">뉴스</h1>
        <NewsDetail
          article={selectedArticle}
          onBack={() => selectArticle(null)}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <h1 className="text-2xl font-bold">뉴스</h1>
        <div className="flex items-center gap-2">
          <CrawlTriggerButton
            onClick={handleRetry}
            loading={retryLoading}
            label="요약 재시도"
          />
          <CrawlTriggerButton
            onClick={handleCrawl}
            loading={crawlLoading}
            label="뉴스 수집 실행"
          />
        </div>
      </div>

      <NewsFilters
        selectedCategory={category}
        minImportance={minImportance}
        dateFrom={dateFrom}
        dateTo={dateTo}
        onSelectCategory={(c) => { setCategory(c); setPage(1); }}
        onSelectImportance={(imp) => { setMinImportance(imp); setPage(1); }}
        onSelectDateFrom={(d) => { setDateFrom(d); setPage(1); }}
        onSelectDateTo={(d) => { setDateTo(d); setPage(1); }}
      />

      {loading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 9 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : articles.length === 0 ? (
        <div className="py-12 text-center text-muted-foreground">
          뉴스 기사가 없습니다
        </div>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {articles.map((article) => (
              <NewsCard
                key={article.id}
                article={article}
                onClick={(a: NewsArticle) => selectArticle(a.id)}
              />
            ))}
          </div>
          <NewsPagination
            page={page}
            totalPages={totalPages}
            onPageChange={handlePageChange}
          />
        </>
      )}
    </div>
  );
}
