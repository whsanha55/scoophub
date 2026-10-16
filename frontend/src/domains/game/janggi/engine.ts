// 장기 규칙 엔진. 보드게임 공통 인터페이스(BoardGameEngine)를 구현한다.
// 규정 기준: 대한장기연맹·한게임 장기 (이슈 #285).
// 좌표: {row, col}, row 0=한(후수) 위, row 9=초(선수) 아래, col 0..8.
// 내부 Side white=초(선), black=한(후). 한은 덤 1.5점.
import type { BoardGameEngine, GameStatus, PieceInfo, Side } from "../lib/board-game";

export type JanggiPieceType = "K" | "R" | "C" | "N" | "B" | "A" | "P";

export interface JanggiPiece {
  type: JanggiPieceType;
  side: Side;
}

export interface Pos {
  row: number;
  col: number;
}

export interface JanggiMove {
  side: Side;
  from: Pos;
  to: Pos;
  pass: boolean;
  notation: string;
}

export interface JanggiSetup {
  cho: string; // 예: "마상마상"
  han: string;
}

export interface JanggiState {
  board: (JanggiPiece | null)[][];
  turn: Side;
  moves: JanggiMove[];
  /** 동형 반복 판정용: (판면+차례) 등장 횟수 */
  positionCounts: Record<string, number>;
  /** 기물이 하나라도 잡혔는가 — 빅장 자격 */
  capturedHappened: boolean;
  setup: JanggiSetup;
  result: { winner: Side | null; reason: string } | null;
}

export const PIECE_SCORE: Record<JanggiPieceType, number> = {
  K: 0,
  R: 13,
  C: 7,
  N: 5,
  B: 3,
  A: 3,
  P: 2,
};

export const HAN_BONUS = 1.5;
export const MOVE_LIMIT = 200;
export const SETUP_PATTERNS = ["마상마상", "마상상마", "상마상마", "상마마상"] as const;
export const PIECE_HANJA: Record<Side, Record<JanggiPieceType, string>> = {
  white: { K: "楚", R: "車", C: "包", N: "馬", B: "象", A: "士", P: "卒" },
  black: { K: "漢", R: "車", C: "包", N: "馬", B: "象", A: "士", P: "兵" },
};
export const PIECE_NAME: Record<JanggiPieceType, string> = {
  K: "궁",
  R: "차",
  C: "포",
  N: "마",
  B: "상",
  A: "사",
  P: "병",
};

const ROWS = 10;
const COLS = 9;

export function posKey(p: Pos): string {
  return `${p.row},${p.col}`;
}

function inBoard(r: number, c: number): boolean {
  return r >= 0 && r < ROWS && c >= 0 && c < COLS;
}

/** 궁성 안인지. side 의 궁성. */
function inPalace(r: number, c: number, side: Side): boolean {
  if (c < 3 || c > 5) return false;
  return side === "black" ? r >= 0 && r <= 2 : r >= 7 && r <= 9;
}

/** 궁성 대각선 교차점인지 (어느 쪽 궁성이든). */
function isDiagPoint(r: number, c: number): boolean {
  const local = palaceLocal(r, c);
  if (local === null) return false;
  return (local[0] + local[1]) % 2 === 0;
}

function palaceLocal(r: number, c: number): [number, number] | null {
  if (c >= 3 && c <= 5) {
    if (r >= 0 && r <= 2) return [r, c - 3];
    if (r >= 7 && r <= 9) return [r - 7, c - 3];
  }
  return null;
}

function parseSetup(pattern: string): [JanggiPieceType, JanggiPieceType, JanggiPieceType, JanggiPieceType] {
  const chars = pattern.split("");
  if (chars.length !== 4 || !chars.every((ch) => ch === "마" || ch === "상")) {
    return ["N", "B", "B", "N"];
  }
  return chars.map((ch) => (ch === "마" ? "N" : "B")) as never;
}

export function createBoard(setup: JanggiSetup): (JanggiPiece | null)[][] {
  const board: (JanggiPiece | null)[][] = Array.from({ length: ROWS }, () =>
    Array.from({ length: COLS }, () => null),
  );
  const put = (r: number, c: number, type: JanggiPieceType, side: Side) => {
    board[r][c] = { type, side };
  };
  const [han1, han2, han3, han4] = parseSetup(setup.han);
  const [cho1, cho2, cho3, cho4] = parseSetup(setup.cho);
  // 한 (위, black)
  put(0, 0, "R", "black");
  put(0, 1, han1, "black");
  put(0, 2, han2, "black");
  put(0, 3, "A", "black");
  put(0, 4, "K", "black");
  put(0, 5, "A", "black");
  put(0, 6, han3, "black");
  put(0, 7, han4, "black");
  put(0, 8, "R", "black");
  put(2, 1, "C", "black");
  put(2, 7, "C", "black");
  for (const c of [0, 2, 4, 6, 8]) put(3, c, "P", "black");
  // 초 (아래, white)
  put(9, 0, "R", "white");
  put(9, 1, cho1, "white");
  put(9, 2, cho2, "white");
  put(9, 3, "A", "white");
  put(9, 4, "K", "white");
  put(9, 5, "A", "white");
  put(9, 6, cho3, "white");
  put(9, 7, cho4, "white");
  put(9, 8, "R", "white");
  put(7, 1, "C", "white");
  put(7, 7, "C", "white");
  for (const c of [0, 2, 4, 6, 8]) put(6, c, "P", "white");
  return board;
}

function cloneBoard(board: (JanggiPiece | null)[][]): (JanggiPiece | null)[][] {
  return board.map((row) => row.map((p) => (p ? { ...p } : null)));
}

function findKing(board: (JanggiPiece | null)[][], side: Side): Pos | null {
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const p = board[r][c];
      if (p && p.type === "K" && p.side === side) return { row: r, col: c };
    }
  }
  return null;
}

/** 국면 키 (판면+차례). 반복수 판정과 테스트에서 쓴다. */
export function positionKey(board: (JanggiPiece | null)[][], turn: Side): string {
  let s = turn === "white" ? "w" : "b";
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const p = board[r][c];
      if (!p) {
        s += ".";
        continue;
      }
      const sideCh = p.side === "white" ? "c" : "h";
      s += sideCh + p.type;
    }
  }
  return s;
}

/** 판면만으로 계산하는 진영 점수(덤 제외). 반복수 예외 판정용. */
function boardScore(board: (JanggiPiece | null)[][], side: Side): number {
  let sum = 0;
  for (const row of board) {
    for (const p of row) {
      if (p && p.side === side) sum += PIECE_SCORE[p.type];
    }
  }
  return sum;
}

/** [side] 진영 점수. 남은 기물 점수 합 + 한 덤. */
export function scoreOf(state: JanggiState, side: Side): number {
  let sum = 0;
  for (const row of state.board) {
    for (const p of row) {
      if (p && p.side === side) sum += PIECE_SCORE[p.type];
    }
  }
  return side === "black" ? sum + HAN_BONUS : sum;
}

// ---- 이동 생성 ----

function stepTargets(
  board: (JanggiPiece | null)[][],
  from: Pos,
  side: Side,
  steps: Pos[],
): Pos[] {
  const out: Pos[] = [];
  for (const s of steps) {
    const r = from.row + s.row;
    const c = from.col + s.col;
    if (!inBoard(r, c)) continue;
    const target = board[r][c];
    if (!target || target.side !== side) out.push({ row: r, col: c });
  }
  return out;
}

function palaceSteps(board: (JanggiPiece | null)[][], from: Pos, side: Side): Pos[] {
  const steps: Pos[] = [];
  for (const s of [
    { row: -1, col: 0 },
    { row: 1, col: 0 },
    { row: 0, col: -1 },
    { row: 0, col: 1 },
  ]) {
    const r = from.row + s.row;
    const c = from.col + s.col;
    if (inPalace(r, c, side)) steps.push(s);
  }
  // 대각선: 궁성 대각선 교차점에서만, 교차점끼리
  if (isDiagPoint(from.row, from.col)) {
    for (const s of [
      { row: -1, col: -1 },
      { row: -1, col: 1 },
      { row: 1, col: -1 },
      { row: 1, col: 1 },
    ]) {
      const r = from.row + s.row;
      const c = from.col + s.col;
      if (inPalace(r, c, side) && isDiagPoint(r, c)) steps.push(s);
    }
  }
  return stepTargets(board, from, side, steps);
}

function slideTargets(
  board: (JanggiPiece | null)[][],
  from: Pos,
  side: Side,
  dirs: Pos[],
  diagOnlyInPalace: boolean,
): Pos[] {
  const out: Pos[] = [];
  for (const d of dirs) {
    let r = from.row + d.row;
    let c = from.col + d.col;
    while (inBoard(r, c)) {
      if (diagOnlyInPalace || (d.row !== 0 && d.col !== 0)) {
        // 대각선 방향은 궁성 대각선 위로만 간다
        if (!isDiagPoint(r, c)) break;
        // 궁성 대각선 범위(같은 궁성)를 벗어나면 정지
        const fromLocal = palaceLocal(from.row, from.col);
        const toLocal = palaceLocal(r, c);
        if (!fromLocal || !toLocal) break;
        if ((fromLocal[0] <= 2 ? 0 : 7) !== (toLocal[0] <= 2 ? 0 : 7)) break;
      }
      const target = board[r][c];
      if (!target) {
        out.push({ row: r, col: c });
      } else {
        if (target.side !== side) out.push({ row: r, col: c });
        break;
      }
      r += d.row;
      c += d.col;
    }
  }
  return out;
}

function cannonTargets(board: (JanggiPiece | null)[][], from: Pos, side: Side): Pos[] {
  const out: Pos[] = [];
  const dirs: Pos[] = [
    { row: -1, col: 0 },
    { row: 1, col: 0 },
    { row: 0, col: -1 },
    { row: 0, col: 1 },
  ];
  if (isDiagPoint(from.row, from.col)) {
    dirs.push({ row: -1, col: -1 }, { row: -1, col: 1 }, { row: 1, col: -1 }, { row: 1, col: 1 });
  }
  for (const d of dirs) {
    let r = from.row + d.row;
    let c = from.col + d.col;
    const diagonal = d.row !== 0 && d.col !== 0;
    // 화포(멱) 찾기: 포는 멱이 될 수 없다
    let screen = false;
    while (inBoard(r, c)) {
      if (diagonal) {
        // 궁성 대각선 위에서만
        if (!isDiagPoint(r, c)) break;
        const fromLocal = palaceLocal(from.row, from.col);
        const toLocal = palaceLocal(r, c);
        if (!fromLocal || !toLocal) break;
        if ((fromLocal[0] <= 2 ? 0 : 7) !== (toLocal[0] <= 2 ? 0 : 7)) break;
      }
      const target = board[r][c];
      if (!screen) {
        if (target) {
          if (target.type === "C") break; // 포는 포를 넘지 못한다
          screen = true;
        }
      } else {
        if (!target) {
          out.push({ row: r, col: c });
        } else {
          if (target.side !== side && target.type !== "C") out.push({ row: r, col: c });
          break;
        }
      }
      r += d.row;
      c += d.col;
    }
  }
  return out;
}

function horseTargets(board: (JanggiPiece | null)[][], from: Pos, side: Side): Pos[] {
  const out: Pos[] = [];
  const legs: { move: Pos; leg: Pos }[] = [
    { move: { row: -2, col: -1 }, leg: { row: -1, col: 0 } },
    { move: { row: -2, col: 1 }, leg: { row: -1, col: 0 } },
    { move: { row: 2, col: -1 }, leg: { row: 1, col: 0 } },
    { move: { row: 2, col: 1 }, leg: { row: 1, col: 0 } },
    { move: { row: -1, col: -2 }, leg: { row: 0, col: -1 } },
    { move: { row: 1, col: -2 }, leg: { row: 0, col: -1 } },
    { move: { row: -1, col: 2 }, leg: { row: 0, col: 1 } },
    { move: { row: 1, col: 2 }, leg: { row: 0, col: 1 } },
  ];
  for (const { move, leg } of legs) {
    const legR = from.row + leg.row;
    const legC = from.col + leg.col;
    if (!inBoard(legR, legC) || board[legR][legC]) continue;
    const r = from.row + move.row;
    const c = from.col + move.col;
    if (!inBoard(r, c)) continue;
    const target = board[r][c];
    if (!target || target.side !== side) out.push({ row: r, col: c });
  }
  return out;
}

function elephantTargets(board: (JanggiPiece | null)[][], from: Pos, side: Side): Pos[] {
  const out: Pos[] = [];
  // 직진 1칸 + 같은 방향 대각선 2칸. 멱: 직진 칸, 대각 1칸.
  const routes: { eye1: Pos; eye2: Pos; to: Pos }[] = [];
  for (const d of [-1, 1]) {
    // 위/아래 직진 후 좌우 대각, 좌우 직진 후 상하 대각
    for (const v of [-1, 1]) {
      routes.push({
        eye1: { row: from.row + v, col: from.col },
        eye2: { row: from.row + 2 * v, col: from.col + d },
        to: { row: from.row + 3 * v, col: from.col + 2 * d },
      });
      routes.push({
        eye1: { row: from.row, col: from.col + d },
        eye2: { row: from.row + v, col: from.col + 2 * d },
        to: { row: from.row + 2 * v, col: from.col + 3 * d },
      });
    }
  }
  for (const route of routes) {
    if (
      inBoard(route.eye1.row, route.eye1.col) &&
      inBoard(route.eye2.row, route.eye2.col) &&
      inBoard(route.to.row, route.to.col) &&
      !board[route.eye1.row][route.eye1.col] &&
      !board[route.eye2.row][route.eye2.col]
    ) {
      const target = board[route.to.row][route.to.col];
      if (!target || target.side !== side) out.push(route.to);
    }
  }
  return out;
}

function soldierTargets(board: (JanggiPiece | null)[][], from: Pos, side: Side): Pos[] {
  const forward = side === "white" ? -1 : 1; // 초는 위로, 한은 아래로
  const steps: Pos[] = [
    { row: forward, col: 0 },
    { row: 0, col: -1 },
    { row: 0, col: 1 },
  ];
  // 궁성 대각선에서는 전진 대각선도 가능
  if (isDiagPoint(from.row, from.col)) {
    steps.push({ row: forward, col: -1 }, { row: forward, col: 1 });
  }
  const out: Pos[] = [];
  for (const s of steps) {
    const r = from.row + s.row;
    const c = from.col + s.col;
    if (!inBoard(r, c)) continue;
    const diagonal = s.row !== 0 && s.col !== 0;
    if (diagonal && !isDiagPoint(r, c)) continue;
    const target = board[r][c];
    if (!target || target.side !== side) out.push({ row: r, col: c });
  }
  return out;
}

/** 규칙상 갈 수 있는 칸(자기 장군 여부는 아직 안 걸러진다). */
function pseudoTargets(board: (JanggiPiece | null)[][], from: Pos): Pos[] {
  const piece = board[from.row][from.col];
  if (!piece) return [];
  switch (piece.type) {
    case "K":
    case "A":
      return palaceSteps(board, from, piece.side);
    case "R": {
      const dirs: Pos[] = [
        { row: -1, col: 0 },
        { row: 1, col: 0 },
        { row: 0, col: -1 },
        { row: 0, col: 1 },
      ];
      if (isDiagPoint(from.row, from.col)) {
        dirs.push({ row: -1, col: -1 }, { row: -1, col: 1 }, { row: 1, col: -1 }, { row: 1, col: 1 });
      }
      return slideTargets(board, from, piece.side, dirs, false);
    }
    case "C":
      return cannonTargets(board, from, piece.side);
    case "N":
      return horseTargets(board, from, piece.side);
    case "B":
      return elephantTargets(board, from, piece.side);
    case "P":
      return soldierTargets(board, from, piece.side);
  }
}

/** side 의 궁이 공격받는지. */
export function isKingAttacked(board: (JanggiPiece | null)[][], side: Side): boolean {
  const king = findKing(board, side);
  if (!king) return false;
  const opponent: Side = side === "white" ? "black" : "white";
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const p = board[r][c];
      if (!p || p.side !== opponent) continue;
      const targets = pseudoTargets(board, { row: r, col: c });
      if (targets.some((t) => t.row === king.row && t.col === king.col)) return true;
    }
  }
  return false;
}

function moveNotation(piece: JanggiPiece, from: Pos, to: Pos): string {
  return `${PIECE_NAME[piece.type]} ${from.row},${from.col}→${to.row},${to.col}`;
}

function afterMove(
  state: JanggiState,
  from: Pos,
  to: Pos,
): { board: (JanggiPiece | null)[][]; captured: JanggiPiece | null; notation: string } {
  const board = cloneBoard(state.board);
  const piece = board[from.row][from.col]!;
  const captured = board[to.row][to.col];
  board[to.row][to.col] = piece;
  board[from.row][from.col] = null;
  return { board, captured, notation: moveNotation(piece, from, to) };
}

function withTurn(state: JanggiState): JanggiState {
  return { ...state, turn: state.turn === "white" ? "black" : "white" };
}

/** 상태에서 실제로 둘 수 있는 칸. 자기 장군 금지와 반복수 금지를 적용한다. */
function legalTargetsOf(state: JanggiState, from: Pos): Pos[] {
  const piece = state.board[from.row][from.col];
  if (!piece || piece.side !== state.turn) return [];
  return pseudoTargets(state.board, from).filter((to) => {
    const { board } = afterMove(state, from, to);
    if (isKingAttacked(board, piece.side)) return false;
    // 같은 수 반복 금지: 동일 국면 3회 등장 금지. 단 양쪽 모두 30점 미만이면 허용
    const key = positionKey(board, state.turn === "white" ? "black" : "white");
    if ((state.positionCounts[key] ?? 0) >= 2) {
      const whiteScore = boardScore(board, "white");
      const blackScore = boardScore(board, "black");
      if (!(whiteScore < 30 && blackScore < 30)) return false;
    }
    return true;
  });
}

export function janggiHasAnyMove(state: JanggiState): boolean {
  for (let r = 0; r < ROWS; r++) {
    for (let c = 0; c < COLS; c++) {
      const p = state.board[r][c];
      if (p && p.side === state.turn && legalTargetsOf(state, { row: r, col: c }).length > 0) {
        return true;
      }
    }
  }
  return false;
}

/** 한수쉼. 장군을 받은 상태에서는 둘 수 없다. */
export function janggiPass(state: JanggiState): { state: JanggiState; notation: string } | null {
  if (state.result) return null;
  if (isKingAttacked(state.board, state.turn)) return null;
  const notation = "한수쉼";
  const next = withTurn(state);
  next.moves = [...state.moves, { side: state.turn, from: { row: -1, col: -1 }, to: { row: -1, col: -1 }, pass: true, notation }];
  const key = positionKey(next.board, next.turn);
  next.positionCounts = { ...state.positionCounts, [key]: (state.positionCounts[key] ?? 0) + 1 };
  return { state: checkLimits(next), notation };
}

/** 빅장 선언. 기물이 잡힌 뒤부터 부를 수 있고, 성립하면 점수로 판정한다. */
export function janggiDeclareBigjang(state: JanggiState): GameStatus | null {
  if (state.result) return null;
  if (!state.capturedHappened) return null;
  const white = scoreOf(state, "white");
  const black = scoreOf(state, "black");
  const winner = white > black ? "white" : black > white ? "black" : null;
  state.result = { winner, reason: "빅장 (점수 판정)" };
  return { over: true, winner, reason: `빅장 — 초 ${white}점 대 한 ${black}점` };
}

function checkLimits(state: JanggiState): JanggiState {
  if (state.result) return state;
  if (state.moves.length >= MOVE_LIMIT) {
    const white = scoreOf(state, "white");
    const black = scoreOf(state, "black");
    const winner = white > black ? "white" : black > white ? "black" : null;
    return {
      ...state,
      result: { winner, reason: `200수 도달 — 초 ${white}점 대 한 ${black}점` },
    };
  }
  return state;
}

function rebuildState(setup: JanggiSetup, moves: JanggiMove[]): JanggiState {
  let state: JanggiState = {
    board: createBoard(setup),
    turn: "white",
    moves: [],
    positionCounts: {},
    capturedHappened: false,
    setup,
    result: null,
  };
  state.positionCounts[positionKey(state.board, state.turn)] = 1;
  for (const move of moves) {
    if (move.pass) {
      const r = janggiPass(state);
      if (!r) break;
      state = r.state;
    } else {
      const r = applyMoveInternal(state, move.from, move.to, true);
      if (!r) break;
      state = r.state;
    }
  }
  return state;
}

function applyMoveInternal(
  state: JanggiState,
  from: Pos,
  to: Pos,
  skipLegality: boolean,
): { state: JanggiState; notation: string } | null {
  const piece = state.board[from.row]?.[from.col];
  if (!piece || piece.side !== state.turn) return null;
  if (!skipLegality) {
    const legal = legalTargetsOf(state, from);
    if (!legal.some((t) => t.row === to.row && t.col === to.col)) return null;
  }
  const { board, captured, notation } = afterMove(state, from, to);
  const mover = state.turn;
  const next: JanggiState = {
    ...state,
    board,
    turn: mover === "white" ? "black" : "white",
    moves: [...state.moves, { side: mover, from, to, pass: false, notation }],
    capturedHappened: state.capturedHappened || captured !== null,
    result: null,
  };
  next.positionCounts = { ...state.positionCounts };
  const key = positionKey(board, next.turn);
  next.positionCounts[key] = (next.positionCounts[key] ?? 0) + 1;

  // 외통수: 상대가 장군을 받고 피할 수 없다. 장군 상태에서는 한수쉼도 불가.
  if (isKingAttacked(board, next.turn)) {
    let escaped = false;
    escapeCheck: for (let r = 0; r < ROWS; r++) {
      for (let c = 0; c < COLS; c++) {
        const p = board[r][c];
        if (!p || p.side !== next.turn) continue;
        for (const t of pseudoTargets(board, { row: r, col: c })) {
          const { board: b2 } = afterMove(next, { row: r, col: c }, t);
          if (!isKingAttacked(b2, next.turn)) {
            escaped = true;
            break escapeCheck;
          }
        }
      }
    }
    if (!escaped) {
      next.result = { winner: mover, reason: "외통수" };
      return { state: next, notation };
    }
  }
  return { state: checkLimits(next), notation };
}

// ---- BoardGameEngine 인터페이스 구현 ----

export interface JanggiEngine extends BoardGameEngine<JanggiState> {
  initialState(setup?: JanggiSetup): JanggiState;
  legalTargetPos(state: JanggiState, from: Pos): Pos[];
  applyMovePos(state: JanggiState, from: Pos, to: Pos): { state: JanggiState; notation: string } | null;
  pass(state: JanggiState): { state: JanggiState; notation: string } | null;
  declareBigjang(state: JanggiState): GameStatus | null;
  scores(state: JanggiState): { white: number; black: number };
}

export const janggiEngine: JanggiEngine = {
  initialState(setup: JanggiSetup = { cho: "마상마상", han: "마상마상" }): JanggiState {
    const state: JanggiState = {
      board: createBoard(setup),
      turn: "white",
      moves: [],
      positionCounts: {},
      capturedHappened: false,
      setup,
      result: null,
    };
    state.positionCounts[positionKey(state.board, "white")] = 1;
    return state;
  },

  turn(state) {
    return state.turn;
  },

  pieces(state): Map<string, PieceInfo> {
    const map = new Map<string, PieceInfo>();
    for (let r = 0; r < ROWS; r++) {
      for (let c = 0; c < COLS; c++) {
        const p = state.board[r][c];
        if (p) map.set(`${r},${c}`, { type: p.type, side: p.side });
      }
    }
    return map;
  },

  legalTargets(state, from: string): string[] {
    const [r, c] = from.split(",").map(Number);
    return legalTargetsOf(state, { row: r, col: c }).map(posKey);
  },

  legalTargetPos: legalTargetsOf,

  applyMove(state, from: string, to: string) {
    const [fr, fc] = from.split(",").map(Number);
    const [tr, tc] = to.split(",").map(Number);
    const r = applyMoveInternal(state, { row: fr, col: fc }, { row: tr, col: tc }, false);
    return r ? { state: r.state, notation: r.notation } : null;
  },

  applyMovePos: (state, from, to) => applyMoveInternal(state, from, to, false),

  pass: janggiPass,

  declareBigjang: janggiDeclareBigjang,

  scores: (state) => ({ white: scoreOf(state, "white"), black: scoreOf(state, "black") }),

  undo(state): JanggiState | null {
    if (state.moves.length === 0) return null;
    return rebuildState(state.setup, state.moves.slice(0, -1));
  },

  status(state): GameStatus {
    if (state.result) {
      return { over: true, winner: state.result.winner, reason: state.result.reason };
    }
    return { over: false, winner: null, reason: null };
  },

  inCheck(state) {
    return isKingAttacked(state.board, state.turn);
  },

  notationHistory(state): string[] {
    return state.moves.map((m) => m.notation);
  },
};
