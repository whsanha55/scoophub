import assert from "node:assert/strict";
import { test } from "node:test";
import { computerMove } from "../src/domains/game/omok/computer.ts";
import { emptyBoard, isForbidden, omokEngine } from "../src/domains/game/omok/engine.ts";

const LEVELS = ["easy", "hard"];
const SEEDS = [1, 2, 3, 4, 5];

/** 시드로 고정한 난수 (mulberry32). 쉬움의 무작위 선택을 테스트마다 같게 만든다. */
function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

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

/** 모든 난이도와 여러 시드에서 같은 기대를 확인한다. */
function eachLevelAndSeed(fn) {
  for (const level of LEVELS) for (const seed of SEEDS) fn(level, seeded(seed), `${level}/seed ${seed}`);
}

// 승부와 무관한 먼 곳의 돌
const FAR = [
  [0, 0],
  [0, 2],
  [0, 4],
  [0, 6],
  [0, 8],
  [0, 10],
];

test("빈 판에서는 천원(H8)에 둔다", () => {
  eachLevelAndSeed((level, random) => {
    assert.equal(computerMove(omokEngine.initialState(), level, random), "7,7");
  });
});

test("둔 수는 언제나 규칙에 맞는 착수다", () => {
  eachLevelAndSeed((level, random, label) => {
    const state = stateWith([[7, 7]], [], "black");
    const point = computerMove(state, level, random);
    assert.ok(omokEngine.applyMove(state, "", point), label);
  });
});

// ---------- 바로 이기는 수 ----------

test("흑: 바로 이기는 수가 있으면 상대의 4를 막기보다 먼저 이긴다", () => {
  // 흑 4목 (7,3–6) 은 왼쪽이 막혀 (7,7) 만 5목. 백도 (3,3–6) 4목으로 (3,7) 에서 이긴다
  const state = stateWith(
    [[7, 3], [7, 4], [7, 5], [7, 6], [3, 2]],
    [[7, 2], [3, 3], [3, 4], [3, 5], [3, 6]],
    "white",
  );
  eachLevelAndSeed((level, random, label) => assert.equal(computerMove(state, level, random), "7,7", label));
});

test("백: 띈 4(XX.XX)의 빈 곳을 채워 이긴다", () => {
  // 흑도 (3,3–6) 4목으로 (3,7) 에서 이기려 한다
  const state = stateWith(
    [[3, 3], [3, 4], [3, 5], [3, 6], [12, 12]],
    [[5, 5], [6, 6], [8, 8], [9, 9], [3, 2]],
    "black",
  );
  eachLevelAndSeed((level, random, label) => assert.equal(computerMove(state, level, random), "7,7", label));
});

// ---------- 바로 막아야 하는 수 ----------

test("흑: 상대(백)가 바로 이기는 점을 막는다", () => {
  // 백 4목 (4,4–7) 은 왼쪽이 막혀 (4,8) 만 남는다
  const state = stateWith([[4, 3], [10, 10], [12, 2]], [[4, 4], [4, 5], [4, 6], [4, 7]], "white");
  eachLevelAndSeed((level, random, label) => assert.equal(computerMove(state, level, random), "4,8", label));
});

test("백: 상대(흑)가 바로 이기는 점을 막는다", () => {
  // 흑 띈 4 (6,5)(6,6)·(6,8)(6,9) 의 빈 곳 (6,7)
  const state = stateWith([[6, 5], [6, 6], [6, 8], [6, 9]], [[12, 12], [1, 1], [13, 2]], "black");
  eachLevelAndSeed((level, random, label) => assert.equal(computerMove(state, level, random), "6,7", label));
});

// ---------- 금수 (컴퓨터가 흑일 때) ----------

test("흑일 때 3-3 금수점에는 두지 않는다", () => {
  const state = stateWith([[7, 5], [7, 6], [5, 7], [6, 7]], FAR.slice(0, 4), "white");
  assert.equal(isForbidden(state.board, 7, 7), true);
  eachLevelAndSeed((level, random, label) => {
    const point = computerMove(state, level, random);
    assert.notEqual(point, "7,7", label);
    assert.ok(omokEngine.applyMove(state, "", point), label);
  });
});

test("흑일 때 4-4 금수점에는 두지 않는다", () => {
  const state = stateWith([[7, 3], [7, 4], [7, 5], [4, 6], [5, 6], [6, 6]], FAR, "white");
  assert.equal(isForbidden(state.board, 7, 6), true);
  eachLevelAndSeed((level, random, label) => {
    const point = computerMove(state, level, random);
    assert.notEqual(point, "7,6", label);
    assert.ok(omokEngine.applyMove(state, "", point), label);
  });
});

test("흑일 때 장목은 이기는 수가 아니고 금수라 두지 않는다", () => {
  const state = stateWith([[7, 2], [7, 3], [7, 4], [7, 6], [7, 7]], FAR.slice(0, 5), "white");
  eachLevelAndSeed((level, random, label) => {
    const point = computerMove(state, level, random);
    assert.notEqual(point, "7,5", label);
    assert.ok(omokEngine.applyMove(state, "", point), label);
  });
});

// ---------- 실력 ----------

/** 한 판을 끝까지 둔다. 모든 수가 엔진에서 받아들여져야 한다 (흑 컴퓨터의 금수 준수 포함). */
function playGame(firstLevel, secondLevel, random) {
  let state = omokEngine.initialState();
  let slowest = 0;
  while (!omokEngine.status(state).over) {
    const side = omokEngine.turn(state);
    const level = side === "white" ? firstLevel : secondLevel;
    const started = performance.now();
    const point = computerMove(state, level, random);
    if (level === "hard") slowest = Math.max(slowest, performance.now() - started);
    const moved = omokEngine.applyMove(state, "", point);
    assert.ok(moved, `${side}(${level}) 가 둘 수 없는 수: ${point} (수 ${state.moves.length + 1})`);
    state = moved.state;
  }
  return { winner: omokEngine.status(state).winner, moves: state.moves.length, slowest };
}

test("어려움이 쉬움을 흑백을 번갈아 10판 중 8판 이상 이긴다", (t) => {
  let hardWins = 0;
  for (let game = 0; game < 10; game++) {
    const hardSide = game % 2 === 0 ? "white" : "black";
    const result =
      hardSide === "white" ? playGame("hard", "easy", seeded(100 + game)) : playGame("easy", "hard", seeded(100 + game));
    if (result.winner === hardSide) hardWins++;
    t.diagnostic(
      `${game + 1}판 어려움=${hardSide === "white" ? "흑" : "백"} 승자=${result.winner ?? "무"} ${result.moves}수 어려움 최장 ${result.slowest.toFixed(0)}ms`,
    );
  }
  t.diagnostic(`어려움 ${hardWins}/10 승`);
  assert.ok(hardWins >= 8, `어려움 ${hardWins}/10 승`);
});
