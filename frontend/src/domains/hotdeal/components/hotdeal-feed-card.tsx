"use client";

import { ExternalLink, ThumbsDown, ThumbsUp } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { formatPrice, formatRelativeTime, nextFeedback, sourceLabel } from "../lib/feed";
import type { FeedbackKind, HotdealFeedItem } from "../types";

interface HotdealFeedCardProps {
  item: HotdealFeedItem;
  onFeedback: (id: number, feedback: FeedbackKind | null) => void;
}

export function HotdealFeedCard({ item, onFeedback }: HotdealFeedCardProps) {
  const price = formatPrice(item.price);
  const meta = [
    sourceLabel(item.source),
    item.mall,
    item.shipping_fee ? `배송 ${item.shipping_fee}` : null,
    formatRelativeTime(item.item_at),
  ].filter(Boolean);

  const click = (kind: FeedbackKind) => onFeedback(item.id, nextFeedback(item.feedback, kind));

  return (
    <Card className={cn("transition-colors duration-200", !item.active && "opacity-70")}>
      <CardContent className="space-y-2">
        <a
          href={item.url}
          target="_blank"
          rel="noopener noreferrer"
          className="block cursor-pointer space-y-1 hover:underline"
        >
          <p className="font-medium leading-snug break-words">{item.title}</p>
          {price && <p className="text-lg font-semibold">{price}</p>}
        </a>
        <p className="text-sm text-muted-foreground">{meta.join(" · ")}</p>
        <div className="flex flex-wrap items-center gap-1.5 text-sm">
          <Badge variant={item.popular ? "secondary" : "outline"}>{item.reason || "추천"}</Badge>
          {!item.active && <Badge variant="outline">추천 조건 벗어남</Badge>}
          {item.hidden_reason && <Badge variant="destructive">숨김: {item.hidden_reason}</Badge>}
          {item.votes > 0 && <span className="text-muted-foreground">추천 {item.votes}</span>}
          <span className="text-xs text-muted-foreground" title="추천 점수">
            점수 {item.score.toFixed(1)}
          </span>
        </div>
        <div className="flex items-center gap-1 pt-1">
          <Button
            variant={item.feedback === "LIKE" ? "default" : "outline"}
            size="sm"
            aria-pressed={item.feedback === "LIKE"}
            onClick={() => click("LIKE")}
            className="cursor-pointer"
          >
            <ThumbsUp /> 관심 있음
          </Button>
          <Button
            variant={item.feedback === "DISLIKE" ? "default" : "outline"}
            size="sm"
            aria-pressed={item.feedback === "DISLIKE"}
            onClick={() => click("DISLIKE")}
            className="cursor-pointer"
          >
            <ThumbsDown /> 관심 없음
          </Button>
          <a
            href={item.url}
            target="_blank"
            rel="noopener noreferrer"
            className={cn(buttonVariants({ variant: "ghost", size: "sm" }), "ml-auto cursor-pointer")}
          >
            <ExternalLink /> 원문
          </a>
        </div>
      </CardContent>
    </Card>
  );
}
