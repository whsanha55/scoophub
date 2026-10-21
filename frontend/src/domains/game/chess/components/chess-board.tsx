"use client";

import type { ReactNode } from "react";
import type { PieceInfo } from "../../lib/board-game";

const FILES = ["a", "b", "c", "d", "e", "f", "g", "h"];

const PIECE_GLYPH: Record<string, string> = {
  k: "♚",
  q: "♛",
  r: "♜",
  b: "♝",
  n: "♞",
  p: "♟",
};

/** 잡힌 기물 글자. 시계 행 배경은 테마를 따르므로 보드와 달리 토큰 색으로 그린다. */
export function chessCapturedGlyph(piece: PieceInfo): ReactNode {
  return (
    <span
      className={`text-lg leading-none ${
        piece.side === "white" ? "text-foreground" : "text-muted-foreground"
      }`}
    >
      {PIECE_GLYPH[piece.type]}
    </span>
  );
}

export interface ChessBoardProps {
  pieces: Map<string, PieceInfo>;
  /** 아래에 보일 진영 */
  orientation: "white" | "black";
  selected: string | null;
  targets: string[];
  lastMove: { from: string; to: string } | null;
  /** 체크 걸린 왕의 칸 */
  checkSquare: string | null;
  onSquareTap: (square: string) => void;
}

export function ChessBoard({
  pieces,
  orientation,
  selected,
  targets,
  lastMove,
  checkSquare,
  onSquareTap,
}: ChessBoardProps) {
  const flipped = orientation === "black";
  // 칸 순서만 뒤집어 기물은 늘 바로 선다
  const ranks = flipped ? [1, 2, 3, 4, 5, 6, 7, 8] : [8, 7, 6, 5, 4, 3, 2, 1];
  const files = flipped ? [...FILES].reverse() : FILES;

  return (
    <div
      className="grid aspect-square w-full grid-cols-8 grid-rows-8 overflow-hidden rounded-lg border-2 border-border shadow-sm"
    >
      {ranks.map((rank) =>
        files.map((file) => {
          const square = `${file}${rank}`;
          const isDark = (FILES.indexOf(file) + rank) % 2 === 0;
          const piece = pieces.get(square);
          const isSelected = selected === square;
          const isTarget = targets.includes(square);
          const isLast = lastMove && (lastMove.from === square || lastMove.to === square);
          const isCheck = checkSquare === square;
          return (
            <button
              key={square}
              type="button"
              aria-label={`${square}${piece ? ` ${piece.side} ${piece.type}` : ""}`}
              className={`relative flex touch-manipulation items-center justify-center ${
                isDark ? "bg-[#b58863]" : "bg-[#f0d9b5]"
              } ${isSelected ? "outline-2 -outline-offset-2 outline-sky-500" : ""} ${
                isLast ? "before:absolute before:inset-0 before:bg-yellow-300/40" : ""
              } ${isCheck ? "before:absolute before:inset-0 before:bg-red-500/50" : ""}`}
              onPointerDown={(e) => {
                e.preventDefault();
                onSquareTap(square);
              }}
            >
              {piece && (
                <span
                  className={`relative z-10 select-none text-[min(7.5vw,2.6rem)] leading-none sm:text-4xl ${
                    piece.side === "white"
                      ? "text-white [-webkit-text-stroke:1px_#1c1917] drop-shadow-[0_1px_1px_rgba(0,0,0,0.4)]"
                      : "text-neutral-900 drop-shadow-[0_1px_1px_rgba(255,255,255,0.25)]"
                  }`}
                >
                  {PIECE_GLYPH[piece.type]}
                </span>
              )}
              {isTarget && !piece && (
                <span className="relative z-10 h-1/4 w-1/4 rounded-full bg-neutral-800/35" />
              )}
              {isTarget && piece && (
                <span className="absolute inset-0 z-0 rounded-sm ring-4 ring-inset ring-emerald-600/60" />
              )}
            </button>
          );
        }),
      )}
    </div>
  );
}
