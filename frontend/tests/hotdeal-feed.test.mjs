import assert from "node:assert/strict";
import { test } from "node:test";
import {
  applyFeedback,
  formatPrice,
  formatRelativeTime,
  nextFeedback,
  sourceLabel,
  stepWeight,
} from "../src/domains/hotdeal/lib/feed.ts";

const item = (id, feedback = null, active = true, hidden_reason = null) => ({ id, feedback, active, hidden_reason });

test("가격은 원 단위로 쉼표를 넣고 0원은 무료, null 은 빈 문자열", () => {
  assert.equal(formatPrice(149000), "149,000원");
  assert.equal(formatPrice(0), "무료");
  assert.equal(formatPrice(null), "");
});

test("상대 시간은 분·시간·일 단위로 표시하고 잘못된 값은 빈 문자열", () => {
  const now = Date.parse("2026-10-14T03:00:00Z");
  assert.equal(formatRelativeTime("2026-10-14T02:59:40Z", now), "방금");
  assert.equal(formatRelativeTime("2026-10-14T02:40:00Z", now), "20분 전");
  assert.equal(formatRelativeTime("2026-10-14T00:00:00Z", now), "3시간 전");
  assert.equal(formatRelativeTime("2026-10-12T03:00:00Z", now), "2일 전");
  assert.equal(formatRelativeTime("not-a-date", now), "");
});

test("소스 코드는 한국어 이름으로, 모르는 값은 그대로", () => {
  assert.equal(sourceLabel("ZOD"), "알구더");
  assert.equal(sourceLabel("PPOMPPU"), "뽐뿌");
  assert.equal(sourceLabel("NEW"), "NEW");
});

test("같은 피드백을 다시 누르면 취소, 다른 피드백은 교체", () => {
  assert.equal(nextFeedback("LIKE", "LIKE"), null);
  assert.equal(nextFeedback("LIKE", "DISLIKE"), "DISLIKE");
  assert.equal(nextFeedback(null, "LIKE"), "LIKE");
});

test("기본 피드에서 싫어요한 항목은 바로 빠지고 좋아요는 남는다", () => {
  const items = [item(1), item(2)];
  assert.deepEqual(applyFeedback(items, 1, "DISLIKE", "default").map((i) => i.id), [2]);
  assert.deepEqual(applyFeedback(items, 1, "LIKE", "default")[0], item(1, "LIKE"));
});

test("기본 피드에서 추천 조건을 벗어난 좋아요 항목을 취소하면 빠진다", () => {
  const items = [item(1, "LIKE", false)];
  assert.deepEqual(applyFeedback(items, 1, null, "default"), []);
});

test("숨긴 항목에서 취소하면 빠지고, 좋아요 목록에서 좋아요를 취소하면 빠진다", () => {
  assert.deepEqual(applyFeedback([item(1, "DISLIKE")], 1, null, "hidden"), []);
  assert.deepEqual(applyFeedback([item(1, "LIKE")], 1, null, "liked"), []);
  assert.deepEqual(applyFeedback([item(1, "LIKE")], 1, "DISLIKE", "liked"), []);
});

test("판정에서 숨겨진 항목은 기본 피드가 아니라 숨긴 항목에 속하고, 좋아요하면 기본 피드로 온다", () => {
  const judged = item(1, null, true, "제외 키워드(중고)");
  assert.deepEqual(applyFeedback([judged], 1, null, "default"), []);
  assert.equal(applyFeedback([judged], 1, "LIKE", "hidden").length, 0);
  assert.equal(applyFeedback([judged], 1, "LIKE", "default").length, 1);
});

test("가중치는 0.5 단위로 0~5 안에서만 바뀐다", () => {
  assert.equal(stepWeight(3, 1), 3.5);
  assert.equal(stepWeight(0.2, -1), 0);
  assert.equal(stepWeight(4.8, 1), 5);
});
