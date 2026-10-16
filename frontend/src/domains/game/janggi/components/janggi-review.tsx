"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { janggiEngine, type JanggiState } from "../engine";
import { JanggiBoard } from "./janggi-board";

function replayJanggi(
  moves: string[],
  setup: { cho: string; han: string },
): { states: JanggiState[] } {
  let state = janggiEngine.initialState(setup);
  const states = [state];
  for (const notation of moves) {
    if (notation === "한수쉼") {
      const r = janggiEngine.pass(state);
      if (!r) break;
      state = r.state;
    } else {
      const coords = notation.match(/(\d+),(\d+)→(\d+),(\d+)/);
      if (!coords) break;
      const r = janggiEngine.applyMovePos(
        state,
        { row: Number(coords[1]), col: Number(coords[2]) },
        { row: Number(coords[3]), col: Number(coords[4]) },
      );
      if (!r) break;
      state = r.state;
    }
    states.push(state);
  }
  return { states };
}

/** 장기 기보 다시보기. */
export function JanggiReview({
  moves,
  setup,
  result,
}: {
  moves: string[];
  setup: { cho: string; han: string };
  result?: string;
}) {
  const [plyIndex, setPly] = useState(moves.length);
  const { states } = useMemo(() => replayJanggi(moves, setup), [moves, setup]);
  const current = states[Math.min(plyIndex, states.length - 1)];
  const lastMove = current.moves[current.moves.length - 1];
  const canPrev = plyIndex > 0;
  const canNext = plyIndex < states.length - 1;

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col items-center gap-3">
      <JanggiBoard
        board={current.board}
        orientation="white"
        selected={null}
        targets={[]}
        lastMove={
          lastMove && !lastMove.pass
            ? {
                from: `${lastMove.from.row},${lastMove.from.col}`,
                to: `${lastMove.to.row},${lastMove.to.col}`,
              }
            : null
        }
        checkSquare={null}
        onPointTap={() => {}}
      />
      <div className="flex items-center justify-center gap-2">
        <Button variant="outline" size="sm" onClick={() => setPly(0)} disabled={!canPrev}>
          처음
        </Button>
        <Button variant="outline" size="sm" onClick={() => setPly((p) => Math.max(0, p - 1))} disabled={!canPrev}>
          이전
        </Button>
        <span className="w-16 text-center text-sm tabular-nums text-muted-foreground">
          {plyIndex}수
        </span>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setPly((p) => Math.min(states.length - 1, p + 1))}
          disabled={!canNext}
        >
          다음
        </Button>
        <Button variant="outline" size="sm" onClick={() => setPly(states.length - 1)} disabled={!canNext}>
          끝
        </Button>
      </div>
      {moves.length > 0 && (
        <div className="max-h-36 w-full overflow-y-auto rounded-lg border bg-card p-2">
          <ol className="flex flex-wrap gap-x-3 text-sm tabular-nums">
            {moves.map((n, i) => (
              <li
                key={i}
                className={`cursor-pointer rounded px-1 ${plyIndex === i + 1 ? "bg-accent font-semibold" : ""}`}
                onClick={() => setPly(i + 1)}
              >
                <span className="mr-1 text-muted-foreground">{i + 1}.</span>
                {i % 2 === 0 ? "초" : "한"} {n}
              </li>
            ))}
          </ol>
        </div>
      )}
      {result && (
        <p className="text-sm text-muted-foreground">
          결과 {result === "white" ? "초 승리" : result === "black" ? "한 승리" : "무승부"}
        </p>
      )}
    </div>
  );
}
