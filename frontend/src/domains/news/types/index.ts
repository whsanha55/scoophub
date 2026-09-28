export interface NewsArticle {
  id: number;
  source: string;
  category: string;
  title: string;
  summary: string;
  url: string;
  published_at: string;
  importance: number;
  summary_status: string;
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
}

export interface CrawlingResult {
  crawler: string;
  items_fetched: number;
  items_new: number;
  errors: string[] | null;
  summary?: {
    articles_processed: number;
    errors: string[] | null;
  };
}

export const NEWS_CATEGORIES = [
  "economy",
  "politics",
  "technology",
  "business",
  "science",
  "health",
  "world",
] as const;

export type NewsCategory = (typeof NEWS_CATEGORIES)[number];

export interface NewsSource {
  id: number;
  name: string;
  url: string;
  active: boolean;
  config: string | null;
  created_at: string;
  updated_at: string;
}

export interface NewsSourceCreateParams {
  name: string;
  url: string;
  active?: boolean;
  config?: string;
}

export interface NewsSourceUpdateParams {
  name?: string;
  url?: string;
  active?: boolean;
  config?: string;
}
