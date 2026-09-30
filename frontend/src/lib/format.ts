// 카드 컴포넌트 공용 포맷 util.

// 백엔드가 배열을 JSON 문자열로 주거나 이미 배열로 줄 수 있음.
// unknown 입력을 안전하게 string[]로 변환.
export function parseJsonArray(field: unknown): string[] {
  let parsed: unknown = field;
  if (typeof field === "string") {
    try {
      parsed = JSON.parse(field);
    } catch {
      return [];
    }
  }
  return Array.isArray(parsed)
    ? parsed.filter((item): item is string => typeof item === "string")
    : [];
}

// 상대 시간 포맷("오늘"/"N일 전"/...).
// Invalid Date/NaN 가드 — 파싱 실패 시 빈 문자열 반환.
export function formatRelativeDate(dateStr: string): string {
  const date = new Date(dateStr);
  const time = date.getTime();
  if (Number.isNaN(time)) return "";
  const diffMs = Date.now() - time;
  const diffDays = Math.max(0, Math.floor(diffMs / (1000 * 60 * 60 * 24)));

  if (diffDays === 0) return "오늘";
  if (diffDays === 1) return "어제";
  if (diffDays < 30) return `${diffDays}일 전`;
  if (diffDays < 365) return `${Math.floor(diffDays / 30)}개월 전`;
  return `${Math.floor(diffDays / 365)}년 전`;
}
