export interface GitHubTrendingRepo {
  id: number;
  fullname: string;
  author: string;
  name: string;
  url: string;
  description: string | null;
  language: string | null;
  stars: number;
  forks: number;
  current_period_stars: number;
  period: "daily" | "weekly" | "monthly";
  fetched_at: string;
  created_at: string;
}

export interface GitHubTrendingParams {
  period?: "daily" | "weekly" | "monthly";
  language?: string;
  limit?: number;
}
