"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Star, GitFork, TrendingUp } from "lucide-react";
import type { GitHubTrendingRepo } from "../types";

interface GitHubTrendingCardProps {
  repo: GitHubTrendingRepo;
}

function formatStars(n: number): string {
  if (n >= 1_000) return `${(n / 1_000).toFixed(1).replace(/\.0$/, "")}k`;
  return n.toLocaleString();
}

const periodLabel: Record<string, string> = {
  daily: "today",
  weekly: "this week",
  monthly: "this month",
};

export function GitHubTrendingCard({ repo }: GitHubTrendingCardProps) {
  return (
    <a
      href={repo.url}
      target="_blank"
      rel="noopener noreferrer"
      className="block"
    >
    <Card
      className="cursor-pointer transition-all duration-200 hover:shadow-md"
    >
      <CardHeader className="pb-2">
        <CardTitle className="text-base font-semibold">{repo.fullname}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {repo.description && (
          <p className="text-sm text-muted-foreground line-clamp-2">
            {repo.description}
          </p>
        )}
        <div className="flex items-center gap-3 text-sm">
          {repo.language && <Badge variant="outline">{repo.language}</Badge>}
          <span className="flex items-center gap-1">
            <Star className="h-4 w-4 text-yellow-500" />
            {formatStars(repo.stars)}
          </span>
          <span className="flex items-center gap-1">
            <GitFork className="h-4 w-4" />
            {formatStars(repo.forks)}
          </span>
        </div>
        {repo.current_period_stars > 0 && (
          <div className="flex items-center gap-1 text-sm text-green-500 font-medium">
            <TrendingUp className="h-4 w-4" />
            {repo.current_period_stars.toLocaleString()} {periodLabel[repo.period] ?? "this period"}
          </div>
        )}
      </CardContent>
    </Card>
    </a>
  );
}
