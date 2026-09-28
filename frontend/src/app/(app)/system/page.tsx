"use client";

import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { HealthPanel } from "@/domains/system/components/health-panel";
import { CrawlLogsPanel } from "@/domains/system/components/crawl-logs-panel";
import { LlmTestPanel } from "@/domains/system/components/llm-test-panel";
import { SchedulesPanel } from "@/domains/system/components/schedules-panel";
import { ConfigPanel } from "@/domains/system/components/config-panel";
import { NotifyPanel } from "@/domains/system/components/notify-panel";
import { WatchlistPanel } from "@/domains/stock/components/watchlist-panel";
import { NewsSourceManager } from "@/domains/news/components/news-source-manager";
import { useAuth } from "@/shared/hooks/use-auth";
import { Loader2, Lock } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function SystemPage() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24 text-muted-foreground">
        <Loader2 className="mr-2 h-5 w-5 animate-spin" />
        불러오는 중…
      </div>
    );
  }

  // mutation 계열 패널 전체가 super 전용.
  if (!user?.is_super) {
    return (
      <div className="space-y-6">
        <h1 className="text-2xl font-bold">시스템 관리</h1>
        <div className="flex flex-col items-center gap-4 rounded-xl border border-border bg-card py-20 text-center">
          <Lock className="h-10 w-10 text-muted-foreground" />
          <div className="space-y-1">
            <p className="text-lg font-semibold">관리자 권한이 필요합니다</p>
            <p className="text-sm text-muted-foreground">
              시스템 관리는 superuser 계정으로 로그인한 사용자만 접근할 수 있어요.
            </p>
          </div>
          <Button
            nativeButton={false}
            render={<a href="/api/auth/login" />}
            className="cursor-pointer"
          >
            Google로 로그인
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">시스템 관리</h1>

      <Tabs defaultValue="health">
        <TabsList>
          <TabsTrigger value="health">헬스 체크</TabsTrigger>
          <TabsTrigger value="crawl-logs">크롤링 로그</TabsTrigger>
          <TabsTrigger value="llm-test">LLM 테스트</TabsTrigger>
          <TabsTrigger value="schedules">스케줄</TabsTrigger>
          <TabsTrigger value="config">config</TabsTrigger>
          <TabsTrigger value="notify">발신 라우팅</TabsTrigger>
          <TabsTrigger value="watchlist">주식 테마</TabsTrigger>
          <TabsTrigger value="news-sources">뉴스 소스</TabsTrigger>
        </TabsList>
        <TabsContent value="health">
          <HealthPanel />
        </TabsContent>
        <TabsContent value="crawl-logs">
          <CrawlLogsPanel />
        </TabsContent>
        <TabsContent value="llm-test">
          <LlmTestPanel />
        </TabsContent>
        <TabsContent value="schedules">
          <SchedulesPanel />
        </TabsContent>
        <TabsContent value="config">
          <ConfigPanel />
        </TabsContent>
        <TabsContent value="notify">
          <NotifyPanel />
        </TabsContent>
        <TabsContent value="watchlist">
          <WatchlistPanel />
        </TabsContent>
        <TabsContent value="news-sources">
          <NewsSourceManager />
        </TabsContent>
      </Tabs>
    </div>
  );
}
