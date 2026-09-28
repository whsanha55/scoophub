"use client";

import { useState, useCallback } from "react";
import type { WeatherData, DailyForecast } from "../types";
import type { ApiResponse } from "@/shared/types";

export function useWeather() {
  const [weather, setWeather] = useState<WeatherData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchWeather = useCallback(async (location = "seoul") => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/weather?location=${encodeURIComponent(location)}`);
      const data: ApiResponse<WeatherData> = await res.json();
      if (data.success && data.data) {
        const d = data.data as WeatherData & { weekly_forecast?: string | DailyForecast[] };
        if (typeof d.weekly_forecast === "string") {
          try { d.weekly_forecast = JSON.parse(d.weekly_forecast); } catch { d.weekly_forecast = []; }
        }
        setWeather(d as WeatherData);
      } else {
        setError(data.error?.message || "Failed to fetch weather");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { weather, loading, error, fetchWeather };
}

export function useWeatherForecast() {
  const [forecast, setForecast] = useState<DailyForecast[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchForecast = useCallback(async (location = "seoul", limit = 3) => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/weather/forecast?location=${encodeURIComponent(location)}&limit=${limit}`);
      const data: ApiResponse<DailyForecast[]> = await res.json();
      if (data.success && data.data) {
        setForecast(data.data);
      } else {
        setError(data.error?.message || "Failed to fetch forecast");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { forecast, loading, error, fetchForecast };
}

export function useWeatherCrawl() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const triggerCrawl = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/crawling/weather", { method: "POST" });
      const data: ApiResponse<{ items_fetched: number; items_new: number }> = await res.json();
      if (!data.success) {
        setError(data.error?.message || "Weather crawl failed");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
    }
  }, []);

  return { loading, error, triggerCrawl };
}
