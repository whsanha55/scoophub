// 잡힌 기물 표시. 대국자 시계와 분리된 칸에 그 대국자가 잡은 상대 기물을 늘어놓는다.
// 기물 모양은 게임마다 다르니 렌더러를 받아 그린다.
import type { ReactNode } from "react";
import type { PieceInfo } from "../lib/board-game";

export function CapturedPiecesView({
  pieces,
  render,
}: {
  pieces: PieceInfo[];
  render: (piece: PieceInfo) => ReactNode;
}) {
  if (pieces.length === 0) return null;
  return (
    <div
      aria-label="잡은 기물"
      className="flex min-h-10 w-full flex-wrap items-center gap-1 rounded-lg border bg-card px-2 py-1"
    >
      <span className="mr-1 shrink-0 text-xs text-muted-foreground">잡은 기물</span>
      {pieces.map((piece, i) => (
        <span key={i}>{render(piece)}</span>
      ))}
    </div>
  );
}
