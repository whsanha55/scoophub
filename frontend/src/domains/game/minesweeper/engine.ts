// 지뢰찾기 순수 로직. 칸은 행 우선 1차원 배열(index = row * cols + col)로 둔다.

export interface Level {
  rows: number;
  cols: number;
  mines: number;
}

export type LevelId = "beginner" | "intermediate" | "expert";

export const LEVELS: Record<LevelId, Level> = {
  beginner: { rows: 9, cols: 9, mines: 10 },
  intermediate: { rows: 16, cols: 16, mines: 40 },
  expert: { rows: 16, cols: 30, mines: 99 },
};

export const LEVEL_LABEL: Record<LevelId, string> = { beginner: "초급", intermediate: "중급", expert: "고급" };

export interface Cell {
  mine: boolean;
  open: boolean;
  flag: boolean;
  /** 주변 8칸의 지뢰 수 */
  adjacent: number;
}

/** ready = 첫 클릭 전(지뢰 미배치) */
export type FieldStatus = "ready" | "playing" | "won" | "lost";

export interface Field extends Level {
  cells: Cell[];
  status: FieldStatus;
}

export function newField(level: Level): Field {
  const cells = Array.from({ length: level.rows * level.cols }, () => ({
    mine: false,
    open: false,
    flag: false,
    adjacent: 0,
  }));
  return { ...level, cells, status: "ready" };
}

export function neighbors(field: Level, index: number): number[] {
  const r = Math.floor(index / field.cols);
  const c = index % field.cols;
  const out: number[] = [];
  for (let dr = -1; dr <= 1; dr++) {
    for (let dc = -1; dc <= 1; dc++) {
      const nr = r + dr;
      const nc = c + dc;
      if ((dr || dc) && nr >= 0 && nr < field.rows && nc >= 0 && nc < field.cols) out.push(nr * field.cols + nc);
    }
  }
  return out;
}

/**
 * 지뢰를 놓고 진행 중으로 바꾼다. safeIndex 와 그 주변 8칸은 비운다.
 * fixed 를 주면 그 칸들에 그대로 놓는다(테스트용).
 */
export function placeMines(
  field: Field,
  safeIndex: number,
  random: () => number = Math.random,
  fixed?: Set<number>,
): Field {
  const mines = fixed ?? new Set<number>();
  if (!fixed) {
    const safe = new Set(safeIndex >= 0 ? [safeIndex, ...neighbors(field, safeIndex)] : []);
    const candidates = field.cells.map((_, i) => i).filter((i) => !safe.has(i));
    // 부분 Fisher–Yates 로 후보에서 지뢰 수만큼 뽑는다
    for (let k = 0; k < field.mines && k < candidates.length; k++) {
      const j = k + Math.floor(random() * (candidates.length - k));
      const picked = candidates[j] ?? 0;
      candidates[j] = candidates[k] ?? 0;
      candidates[k] = picked;
      mines.add(picked);
    }
  }
  const cells = field.cells.map((cell, i) => ({
    ...cell,
    mine: mines.has(i),
    adjacent: neighbors(field, i).filter((n) => mines.has(n)).length,
  }));
  return { ...field, cells, status: "playing" };
}

const isWon = (cells: Cell[]) => cells.every((c) => c.mine || c.open);

function openCells(field: Field, starts: number[]): Field {
  const cells = field.cells.map((c) => ({ ...c }));
  const stack = [...starts];
  let hitMine = false;
  while (stack.length > 0) {
    const i = stack.pop() ?? 0;
    const cell = cells[i];
    if (!cell || cell.open || cell.flag) continue;
    cell.open = true;
    if (cell.mine) hitMine = true;
    else if (cell.adjacent === 0) stack.push(...neighbors(field, i));
  }
  if (hitMine) {
    for (const c of cells) if (c.mine) c.open = true;
    return { ...field, cells, status: "lost" };
  }
  return { ...field, cells, status: isWon(cells) ? "won" : "playing" };
}

/** 칸을 연다. 첫 클릭이면 지뢰부터 놓는다. 열 수 없으면 같은 판을 돌려준다 */
export function reveal(field: Field, index: number, random: () => number = Math.random): Field {
  const cell = field.cells[index];
  if (field.status === "won" || field.status === "lost" || !cell || cell.open || cell.flag) return field;
  const started = field.status === "ready" ? placeMines(field, index, random) : field;
  return openCells(started, [index]);
}

/** 열린 숫자 칸 주변 깃발 수가 숫자와 같으면 나머지 주변 칸을 한 번에 연다 */
export function chord(field: Field, index: number): Field {
  const cell = field.cells[index];
  if (field.status !== "playing" || !cell?.open || cell.adjacent === 0) return field;
  const around = neighbors(field, index);
  const flags = around.filter((n) => field.cells[n]?.flag).length;
  if (flags !== cell.adjacent) return field;
  return openCells(field, around);
}

export function toggleFlag(field: Field, index: number): Field {
  const cell = field.cells[index];
  if (field.status === "won" || field.status === "lost" || !cell || cell.open) return field;
  const cells = field.cells.map((c, i) => (i === index ? { ...c, flag: !c.flag } : c));
  return { ...field, cells };
}

export function flagsPlaced(field: Field): number {
  return field.cells.filter((c) => c.flag).length;
}
