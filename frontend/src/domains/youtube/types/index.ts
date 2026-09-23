export interface YouTubeVideo {
  id: number;
  video_id: string;
  title: string;
  channel_title: string;
  description: string | null;
  thumbnail_url: string | null;
  view_count: number;
  like_count: number | null;
  comment_count: number | null;
  published_at: string;
  fetched_at: string;
}

export interface YouTubeVideoParams {
  channel?: string;
  sort?: "view_count" | "published_at";
  order?: "desc" | "asc";
  limit?: number;
  page?: number;
}
