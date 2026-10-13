import assert from "node:assert/strict";
import { test } from "node:test";
import { filterWeeks, formatReturn, formatSigma, groupPeers, sigmaBoard } from "../src/domains/stock/lib/sigma.ts";

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

function report(ticker, position, state, movePct, group = "semi") {
  return {
    ticker,
    group,
    sigma: state ? { position, state, expected_move_pct: movePct } : null,
  };
}

test("σ 보드는 돌파·이탈을 σ 크기순으로, 기대변동은 상위 N개만 고른다", () => {
  const reports = [
    report("A", 1.2, "UPPER_BREAK", 3),
    report("B", 2.1, "UPPER_BREAK", 5),
    report("C", -1.5, "LOWER_BREAK", 9),
    report("D", 0.1, "NORMAL", 7),
    report("E", 0, null, 0),
  ];
  const board = sigmaBoard(reports, 2);
  assert.deepEqual(board.upper.map((r) => r.ticker), ["B", "A"]);
  assert.deepEqual(board.lower.map((r) => r.ticker), ["C"]);
  assert.deepEqual(board.topMove.map((r) => r.ticker), ["C", "D"]);
});

test("같은 그룹 종목은 자신과 σ 없는 종목을 빼고 σ 큰 순으로 고른다", () => {
  const reports = [
    report("NVDA", 0.5, "NORMAL", 4),
    report("AMD", 1.1, "UPPER_BREAK", 5),
    report("AAPL", 0.2, "NORMAL", 2, "bigtech"),
    report("INTC", 0, null, 0),
    report("MU", -0.3, "NORMAL", 6),
  ];
  assert.deepEqual(groupPeers(reports, "NVDA", "semi").map((r) => r.ticker), ["AMD", "MU"]);
  assert.deepEqual(groupPeers(reports, "NVDA", null), []);
});
