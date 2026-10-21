import assert from "node:assert/strict";
import { test } from "node:test";
import {
  LEVELS,
  chord,
  flagsPlaced,
  newField,
  placeMines,
  reveal,
  toggleFlag,
} from "../src/domains/game/minesweeper/engine.ts";

const at = (field, row, col) => row * field.cols + col;

/** 지정한 칸에 지뢰를 둔 진행 중 판 */
function fieldWith(rows, cols, mineCells) {
  const f = newField({ rows, cols, mines: mineCells.length });
  const mines = new Set(mineCells.map(([r, c]) => r * cols + c));
  return placeMines(f, -1, () => 0, mines);
}

test("난이도는 표준 3단계", () => {
  assert.deepEqual(LEVELS.beginner, { rows: 9, cols: 9, mines: 10 });
  assert.deepEqual(LEVELS.intermediate, { rows: 16, cols: 16, mines: 40 });
  assert.deepEqual(LEVELS.expert, { rows: 16, cols: 30, mines: 99 });
});

test("첫 클릭한 칸과 주변 8칸에는 지뢰가 없다", () => {
  for (let seed = 0; seed < 20; seed++) {
    let n = seed;
    const random = () => ((n = (n * 9301 + 49297) % 233280) / 233280);
    const f = reveal(newField(LEVELS.beginner), 40, random);
    const mines = f.cells.filter((c) => c.mine).length;
    assert.equal(mines, 10);
    for (const r of [3, 4, 5]) for (const c of [3, 4, 5]) assert.equal(f.cells[r * 9 + c].mine, false);
    assert.notEqual(f.status, "lost");
  }
});

test("0인 칸을 열면 이어진 빈 칸과 그 경계 숫자까지 연다", () => {
  // 3x3, 오른쪽 아래 모서리에만 지뢰
  const f = reveal(fieldWith(3, 3, [[2, 2]]), 0);
  const opened = f.cells.filter((c) => c.open).length;
  assert.equal(opened, 8);
  assert.equal(f.cells[at(f, 1, 1)].adjacent, 1);
  assert.equal(f.status, "won", "지뢰 아닌 칸을 다 열면 이긴다");
});

test("지뢰를 열면 지고 모든 지뢰가 드러난다", () => {
  const f = reveal(fieldWith(3, 3, [[0, 0], [2, 2]]), at({ cols: 3 }, 0, 0));
  assert.equal(f.status, "lost");
  assert.equal(f.cells[8].open, true);
});

test("깃발 꽂힌 칸은 열리지 않고, 깃발은 다시 누르면 빠진다", () => {
  let f = fieldWith(3, 3, [[0, 0]]);
  f = toggleFlag(f, 0);
  assert.equal(flagsPlaced(f), 1);
  assert.equal(reveal(f, 0).status, "playing");
  f = toggleFlag(f, 0);
  assert.equal(flagsPlaced(f), 0);
});

test("숫자 칸을 누르면 주변 깃발 수가 맞을 때 나머지를 연다", () => {
  let f = fieldWith(3, 3, [[0, 0]]);
  f = reveal(f, at(f, 1, 1));
  assert.equal(f.cells[at(f, 1, 1)].adjacent, 1);
  const before = chord(f, at(f, 1, 1));
  assert.equal(before, f, "깃발 수가 모자라면 아무것도 안 한다");
  f = toggleFlag(f, 0);
  f = chord(f, at(f, 1, 1));
  assert.equal(f.status, "won");
});

test("틀린 깃발로 주변을 열면 진다", () => {
  let f = fieldWith(3, 3, [[0, 0]]);
  f = reveal(f, at(f, 1, 1));
  f = toggleFlag(f, at(f, 0, 1));
  f = chord(f, at(f, 1, 1));
  assert.equal(f.status, "lost");
});
