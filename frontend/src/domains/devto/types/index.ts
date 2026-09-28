export interface DevtoArticle {
  id: number;
  title: string;
  url: string;
  author: string;
  description: string | null;
  tags: string[];
  positive_reactions_count: number;
  comments_count: number;
  reading_time_minutes: number;
  published_at: string;
  fetched_at: string;
  source: "devto" | "hashnode";
}

export interface DevtoArticleParams {
  tag?: string;
  source?: "devto" | "hashnode";
  sort?: "published_at" | "positive_reactions_count";
  order?: "desc" | "asc";
  limit?: number;
  page?: number;
}
