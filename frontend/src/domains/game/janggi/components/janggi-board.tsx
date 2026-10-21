"use client";

import type { ReactNode } from "react";
import { PIECE_HANJA, type JanggiPiece, type JanggiPieceType } from "../engine";
import type { PieceInfo } from "../../lib/board-game";

/** 기물 등급: 궁 > 차·포·마·상 > 사·병. 판과 잡은 기물 칸이 같은 비율(1 : 0.87 : 0.63)로 그린다. */
type PieceTier = "large" | "medium" | "small";
const PIECE_TIER: Record<JanggiPieceType, PieceTier> = {
  K: "large",
  R: "medium",
  C: "medium",
  N: "medium",
  B: "medium",
  A: "small",
  P: "small",
};

/** 판 위 기물 크기 (칸 너비 기준) */
const BOARD_TOKEN_SIZE: Record<PieceTier, string> = {
  large: "w-full text-[min(4.5vw,1.35rem)]",
  medium: "w-[87%] text-[min(3.9vw,1.15rem)]",
  small: "w-[63%] text-[min(2.9vw,0.85rem)]",
};

/** 잡힌 기물 토큰 크기 (궁은 잡히는 순간 대국이 끝나므로 표시되지 않는다) */
const CAPTURED_TOKEN_SIZE: Record<PieceTier, string> = {
  large: "h-9 w-9 text-lg",
  medium: "h-8 w-8 text-base",
  small: "h-6 w-6 text-xs",
};

export function janggiCapturedGlyph(piece: PieceInfo): ReactNode {
  const type = piece.type as JanggiPieceType;
  return (
    <span
      className={`flex shrink-0 items-center justify-center rounded-full border font-bold leading-none ${
        CAPTURED_TOKEN_SIZE[PIECE_TIER[type]]
      } ${
        piece.side === "white"
          ? "border-emerald-300/40 bg-emerald-700 text-emerald-50"
          : "border-red-300/40 bg-red-700 text-red-50"
      }`}
    >
      {PIECE_HANJA[piece.side][type]}
    </span>
  );
}

export interface JanggiBoardProps {
  board: (JanggiPiece | null)[][];
  /** 아래에 보일 진영 */
  orientation: "white" | "black";
  selected: string | null; // "r,c"
  targets: string[];
  lastMove: { from: string; to: string } | null;
  /** 장군 걸린 궁 위치 */
  checkSquare: string | null;
  onPointTap: (point: string) => void;
}

/** 9×10 교차점 장기판. */
export function JanggiBoard({
  board,
  orientation,
  selected,
  targets,
  lastMove,
  checkSquare,
  onPointTap,
}: JanggiBoardProps) {
  // 판선은 상하·좌우 대칭이라 기물 좌표만 뒤집는다. 글자는 늘 바로 선다.
  const flipped = orientation === "black";
  const rows = [...Array(10).keys()];
  const cols = [...Array(9).keys()];

  return (
    <div
      className="relative aspect-[9/10] w-full max-w-[min(88vw,480px)] select-none overflow-hidden rounded-lg border-2 border-amber-900/60 bg-[#e8c98f] shadow-sm"
    >
      {/* 판선: 세로 9 · 가로 10 · 궁성 대각선 · 강 표시 */}
      <svg
        viewBox="0 0 8 9"
        className="absolute inset-[6%] h-[88%] w-[88%] overflow-visible"
        preserveAspectRatio="none"
        aria-hidden
      >
        <g stroke="#7a5220" strokeWidth="0.035">
          {Array.from({ length: 9 }, (_, c) => (
            <line key={`v${c}`} x1={c} y1={0} x2={c} y2={9} />
          ))}
          {Array.from({ length: 10 }, (_, r) => (
            <line key={`h${r}`} x1={0} y1={r} x2={8} y2={r} />
          ))}
          {/* 궁성 대각선 */}
          <line x1={3} y1={0} x2={5} y2={2} />
          <line x1={5} y1={0} x2={3} y2={2} />
          <line x1={3} y1={7} x2={5} y2={9} />
          <line x1={5} y1={7} x2={3} y2={9} />
          {/* 궁성 테두리 강조 */}
          <rect x={3} y={0} width={2} height={2} fill="none" strokeWidth="0.05" />
          <rect x={3} y={7} width={2} height={2} fill="none" strokeWidth="0.05" />
        </g>
      </svg>
      <div className="absolute inset-[6%]">
        {rows.map((r) =>
          cols.map((c) => {
            const point = `${r},${c}`;
            const piece = board[r][c];
            const isSelected = selected === point;
            const isTarget = targets.includes(point);
            const isLast = lastMove && (lastMove.from === point || lastMove.to === point);
            const isCheck = checkSquare === point;
            return (
              <button
                key={point}
                type="button"
                aria-label={`${point}${piece ? ` ${piece.side === "white" ? "초" : "한"} ${piece.type}` : ""}`}
                className="absolute flex h-[10%] w-[11%] -translate-x-1/2 -translate-y-1/2 items-center justify-center"
                style={{
                  left: `${((flipped ? 8 - c : c) / 8) * 100}%`,
                  top: `${((flipped ? 9 - r : r) / 9) * 100}%`,
                }}
                onPointerDown={(e) => {
                  e.preventDefault();
                  onPointTap(point);
                }}
              >
                {piece && (
                  <span
                    className={`flex aspect-square items-center justify-center rounded-full border font-bold leading-none ${
                      BOARD_TOKEN_SIZE[PIECE_TIER[piece.type]]
                    } ${
                      piece.side === "white"
                        ? "border-emerald-300/40 bg-emerald-700 text-emerald-50"
                        : "border-red-300/40 bg-red-700 text-red-50"
                    } ${isSelected ? "ring-2 ring-sky-400 ring-offset-1 ring-offset-[#e8c98f]" : ""} ${
                      isCheck ? "ring-2 ring-red-500 ring-offset-1 ring-offset-[#e8c98f]" : ""
                    }`}
                  >
                    {PIECE_HANJA[piece.side][piece.type]}
                  </span>
                )}
                {!piece && isTarget && (
                  <span className="h-1/3 w-1/3 rounded-full bg-neutral-800/30" />
                )}
                {!piece && isLast && (
                  <span className="absolute inset-0 rounded-full bg-yellow-500/25" />
                )}
              </button>
            );
          }),
        )}
      </div>
    </div>
  );
}
