import assert from "node:assert/strict";
import { test } from "node:test";
import { formatRelativeDate, parseJsonArray } from "../src/lib/format.ts";

test("문자열 배열과 JSON 문자열에서 문자열 원소만 반환한다", () => {
  assert.deepEqual(parseJsonArray(["AI", 1, null, { name: "test" }]), ["AI"]);
  assert.deepEqual(parseJsonArray('["AI", false, "Kotlin"]'), ["AI", "Kotlin"]);
});

test("잘못된 JSON과 배열이 아닌 값은 빈 배열을 반환한다", () => {
  for (const value of [null, undefined, 42, '{"name":"AI"}', "invalid", "null"]) {
    assert.deepEqual(parseJsonArray(value), []);
  }
});

test("상대 날짜는 파싱 오류와 미래 시각을 처리한다", () => {
  assert.equal(formatRelativeDate("invalid"), "");
  assert.equal(formatRelativeDate(new Date(Date.now() + 86400000).toISOString()), "오늘");
  assert.equal(formatRelativeDate(new Date(Date.now() - 86400000).toISOString()), "어제");
});
