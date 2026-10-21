"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { useLocalValue } from "../../lib/local-value";
import {
  MAX_ROLLS,
  initialSoloState,
  isSoloOver,
  soloRecord,
  soloRoll,
  soloToggleKeep,
  totalOf,
  type Category,
  type SoloYachtState,
} from "../engine";
import { DiceTray } from "./dice-tray";
import { ScoreSheet } from "./score-sheet";

const BEST_KEY = "game.yacht.solo.best";

export function SoloYachtGame() {
  const [state, setState] = useState<SoloYachtState>(() => initialSoloState());
  const [best, setBest] = useLocalValue<number | null>(BEST_KEY, null);
  const [newBest, setNewBest] = useState(false);
  const over = isSoloOver(state);
  const rolled = state.rollsLeft < MAX_ROLLS;

  const apply = (next: SoloYachtState | null) => {
    if (next) setState(next);
  };

  const onRecord = (category: Category) => {
    const next = soloRecord(state, category);
    if (!next) return;
    setState(next);
    if (isSoloOver(next)) {
      const total = totalOf(next.sheet);
      if (best === null || total > best) {
        setBest(total);
        setNewBest(true);
      }
    }
  };

  const restart = () => {
    setState(initialSoloState());
    setNewBest(false);
  };

  return (
    <div className="mx-auto flex w-full max-w-md flex-col gap-3">
      <div className="flex items-center justify-between text-sm">
        <span className="text-muted-foreground">12칸을 채워 최고 총점에 도전한다</span>
        <span>
          최고 기록 <b className="tabular-nums">{best ?? "-"}</b>
        </span>
      </div>
      <DiceTray
        dice={state.dice}
        kept={state.kept}
        rollsLeft={state.rollsLeft}
        canAct={!over}
        onToggleKeep={(i) => apply(soloToggleKeep(state, i))}
        onRoll={() => apply(soloRoll(state))}
      />
      <ScoreSheet
        sheets={{ white: state.sheet, black: {} }}
        turn="white"
        names={{ white: "점수" }}
        sides={["white"]}
        dice={!over && rolled ? state.dice : null}
        onRecord={over ? undefined : onRecord}
      />
      {over && (
        <div className="flex flex-col items-center gap-2 rounded-xl border bg-card p-5 text-center">
          <p className="text-lg font-bold">
            총점 {totalOf(state.sheet)}
            {newBest && <span className="ml-1 text-primary">(최고 기록!)</span>}
          </p>
          <Button onClick={restart}>다시하기</Button>
        </div>
      )}
    </div>
  );
}
