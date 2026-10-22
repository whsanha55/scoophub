// 오목 규칙 엔진. 보드게임 공통 인터페이스(BoardGameEngine)를 구현한다.
// 규칙: 렌주 표준 (이슈 #314, 재검토 #319). 15×15 판의 교차점에 돌을 놓는다.
// 좌표: "r,c" (row 0 위, col 0 왼쪽). 내부 Side white=선수(흑돌), black=후수(백돌).
// 선수(흑)만 금수가 있다: 3-3, 4-4, 장목(6목 이상). 정확히 5목이 되는 수는 금수보다 우선해 승리한다.
// 후수(백)는 5목 이상이면 승리한다.
import type { BoardGameEngine, GameStatus, PieceInfo, Side } from "../lib/board-game";

export const SIZE = 15;
/** 금수가 있는 선수(흑돌) */
export const FIRST: Side = "white";
const COLUMNS = "ABCDEFGHIJKLMNO";
const DIRECTIONS = [
  [0, 1],
  [1, 0],
  [1, 1],
  [1, -1],
] as const;
/** 열린 3 판정의 재귀 깊이 상한. 3을 4로 만드는 수가 금수인지 볼 때만 한 단계 더 들어간다. */
const MAX_DEPTH = 2;

export type Stone = Side | null;
export type Board = Stone[][];

export interface OmokMove {
  row: number;
  col: number;
  side: Side;
  notation: string;
}

export interface OmokState {
  board: Board;
  moves: OmokMove[];
}

const inside = (r: number, c: number) => r >= 0 && r < SIZE && c >= 0 && c < SIZE;

/** 한 방향 직선에서 (r,c)를 포함해 이어진 side 돌의 범위. [뒤로 몇 칸, 앞으로 몇 칸] */
function runBounds(board: Board, r: number, c: number, dr: number, dc: number, side: Side): [number, number] {
  let back = 0;
  while (inside(r - dr * (back + 1), c - dc * (back + 1)) && board[r - dr * (back + 1)][c - dc * (back + 1)] === side) back++;
  let fwd = 0;
  while (inside(r + dr * (fwd + 1), c + dc * (fwd + 1)) && board[r + dr * (fwd + 1)][c + dc * (fwd + 1)] === side) fwd++;
  return [back, fwd];
}

function runLength(board: Board, r: number, c: number, dr: number, dc: number, side: Side): number {
  const [back, fwd] = runBounds(board, r, c, dr, dc, side);
  return back + fwd + 1;
}

/** 이 길이로 이으면 그 진영이 이기는가. 선수는 정확히 5, 후수는 5 이상. */
function isWinningRun(length: number, side: Side): boolean {
  return side === FIRST ? length === 5 : length >= 5;
}

/**
 * 선수 돌이 (r,c)에 놓인 판에서, 한 방향으로 (r,c)를 포함한 정확한 5목을 만드는 빈 점들의 오프셋.
 * 오프셋 k는 (r,c)에서 그 방향으로 k칸 떨어진 점이다.
 */
function fiveCompletions(board: Board, r: number, c: number, dr: number, dc: number): number[] {
  const offsets: number[] = [];
  for (let k = -4; k <= 4; k++) {
    if (k === 0) continue;
    const qr = r + dr * k;
    const qc = c + dc * k;
    if (!inside(qr, qc) || board[qr][qc] !== null) continue;
    board[qr][qc] = FIRST;
    const [back, fwd] = runBounds(board, qr, qc, dr, dc, FIRST);
    // 이어진 범위가 (r,c)를 지나야 이 수가 만든 5목이다
    if (back + fwd + 1 === 5 && k - back <= 0 && 0 <= k + fwd) offsets.push(k);
    board[qr][qc] = null;
  }
  return offsets;
}

/** 한 방향의 4 개수. 열린 4(.XXXX.)는 완성점이 둘이어도 하나로 센다. 한 줄 안의 겹사(X.XXX.X)는 둘이다. */
function countFours(completions: number[]): number {
  if (completions.length === 2 && Math.abs(completions[0] - completions[1]) === 5) return 1;
  return completions.length;
}

/** 열린 4: 양 끝 어느 쪽에 두어도 5목이 되는 4 (.XXXX.) */
function isStraightFour(completions: number[]): boolean {
  return completions.length === 2 && Math.abs(completions[0] - completions[1]) === 5;
}

/** (r,c)의 선수 돌이 한 방향으로 열린 3인가: 금수가 아닌 한 수로 그 줄에 열린 4를 만들 수 있다. */
function isOpenThree(board: Board, r: number, c: number, dr: number, dc: number, depth: number): boolean {
  for (let k = -4; k <= 4; k++) {
    if (k === 0) continue;
    const qr = r + dr * k;
    const qc = c + dc * k;
    if (!inside(qr, qc) || board[qr][qc] !== null) continue;
    board[qr][qc] = FIRST;
    const straight = isStraightFour(fiveCompletions(board, r, c, dr, dc));
    board[qr][qc] = null;
    if (straight && (depth >= MAX_DEPTH || !isForbiddenAt(board, qr, qc, depth + 1))) return true;
  }
  return false;
}

function isForbiddenAt(board: Board, r: number, c: number, depth: number): boolean {
  board[r][c] = FIRST;
  try {
    const lengths = DIRECTIONS.map(([dr, dc]) => runLength(board, r, c, dr, dc, FIRST));
    if (lengths.some((n) => n === 5)) return false; // 5목 우선
    if (lengths.some((n) => n >= 6)) return true; // 장목
    const fours = DIRECTIONS.map(([dr, dc]) => countFours(fiveCompletions(board, r, c, dr, dc)));
    if (fours.reduce((a, b) => a + b, 0) >= 2) return true; // 4-4
    let threes = 0;
    DIRECTIONS.forEach(([dr, dc], i) => {
      if (fours[i] === 0 && isOpenThree(board, r, c, dr, dc, depth)) threes++;
    });
    return threes >= 2; // 3-3
  } finally {
    board[r][c] = null;
  }
}

/** 선수가 빈 점 (r,c)에 두면 금수인가. 판은 바꾸지 않는다. */
export function isForbidden(board: Board, r: number, c: number): boolean {
  if (!inside(r, c) || board[r][c] !== null) return false;
  return isForbiddenAt(board.map((row) => [...row]), r, c, 0);
}

/** side 가 빈 점 (r,c)에 두면 바로 이기는가. 선수는 정확히 5목, 후수는 5목 이상. 판은 바꾸지 않는다. */
export function isWinningMove(board: Board, r: number, c: number, side: Side): boolean {
  if (!inside(r, c) || board[r][c] !== null) return false;
  board[r][c] = side;
  try {
    return DIRECTIONS.some(([dr, dc]) => isWinningRun(runLength(board, r, c, dr, dc, side), side));
  } finally {
    board[r][c] = null;
  }
}

/** 근처(4칸 이내)에 선수 돌이 2개 이상 없으면 금수가 될 수 없다. 판 전체 계산을 줄이는 거름망. */
function mayBeForbidden(board: Board, r: number, c: number): boolean {
  let near = 0;
  for (const [dr, dc] of DIRECTIONS) {
    for (let k = -4; k <= 4; k++) {
      if (k === 0) continue;
      const qr = r + dr * k;
      const qc = c + dc * k;
      if (inside(qr, qc) && board[qr][qc] === FIRST && ++near >= 2) return true;
    }
  }
  return false;
}

export function emptyBoard(): Board {
  return Array.from({ length: SIZE }, () => Array<Stone>(SIZE).fill(null));
}

export function pointNotation(row: number, col: number): string {
  return `${COLUMNS[col]}${SIZE - row}`;
}

function parsePoint(point: string): [number, number] | null {
  const m = point.match(/^(\d+),(\d+)$/);
  if (!m) return null;
  const r = Number(m[1]);
  const c = Number(m[2]);
  return inside(r, c) ? [r, c] : null;
}

function turnOf(state: OmokState): Side {
  return state.moves.length % 2 === 0 ? "white" : "black";
}

/** 마지막 수가 만든 승리 줄. 없으면 null. */
export function winningLine(state: OmokState): string[] | null {
  const last = state.moves[state.moves.length - 1];
  if (!last) return null;
  for (const [dr, dc] of DIRECTIONS) {
    const [back, fwd] = runBounds(state.board, last.row, last.col, dr, dc, last.side);
    if (!isWinningRun(back + fwd + 1, last.side)) continue;
    const line: string[] = [];
    for (let k = -back; k <= fwd; k++) line.push(`${last.row + dr * k},${last.col + dc * k}`);
    return line;
  }
  return null;
}

/** 지금 차례가 선수일 때 금수인 빈 점. 후수 차례거나 대국이 끝났으면 빈 목록. */
export function forbiddenPoints(state: OmokState): string[] {
  if (turnOf(state) !== FIRST || omokEngine.status(state).over) return [];
  const board = state.board.map((row) => [...row]);
  const points: string[] = [];
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      if (board[r][c] === null && mayBeForbidden(board, r, c) && isForbiddenAt(board, r, c, 0)) {
        points.push(`${r},${c}`);
      }
    }
  }
  return points;
}

export const omokEngine: BoardGameEngine<OmokState> = {
  initialState() {
    return { board: emptyBoard(), moves: [] };
  },

  turn: turnOf,

  pieces(state) {
    const map = new Map<string, PieceInfo>();
    state.board.forEach((row, r) =>
      row.forEach((stone, c) => {
        if (stone) map.set(`${r},${c}`, { type: "stone", side: stone });
      }),
    );
    return map;
  },

  capturedPieces() {
    return { white: [], black: [] };
  },

  legalTargets(state) {
    if (omokEngine.status(state).over) return [];
    const forbidden = new Set(forbiddenPoints(state));
    const targets: string[] = [];
    for (let r = 0; r < SIZE; r++) {
      for (let c = 0; c < SIZE; c++) {
        const point = `${r},${c}`;
        if (state.board[r][c] === null && !forbidden.has(point)) targets.push(point);
      }
    }
    return targets;
  },

  /** 오목은 놓기만 하므로 from 을 쓰지 않는다. to 에 돌을 놓는다. */
  applyMove(state, _from, to) {
    const point = parsePoint(to);
    if (!point || omokEngine.status(state).over) return null;
    const [r, c] = point;
    if (state.board[r][c] !== null) return null;
    const side = turnOf(state);
    if (side === FIRST && isForbidden(state.board, r, c)) return null;
    const board = state.board.map((row) => [...row]);
    board[r][c] = side;
    const notation = pointNotation(r, c);
    return { state: { board, moves: [...state.moves, { row: r, col: c, side, notation }] }, notation };
  },

  undo(state) {
    const last = state.moves[state.moves.length - 1];
    if (!last) return null;
    const board = state.board.map((row) => [...row]);
    board[last.row][last.col] = null;
    return { board, moves: state.moves.slice(0, -1) };
  },

  status(state): GameStatus {
    const last = state.moves[state.moves.length - 1];
    if (last && winningLine(state)) return { over: true, winner: last.side, reason: "오목" };
    if (state.moves.length >= SIZE * SIZE) return { over: true, winner: null, reason: "판이 가득 참" };
    return { over: false, winner: null, reason: null };
  },

  inCheck() {
    return false;
  },

  notationHistory(state) {
    return state.moves.map((m) => m.notation);
  },
};
