"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Heart, MessageCircle, Clock, ExternalLink } from "lucide-react";
import { parseJsonArray } from "@/lib/format";
import type { DevtoArticle } from "../types";

interface DevtoCardProps {
  article: DevtoArticle;
}

export function DevtoCard({ article }: DevtoCardProps) {
  const tags = parseJsonArray(article.tags);
  return (
    <a
      href={article.url}
      target="_blank"
      rel="noopener noreferrer"
      className="block"
    >
      <Card className="cursor-pointer transition-all duration-200 hover:shadow-md">
        <CardHeader className="pb-2">
          <div className="flex items-start justify-between gap-2">
            <CardTitle className="text-base font-semibold leading-snug line-clamp-2">
              {article.title}
            </CardTitle>
            <ExternalLink className="h-4 w-4 shrink-0 text-muted-foreground" />
          </div>
        </CardHeader>
        <CardContent className="space-y-2">
          {article.description && (
            <p className="text-sm text-muted-foreground line-clamp-3">
              {article.description}
            </p>
          )}
          <div className="flex items-center gap-3 text-sm text-muted-foreground">
            <span>{article.author}</span>
            <Badge variant="secondary">{article.source}</Badge>
          </div>
          {tags.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5">
            {tags.slice(0, 3).map((tag) => (
              <Badge key={tag} variant="outline" className="text-xs">
                {tag}
              </Badge>
            ))}
          </div>
          )}
          <div className="flex items-center gap-3 text-xs text-muted-foreground">
            <span className="flex items-center gap-1">
              <Heart className="h-3.5 w-3.5" />
              {article.positive_reactions_count}
            </span>
            <span className="flex items-center gap-1">
              <MessageCircle className="h-3.5 w-3.5" />
              {article.comments_count}
            </span>
            <span className="flex items-center gap-1">
              <Clock className="h-3.5 w-3.5" />
              {article.reading_time_minutes}min
            </span>
          </div>
        </CardContent>
      </Card>
    </a>
  );
}
