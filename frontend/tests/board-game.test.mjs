import assert from "node:assert/strict";
import { test } from "node:test";
import { Chess } from "chess.js";
import {
  CHESS_DEFAULT_RULE,
  NO_TIME_LIMIT,
  initClock,
  tickClock,
  clockAfterMove,
  isFlagged,
  isUntimed,
  formatClock,
} from "../src/domains/game/lib/time-control.ts";
import { chessEngine, hasMatingMaterial } from "../src/domains/game/chess/engine.ts";

// ---------- 시간 규칙 ----------

test("무제한 규칙은 시간패가 나지 않는다", () => {
  assert.equal(isUntimed(NO_TIME_LIMIT), true);
  const clock = initClock(NO_TIME_LIMIT);
  const ticked = tickClock(clock, NO_TIME_LIMIT, 999_999);
  assert.deepEqual(ticked, clock);
  assert.equal(isFlagged(ticked, NO_TIME_LIMIT), false);
});

test("기본 시간이 흐르고, 소진하면 초읽기로 넘어간다", () => {
  const rule = { baseMs: 10_000, byoYomiMs: 5_000, byoYomiCount: 2 };
  let clock = initClock(rule);
  clock = tickClock(clock, rule, 4_000);
  assert.equal(clock.baseLeftMs, 6_000);
  assert.equal(clock.inByoYomi, false);

  clock = tickClock(clock, rule, 6_000);
  assert.equal(clock.inByoYomi, true);
  assert.equal(clock.byoYomiLeftMs, 5_000); // 첫 초읽기 수는 전체 시간
  assert.equal(clock.byoYomiLeft, 2);
});

test("초읽기에서 수를 두면 다음 수 시간이 다시 채워진다", () => {
  const rule = { baseMs: 0, byoYomiMs: 10_000, byoYomiCount: 1 };
  let clock = initClock(rule);
  // base 0 → 이미 초읽기
  clock = tickClock(clock, rule, 1_000);
  assert.equal(clock.inByoYomi, true);
  assert.equal(clock.byoYomiLeftMs, 9_000);
  const afterMove = clockAfterMove(clock, rule);
  assert.equal(afterMove.byoYomiLeftMs, 10_000);
});

test("초읽기를 넘기면 횟수가 줄고, 다 넘기면 시간패다", () => {
  const rule = { baseMs: 0, byoYomiMs: 10_000, byoYomiCount: 1 };
  let clock = initClock(rule);
  clock = tickClock(clock, rule, 15_000); // 첫 수 10초 초과 → 넘김 1회 소비
  assert.equal(clock.byoYomiLeft, 0);
  assert.equal(clock.byoYomiLeftMs, 10_000);
  assert.equal(isFlagged(clock, rule), false);

  clock = tickClock(clock, rule, 10_000); // 다시 초과 → 넘길 횟수 없음
  assert.equal(isFlagged(clock, rule), true);
});

test("초읽기 없는 규칙은 기본 시간 소진 즉시 시간패다", () => {
  const rule = { baseMs: 5_000, byoYomiMs: 0, byoYomiCount: 0 };
  let clock = initClock(rule);
  clock = tickClock(clock, rule, 5_000);
  assert.equal(clock.baseLeftMs, 0);
  assert.equal(clock.inByoYomi, false);
  assert.equal(isFlagged(clock, rule), true);
});

test("시계 표기", () => {
  assert.equal(formatClock(600_000), "10:00");
  assert.equal(formatClock(CHESS_DEFAULT_RULE.byoYomiMs), "0:30");
  assert.equal(formatClock(3_600_000), "1:00:00");
});

// ---------- 체스 어댑터 ----------

test("초기 상태: 32개 기물, 백 차례, e2-e4 합법", () => {
  const state = chessEngine.initialState();
  assert.equal(chessEngine.pieces(state).size, 32);
  assert.equal(chessEngine.turn(state), "white");
  assert.ok(chessEngine.legalTargets(state, "e2").includes("e4"));
  assert.ok(chessEngine.legalTargets(state, "e4").length === 0);
  assert.equal(chessEngine.status(state).over, false);
});

test("수 적용: 기물 이동과 기보, 원본 상태 불변", () => {
  const state = chessEngine.initialState();
  const result = chessEngine.applyMove(state, "e2", "e4");
  assert.equal(result.notation, "e4");
  assert.equal(chessEngine.turn(result.state), "black");
  assert.equal(chessEngine.pieces(result.state).get("e4")?.type, "p");
  // 원본은 그대로
  assert.equal(chessEngine.turn(state), "white");
  assert.equal(chessEngine.pieces(state).get("e2")?.type, "p");
});

test("불법수는 거절된다", () => {
  const state = chessEngine.initialState();
  assert.equal(chessEngine.applyMove(state, "e2", "e5"), null);
  assert.equal(chessEngine.applyMove(state, "e1", "e2"), null);
});

test("체크메이트: 바보메이트 4수", () => {
  let state = chessEngine.initialState();
  for (const [from, to] of [
    ["f2", "f3"],
    ["e7", "e5"],
    ["g2", "g4"],
    ["d8", "h4"],
  ]) {
    const r = chessEngine.applyMove(state, from, to);
    state = r.state;
  }
  const status = chessEngine.status(state);
  assert.equal(status.over, true);
  assert.equal(status.winner, "black");
  assert.equal(status.reason, "체크메이트");
  assert.equal(chessEngine.inCheck(state), true);
  assert.deepEqual(chessEngine.notationHistory(state), ["f3", "e5", "g4", "Qh4#"]);
});

test("스테일메이트 판정", () => {
  const game = new Chess("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"); // 흑 왕 h8, 백 퀸 f7, 백왕 g6 → 스테일메이트
  const state = { game };
  const status = chessEngine.status(state);
  assert.equal(status.over, true);
  assert.equal(status.reason, "스테일메이트");
  assert.equal(status.winner, null);
});

test("무르기: 마지막 수가 사라진다", () => {
  let state = chessEngine.initialState();
  state = chessEngine.applyMove(state, "e2", "e4").state;
  const undone = chessEngine.undo(state);
  assert.equal(chessEngine.turn(undone), "white");
  assert.deepEqual(chessEngine.notationHistory(undone), []);
  assert.equal(chessEngine.undo(undone), null);
});

test("3회 반복 무승부", () => {
  let state = chessEngine.initialState();
  for (const [from, to] of [
    ["g1", "f3"],
    ["g8", "f6"],
    ["f3", "g1"],
    ["f6", "g8"],
    ["g1", "f3"],
    ["g8", "f6"],
    ["f3", "g1"],
    ["f6", "g8"],
  ]) {
    state = chessEngine.applyMove(state, from, to).state;
  }
  const status = chessEngine.status(state);
  assert.equal(status.over, true);
  assert.equal(status.reason, "3회 반복 무승부");
});

test("시간패 무승부 판정: 메이트할 기물이 없으면 무승부", () => {
  // 왕+기사1 or 왕+비숍1 or 왕뿐 → 불가
  assert.equal(hasMatingMaterial([[{ type: "k", color: "w" }, null], [null, null]].concat(Array.from({ length: 6 }, () => [null, null])), "w"), false);
  const boardWithKnight = Array.from({ length: 8 }, (_, r) =>
    r === 7 ? [{ type: "k", color: "w" }, { type: "n", color: "w" }] : [null, null],
  );
  assert.equal(hasMatingMaterial(boardWithKnight, "w"), false);
  const boardWithRook = Array.from({ length: 8 }, (_, r) =>
    r === 7 ? [{ type: "k", color: "w" }, { type: "r", color: "w" }] : [null, null],
  );
  assert.equal(hasMatingMaterial(boardWithRook, "w"), true);
  const boardWithBishops = Array.from({ length: 8 }, (_, r) =>
    r === 7 ? [{ type: "k", color: "w" }, { type: "b", color: "w" }, { type: "b", color: "w" }] : [null, null],
  );
  assert.equal(hasMatingMaterial(boardWithBishops, "w"), true);
});

test("시간패 무승부 판정: 시간이 다 된 쪽의 기물은 세지 않는다", () => {
  // given — 백은 왕+퀸, 흑은 왕뿐
  const board = Array.from({ length: 8 }, (_, r) =>
    r === 7
      ? [{ type: "k", color: "w" }, { type: "q", color: "w" }, { type: "k", color: "b" }]
      : [null, null, null],
  );
  // then — 백이 시간패해도 흑은 메이트할 기물이 없다
  assert.equal(hasMatingMaterial(board, "b"), false);
  assert.equal(hasMatingMaterial(board, "w"), true);
});
