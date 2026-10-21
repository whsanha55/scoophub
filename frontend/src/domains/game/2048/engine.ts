// 2048 순수 로직. 판은 number[][] — 0은 빈 칸.

export type Board = number[][];
export type Direction = "up" | "down" | "left" | "right";

export const SIZE = 4;
export const WIN_TILE = 2048;
/** 새 타일이 4로 나올 확률 */
const FOUR_CHANCE = 0.1;

/** 한 줄을 왼쪽으로 민다. 같은 숫자는 한 번씩만 합쳐진다 */
export function slideRow(row: number[]): { row: number[]; gained: number } {
  const tiles = row.filter((v) => v > 0);
  const out: number[] = [];
  let gained = 0;
  for (let i = 0; i < tiles.length; i++) {
    const v = tiles[i] ?? 0;
    if (v === tiles[i + 1]) {
      out.push(v * 2);
      gained += v * 2;
      i++;
    } else {
      out.push(v);
    }
  }
  while (out.length < row.length) out.push(0);
  return { row: out, gained };
}

const transpose = (b: Board): Board => b[0]?.map((_, c) => b.map((r) => r[c] ?? 0)) ?? [];
const mirror = (b: Board): Board => b.map((r) => [...r].reverse());

/** 판을 한 방향으로 민다. 아무 타일도 움직이지 않으면 moved=false */
export function move(board: Board, dir: Direction): { board: Board; gained: number; moved: boolean } {
  // 모든 방향을 "왼쪽으로 밀기"로 바꿔 처리한 뒤 되돌린다
  const toLeft = { left: (b: Board) => b, right: mirror, up: transpose, down: (b: Board) => mirror(transpose(b)) }[dir];
  const fromLeft = { left: (b: Board) => b, right: mirror, up: transpose, down: (b: Board) => transpose(mirror(b)) }[dir];
  let gained = 0;
  const slid = toLeft(board).map((r) => {
    const res = slideRow(r);
    gained += res.gained;
    return res.row;
  });
  const next = fromLeft(slid);
  const moved = next.some((r, i) => r.some((v, j) => v !== board[i]?.[j]));
  return { board: next, gained, moved };
}

/** 빈 칸 하나에 2(90%) 또는 4(10%)를 놓는다 */
export function addRandomTile(board: Board, random: () => number = Math.random): Board {
  const empty: [number, number][] = [];
  board.forEach((r, i) => r.forEach((v, j) => v === 0 && empty.push([i, j])));
  const spot = empty[Math.floor(random() * empty.length)];
  if (!spot) return board;
  const value = random() < 1 - FOUR_CHANCE ? 2 : 4;
  return board.map((r, i) => r.map((v, j) => (i === spot[0] && j === spot[1] ? value : v)));
}

export function newBoard(random: () => number = Math.random): Board {
  const empty = Array.from({ length: SIZE }, () => Array<number>(SIZE).fill(0));
  return addRandomTile(addRandomTile(empty, random), random);
}

export function canMove(board: Board): boolean {
  return (["up", "down", "left", "right"] as const).some((d) => move(board, d).moved);
}

export function hasWon(board: Board): boolean {
  return board.some((r) => r.some((v) => v >= WIN_TILE));
}
