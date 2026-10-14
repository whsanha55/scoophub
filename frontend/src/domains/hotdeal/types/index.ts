export type FeedbackKind = "LIKE" | "DISLIKE";
export type FeedSort = "recommended" | "latest";
// default: 싫어요·판정 제외 추천 피드, hidden: 싫어요 + 관련 없음·제외 판정, liked: 좋아요한 항목
export type FeedFilter = "default" | "hidden" | "liked";
// EXCLUDE 는 제외 키워드 (가중치 없음)
export type ProfileKind = "KEYWORD" | "CATEGORY" | "SOURCE" | "EXCLUDE";

export interface MatchedTerm {
  kind: ProfileKind;
  value: string;
  weight: number;
}

export interface HotdealFeedItem {
  id: number;
  source: string;
  title: string;
  url: string;
  mall: string | null;
  price: number | null;
  shipping_fee: string | null;
  votes: number;
  item_at: string;
  recommended_at: string;
  score: number;
  popular: boolean;
  reason: string;
  matched_terms: MatchedTerm[];
  // false 면 재평가에서 추천 조건을 벗어난 항목 (좋아요로 남아 있는 경우)
  active: boolean;
  feedback: FeedbackKind | null;
  // 관련 없음·제외로 판정되어 숨겨진 항목의 사유
  hidden_reason: string | null;
}

export interface HotdealProfile {
  id: number;
  kind: ProfileKind;
  value: string;
  seed_weight: number;
  learned_weight: number;
  effective_weight: number;
}
