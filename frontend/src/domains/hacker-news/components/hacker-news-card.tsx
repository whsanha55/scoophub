"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ArrowUp, MessageSquare, ExternalLink } from "lucide-react";
import type { HackerNewsItem } from "../types";

interface HackerNewsCardProps {
  item: HackerNewsItem;
}

export function HackerNewsCard({ item }: HackerNewsCardProps) {
  const content = (
    <Card className="cursor-pointer transition-all duration-200 hover:shadow-md">
      <CardHeader className="pb-2">
        <div className="flex items-start justify-between gap-2">
          <CardTitle className="text-base font-semibold">{item.title}</CardTitle>
          {item.url && (
            <ExternalLink className="h-4 w-4 shrink-0 text-muted-foreground" />
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-sm text-muted-foreground">{item.author}</p>
        <div className="flex items-center gap-3 text-sm text-muted-foreground">
          <span className="flex items-center gap-1">
            <ArrowUp className="h-4 w-4" />
            {item.points}
          </span>
          <span className="flex items-center gap-1">
            <MessageSquare className="h-4 w-4" />
            {item.num_comments}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-1">
          <Badge variant="outline">{item.type}</Badge>
        </div>
      </CardContent>
    </Card>
  );

  if (item.url) {
    return (
      <a
        href={item.url}
        target="_blank"
        rel="noopener noreferrer"
        className="block"
      >
        {content}
      </a>
    );
  }

  return <div>{content}</div>;
}
