export interface ProductHuntPost {
  id: number;
  name: string;
  tagline: string | null;
  url: string;
  votes_count: number;
  comments_count: number;
  topics: string[];
  thumbnail_url: string | null;
  fetched_at: string;
  created_at: string;
}

export interface ProductHuntParams {
  topic?: string;
  sort?: "votes_count" | "created_at";
  order?: "desc" | "asc";
  limit?: number;
  page?: number;
}
