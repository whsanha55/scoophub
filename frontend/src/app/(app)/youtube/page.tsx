"use client";

import { useEffect, useState } from "react";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useYouTubeTrending, useYouTubeTrendingCrawl } from "@/domains/youtube/hooks/use-youtube";
import { YouTubeCard } from "@/domains/youtube/components/youtube-card";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const SORT_OPTIONS = [
  { value: "view_count", label: "조회수순" },
  { value: "published_at", label: "최신순" },
] as const;

type SortValue = (typeof SORT_OPTIONS)[number]["value"];

export default function YouTubeTrendingPage() {
  const { videos, loading, fetchVideos } = useYouTubeTrending();
  const { loading: crawlLoading, triggerCrawl } = useYouTubeTrendingCrawl();

  const [sort, setSort] = useState<SortValue>("view_count");
  const [channel, setChannel] = useState("");
  const debouncedChannel = useDebouncedValue(channel);

  useEffect(() => {
    fetchVideos({ sort, order: "desc", channel: debouncedChannel || undefined, limit: 25 });
  }, [sort, debouncedChannel, fetchVideos]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchVideos({ sort, order: "desc", channel: debouncedChannel || undefined, limit: 25 });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">YouTube Trending</h1>
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
          placeholder="채널 필터"
          aria-label="채널 필터"
          value={channel}
          onChange={(e) => setChannel(e.target.value)}
        />
      </div>

      {loading ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : videos && videos.length > 0 ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {videos.map((video) => (
            <YouTubeCard key={video.id} video={video} />
          ))}
        </div>
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          트렌딩 영상이 없습니다
        </div>
      )}
    </div>
  );
}
