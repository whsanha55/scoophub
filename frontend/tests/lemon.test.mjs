import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createLemonGrid,
  rectBounds,
  rectSum,
  inBounds,
  isClearableRect,
  clearRect,
  formatClock,
  LEMON_ROWS,
  LEMON_COLS,
} from "../src/domains/game/lib/lemon.ts";

test("격자 생성: 크기와 값 범위, 주입한 random으로 결정적 생성", () => {
  const grid = createLemonGrid();
  assert.equal(grid.length, LEMON_ROWS);
  assert.ok(grid.every((row) => row.length === LEMON_COLS));
  assert.ok(
    grid.every((row) => row.every((v) => Number.isInteger(v) && v >= 1 && v <= 9)),
  );

  let seed = 0;
  const fakeRandom = () => {
    seed = (seed + 1) % 9;
    return seed / 10; // 0.1 ~ 0.9 → 값 1~9 순환
  };
  const deterministic = createLemonGrid(2, 3, fakeRandom);
  assert.deepEqual(
    deterministic,
    [
      [1, 2, 3],
      [4, 5, 6],
    ],
  );
});

test("rectBounds: 두 칸으로 감싼 직사각형 경계(역방향 포함)", () => {
  assert.deepEqual(
    rectBounds({ row: 0, col: 0 }, { row: 2, col: 3 }),
    { r1: 0, r2: 2, c1: 0, c2: 3 },
  );
  assert.deepEqual(
    rectBounds({ row: 2, col: 3 }, { row: 0, col: 0 }),
    { r1: 0, r2: 2, c1: 0, c2: 3 },
  );
});

test("rectSum: 직사각형 합. 빈 칸(0)은 0으로 더해진다", () => {
  const grid = [
    [1, 2, 3],
    [9, 0, 1],
  ];
  assert.equal(rectSum(grid, { row: 0, col: 0 }, { row: 0, col: 1 }), 3);
  assert.equal(rectSum(grid, { row: 0, col: 0 }, { row: 1, col: 2 }), 16);
  assert.equal(rectSum(grid, { row: 1, col: 1 }, { row: 1, col: 1 }), 0);
  assert.equal(rectSum(grid, { row: 1, col: 0 }, { row: 1, col: 0 }), 9);
});

test("inBounds: 경계 포함 판정", () => {
  const rect = { r1: 1, r2: 2, c1: 3, c2: 5 };
  assert.equal(inBounds(rect, { row: 1, col: 3 }), true);
  assert.equal(inBounds(rect, { row: 2, col: 5 }), true);
  assert.equal(inBounds(rect, { row: 0, col: 4 }), false);
  assert.equal(inBounds(rect, { row: 2, col: 6 }), false);
});

test("isClearableRect: 합이 정확히 10일 때만 지울 수 있다", () => {
  const grid = [
    [1, 9],
    [2, 8],
  ];
  assert.equal(isClearableRect(grid, { row: 0, col: 0 }, { row: 0, col: 1 }), true);
  assert.equal(isClearableRect(grid, { row: 0, col: 0 }, { row: 1, col: 0 }), false);
  assert.equal(isClearableRect(grid, { row: 1, col: 0 }, { row: 1, col: 1 }), true);
});

test("clearRect: 레몬만 지우고 개수를 센다. 원본 불변", () => {
  const grid = [
    [1, 9],
    [2, 8],
    [0, 5],
  ];
  const { grid: next, cleared } = clearRect(grid, { row: 0, col: 0 }, { row: 2, col: 1 });
  assert.equal(cleared, 5); // 0은 세지 않는다
  assert.deepEqual(
    next,
    [
      [0, 0],
      [0, 0],
      [0, 0],
    ],
  );
  assert.equal(grid[0][0], 1); // 원본 유지
});

test("formatClock: 남은 시간 표시", () => {
  assert.equal(formatClock(120_000), "2:00");
  assert.equal(formatClock(119_999), "2:00"); // 올림
  assert.equal(formatClock(0), "0:00");
  assert.equal(formatClock(61_000), "1:01");
});
