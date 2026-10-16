// 레몬게임 순수 로직. UI 없이 단위 테스트로 검증한다.
// 격자: number[][] — 칸 값 1~9는 레몬, 0은 비어 있음.

export type Cell = { row: number; col: number };
export type Rect = { r1: number; r2: number; c1: number; c2: number };

export const LEMON_ROWS = 10;
export const LEMON_COLS = 17;
export const TARGET_SUM = 10;
export const TIME_LIMIT_MS = 120_000;

export function createLemonGrid(
  rows: number = LEMON_ROWS,
  cols: number = LEMON_COLS,
  random: () => number = Math.random,
): number[][] {
  const grid: number[][] = [];
  for (let r = 0; r < rows; r++) {
    const row: number[] = [];
    for (let c = 0; c < cols; c++) {
      row.push(1 + Math.floor(random() * 9));
    }
    grid.push(row);
  }
  return grid;
}

export function rectBounds(a: Cell, b: Cell): Rect {
  return {
    r1: Math.min(a.row, b.row),
    r2: Math.max(a.row, b.row),
    c1: Math.min(a.col, b.col),
    c2: Math.max(a.col, b.col),
  };
}

export function inBounds(rect: Rect, cell: Cell): boolean {
  return (
    cell.row >= rect.r1 &&
    cell.row <= rect.r2 &&
    cell.col >= rect.c1 &&
    cell.col <= rect.c2
  );
}

// 직사각형 안 숫자의 합. 빈 칸(0)은 합에 영향을 주지 않는다.
export function rectSum(grid: number[][], a: Cell, b: Cell): number {
  const { r1, r2, c1, c2 } = rectBounds(a, b);
  let sum = 0;
  for (let r = r1; r <= r2; r++) {
    for (let c = c1; c <= c2; c++) {
      sum += grid[r][c];
    }
  }
  return sum;
}

export function isClearableRect(grid: number[][], a: Cell, b: Cell): boolean {
  return rectSum(grid, a, b) === TARGET_SUM;
}

// 직사각형 안 레몬을 지운다. 원본은 바꾸지 않고 새 격자와 지운 개수를 반환한다.
export function clearRect(
  grid: number[][],
  a: Cell,
  b: Cell,
): { grid: number[][]; cleared: number } {
  const { r1, r2, c1, c2 } = rectBounds(a, b);
  const next = grid.map((row) => [...row]);
  let cleared = 0;
  for (let r = r1; r <= r2; r++) {
    for (let c = c1; c <= c2; c++) {
      if (next[r][c] !== 0) {
        next[r][c] = 0;
        cleared++;
      }
    }
  }
  return { grid: next, cleared };
}

export function formatClock(ms: number): string {
  const total = Math.ceil(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}
