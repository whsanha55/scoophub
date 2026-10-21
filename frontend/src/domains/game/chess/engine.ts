// 체스 규칙 어댑터. 검증된 chess.js를 보드게임 공통 인터페이스 뒤에 둔다.
import { Chess, type Move, type Square } from "chess.js";
import type { BoardGameEngine, GameStatus, PieceInfo, Side } from "../lib/board-game";

/** color 진영에 메이트할 기물이 있는지. 시간패한 쪽의 상대에게 없으면 시간패 대신 무승부다. */
export function hasMatingMaterial(
  board: (null | { type: string; color: string })[][],
  color: "w" | "b",
): boolean {
  const pieces = board
    .flat()
    .filter((p): p is { type: string; color: string } => p !== null && p.color === color);
  // 폰·룩·퀸은 물론 소수 기물 조합도 메이트 가능성이 있다.
  // 왕뿐이거나 왕+기사1/비숍1만으로는 강제 메이트가 불가능하다.
  const nonKings = pieces.filter((p) => p.type !== "k");
  if (nonKings.length === 0) return false;
  if (nonKings.length === 1 && (nonKings[0].type === "n" || nonKings[0].type === "b")) {
    return false;
  }
  return true;
}

export interface ChessState {
  game: Chess;
}

/** 잡힌 기물 표기 순서: 값 높은 기물부터 */
const CAPTURED_ORDER: Record<string, number> = { q: 0, r: 1, b: 2, n: 3, p: 4 };

function capturedOf(game: Chess): Record<Side, PieceInfo[]> {
  const out: Record<Side, PieceInfo[]> = { white: [], black: [] };
  // verbose 기보의 captured 필드가 잡은 기물을 알려준다. 남은 기물 수를 세는 방식과 달리 프로모션과 무관하게 정확하다
  for (const m of game.history({ verbose: true })) {
    if (!m.captured) continue;
    const mover: Side = m.color === "w" ? "white" : "black";
    out[mover].push({ type: m.captured, side: mover === "white" ? "black" : "white" });
  }
  for (const side of ["white", "black"] as const) {
    out[side].sort((a, b) => CAPTURED_ORDER[a.type] - CAPTURED_ORDER[b.type]);
  }
  return out;
}

function statusOf(game: Chess): GameStatus {
  if (game.isCheckmate()) {
    // 체크메이트 당한 쪽이 turn
    const loser = game.turn();
    return { over: true, winner: loser === "w" ? "black" : "white", reason: "체크메이트" };
  }
  if (game.isStalemate()) {
    return { over: true, winner: null, reason: "스테일메이트" };
  }
  if (game.isInsufficientMaterial()) {
    return { over: true, winner: null, reason: "기물 부족 무승부" };
  }
  if (game.isThreefoldRepetition()) {
    return { over: true, winner: null, reason: "3회 반복 무승부" };
  }
  if (game.isDrawByFiftyMoves()) {
    return { over: true, winner: null, reason: "50수 무승부" };
  }
  return { over: false, winner: null, reason: null };
}

export const chessEngine: BoardGameEngine<ChessState> = {
  initialState(): ChessState {
    return { game: new Chess() };
  },

  turn(state: ChessState) {
    return state.game.turn() === "w" ? "white" : "black";
  },

  pieces(state: ChessState): Map<string, PieceInfo> {
    const map = new Map<string, PieceInfo>();
    const board = state.game.board();
    for (let r = 0; r < 8; r++) {
      for (let f = 0; f < 8; f++) {
        const p = board[r][f];
        if (!p) continue;
        // board()는 rank 8부터 rank 1 순서
        const rank = 8 - r;
        const file = "abcdefgh"[f];
        map.set(`${file}${rank}`, { type: p.type, side: p.color === "w" ? "white" : "black" });
      }
    }
    return map;
  },

  capturedPieces(state: ChessState): Record<Side, PieceInfo[]> {
    return capturedOf(state.game);
  },

  legalTargets(state: ChessState, from: string): string[] {
    try {
      return state.game
        .moves({ square: from as Square, verbose: true })
        .map((m) => m.to as string);
    } catch {
      return [];
    }
  },

  applyMove(state: ChessState, from: string, to: string) {
    const game = state.game;
    const legal = game
      .moves({ square: from as Square, verbose: true })
      .some((m) => m.to === to);
    if (!legal) return null;
    // 반복 무승부 판정에는 지금까지 둔 수가 필요하므로 초기 국면에서 기보를 다시 놓는다
    const next = new Chess();
    for (const hist of game.history()) {
      next.move(hist);
    }
    const move: Move = next.move({ from: from as Square, to: to as Square });
    return { state: { game: next }, notation: move.san };
  },

  undo(state: ChessState): ChessState | null {
    const next = new Chess();
    const hist = state.game.history();
    if (hist.length === 0) return null;
    for (const san of hist.slice(0, -1)) next.move(san);
    return { game: next };
  },

  status(state: ChessState): GameStatus {
    return statusOf(state.game);
  },

  inCheck(state: ChessState): boolean {
    return state.game.inCheck();
  },

  notationHistory(state: ChessState): string[] {
    return state.game.history();
  },
};
