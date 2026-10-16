"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Chess, type Square } from "chess.js";
import { ChessBoard } from "./chess-board";
import type { PieceInfo } from "../../lib/board-game";

interface Ply {
  pieces: Map<string, PieceInfo>;
  lastMove: { from: string; to: string } | null;
}

/** 기보 다시보기: 한 수씩 넘겨 보는 체스 판. */
export function ChessReview({ moves, result }: { moves: string[]; result?: string }) {
  const [plyIndex, setPly] = useState(moves.length);

  // 각 시점의 기물 배치를 미리 계산한다
  const plies = useMemo(() => {
    const list: Ply[] = [{ pieces: snapshotOf(new Chess()), lastMove: null }];
    const game = new Chess();
    for (const san of moves) {
      try {
        const mv = game.move(san);
        list.push({ pieces: snapshotOf(game), lastMove: { from: mv.from, to: mv.to } });
      } catch {
        break;
      }
    }
    return list;
  }, [moves]);

  const current = plies[Math.min(plyIndex, plies.length - 1)];
  const canPrev = plyIndex > 0;
  const canNext = plyIndex < plies.length - 1;

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-3">
      <ChessBoard
        pieces={current.pieces}
        orientation="white"
        selected={null}
        targets={[]}
        lastMove={current.lastMove}
        checkSquare={null}
        onSquareTap={() => {}}
      />
      <div className="flex items-center justify-center gap-2">
        <Button variant="outline" size="sm" onClick={() => setPly(0)} disabled={!canPrev}>
          처음
        </Button>
        <Button variant="outline" size="sm" onClick={() => setPly((p) => Math.max(0, p - 1))} disabled={!canPrev}>
          이전
        </Button>
        <span className="w-16 text-center text-sm tabular-nums text-muted-foreground">
          {Math.ceil(plyIndex / 2)}수
        </span>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setPly((p) => Math.min(plies.length - 1, p + 1))}
          disabled={!canNext}
        >
          다음
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setPly(plies.length - 1)}
          disabled={!canNext}
        >
          끝
        </Button>
      </div>
      {moves.length > 0 && (
        <div className="max-h-40 overflow-y-auto rounded-lg border bg-card p-2">
          <ol className="grid grid-cols-[2rem_1fr_1fr] items-center gap-x-2 gap-y-0.5 text-sm tabular-nums">
            {Array.from({ length: Math.ceil(moves.length / 2) }, (_, i) => (
              <li
                key={i}
                className={`col-span-3 grid cursor-pointer grid-cols-subgrid rounded ${
                  Math.floor((plyIndex - 1) / 2) === i ? "bg-accent" : ""
                }`}
              >
                <span className="text-muted-foreground">{i + 1}.</span>
                <span
                  onClick={() => setPly(i * 2 + 1)}
                  className={plyIndex === i * 2 + 1 ? "font-bold" : ""}
                >
                  {moves[i * 2] ?? ""}
                </span>
                <span
                  onClick={() => moves[i * 2 + 1] && setPly(i * 2 + 2)}
                  className={plyIndex === i * 2 + 2 ? "font-bold" : ""}
                >
                  {moves[i * 2 + 1] ?? ""}
                </span>
              </li>
            ))}
          </ol>
        </div>
      )}
      {result && (
        <p className="text-center text-sm text-muted-foreground">
          결과 {result === "1-0" ? "백 승리" : result === "0-1" ? "흑 승리" : "무승부"}
        </p>
      )}
    </div>
  );
}

function snapshotOf(game: Chess): Map<string, PieceInfo> {
  const map = new Map<string, PieceInfo>();
  const board = game.board();
  for (let r = 0; r < 8; r++) {
    for (let f = 0; f < 8; f++) {
      const p = board[r][f];
      if (!p) continue;
      const square = `${"abcdefgh"[f]}${8 - r}` as Square;
      map.set(square, { type: p.type, side: p.color === "w" ? "white" : "black" });
    }
  }
  return map;
}
