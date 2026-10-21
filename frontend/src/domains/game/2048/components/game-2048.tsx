"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useLocalValue } from "../../lib/local-value";
import { SIZE, addRandomTile, canMove, hasWon, move, newBoard, type Board, type Direction } from "../engine";

const BEST_KEY = "game.2048.best";
const SWIPE_MIN_PX = 30;

const KEY_DIRECTION: Record<string, Direction> = {
  ArrowUp: "up",
  ArrowDown: "down",
  ArrowLeft: "left",
  ArrowRight: "right",
  w: "up",
  s: "down",
  a: "left",
  d: "right",
};

const TILE_CLASS: Record<number, string> = {
  2: "bg-amber-50 text-stone-700",
  4: "bg-amber-100 text-stone-700",
  8: "bg-orange-300 text-white",
  16: "bg-orange-400 text-white",
  32: "bg-orange-500 text-white",
  64: "bg-red-500 text-white",
  128: "bg-yellow-300 text-white",
  256: "bg-yellow-400 text-white",
  512: "bg-yellow-500 text-white",
  1024: "bg-amber-500 text-white",
  2048: "bg-amber-600 text-white",
};

type Phase = "playing" | "won" | "over";

interface Game {
  board: Board;
  score: number;
  phase: Phase;
  /** 2048 달성 후 계속하기를 골랐으면 다시 달성 안내를 띄우지 않는다 */
  keepPlaying: boolean;
}

const EMPTY_BOARD: Board = Array.from({ length: SIZE }, () => Array<number>(SIZE).fill(0));

const freshGame = (): Game => ({ board: newBoard(), score: 0, phase: "playing", keepPlaying: false });

export function Game2048() {
  const [game, setGame] = useState<Game | null>(null);
  const [best, setBest] = useLocalValue<number | null>(BEST_KEY, null);
  const [newBest, setNewBest] = useState(false);
  const touchStart = useRef<{ x: number; y: number } | null>(null);

  const step = (dir: Direction) => {
    if (!game || game.phase !== "playing") return;
    const res = move(game.board, dir);
    if (!res.moved) return;
    const board = addRandomTile(res.board);
    const score = game.score + res.gained;
    const phase: Phase = !game.keepPlaying && hasWon(board) ? "won" : canMove(board) ? "playing" : "over";
    setGame({ ...game, board, score, phase });
    if (score > (best ?? 0)) {
      setBest(score);
      setNewBest(true);
    }
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const dir = KEY_DIRECTION[e.key.length === 1 ? e.key.toLowerCase() : e.key];
      if (!dir || game?.phase !== "playing") return;
      // 입력창에서 친 글자는 가로채지 않는다
      if (e.target instanceof HTMLElement && e.target.closest("input, textarea, [contenteditable]")) return;
      e.preventDefault();
      step(dir);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const onTouchEnd = (e: React.TouchEvent) => {
    const start = touchStart.current;
    const t = e.changedTouches[0];
    touchStart.current = null;
    if (!start || !t) return;
    const dx = t.clientX - start.x;
    const dy = t.clientY - start.y;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < SWIPE_MIN_PX) return;
    step(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : dy > 0 ? "down" : "up");
  };

  const restart = () => {
    setGame(freshGame());
    setNewBest(false);
  };

  return (
    <div className="mx-auto flex w-full max-w-sm flex-col gap-3">
      <div className="flex items-center justify-between text-sm">
        <span>
          점수 <b className="tabular-nums">{game?.score ?? 0}</b>
        </span>
        <span>
          최고 기록 <b className="tabular-nums">{best ?? "-"}</b>
        </span>
        <Button size="sm" variant="outline" onClick={restart}>
          새 게임
        </Button>
      </div>
      <div
        className="relative grid touch-none grid-cols-4 gap-2 rounded-xl bg-stone-300 p-2 select-none dark:bg-stone-700"
        onTouchStart={(e) => {
          const t = e.touches[0];
          if (t) touchStart.current = { x: t.clientX, y: t.clientY };
        }}
        onTouchEnd={onTouchEnd}
        role="group"
        aria-label="2048 판. 방향키나 스와이프로 민다"
      >
        {(game?.board ?? EMPTY_BOARD).flat().map((v, i) => (
          <div
            key={i}
            className={`flex aspect-square items-center justify-center rounded-md font-bold tabular-nums ${
              v === 0
                ? "bg-stone-200 dark:bg-stone-600"
                : (TILE_CLASS[v] ?? "bg-stone-800 text-white")
            } ${v >= 1024 ? "text-xl" : v >= 128 ? "text-2xl" : "text-3xl"}`}
          >
            {v > 0 ? v : null}
          </div>
        ))}
        {!game && (
          <div className="absolute inset-0 flex items-center justify-center rounded-xl bg-background/60">
            <Button onClick={restart}>시작</Button>
          </div>
        )}
        {game && game.phase !== "playing" && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 rounded-xl bg-background/80 text-center">
            <p className="text-2xl font-bold">{game.phase === "won" ? "2048 달성!" : "게임 끝"}</p>
            <p className="text-sm">
              점수 {game.score}
              {newBest && <span className="ml-1 font-semibold text-primary">(최고 기록!)</span>}
            </p>
            <div className="flex gap-2">
              {game.phase === "won" && (
                <Button
                  onClick={() =>
                    setGame({ ...game, phase: canMove(game.board) ? "playing" : "over", keepPlaying: true })
                  }
                >
                  계속하기
                </Button>
              )}
              <Button variant={game.phase === "won" ? "outline" : "default"} onClick={restart}>
                다시하기
              </Button>
            </div>
          </div>
        )}
      </div>
      <p className="text-center text-xs text-muted-foreground">방향키·WASD 또는 스와이프로 타일을 밀어 같은 숫자를 합친다</p>
    </div>
  );
}
