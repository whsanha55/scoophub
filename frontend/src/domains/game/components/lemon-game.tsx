"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Button } from "@/components/ui/button";
import {
  TARGET_SUM,
  TIME_LIMIT_MS,
  LEMON_COLS,
  LEMON_ROWS,
  clearRect,
  createLemonGrid,
  formatClock,
  inBounds,
  rectBounds,
  rectSum,
  type Cell,
} from "../lib/lemon";

type Phase = "ready" | "playing" | "over";

const BEST_KEY = "game.lemon.best";
const bestListeners = new Set<() => void>();

function readBest(): number | null {
  const raw = localStorage.getItem(BEST_KEY);
  return raw !== null && Number.isFinite(Number(raw)) ? Number(raw) : null;
}

function saveBest(score: number) {
  const prev = readBest();
  if (score > (prev ?? -1)) {
    localStorage.setItem(BEST_KEY, String(score));
    bestListeners.forEach((l) => l());
  }
}

function useBest(): number | null {
  return useSyncExternalStore(
    (onChange) => {
      bestListeners.add(onChange);
      return () => bestListeners.delete(onChange);
    },
    () => readBest(),
    () => null,
  );
}

// pointer capture 중에는 elementFromPoint hit-test가 믿을 수 없어
// 격자 컨테이너 지오메트리로 좌표 → 칸을 계산한다.
function cellAt(container: HTMLElement, x: number, y: number): Cell | null {
  const rect = container.getBoundingClientRect();
  const col = Math.floor(((x - rect.left) / rect.width) * LEMON_COLS);
  const row = Math.floor(((y - rect.top) / rect.height) * LEMON_ROWS);
  if (row < 0 || row >= LEMON_ROWS || col < 0 || col >= LEMON_COLS) return null;
  return { row, col };
}

export function LemonGame() {
  const [phase, setPhase] = useState<Phase>("ready");
  const [grid, setGrid] = useState<number[][]>(() => createLemonGrid());
  const [score, setScore] = useState(0);
  const [timeLeft, setTimeLeft] = useState(TIME_LIMIT_MS);
  const best = useBest();
  // 드래그 시작·현재 칸은 이벤트 사이에 렌더를 보장할 수 없어 ref로 유지하고,
  // 렌더는 selection state로만 한다.
  const dragRef = useRef<{ start: Cell; current: Cell } | null>(null);
  const [selection, setSelection] = useState<{ start: Cell; current: Cell } | null>(null);
  const endsAt = useRef(0);
  const scoreRef = useRef(0);

  useEffect(() => {
    if (phase !== "playing") return;
    const tick = () => {
      const left = Math.max(0, endsAt.current - Date.now());
      setTimeLeft(left);
      if (left <= 0) {
        setPhase("over");
        saveBest(scoreRef.current);
      }
    };
    const id = setInterval(tick, 200);
    return () => clearInterval(id);
  }, [phase]);

  const start = () => {
    setGrid(createLemonGrid());
    setScore(0);
    scoreRef.current = 0;
    setTimeLeft(TIME_LIMIT_MS);
    endsAt.current = Date.now() + TIME_LIMIT_MS;
    setPhase("playing");
  };

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (phase !== "playing") return;
    const cell = cellAt(e.currentTarget, e.clientX, e.clientY);
    if (!cell) return;
    dragRef.current = { start: cell, current: cell };
    setSelection({ start: cell, current: cell });
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag) return;
    const cell = cellAt(e.currentTarget, e.clientX, e.clientY);
    if (cell && (cell.row !== drag.current.row || cell.col !== drag.current.col)) {
      drag.current = cell;
      setSelection({ start: drag.start, current: cell });
    }
  };

  const endDrag = () => {
    const drag = dragRef.current;
    dragRef.current = null;
    setSelection(null);
    if (!drag) return;
    const a = drag.start;
    const b = drag.current;
    if (rectSum(grid, a, b) === TARGET_SUM) {
      const { grid: next, cleared } = clearRect(grid, a, b);
      setGrid(next);
      scoreRef.current += cleared;
      setScore(scoreRef.current);
    }
  };

  const selectionRect = selection ? rectBounds(selection.start, selection.current) : null;
  const selectionSum = selection ? rectSum(grid, selection.start, selection.current) : null;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4">
      <div className="flex items-center justify-between gap-2">
        <div className="text-sm text-muted-foreground">
          점수 <span className="text-lg font-bold text-foreground">{score}</span>
        </div>
        <div
          className={`text-sm ${timeLeft <= 10_000 && phase === "playing" ? "font-bold text-red-500" : "text-muted-foreground"}`}
        >
          {formatClock(timeLeft)}
        </div>
        <div className="text-sm text-muted-foreground">
          최고점 <span className="font-semibold text-foreground">{best ?? "—"}</span>
        </div>
      </div>

      {/* 드래그 중 배지가 나타나 그리드가 밀리지 않도록 자리를 고정한다. */}
      <div className="flex h-7 items-center justify-center" aria-live="polite">
        {phase === "playing" && selectionSum !== null && (
          <div
            className={`rounded-full px-3 py-0.5 text-sm font-bold ${
              selectionSum === TARGET_SUM
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground"
            }`}
          >
            {selectionSum} / {TARGET_SUM}
          </div>
        )}
      </div>

      <div
        className="relative grid touch-none select-none grid-cols-[repeat(17,minmax(0,1fr))] gap-0.5"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onContextMenu={(e) => e.preventDefault()}
      >
        {grid.map((row, r) =>
          row.map((v, c) => {
            const selected =
              selectionRect !== null && inBounds(selectionRect, { row: r, col: c });
            return (
              <div
                key={`${r}-${c}`}
                data-cell
                data-row={r}
                data-col={c}
                className="flex aspect-square items-center justify-center rounded-full"
              >
                {v > 0 && (
                  <div
                    className={`flex h-full w-full items-center justify-center rounded-full border bg-linear-to-b from-yellow-200 to-amber-300 text-[10px] font-bold text-amber-900 tabular-nums transition-shadow sm:text-xs ${
                      v >= 7 ? "border-amber-500/60" : "border-amber-400/40"
                    } ${selected ? "ring-2 ring-sky-500 ring-offset-1 ring-offset-background" : ""}`}
                  >
                    {v}
                  </div>
                )}
              </div>
            );
          }),
        )}

        {phase !== "playing" && (
          <div className="absolute inset-0 flex items-center justify-center rounded-xl bg-background/70 backdrop-blur-sm">
            <div className="flex flex-col items-center gap-3 rounded-xl border bg-card p-6 text-center shadow-lg">
              {phase === "ready" ? (
                <>
                  <p className="text-sm text-muted-foreground">
                    드래그로 직사각형을 그려 합이 {TARGET_SUM}이 되면 지워집니다
                  </p>
                  <Button onClick={start}>시작</Button>
                </>
              ) : (
                <>
                  <p className="text-lg font-bold">시간 종료</p>
                  <p className="text-sm text-muted-foreground">
                    점수 <span className="font-bold text-foreground">{score}</span>
                    {best !== null && score >= best && score > 0 && (
                      <span className="ml-1 font-semibold text-primary">(최고 기록!)</span>
                    )}
                  </p>
                  <Button onClick={start}>다시하기</Button>
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
