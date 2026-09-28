export interface ArxivPaper {
  id: number;
  arxiv_id: string;
  title: string;
  authors: string[];
  summary: string | null;
  url: string;
  pdf_url: string | null;
  categories: string[];
  primary_category: string | null;
  published_at: string;
  fetched_at: string;
}

export interface ArxivPaperParams {
  category?: string;
  search?: string;
  sort?: "published_at" | "fetched_at";
  order?: "desc" | "asc";
  limit?: number;
  page?: number;
}
