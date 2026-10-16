import assert from "node:assert/strict";
import { test } from "node:test";
import {
  janggiEngine,
  janggiPass,
  janggiDeclareBigjang,
  createBoard,
  scoreOf,
  isKingAttacked,
  positionKey,
  SETUP_PATTERNS,
  PIECE_HANJA,
} from "../src/domains/game/janggi/engine.ts";

const key = (r, c) => `${r},${c}`;

/** 커스텀 판을 가진 상태를 만든다. */
function stateWithBoard(pieces, turn = "white") {
  const state = janggiEngine.initialState();
  state.board = createBoard({ cho: "마상마상", han: "마상마상" }).map((row) => row.map(() => null));
  for (const [r, c, type, side] of pieces) {
    state.board[r][c] = { type, side };
  }
  state.turn = turn;
  state.positionCounts = { [positionKey(state.board, turn)]: 1 };
  return state;
}

// ---------- 초기 상치 ----------

test("초기 상태: 32개 기물(궁 제외 기물 좌표 포함), 초가 선수", () => {
  const state = janggiEngine.initialState();
  assert.equal(janggiEngine.turn(state), "white");
  assert.equal(janggiEngine.pieces(state).size, 32);
  assert.equal(state.board[0][4].type, "K"); // 한 궁
  assert.equal(state.board[9][4].type, "K"); // 초 궁
  assert.equal(state.board[2][1].type, "C"); // 한 포
  assert.equal(state.board[7][1].type, "C"); // 초 포
});

test("상차림 4가지가 각기 다른 배치를 만든다", () => {
  const boards = SETUP_PATTERNS.map((p) =>
    janggiEngine.initialState({ cho: p, han: p }),
  );
  const signatures = boards.map((s) =>
    [1, 2, 6, 7].map((c) => s.board[9][c].type).join(""),
  );
  assert.equal(new Set(signatures).size, 4);
  assert.deepEqual(signatures[0].split(""), ["N", "B", "N", "B"]); // 마상마상: 왼쪽부터
  assert.equal(PIECE_HANJA.white.K, "楚");
  assert.equal(PIECE_HANJA.black.K, "漢");
});

// ---------- 기물 이동 ----------

test("마: 멱이 막히면 갈 수 없고, 초기 마는 두 곳만 갈 수 있다", () => {
  const state = janggiEngine.initialState();
  // 초 마 (9,1): 다리 (8,1) 비어 → (7,0),(7,2). 좌우 다리(9,2),(9,0)는 기물이 있어 막힘
  const targets = janggiEngine.legalTargetPos(state, { row: 9, col: 1 }).map((p) => key(p.row, p.col));
  assert.deepEqual(targets.sort(), ["7,0", "7,2"]);
});

test("상: 초기 상은 제 병과 포에 막혀 갈 곳이 없다", () => {
  const state = janggiEngine.initialState();
  const targets = janggiEngine.legalTargetPos(state, { row: 9, col: 2 });
  assert.equal(targets.length, 0);
});

test("상: 멱 2곳이 모두 비어야 이동한다", () => {
  // (5,4)에서 위-오른쪽 직진+대각: eye1(4,4), eye2(3,5) → 도착 (2,6)
  // 오른쪽 직진+대각: eye1(5,5), eye2(4,5) → 도착 (3,7)
  const open = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 4, "B", "white"],
    [4, 6, "P", "black"], // 오른쪽-위 대각 멱만 막는다
  ]);
  const t1 = janggiEngine.legalTargetPos(open, { row: 5, col: 4 });
  assert.ok(t1.some((p) => p.row === 2 && p.col === 6), "직진 멱은 열려 도착 가능");
  assert.ok(!t1.some((p) => p.row === 3 && p.col === 7), "대각 멱이 막혀 금지");

  const leg1 = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 4, "B", "white"],
    [4, 4, "P", "black"], // 첫 직진 멱만 막는다
  ]);
  const t2 = janggiEngine.legalTargetPos(leg1, { row: 5, col: 4 });
  assert.ok(!t2.some((p) => p.row === 2 && p.col === 6));
  assert.ok(!t2.some((p) => p.row === 2 && p.col === 2));
});

test("포: 멱 없이는 못 움직이고, 포를 넘거나 포를 잡을 수 없다", () => {
  // 초 포(5,1), 한 포(1,1)가 같은 열 — 사이에 멱이 없어 서로 접근 불가
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 1, "C", "white"],
    [1, 1, "C", "black"],
    [5, 5, "N", "white"],
  ]);
  const targets = janggiEngine.legalTargetPos(state, { row: 5, col: 1 });
  // 위 방향: 멱 없이는 못 나아가고, 포(1,1)를 공격하지도 못한다
  assert.ok(!targets.some((p) => p.row === 2 && p.col === 1));
  assert.ok(!targets.some((p) => p.row === 1 && p.col === 1));

  // 멱(마)을 넘어 빈 칸으로: (5,5) 마가 멱 → (5,6),(5,7),(5,8) 이동 가능
  assert.ok(targets.some((p) => p.row === 5 && p.col === 8));

  // 멱(사)을 넘어 공격: 초 포가 (5,1), 멱 (3,1) 사, 한 차 (1,1)
  const state2 = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 1, "C", "white"],
    [3, 1, "A", "black"],
    [1, 1, "R", "black"],
  ]);
  const t2 = janggiEngine.legalTargetPos(state2, { row: 5, col: 1 });
  assert.ok(t2.some((p) => p.row === 1 && p.col === 1), "멱을 넘어 차를 잡는다");
  assert.ok(t2.some((p) => p.row === 2 && p.col === 1), "멱 너머 빈 칸");
  assert.ok(!t2.some((p) => p.row === 4 && p.col === 1), "멱 앞 빈 칸은 못 간다");
});

test("차: 궁성 대각선에서 대각으로 움직인다", () => {
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [2, 3, "R", "white"], // 한 궁성 좌하단 대각점
  ]);
  const targets = janggiEngine.legalTargetPos(state, { row: 2, col: 3 });
  assert.ok(targets.some((p) => p.row === 1 && p.col === 4), "대각선으로 궁성 중앙");
  assert.ok(targets.some((p) => p.row === 0 && p.col === 5), "대각선으로 궁성 끝까지");
});

test("궁과 사: 궁성 안에서만, 대각선은 교차점에서만", () => {
  const state = stateWithBoard([
    [0, 4, "K", "black"], // 대각점 아님 → 직진만
    [9, 4, "K", "white"],
    [0, 3, "A", "black"], // 대각점 → (1,4) 대각 가능
  ]);
  state.turn = "black";
  const kingMoves = janggiEngine.legalTargetPos(state, { row: 0, col: 4 });
  assert.deepEqual(
    kingMoves.map((p) => key(p.row, p.col)).sort(),
    ["0,5", "1,4"],
    "궁은 궁성 안 직진만(사가 (0,3)에 있어 좌는 막힘)",
  );
  const aideMoves = janggiEngine.legalTargetPos(state, { row: 0, col: 3 });
  assert.ok(aideMoves.some((p) => p.row === 1 && p.col === 4), "사는 궁성 대각선");
});

test("졸병: 앞과 옆으로만, 궁성 대각선에서 전진 대각 가능", () => {
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 4, "P", "white"], // 초 병 — 전진은 위(-1)
  ]);
  const targets = janggiEngine.legalTargetPos(state, { row: 5, col: 4 });
  assert.deepEqual(
    targets.map((p) => key(p.row, p.col)).sort(),
    ["4,4", "5,3", "5,5"],
    "앞과 양옆. 뒤로는 못 간다",
  );
  assert.ok(!targets.some((p) => p.row === 6));
});

// ---------- 점수와 판정 ----------

test("점수: 기물 점수 합과 한 덤 1.5", () => {
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 0, "R", "white"],
    [5, 8, "P", "black"],
  ]);
  assert.equal(scoreOf(state, "white"), 13);
  assert.equal(scoreOf(state, "black"), 2 + 1.5);
});

test("빅장: 기물이 잡힌 뒤에만 부를 수 있고, 점수로 승패를 정한다", () => {
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [6, 2, "R", "white"],
    [6, 0, "P", "black"],
  ]);
  assert.equal(janggiDeclareBigjang(state), null);
  const moved = janggiEngine.applyMovePos(state, { row: 6, col: 2 }, { row: 6, col: 0 });
  assert.ok(moved, "차가 병을 잡는다");
  assert.ok(moved.state.capturedHappened);
  const result = janggiDeclareBigjang(moved.state);
  assert.ok(result);
  assert.equal(result.winner, "white");
  assert.ok(result.reason.includes("빅장"));
});

test("외통수: 궁이 장군을 피할 수 없으면 끝난다", () => {
  // 초 차(4,0)가 (0,0)으로 이동해 열 0에서 한 궁(0,4)을 묶고,
  // 초 차(2,4)가 궁의 아래 도주처 (1,4)를 커버한다.
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [4, 0, "R", "white"],
    [2, 4, "R", "white"],
  ]);
  const moved = janggiEngine.applyMovePos(state, { row: 4, col: 0 }, { row: 0, col: 0 });
  assert.ok(moved);
  const status = janggiEngine.status(moved.state);
  assert.equal(status.over, true);
  assert.equal(status.winner, "white");
  assert.equal(status.reason, "외통수");
});

test("장군 상태에서는 한수쉼을 못 둔다", () => {
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [4, 0, "R", "white"],
    [2, 4, "R", "white"],
  ]);
  const moved = janggiEngine.applyMovePos(state, { row: 4, col: 0 }, { row: 0, col: 0 });
  assert.ok(moved);
  assert.equal(janggiPass(moved.state), null);
  // 평시에는 한수쉼 가능
  const passed = janggiPass(state);
  assert.ok(passed);
  assert.equal(passed.notation, "한수쉼");
  assert.equal(janggiEngine.turn(passed.state), "black");
});

test("반복수: 동일 국면 3회는 금지된다", () => {
  // 초 31점(차2+마) — 30점 미만 예외에 해당하지 않는다
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 0, "R", "white"],
    [6, 4, "R", "white"],
    [6, 2, "N", "white"],
    [5, 8, "R", "black"],
    [3, 4, "P", "black"], // 초 차의 세로 장군을 막는다
  ]);
  const seq = [
    { f: [5, 0], t: [5, 1] },
    { f: [5, 8], t: [5, 7] },
    { f: [5, 1], t: [5, 0] },
    { f: [5, 7], t: [5, 8] },
  ];
  let s = state;
  for (const m of seq) {
    const r = janggiEngine.applyMovePos(s, { row: m.f[0], col: m.f[1] }, { row: m.t[0], col: m.t[1] });
    assert.ok(r, `1차 순환 ${m.f}→${m.t}`);
    s = r.state;
  }
  // 두 번째 순환을 돌다가, 초기 국면을 3회째 만드는 한 차의 복귀수가 막힌다
  const second = [
    { f: [5, 0], t: [5, 1] },
    { f: [5, 8], t: [5, 7] },
    { f: [5, 1], t: [5, 0] },
  ];
  for (const m of second) {
    const r = janggiEngine.applyMovePos(s, { row: m.f[0], col: m.f[1] }, { row: m.t[0], col: m.t[1] });
    assert.ok(r, `두 번째 순환 ${m.f}→${m.t}`);
    s = r.state;
  }
  const blocked = janggiEngine.applyMovePos(s, { row: 5, col: 7 }, { row: 5, col: 8 });
  assert.equal(blocked, null, "동일 국면 3회 등장 금지");
  // 다른 수(세로 이동)는 둘 수 있다
  const alt = janggiEngine.applyMovePos(s, { row: 5, col: 7 }, { row: 4, col: 7 });
  assert.ok(alt);
});

test("반복수 예외: 양쪽 모두 30점 미만이면 반복이 허용된다", () => {
  // 초: 궁+차(13), 한: 궁+병(2)+덤 → 둘 다 30점 미만
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 0, "R", "white"],
    [5, 8, "P", "black"],
  ]);
  const seq = [
    { f: [5, 0], t: [5, 1] },
    { f: [5, 8], t: [5, 7] },
    { f: [5, 1], t: [5, 0] },
    { f: [5, 7], t: [5, 8] },
    { f: [5, 0], t: [5, 1] },
    { f: [5, 8], t: [5, 7] },
    { f: [5, 1], t: [5, 0] },
    { f: [5, 7], t: [5, 8] },
    { f: [5, 0], t: [5, 1] },
  ];
  let s = state;
  for (const m of seq) {
    const r = janggiEngine.applyMovePos(s, { row: m.f[0], col: m.f[1] }, { row: m.t[0], col: m.t[1] });
    assert.ok(r, `저점수 반복 허용 ${m.f}→${m.t}`);
    s = r.state;
  }
});

test("200수 제한: 한수쉼 포함 200수에 도달하면 점수로 판정한다", () => {
  // 저점수(반복 허용) 상태로 한수쉼만 200번 반복
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [5, 0, "N", "white"],
    [5, 8, "P", "black"],
  ]);
  let s = state;
  for (let i = 0; i < 199; i++) {
    const r = janggiPass(s);
    assert.ok(r);
    s = r.state;
  }
  assert.equal(janggiEngine.status(s).over, false);
  const last = janggiPass(s);
  assert.ok(last);
  const status = janggiEngine.status(last.state);
  assert.equal(status.over, true);
  assert.equal(status.winner, "white"); // 초 마 5점 > 한 병+덤 3.5점
});

test("무르기: 마지막 수가 사라진다", () => {
  const state = janggiEngine.initialState();
  const moved = janggiEngine.applyMovePos(state, { row: 6, col: 4 }, { row: 5, col: 4 });
  assert.ok(moved, "초 병 전진");
  const undone = janggiEngine.undo(moved.state);
  assert.equal(undone.moves.length, 0);
  assert.equal(undone.board[6][4].type, "P");
  assert.equal(undone.board[5][4], null);
  assert.equal(janggiEngine.turn(undone), "white");
  assert.equal(janggiEngine.undo(state), null);
});

test("자기 장군 금지: 내수를 두고 내 궁이 잡히면 그 수는 불법이다", () => {
  // 한 차(5,4)가 초 사(8,4)에 막혀 있다. 사가 비키면 궁(9,4)이 잡힌다.
  const state = stateWithBoard([
    [0, 4, "K", "black"],
    [9, 4, "K", "white"],
    [8, 4, "A", "white"],
    [5, 4, "R", "black"],
    [6, 2, "N", "white"],
  ], "white");
  assert.equal(isKingAttacked(state.board, "white"), false);
  // 사를 옆으로 보내면 궁이 차에 잡히므로 불법
  const bad = janggiEngine.applyMovePos(state, { row: 8, col: 4 }, { row: 8, col: 3 });
  assert.equal(bad, null);
  // 궁을 지키는 수(마로 차를 막거나 공격)는 합법 — 마(6,2)가 (5,4) 차를 잡는다
  const good = janggiEngine.applyMovePos(state, { row: 6, col: 2 }, { row: 5, col: 4 });
  assert.ok(good);
});
