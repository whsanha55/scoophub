export interface NewsletterArticle {
  id: number;
  title: string;
  url: string;
  author: string | null;
  newsletter_name: string;
  summary: string | null;
  tags: string[];
  published_at: string;
  fetched_at: string;
}

export interface NewsletterArticleParams {
  newsletter?: string;
  sort?: "published_at" | "fetched_at";
  order?: "desc" | "asc";
  limit?: number;
  page?: number;
}
