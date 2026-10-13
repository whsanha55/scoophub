import assert from "node:assert/strict";
import { test } from "node:test";
import { filterWeeks, formatReturn, formatSigma } from "../src/domains/stock/lib/sigma.ts";

test("σ 위치는 부호와 소수 둘째 자리로 표시한다", () => {
  assert.equal(formatSigma(0.564), "+0.56σ");
  assert.equal(formatSigma(-1.2), "−1.20σ");
  assert.equal(formatSigma(0), "0.00σ");
});

test("주간 마감 표 필터는 당시 σ 가 +1 이상 / −1 이하인 주만 남긴다", () => {
  const weeks = ["NORMAL", "UPPER_BREAK", "UPPER_BREAK", "LOWER_BREAK", "NORMAL"].map((sigma_state, i) => ({
    sigma_state,
    close_date: `d${i}`,
  }));
  assert.deepEqual(filterWeeks(weeks, "UPPER").map((w) => w.close_date), ["d1", "d2"]);
  assert.deepEqual(filterWeeks(weeks, "LOWER").map((w) => w.close_date), ["d3"]);
  assert.equal(filterWeeks(weeks, "ALL").length, 5);
});

test("수익률은 부호를 붙이고 없으면 대시로 표시한다", () => {
  assert.equal(formatReturn(1.234), "+1.23%");
  assert.equal(formatReturn(-0.5), "-0.50%");
  assert.equal(formatReturn(null), "-");
});
