export interface NewsArticle {
  id: number;
  source: string;
  headline: string;
  summary: string | null;
  summary_ko: string | null;
  author: string | null;
  url: string | null;
  symbols: string[];
  published_at: string;
  source_updated_at: string;
  importance: number | null;
  category: string | null;
  status: "pending" | "filtered" | "pushed" | "skipped" | "failed";
  decision_reason: string | null;
  created_at: string;
  updated_at: string;
}

export interface NewsListParams {
  minutes?: number;
  from?: string;
  to?: string;
  category?: string;
  min_importance?: number;
  limit?: number;
  page?: number;
  symbol?: string;
}

export const NEWS_CATEGORIES = ["실적", "M&A", "거시", "규제", "기업", "기타"] as const;

export type NewsCategory = (typeof NEWS_CATEGORIES)[number];
