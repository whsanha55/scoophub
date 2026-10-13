"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { CrawlTriggerButton } from "@/shared/components/crawl-trigger-button";
import { useAuth } from "@/shared/hooks/use-auth";
import { HotdealFeedCard } from "@/domains/hotdeal/components/hotdeal-feed-card";
import { HotdealProfileManager } from "@/domains/hotdeal/components/hotdeal-profile-manager";
import {
  useHotdealCrawl,
  useHotdealFeed,
  useHotdealProfiles,
} from "@/domains/hotdeal/hooks/use-hotdeal";
import type { FeedFilter, FeedSort } from "@/domains/hotdeal/types";

const FILTERS: { value: FeedFilter; label: string }[] = [
  { value: "default", label: "추천" },
  { value: "liked", label: "좋아요" },
  { value: "hidden", label: "숨긴 항목" },
];

const SORTS: { value: FeedSort; label: string }[] = [
  { value: "recommended", label: "추천순" },
  { value: "latest", label: "최신순" },
];

const EMPTY_MESSAGES: Record<FeedFilter, string> = {
  default: "추천 항목이 없습니다. 관심 키워드를 추가해 보세요",
  liked: "좋아요한 항목이 없습니다",
  hidden: "숨긴 항목이 없습니다",
};

export default function HotdealPage() {
  const { user, loading: authLoading } = useAuth();

  if (authLoading) return <Skeleton className="h-40 rounded-xl" />;
  // 추천 피드는 개인 관심도 기반이라 관리자 전용이다 (API 도 @SuperOnly).
  if (!user?.is_super) {
    return (
      <div className="py-12 text-center text-muted-foreground">
        핫딜 추천 피드는 관리자 로그인 후 사용할 수 있습니다
      </div>
    );
  }
  return <HotdealFeed />;
}

function HotdealFeed() {
  const { items, total, loading, error, fetchFeed, loadMore, changeFeedback } = useHotdealFeed();
  const profiles = useHotdealProfiles();
  const { loading: crawlLoading, triggerCrawl } = useHotdealCrawl();
  const { fetchProfiles } = profiles;

  const [filter, setFilter] = useState<FeedFilter>("default");
  const [sort, setSort] = useState<FeedSort>("recommended");

  useEffect(() => {
    fetchProfiles();
  }, [fetchProfiles]);

  useEffect(() => {
    fetchFeed(sort, filter);
  }, [sort, filter, fetchFeed]);

  const reload = () => fetchFeed(sort, filter);

  const handleCrawl = async () => {
    await triggerCrawl();
    await reload();
  };

  const handleProfileChange = async (ok: boolean) => {
    // 서버가 즉시 재평가하므로 프로필 가중치와 피드를 함께 새로 고친다.
    if (ok) await Promise.all([fetchProfiles(), reload()]);
    return ok;
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2">
        <h1 className="text-2xl font-bold">핫딜</h1>
        <CrawlTriggerButton onClick={handleCrawl} loading={crawlLoading} label="알구더 수집" />
      </div>

      <HotdealProfileManager
        profiles={profiles.profiles}
        error={profiles.error}
        onAdd={async (value) => handleProfileChange(await profiles.addProfile("KEYWORD", value))}
        onDelete={async (id) => handleProfileChange(await profiles.deleteProfile(id))}
      />

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex gap-1">
          {FILTERS.map((f) => (
            <Button
              key={f.value}
              variant={filter === f.value ? "default" : "outline"}
              size="sm"
              onClick={() => setFilter(f.value)}
              className="cursor-pointer"
            >
              {f.label}
            </Button>
          ))}
        </div>
        <div className="flex gap-1">
          {SORTS.map((s) => (
            <Button
              key={s.value}
              variant={sort === s.value ? "secondary" : "ghost"}
              size="sm"
              onClick={() => setSort(s.value)}
              className="cursor-pointer"
            >
              {s.label}
            </Button>
          ))}
        </div>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      {loading && items.length === 0 ? (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-40 rounded-xl" />
          ))}
        </div>
      ) : items.length > 0 ? (
        <>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {items.map((item) => (
              <HotdealFeedCard
                key={item.id}
                item={item}
                onFeedback={(id, feedback) => changeFeedback(id, feedback, filter)}
              />
            ))}
          </div>
          {items.length < total && (
            <div className="flex justify-center">
              <Button variant="outline" onClick={() => loadMore(sort, filter)} disabled={loading} className="cursor-pointer">
                {loading ? "불러오는 중..." : `더 보기 (${items.length}/${total})`}
              </Button>
            </div>
          )}
        </>
      ) : (
        <div className="py-12 text-center text-muted-foreground">{EMPTY_MESSAGES[filter]}</div>
      )}
    </div>
  );
}
