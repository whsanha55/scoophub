import assert from "node:assert/strict";
import { test } from "node:test";
import { bandRuns, linePath, sliceRange, valueDomain } from "../src/domains/stock/lib/chart.ts";

function point(date, close, band = null) {
  return {
    date,
    close,
    sma50: null,
    sma200: null,
    sigma_upper: band ? band[1] : null,
    sigma_lower: band ? band[0] : null,
    sigma_anchor: band ? (band[0] + band[1]) / 2 : null,
    sigma_position: null,
    sigma_state: null,
    cross: null,
  };
}

test("기간 토글은 마지막 일봉 기준 N개월만 남긴다", () => {
  const points = [point("2026-06-30", 1), point("2026-07-14", 2), point("2026-08-14", 3)];
  assert.deepEqual(sliceRange(points, "1M").map((p) => p.date), ["2026-07-14", "2026-08-14"]);
  assert.equal(sliceRange(points, "1Y").length, 3);
  assert.deepEqual(sliceRange([], "3M"), []);
});

test("같은 주간 밴드가 이어지는 구간을 묶고 밴드 없는 날에서 끊는다", () => {
  const a = [95, 105];
  const b = [100, 110];
  const points = [point("d1", 1), point("d2", 1, a), point("d3", 1, a), point("d4", 1, b), point("d5", 1), point("d6", 1, b)];
  assert.deepEqual(bandRuns(points), [
    { start: 1, end: 2 },
    { start: 3, end: 3 },
    { start: 5, end: 5 },
  ]);
});

test("선 경로는 null 에서 끊는다", () => {
  assert.equal(linePath([1, null, 2, 3], (i) => i, (v) => v * 10), "M0.0,10.0M2.0,20.0L3.0,30.0");
});

test("y 범위는 종가와 밴드를 모두 담고 여백을 둔다", () => {
  const [min, max] = valueDomain([point("d1", 100, [90, 110])]);
  assert.equal(min, 89);
  assert.equal(max, 111);
});
