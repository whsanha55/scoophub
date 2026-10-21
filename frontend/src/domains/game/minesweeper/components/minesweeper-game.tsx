"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useLocalValue } from "../../lib/local-value";
import {
  LEVELS,
  LEVEL_LABEL,
  chord,
  flagsPlaced,
  newField,
  reveal,
  toggleFlag,
  type Field,
  type LevelId,
} from "../engine";

const NUMBER_CLASS = [
  "",
  "text-blue-600 dark:text-blue-400",
  "text-green-600 dark:text-green-400",
  "text-red-600 dark:text-red-400",
  "text-indigo-700 dark:text-indigo-300",
  "text-amber-800 dark:text-amber-500",
  "text-cyan-600 dark:text-cyan-400",
  "text-foreground",
  "text-muted-foreground",
];

const formatSeconds = (ms: number) => (ms / 1000).toFixed(1);

export function MinesweeperGame() {
  const [levelId, setLevelId] = useState<LevelId>("beginner");
  const [field, setField] = useState<Field>(() => newField(LEVELS.beginner));
  const [flagMode, setFlagMode] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [newBest, setNewBest] = useState(false);
  const [best, setBest] = useLocalValue<number | null>(`game.minesweeper.best.${levelId}`, null);
  const startedAt = useRef(0);

  // 진행 중에만 시계를 돌린다
  useEffect(() => {
    if (field.status !== "playing") return;
    const id = setInterval(() => setElapsed(performance.now() - startedAt.current), 100);
    return () => clearInterval(id);
  }, [field.status]);

  const reset = (id: LevelId = levelId) => {
    setLevelId(id);
    setField(newField(LEVELS[id]));
    setElapsed(0);
    setNewBest(false);
  };

  // now 는 이벤트의 timeStamp (performance.now() 와 같은 기준)
  const apply = (next: Field, now: number) => {
    if (next === field) return;
    if (field.status === "ready" && next.status !== "ready") startedAt.current = now;
    if (next.status === "won" || next.status === "lost") {
      const time = now - startedAt.current;
      setElapsed(time);
      if (next.status === "won" && (best === null || time < best)) {
        setBest(time);
        setNewBest(true);
      }
    }
    setField(next);
  };

  const onCell = (index: number, now: number) => {
    const cell = field.cells[index];
    if (!cell) return;
    if (flagMode && !cell.open) apply(toggleFlag(field, index), now);
    else if (cell.open) apply(chord(field, index), now);
    else apply(reveal(field, index), now);
  };

  const over = field.status === "won" || field.status === "lost";

  return (
    <div className="flex w-full flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {(Object.keys(LEVELS) as LevelId[]).map((id) => (
          <Button key={id} size="sm" variant={id === levelId ? "default" : "outline"} onClick={() => reset(id)}>
            {LEVEL_LABEL[id]}
          </Button>
        ))}
        <span className="ml-auto text-sm">
          최고 기록 <b className="tabular-nums">{best === null ? "-" : `${formatSeconds(best)}초`}</b>
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span>
          남은 지뢰 <b className="tabular-nums">{field.mines - flagsPlaced(field)}</b>
        </span>
        <span>
          시간 <b className="tabular-nums">{Math.floor(elapsed / 1000)}</b>초
        </span>
        <Button
          size="sm"
          className="ml-auto"
          variant={flagMode ? "default" : "outline"}
          aria-pressed={flagMode}
          onClick={() => setFlagMode((v) => !v)}
        >
          🚩 깃발 모드
        </Button>
        <Button size="sm" variant="outline" onClick={() => reset()}>
          새 게임
        </Button>
      </div>
      <div className="max-w-full overflow-x-auto">
        <div
          role="grid"
          aria-label={`지뢰찾기 ${LEVEL_LABEL[levelId]} 판`}
          className="mx-auto grid w-max gap-px rounded-md border bg-border p-px select-none"
          style={{ gridTemplateColumns: `repeat(${field.cols}, 1.75rem)` }}
        >
          {field.cells.map((cell, i) => (
            <button
              key={i}
              type="button"
              disabled={over}
              onClick={(e) => onCell(i, e.timeStamp)}
              onContextMenu={(e) => {
                e.preventDefault();
                apply(toggleFlag(field, i), e.timeStamp);
              }}
              aria-label={cell.open ? (cell.mine ? "지뢰" : `${cell.adjacent}`) : cell.flag ? "깃발" : "닫힌 칸"}
              className={`flex h-7 w-7 items-center justify-center text-sm font-bold ${
                cell.open
                  ? cell.mine
                    ? "bg-red-200 dark:bg-red-900"
                    : `bg-background ${NUMBER_CLASS[cell.adjacent] ?? ""}`
                  : "bg-muted hover:bg-accent"
              }`}
            >
              {cell.open ? (cell.mine ? "💣" : cell.adjacent || null) : cell.flag ? "🚩" : null}
            </button>
          ))}
        </div>
      </div>
      {over && (
        <div className="mx-auto flex flex-col items-center gap-2 rounded-xl border bg-card p-5 text-center">
          <p className="text-lg font-bold">{field.status === "won" ? "성공!" : "지뢰를 밟았다"}</p>
          {field.status === "won" && (
            <p className="text-sm">
              {formatSeconds(elapsed)}초
              {newBest && <span className="ml-1 font-semibold text-primary">(최고 기록!)</span>}
            </p>
          )}
          <Button onClick={() => reset()}>다시하기</Button>
        </div>
      )}
      <p className="text-center text-xs text-muted-foreground">
        우클릭 또는 깃발 모드로 깃발을 꽂는다. 열린 숫자 칸을 누르면 깃발 수가 맞을 때 주변을 한 번에 연다.
      </p>
    </div>
  );
}
