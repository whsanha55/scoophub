"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ArrowLeft, ExternalLink, Clock, Flame } from "lucide-react";
import type { NewsArticle } from "../types";

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  return `${days}일 전`;
}

export function NewsDetail({
  article,
  onBack,
}: {
  article: NewsArticle;
  onBack?: () => void;
}) {
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2 mb-2">
          {onBack && (
            <Button
              variant="ghost"
              size="sm"
              onClick={onBack}
              className="cursor-pointer transition-colors duration-200"
            >
              <ArrowLeft className="h-4 w-4 mr-1" />
              뒤로
            </Button>
          )}
        </div>
        <CardTitle className="text-xl font-bold">{article.title}</CardTitle>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <Badge variant="secondary">{article.category}</Badge>
          <Badge variant="outline">{article.source}</Badge>
          <span className="flex items-center gap-1">
            <Flame className="h-3.5 w-3.5" />
            중요도 {article.importance}/5
          </span>
          <span className="flex items-center gap-1">
            <Clock className="h-3.5 w-3.5" />
            {timeAgo(article.published_at)}
          </span>
          <a
            href={article.url}
            target="_blank"
            rel="noopener noreferrer"
            className="ml-auto inline-flex items-center gap-1 rounded-md border px-3 py-1.5 text-sm font-medium cursor-pointer transition-colors duration-200 hover:bg-accent hover:text-accent-foreground"
          >
            원문 보기 <ExternalLink className="h-4 w-4" />
          </a>
        </div>
      </CardHeader>
      <CardContent>
        <div className="prose prose-sm dark:prose-invert max-w-none whitespace-pre-wrap">
          {article.summary}
        </div>
      </CardContent>
    </Card>
  );
}
