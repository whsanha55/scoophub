"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Search } from "lucide-react";
import { useCrawlLogs } from "../hooks/use-crawl-logs";

function formatDuration(start: string, end: string | null): string {
  if (!end) return "-";
  const ms = new Date(end).getTime() - new Date(start).getTime();
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

export function CrawlLogsPanel() {
  const { logs, loading, error, fetchLogs } = useCrawlLogs();
  const [filterCrawler, setFilterCrawler] = useState("");
  const [filterDetail, setFilterDetail] = useState("");
  const [filterLimit, setFilterLimit] = useState(20);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  const handleSearch = () => {
    fetchLogs({
      crawler: filterCrawler || undefined,
      crawler_detail: filterDetail || undefined,
      limit: filterLimit,
    });
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          placeholder="크롤러 이름"
          value={filterCrawler}
          onChange={(e) => setFilterCrawler(e.target.value)}
          className="w-40"
        />
        <Input
          placeholder="크롤러 상세"
          value={filterDetail}
          onChange={(e) => setFilterDetail(e.target.value)}
          className="w-40"
        />
        <Select
          value={String(filterLimit)}
          onValueChange={(v) => setFilterLimit(Number(v))}
        >
          <SelectTrigger className="w-24">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="10">10</SelectItem>
            <SelectItem value="20">20</SelectItem>
            <SelectItem value="50">50</SelectItem>
            <SelectItem value="100">100</SelectItem>
          </SelectContent>
        </Select>
        <Button onClick={handleSearch} className="cursor-pointer">
          <Search />
          조회
        </Button>
      </div>

      {loading && (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}

      {!loading && !error && logs.length === 0 && (
        <p className="text-sm text-muted-foreground">크롤링 로그가 없습니다</p>
      )}

      {!loading && !error && logs.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-muted-foreground">
                <th className="px-3 py-2 font-medium">크롤러</th>
                <th className="px-3 py-2 font-medium">상세</th>
                <th className="px-3 py-2 font-medium">상태</th>
                <th className="px-3 py-2 font-medium">수집/신규</th>
                <th className="px-3 py-2 font-medium">시작시간</th>
                <th className="px-3 py-2 font-medium">소요시간</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((log) => (
                <tr key={log.id} className="border-b last:border-0">
                  <td className="px-3 py-2">{log.crawler}</td>
                  <td className="px-3 py-2">{log.crawler_detail}</td>
                  <td className="px-3 py-2">
                    <Badge
                      variant={
                        log.status === "success" ? "default" : "destructive"
                      }
                      className={
                        log.status === "success"
                          ? "bg-green-500/15 text-green-600 dark:text-green-400"
                          : ""
                      }
                    >
                      {log.status === "success" ? "성공" : "실패"}
                    </Badge>
                  </td>
                  <td className="px-3 py-2">
                    {log.items_fetched}/{log.items_new}
                  </td>
                  <td className="px-3 py-2 whitespace-nowrap">
                    {new Date(log.started_at).toLocaleString("ko-KR")}
                  </td>
                  <td className="px-3 py-2">
                    {formatDuration(log.started_at, log.finished_at)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {logs.some((log) => log.error_message) && (
            <div className="mt-2 space-y-1">
              {logs
                .filter((log) => log.error_message)
                .map((log) => (
                  <p key={log.id} className="text-xs text-destructive">
                    {log.crawler}: {log.error_message}
                  </p>
                ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
