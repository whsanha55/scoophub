"use client";

import { useEffect } from "react";
import { useWeather, useWeatherCrawl } from "@/domains/weather/hooks/use-weather";
import { WeatherWidget } from "@/domains/weather/components/weather-widget";
import { CrawlTriggerButton } from "@/domains/news/components/crawl-trigger-button";
import { Skeleton } from "@/components/ui/skeleton";

export default function WeatherPage() {
  const { weather, loading, fetchWeather } = useWeather();
  const { loading: crawlLoading, triggerCrawl } = useWeatherCrawl();

  useEffect(() => {
    fetchWeather();
  }, [fetchWeather]);

  const handleCrawl = async () => {
    await triggerCrawl();
    await fetchWeather();
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">날씨</h1>
        <CrawlTriggerButton
          onClick={handleCrawl}
          loading={crawlLoading}
          label="날씨 수집"
        />
      </div>

      {loading ? (
        <Skeleton className="h-80 rounded-xl" />
      ) : weather ? (
        <WeatherWidget weather={weather} />
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          날씨 데이터를 불러올 수 없습니다
        </div>
      )}
    </div>
  );
}
