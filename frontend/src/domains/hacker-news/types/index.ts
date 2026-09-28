export interface HackerNewsItem {
  id: number;
  hn_id: number;
  title: string;
  url: string | null;
  author: string;
  points: number;
  num_comments: number;
  type: string;
  fetched_at: string;
  created_at: string;
}

export interface HackerNewsParams {
  type?: string;
  sort?: "points" | "created_at";
  order?: "desc" | "asc";
  limit?: number;
  page?: number;
}
