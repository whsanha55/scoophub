import assert from "node:assert/strict";
import { test } from "node:test";
import {
  omokEngine,
  emptyBoard,
  forbiddenPoints,
  isForbidden,
  isWinningMove,
  winningLine,
  pointNotation,
  SIZE,
} from "../src/domains/game/omok/engine.ts";

const key = (r, c) => `${r},${c}`;

/** 흑(선수, white)·백(후수, black) 돌을 직접 깔고 차례를 맞춘 상태. moves 는 차례 계산용 자리만 채운다. */
function stateWith(firstStones, secondStones, turn = "white") {
  const board = emptyBoard();
  for (const [r, c] of firstStones) board[r][c] = "white";
  for (const [r, c] of secondStones) board[r][c] = "black";
  const count = firstStones.length + secondStones.length;
  const length = turn === "white" ? count + (count % 2) : count + ((count + 1) % 2);
  const moves = Array.from({ length }, () => ({ row: 0, col: 0, side: "white", notation: "" }));
  // 승부 판정은 마지막 수를 보므로, 마지막 수 자리는 판 밖(-1,-1)으로 두어 판정에 걸리지 않게 한다
  if (length > 0) moves[length - 1] = { row: -1, col: -1, side: turn === "white" ? "black" : "white", notation: "" };
  return { board, moves };
}

/** 번갈아 두며 진행한다. 실패하면 어느 수에서 막혔는지 알린다. */
function play(points) {
  let state = omokEngine.initialState();
  for (const [r, c] of points) {
    const moved = omokEngine.applyMove(state, "", key(r, c));
    assert.ok(moved, `둘 수 없는 수: ${key(r, c)}`);
    state = moved.state;
  }
  return state;
}

// 후수 돌을 승부와 무관한 먼 곳에 흩어 둔다
const FAR = [
  [0, 0],
  [0, 2],
  [0, 4],
  [0, 6],
  [0, 8],
  [0, 10],
];

// ---------- 기본 ----------

test("초기 상태: 빈 판, 선수(흑) 차례, 잡은 기물·장군 없음", () => {
  const state = omokEngine.initialState();
  assert.equal(omokEngine.turn(state), "white");
  assert.equal(omokEngine.pieces(state).size, 0);
  assert.deepEqual(omokEngine.capturedPieces(state), { white: [], black: [] });
  assert.equal(omokEngine.inCheck(state), false);
  assert.equal(omokEngine.legalTargets(state, "").length, SIZE * SIZE);
  assert.deepEqual(omokEngine.status(state), { over: false, winner: null, reason: null });
});

test("착수: from 은 쓰지 않고 to 에 돌을 놓는다. 표기는 열 A–O, 행은 아래부터 1–15", () => {
  const moved = omokEngine.applyMove(omokEngine.initialState(), "", "7,7");
  assert.equal(moved.notation, "H8");
  assert.equal(moved.state.board[7][7], "white");
  assert.equal(omokEngine.turn(moved.state), "black");
  assert.equal(pointNotation(0, 0), "A15");
  assert.equal(pointNotation(14, 14), "O1");
});

test("이미 돌이 있는 점·판 밖·잘못된 좌표에는 둘 수 없다", () => {
  const state = play([[7, 7]]);
  assert.equal(omokEngine.applyMove(state, "", "7,7"), null);
  assert.equal(omokEngine.applyMove(state, "", "15,0"), null);
  assert.equal(omokEngine.applyMove(state, "", "e4"), null);
});

// ---------- 승리 ----------

test("가로 5목이면 승리하고 승리 줄을 알려준다", () => {
  const state = play([
    [7, 3], FAR[0], [7, 4], FAR[1], [7, 5], FAR[2], [7, 6], FAR[3], [7, 7],
  ]);
  assert.deepEqual(omokEngine.status(state), { over: true, winner: "white", reason: "오목" });
  assert.deepEqual(winningLine(state), ["7,3", "7,4", "7,5", "7,6", "7,7"]);
  assert.equal(omokEngine.applyMove(state, "", "10,10"), null, "끝난 대국에는 더 둘 수 없다");
  assert.deepEqual(omokEngine.legalTargets(state, ""), []);
});

test("세로 5목 승리 (후수)", () => {
  const state = play([
    [14, 0], [3, 7], [14, 2], [4, 7], [14, 4], [5, 7], [14, 6], [6, 7], [12, 12], [7, 7],
  ]);
  assert.deepEqual(omokEngine.status(state), { over: true, winner: "black", reason: "오목" });
});

test("대각선 5목 승리 (양 방향)", () => {
  const down = play([[3, 3], FAR[0], [4, 4], FAR[1], [5, 5], FAR[2], [6, 6], FAR[3], [7, 7]]);
  assert.equal(omokEngine.status(down).winner, "white");
  const up = play([[7, 3], FAR[0], [6, 4], FAR[1], [5, 5], FAR[2], [4, 6], FAR[3], [3, 7]]);
  assert.equal(omokEngine.status(up).winner, "white");
});

test("4목은 아직 승리가 아니다", () => {
  const state = play([[7, 3], FAR[0], [7, 4], FAR[1], [7, 5], FAR[2], [7, 6]]);
  assert.equal(omokEngine.status(state).over, false);
});

test("후수(백)는 장목(6목 이상)도 승리한다", () => {
  const state = stateWith([], [[7, 2], [7, 3], [7, 4], [7, 6], [7, 7]], "black");
  const moved = omokEngine.applyMove(state, "", "7,5");
  assert.ok(moved);
  assert.deepEqual(omokEngine.status(moved.state), { over: true, winner: "black", reason: "오목" });
  assert.equal(winningLine(moved.state).length, 6);
});

test("바로 이기는 점: 선수(흑)는 정확히 5목만, 후수(백)는 장목도 이긴다. 판은 바꾸지 않는다", () => {
  const board = emptyBoard();
  for (const c of [2, 3, 4, 6, 7]) board[7][c] = "white";
  for (const c of [2, 3, 4, 6, 7]) board[9][c] = "black";
  for (const c of [3, 4, 5, 6]) board[11][c] = "white";
  const before = JSON.stringify(board);
  assert.equal(isWinningMove(board, 7, 5, "white"), false); // 장목
  assert.equal(isWinningMove(board, 9, 5, "black"), true);
  assert.equal(isWinningMove(board, 11, 7, "white"), true);
  assert.equal(isWinningMove(board, 11, 7, "black"), false);
  assert.equal(isWinningMove(board, 7, 2, "white"), false); // 이미 돌이 있는 점
  assert.equal(JSON.stringify(board), before);
});

// ---------- 금수 (선수만) ----------

test("3-3 금수: 열린 3이 두 줄 생기는 점", () => {
  const board = emptyBoard();
  for (const [r, c] of [[7, 5], [7, 6], [5, 7], [6, 7]]) board[r][c] = "white";
  assert.equal(isForbidden(board, 7, 7), true);
});

test("3-3 금수: 띈 3(X.XX)도 열린 3으로 센다", () => {
  const board = emptyBoard();
  for (const [r, c] of [[7, 4], [7, 6], [5, 7], [6, 7]]) board[r][c] = "white";
  assert.equal(isForbidden(board, 7, 7), true);
});

test("한쪽이 막힌 3은 열린 3이 아니므로 3-3이 아니다", () => {
  const board = emptyBoard();
  for (const [r, c] of [[7, 5], [7, 6], [5, 7], [6, 7]]) board[r][c] = "white";
  board[7][4] = "black"; // 가로 3의 왼쪽을 막는다: O X X [X] . .
  assert.equal(isForbidden(board, 7, 7), false);
});

test("4-3은 금수가 아니다: 4가 있는 줄은 3으로 세지 않는다", () => {
  const board = emptyBoard();
  for (const [r, c] of [[7, 4], [7, 5], [7, 6], [5, 7], [6, 7]]) board[r][c] = "white";
  assert.equal(isForbidden(board, 7, 7), false);
});

test("판 가장자리에 막힌 3은 열린 3이 아니므로 3-3이 아니다", () => {
  const board = emptyBoard();
  for (const [r, c] of [[2, 5], [2, 6], [0, 7], [1, 7]]) board[r][c] = "white";
  // 세로 0·1·2행 3은 위가 판 끝이라 열린 4가 될 수 없다
  assert.equal(isForbidden(board, 2, 7), false);
});

test("4-4 금수: 4가 두 줄 생기는 점 (막힌 4도 센다)", () => {
  const board = emptyBoard();
  for (const [r, c] of [[7, 4], [7, 5], [7, 6], [4, 7], [5, 7], [6, 7]]) board[r][c] = "white";
  board[7][3] = "black";
  assert.equal(isForbidden(board, 7, 7), true);
});

test("4-4 금수: 한 줄 안의 겹사 (X.X?X.X)", () => {
  const board = emptyBoard();
  for (const c of [3, 5, 7, 9]) board[7][c] = "white";
  // (7,6)에 두면 3·5·6·7 과 5·6·7·9 가 각각 4가 된다
  assert.equal(isForbidden(board, 7, 6), true);
});

test("장목 금수: 6목 이상이 되는 점", () => {
  const board = emptyBoard();
  for (const c of [2, 3, 4, 6, 7]) board[7][c] = "white";
  assert.equal(isForbidden(board, 7, 5), true);
});

test("정확히 5목이 되는 수는 4-4·3-3이 겹쳐도 금수가 아니고 승리한다", () => {
  const first = [[7, 3], [7, 4], [7, 5], [7, 6], [4, 7], [5, 7], [6, 7]];
  const board = emptyBoard();
  for (const [r, c] of first) board[r][c] = "white";
  assert.equal(isForbidden(board, 7, 7), false);
  const state = stateWith(first, [[0, 0], [0, 2], [0, 4], [0, 6], [0, 8], [0, 10], [0, 12]], "white");
  const moved = omokEngine.applyMove(state, "", "7,7");
  assert.ok(moved);
  assert.equal(omokEngine.status(moved.state).winner, "white");
});

test("금수 칸은 착수가 거부되고, 둘 수 있는 점에서 빠지며, 금수 목록에 나온다", () => {
  const state = stateWith([[7, 5], [7, 6], [5, 7], [6, 7]], [[0, 0], [0, 2], [0, 4], [0, 6]], "white");
  assert.equal(omokEngine.applyMove(state, "", "7,7"), null);
  assert.equal(omokEngine.legalTargets(state, "").includes("7,7"), false);
  assert.ok(forbiddenPoints(state).includes("7,7"));
});

test("후수(백)에게는 금수가 없다", () => {
  const state = stateWith([[0, 0], [0, 2], [0, 4], [0, 6], [0, 8]], [[7, 5], [7, 6], [5, 7], [6, 7]], "black");
  assert.deepEqual(forbiddenPoints(state), []);
  const moved = omokEngine.applyMove(state, "", "7,7");
  assert.ok(moved);
});

// ---------- 무승부·무르기 ----------

test("판이 가득 차면 무승부", () => {
  // 가로 2칸씩 번갈아, 줄마다 엇갈리는 무늬는 어느 방향으로도 5목이 없다
  const board = emptyBoard();
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) board[r][c] = (Math.floor(c / 2) + r) % 2 === 0 ? "white" : "black";
  }
  const moves = [];
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) moves.push({ row: r, col: c, side: board[r][c], notation: pointNotation(r, c) });
  }
  const state = { board, moves };
  assert.equal(winningLine(state), null);
  assert.deepEqual(omokEngine.status(state), { over: true, winner: null, reason: "판이 가득 참" });
});

test("무르기: 마지막 돌을 걷고 차례를 되돌린다", () => {
  const state = play([[7, 7], [7, 8]]);
  const undone = omokEngine.undo(state);
  assert.equal(undone.board[7][8], null);
  assert.equal(undone.board[7][7], "white");
  assert.equal(omokEngine.turn(undone), "black");
  assert.deepEqual(omokEngine.notationHistory(undone), ["H8"]);
  assert.equal(omokEngine.undo(omokEngine.initialState()), null);
});
