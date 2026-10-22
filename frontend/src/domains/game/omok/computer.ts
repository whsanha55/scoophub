// 오목 컴퓨터 대국에서 컴퓨터가 둘 점을 고른다. 화면과 분리된 순수 함수다.
// 승리·금수 판정은 engine.ts 를 그대로 쓰고 (ADR-0001), 여기서는 어디가 좋은 점인지만 어림한다.
// 쉬움: 바로 이기는 수·바로 막는 수만 놓치지 않고, 나머지는 괜찮아 보이는 점 몇 개 중에서 무작위로 고른다.
// 어려움: 같은 어림 점수로 후보를 추린 뒤 몇 수 앞까지 읽는다 (알파베타 탐색).
import type { Side } from "../lib/board-game";
import { FIRST, SIZE, isForbidden, isWinningMove, omokEngine, type Board, type OmokState } from "./engine.ts";

export type Difficulty = "easy" | "hard";

const DIRECTIONS = [
  [0, 1],
  [1, 0],
  [1, 1],
  [1, -1],
] as const;
const CENTER = Math.floor(SIZE / 2);
/** 이어서 5칸이 될 수 있는 창 하나에 같은 편 돌이 n개 있을 때의 값. 상대 돌이 섞인 창은 0이다. */
const WINDOW_VALUE = [0, 1, 10, 100, 1_000, 100_000];
/** 쉬움이 무작위로 고르는 상위 후보 수 */
const EASY_CHOICES = 6;
/** 어려움이 읽는 깊이(수)와 한 국면에서 살펴보는 후보 수. 6수는 조금 더 강하지만 한 수에 수백 ms 가 걸려 5수로 둔다. */
const HARD_DEPTH = 5;
const HARD_BRANCH = 8;
const WIN = 1_000_000_000;

type Point = [number, number];

const other = (side: Side): Side => (side === "white" ? "black" : "white");
const inside = (r: number, c: number) => r >= 0 && r < SIZE && c >= 0 && c < SIZE;

/** side 가 (r,c)에 둘 수 있는가. 선수(흑)는 금수점에 둘 수 없다. */
function canPlace(board: Board, r: number, c: number, side: Side): boolean {
  return side !== FIRST || !isForbidden(board, r, c);
}

/** 이미 놓인 돌에서 두 칸 이내의 빈 점. 멀리 떨어진 점은 승부에 영향이 없어 보지 않는다. */
function nearbyPoints(board: Board): Point[] {
  const points: Point[] = [];
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      if (board[r][c] !== null) continue;
      let near = false;
      for (let dr = -2; dr <= 2 && !near; dr++) {
        for (let dc = -2; dc <= 2 && !near; dc++) {
          const qr = r + dr;
          const qc = c + dc;
          if (inside(qr, qc) && board[qr][qc] !== null) near = true;
        }
      }
      if (near) points.push([r, c]);
    }
  }
  return points;
}

/** side 가 (r,c)에 두면 그 점을 지나는 5칸 창들의 값이 얼마나 늘어나는가. */
function placementGain(board: Board, r: number, c: number, side: Side): number {
  let gain = 0;
  for (const [dr, dc] of DIRECTIONS) {
    for (let start = -4; start <= 0; start++) {
      let mine = 0;
      let open = true;
      for (let k = start; k < start + 5 && open; k++) {
        if (k === 0) continue;
        const qr = r + dr * k;
        const qc = c + dc * k;
        if (!inside(qr, qc)) open = false;
        else if (board[qr][qc] === side) mine++;
        else if (board[qr][qc] !== null) open = false;
      }
      if (open) gain += WINDOW_VALUE[mine + 1] - WINDOW_VALUE[mine];
    }
  }
  return gain;
}

/** 점의 어림 점수: 내가 두어 얻는 값 + 상대가 그 점에서 얻을 값(막는 값). */
function pointScore(board: Board, r: number, c: number, side: Side): number {
  return placementGain(board, r, c, side) + placementGain(board, r, c, other(side));
}

/** 판 전체의 어림 값. side 기준으로 내 창의 값에서 상대 창의 값을 뺀다. */
function evaluate(board: Board, side: Side): number {
  let total = 0;
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      for (const [dr, dc] of DIRECTIONS) {
        if (!inside(r + dr * 4, c + dc * 4)) continue;
        let mine = 0;
        let theirs = 0;
        for (let k = 0; k < 5; k++) {
          const stone = board[r + dr * k][c + dc * k];
          if (stone === side) mine++;
          else if (stone !== null) theirs++;
        }
        if (theirs === 0) total += WINDOW_VALUE[mine];
        else if (mine === 0) total -= WINDOW_VALUE[theirs];
      }
    }
  }
  return total;
}

/**
 * side 차례에 꼭 둬야 할 점. 바로 이기는 점이 있으면 그 하나, 상대가 바로 이기는 점이 있으면 그 점들(막을 수 없는 점은 뺀다).
 * 둘 다 없으면 null.
 */
function forcedPoints(board: Board, candidates: Point[], side: Side): Point[] | null {
  const win = candidates.find(([r, c]) => isWinningMove(board, r, c, side));
  if (win) return [win];
  const threats = candidates.filter(([r, c]) => isWinningMove(board, r, c, other(side)));
  if (threats.length === 0) return null;
  return threats.filter(([r, c]) => canPlace(board, r, c, side));
}

/** 어림 점수가 높은 순으로 둘 수 있는 점을 limit 개까지. */
function bestPoints(board: Board, candidates: Point[], side: Side, limit: number): Point[] {
  const ranked = candidates
    .map((p) => ({ p, score: pointScore(board, p[0], p[1], side) }))
    .sort((a, b) => b.score - a.score);
  const picked: Point[] = [];
  for (const { p } of ranked) {
    if (picked.length >= limit) break;
    if (canPlace(board, p[0], p[1], side)) picked.push(p);
  }
  return picked;
}

/** 네가맥스 알파베타. side 기준 점수를 돌려준다. 빨리 이기는 수일수록 점수가 크다. */
function search(board: Board, side: Side, depth: number, alpha: number, beta: number): number {
  const candidates = nearbyPoints(board);
  if (candidates.length === 0) return 0;
  if (candidates.some(([r, c]) => isWinningMove(board, r, c, side))) return WIN + depth;
  if (depth === 0) return evaluate(board, side);
  const forced = forcedPoints(board, candidates, side);
  const moves = forced ?? bestPoints(board, candidates, side, HARD_BRANCH);
  if (moves.length === 0) return -(WIN + depth); // 막을 수 없다
  let best = -Infinity;
  for (const [r, c] of moves) {
    board[r][c] = side;
    const score = -search(board, other(side), depth - 1, -beta, -alpha);
    board[r][c] = null;
    if (score > best) best = score;
    if (best > alpha) alpha = best;
    if (alpha >= beta) break;
  }
  return best;
}

function hardMove(board: Board, candidates: Point[], side: Side): Point | undefined {
  const moves = bestPoints(board, candidates, side, HARD_BRANCH);
  let best = moves[0];
  let bestScore = -Infinity;
  for (const [r, c] of moves) {
    board[r][c] = side;
    const score = -search(board, other(side), HARD_DEPTH - 1, -Infinity, -bestScore);
    board[r][c] = null;
    if (score > bestScore) {
      bestScore = score;
      best = [r, c];
    }
  }
  return best;
}

/**
 * 지금 차례인 편을 컴퓨터가 맡아 둘 점 "r,c". 둘 수 있는 점이 없으면 null.
 * random 은 쉬움의 무작위 선택에 쓴다 (테스트에서 시드를 고정하려고 받는다).
 */
export function computerMove(state: OmokState, difficulty: Difficulty, random: () => number = Math.random): string | null {
  const side = omokEngine.turn(state);
  const board = state.board.map((row) => [...row]);
  const candidates = nearbyPoints(board);
  if (candidates.length === 0) return `${CENTER},${CENTER}`;

  const forced = forcedPoints(board, candidates, side);
  let point: Point | undefined;
  if (forced && forced.length > 0) point = forced[0];
  else if (difficulty === "hard") point = hardMove(board, candidates, side);
  else {
    const choices = bestPoints(board, candidates, side, EASY_CHOICES);
    point = choices[Math.floor(random() * choices.length)];
  }
  if (point) return `${point[0]},${point[1]}`;
  // 돌 근처가 모두 금수면 판 어디든 둘 수 있는 점에 둔다
  return omokEngine.legalTargets(state, "")[0] ?? null;
}
