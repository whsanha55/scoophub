export interface HealthData {
  status: "ok" | "error";
}

export interface CrawlLog {
  id: number;
  crawler: string;
  status: "success" | "error";
  items_fetched: number;
  items_new: number;
  error_message: string | null;
  started_at: string;
  finished_at: string | null;
  crawler_detail: string;
}

export interface CrawlLogsParams {
  crawler?: string;
  crawler_detail?: string;
  limit?: number;
}

export interface LLMTestRequest {
  message: string;
  system?: string;
}

export interface LLMTestResponse {
  model: string;
  content: string;
}

// #41 — crawl_schedule 런타임 관리
export interface CrawlSchedule {
  crawler: string;
  job_id: string;
  interval_seconds: number | null;
  cron_expr: string | null;
  is_active: boolean;
  last_run_at: string | null;
  next_run_at: string | null;
}

export interface CrawlSchedulePatch {
  interval_seconds?: number | null;
  cron_expr?: string | null;
  is_active?: boolean;
}

// #42 — crawl_config 런타임 관리
export interface CrawlConfigEntry {
  crawler: string;
  params: Record<string, unknown>;
}

export type CrawlConfigPatch = Record<string, unknown>;

// #46 — notify 발신 라우팅 + 발신 이력
export interface NotifyRoute {
  id: number;
  category: string;        // news/weather/stock/community/feed/kal_bonus, "" = wildcard
  purpose: string;         // 세부 목적, "" = wildcard
  channel: string;         // telegram | discord | email
  chat_id: string;
  topic_id: number | null; // null = 자동생성 대상
  topic_name: string;      // 자동생성용 이름, "" = 수동
  enabled: boolean;
  created_at: string;
  updated_at: string;
}

export interface NotifyRouteCreate {
  category?: string;
  purpose?: string;
  channel?: string;
  chat_id: string;
  topic_id?: number | null;
  topic_name?: string;
  enabled?: boolean;
}

export type NotifyRoutePatch = Partial<Omit<NotifyRouteCreate, "chat_id">> & { chat_id?: string };

export interface NotifyLog {
  id: number;
  route_id: number;
  payload_key: string;
  status: "success" | "error";
  error: string | null;
  sent_at: string;
}

export interface NotifyLogsParams {
  route_id?: number;
  status?: "success" | "error";
  limit?: number;
}

export interface NotifyTestResult {
  route_id: number;
  status: "success" | "error" | "unknown";
  error: string | null;
}
