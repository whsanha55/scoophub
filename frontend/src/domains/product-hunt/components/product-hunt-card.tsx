"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ArrowUp, MessageCircle, ExternalLink } from "lucide-react";
import { parseJsonArray } from "@/lib/format";
import type { ProductHuntPost } from "../types";

interface ProductHuntCardProps {
  post: ProductHuntPost;
}

export function ProductHuntCard({ post }: ProductHuntCardProps) {
  const topics = parseJsonArray(post.topics);
  return (
    <a
      href={post.url}
      target="_blank"
      rel="noopener noreferrer"
      className="block"
    >
      <Card className="cursor-pointer transition-all duration-200 hover:shadow-md">
        <CardHeader className="pb-2">
          <div className="flex items-start justify-between gap-2">
            <CardTitle className="text-base font-semibold">{post.name}</CardTitle>
            <ExternalLink className="h-4 w-4 shrink-0 text-muted-foreground" />
          </div>
        </CardHeader>
        <CardContent className="space-y-2">
          {post.tagline && (
            <p className="text-sm text-muted-foreground line-clamp-2">
              {post.tagline}
            </p>
          )}
          <div className="flex items-center gap-3 text-sm text-muted-foreground">
            <span className="flex items-center gap-1">
              <ArrowUp className="h-3.5 w-3.5" />
              {post.votes_count}
            </span>
            <span className="flex items-center gap-1">
              <MessageCircle className="h-3.5 w-3.5" />
              {post.comments_count}
            </span>
          </div>
          {topics.length > 0 && (
            <div className="flex flex-wrap items-center gap-1">
              {topics.slice(0, 3).map((topic) => (
                <Badge key={topic} variant="outline">
                  {topic}
                </Badge>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </a>
  );
}
