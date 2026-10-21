import assert from "node:assert/strict";
import { test } from "node:test";
import { addRandomTile, canMove, hasWon, move, newBoard, slideRow } from "../src/domains/game/2048/engine.ts";

test("한 줄을 왼쪽으로 밀면 같은 숫자가 한 번씩만 합쳐진다", () => {
  assert.deepEqual(slideRow([2, 2, 2, 2]), { row: [4, 4, 0, 0], gained: 8 });
  assert.deepEqual(slideRow([2, 0, 2, 4]), { row: [4, 4, 0, 0], gained: 4 });
  assert.deepEqual(slideRow([4, 4, 8, 0]), { row: [8, 8, 0, 0], gained: 8 });
  assert.deepEqual(slideRow([2, 4, 8, 16]), { row: [2, 4, 8, 16], gained: 0 });
});

test("네 방향으로 밀 수 있고, 아무것도 안 움직이면 moved=false", () => {
  const board = [
    [2, 0, 0, 2],
    [0, 0, 0, 0],
    [0, 4, 0, 0],
    [0, 4, 0, 0],
  ];
  assert.deepEqual(move(board, "left").board[0], [4, 0, 0, 0]);
  assert.deepEqual(move(board, "right").board[0], [0, 0, 0, 4]);
  assert.deepEqual(
    move(board, "down").board.map((r) => r[1]),
    [0, 0, 0, 8],
  );
  assert.deepEqual(
    move(board, "up").board.map((r) => r[1]),
    [8, 0, 0, 0],
  );
  assert.equal(move(board, "up").gained, 8);
  const stuck = [
    [2, 4, 0, 0],
    [0, 0, 0, 0],
    [0, 0, 0, 0],
    [0, 0, 0, 0],
  ];
  assert.equal(move(stuck, "left").moved, false);
  assert.equal(move(stuck, "right").moved, true);
});

test("새 타일은 빈 칸에 2(90%) 또는 4(10%)로 나온다", () => {
  const empty = Array.from({ length: 4 }, () => [0, 0, 0, 0]);
  const two = addRandomTile(empty, () => 0.5);
  assert.equal(two.flat().filter((v) => v === 2).length, 1);
  const four = addRandomTile(empty, () => 0.95);
  assert.equal(four.flat().filter((v) => v === 4).length, 1);
  assert.equal(newBoard(() => 0).flat().filter((v) => v > 0).length, 2);
});

test("움직일 수 없으면 끝, 2048 타일이 있으면 달성", () => {
  const full = [
    [2, 4, 2, 4],
    [4, 2, 4, 2],
    [2, 4, 2, 4],
    [4, 2, 4, 2],
  ];
  assert.equal(canMove(full), false);
  const mergeable = full.map((r) => [...r]);
  mergeable[0][1] = 2;
  assert.equal(canMove(mergeable), true);
  assert.equal(hasWon(full), false);
  full[3][3] = 2048;
  assert.equal(hasWon(full), true);
});
