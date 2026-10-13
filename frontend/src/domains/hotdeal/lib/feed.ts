import type { FeedFilter, FeedbackKind, HotdealFeedItem } from "../types";

const SOURCE_LABELS: Record<string, string> = {
  ZOD: "알구더",
  FMKOREA: "FM코리아",
  ARCA: "아카라이브",
};

export function sourceLabel(source: string): string {
  return SOURCE_LABELS[source] ?? source;
}

// 0원은 공짜딜이라 "무료"로 보여준다. 가격을 알 수 없으면 빈 문자열.
export function formatPrice(price: number | null): string {
  if (price === null) return "";
  if (price === 0) return "무료";
  return `${price.toLocaleString("ko-KR")}원`;
}

// 핫딜은 분 단위로 신선도가 중요해 "N분 전"까지 표시한다. 파싱 실패 시 빈 문자열.
export function formatRelativeTime(dateStr: string, now: number = Date.now()): string {
  const time = new Date(dateStr).getTime();
  if (Number.isNaN(time)) return "";
  const minutes = Math.max(0, Math.floor((now - time) / 60_000));
  if (minutes < 1) return "방금";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}

// 같은 피드백을 다시 누르면 취소(null)한다.
export function nextFeedback(
  current: FeedbackKind | null,
  clicked: FeedbackKind,
): FeedbackKind | null {
  return current === clicked ? null : clicked;
}

// 피드백 변경 후에도 현재 필터에 남는지. 서버 필터 규칙과 맞춘다.
// default: 싫어요 제외(좋아요는 추천 조건과 무관하게 유지, 피드백 없으면 active 만)
export function belongsToFilter(
  item: Pick<HotdealFeedItem, "active" | "feedback">,
  filter: FeedFilter,
): boolean {
  switch (filter) {
    case "hidden":
      return item.feedback === "DISLIKE";
    case "liked":
      return item.feedback === "LIKE";
    default:
      return item.feedback === "LIKE" || (item.feedback === null && item.active);
  }
}

// 피드백을 화면에 즉시 반영한다. 필터에서 벗어난 항목은 목록에서 뺀다.
export function applyFeedback(
  items: HotdealFeedItem[],
  id: number,
  feedback: FeedbackKind | null,
  filter: FeedFilter,
): HotdealFeedItem[] {
  return items
    .map((item) => (item.id === id ? { ...item, feedback } : item))
    .filter((item) => item.id !== id || belongsToFilter(item, filter));
}
