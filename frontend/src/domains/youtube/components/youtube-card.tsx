"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Eye, ThumbsUp, MessageSquare, ExternalLink } from "lucide-react";
import type { YouTubeVideo } from "../types";

interface YouTubeCardProps {
  video: YouTubeVideo;
}

function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, "")}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1).replace(/\.0$/, "")}K`;
  return n.toLocaleString();
}

export function YouTubeCard({ video }: YouTubeCardProps) {
  return (
    <a
      href={`https://www.youtube.com/watch?v=${video.video_id}`}
      target="_blank"
      rel="noopener noreferrer"
      className="block"
    >
      <Card className="cursor-pointer transition-all duration-200 hover:shadow-md">
        {video.thumbnail_url && (
          <img
            src={video.thumbnail_url}
            alt={video.title}
            className="aspect-video w-full object-cover rounded-t-lg"
          />
        )}
        <CardHeader className="pb-2">
          <CardTitle className="text-base font-semibold line-clamp-2">
            {video.title}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <p className="text-sm text-muted-foreground">{video.channel_title}</p>
          <div className="flex items-center gap-3 text-sm">
            <span className="flex items-center gap-1">
              <Eye className="h-4 w-4" />
              {formatCount(video.view_count)}
            </span>
            {video.like_count != null && (
              <span className="flex items-center gap-1">
                <ThumbsUp className="h-4 w-4" />
                {formatCount(video.like_count)}
              </span>
            )}
            {video.comment_count != null && (
              <span className="flex items-center gap-1">
                <MessageSquare className="h-4 w-4" />
                {formatCount(video.comment_count)}
              </span>
            )}
          </div>
          <div className="flex items-center justify-between">
            <p className="text-xs text-muted-foreground">
              {new Date(video.published_at).toLocaleDateString()}
            </p>
            <ExternalLink className="h-4 w-4 text-muted-foreground" />
          </div>
        </CardContent>
      </Card>
    </a>
  );
}
