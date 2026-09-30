import type { ApiResponse } from "@/shared/types";

export async function requestCrawl(crawler: string, fallbackMessage: string): Promise<void> {
  const response = await fetch(`/api/crawling/${encodeURIComponent(crawler)}`, {
    method: "POST",
  });
  const body: unknown = await response.json().catch(() => null);
  const payload = body as (Partial<ApiResponse<unknown>> & { detail?: unknown }) | null;

  if (!response.ok || payload?.success !== true) {
    const message = payload?.error?.message
      || (typeof payload?.detail === "string" ? payload.detail : null)
      || `${fallbackMessage} (HTTP ${response.status})`;
    throw new Error(message);
  }
}
