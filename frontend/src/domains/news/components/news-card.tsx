"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ExternalLink, Clock, Flame } from "lucide-react";
import type { NewsArticle } from "../types";

function importanceColor(importance: number): string {
  if (importance >= 4) return "bg-red-500/15 text-red-600 dark:text-red-400 border-red-500/20";
  if (importance >= 2) return "bg-yellow-500/15 text-yellow-600 dark:text-yellow-400 border-yellow-500/20";
  return "bg-green-500/15 text-green-600 dark:text-green-400 border-green-500/20";
}

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  return `${days}일 전`;
}

export function NewsCard({
  article,
  onClick,
}: {
  article: NewsArticle;
  onClick?: (article: NewsArticle) => void;
}) {
  return (
    <Card
      className={`group cursor-pointer transition-all duration-200 hover:shadow-md ${onClick ? "" : ""}`}
      onClick={() => onClick?.(article)}
    >
      <CardHeader className="pb-2">
        <div className="flex items-start justify-between gap-2">
          <a
            href={article.url}
            target="_blank"
            rel="noopener noreferrer"
            onClick={(e) => e.stopPropagation()}
            className="min-w-0 cursor-pointer"
          >
            <CardTitle className="text-base font-semibold leading-snug line-clamp-2 transition-colors duration-200 hover:text-primary hover:underline decoration-primary underline-offset-2">
              {article.title}
            </CardTitle>
          </a>
          <a
            href={article.url}
            target="_blank"
            rel="noopener noreferrer"
            onClick={(e) => e.stopPropagation()}
            className="shrink-0 cursor-pointer rounded-md p-1.5 text-muted-foreground transition-colors duration-200 hover:text-primary hover:bg-accent"
            title="새창으로 보기"
          >
            <ExternalLink className="h-5 w-5" />
          </a>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-sm text-muted-foreground line-clamp-3">
          {article.summary}
        </p>
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <Badge variant="secondary" className="text-xs">
            {article.category}
          </Badge>
          <Badge variant="outline" className="text-xs">
            {article.source}
          </Badge>
          <Badge variant="outline" className={`text-xs ${importanceColor(article.importance)}`}>
            <Flame className="mr-1 h-3 w-3" />
            {article.importance}
          </Badge>
          <span className="flex items-center gap-1">
            <Clock className="h-3 w-3" />
            {timeAgo(article.published_at)}
          </span>
        </div>
      </CardContent>
    </Card>
  );
}
