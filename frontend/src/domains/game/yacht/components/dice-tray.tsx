"use client";

import { Button } from "@/components/ui/button";
import { MAX_ROLLS } from "../engine";

// 3×3 칸 중 눈이 찍히는 자리 (0..8, 왼쪽 위부터)
const PIPS: Record<number, number[]> = {
  1: [4],
  2: [2, 6],
  3: [2, 4, 6],
  4: [0, 2, 6, 8],
  5: [0, 2, 4, 6, 8],
  6: [0, 2, 3, 5, 6, 8],
};

function DieFace({ value }: { value: number }) {
  if (value === 0) return <span className="text-lg text-muted-foreground">?</span>;
  return (
    <span className="grid h-full w-full grid-cols-3 grid-rows-3 p-[18%]">
      {Array.from({ length: 9 }, (_, i) => (
        <span key={i} className="flex items-center justify-center">
          {PIPS[value].includes(i) && <span className="h-[70%] w-[70%] rounded-full bg-current" />}
        </span>
      ))}
    </span>
  );
}

export interface DiceTrayProps {
  dice: number[];
  kept: boolean[];
  rollsLeft: number;
  /** 지금 굴리거나 고정을 바꿀 수 있는가 (내 차례) */
  canAct: boolean;
  onToggleKeep: (index: number) => void;
  onRoll: () => void;
}

/** 주사위 5개와 굴리기 버튼. 굴린 뒤 주사위를 누르면 고정한다 */
export function DiceTray({ dice, kept, rollsLeft, canAct, onToggleKeep, onRoll }: DiceTrayProps) {
  const rolled = rollsLeft < MAX_ROLLS;
  return (
    <div className="flex w-full flex-col items-center gap-3 rounded-xl border bg-card p-4">
      <div className="flex gap-2">
        {dice.map((value, i) => (
          <button
            key={i}
            type="button"
            aria-label={`주사위 ${i + 1}: ${value || "아직 안 굴림"}${kept[i] ? " (고정)" : ""}`}
            aria-pressed={kept[i]}
            disabled={!canAct || !rolled || rollsLeft === 0}
            onClick={() => onToggleKeep(i)}
            className={`relative flex aspect-square w-[min(15vw,4rem)] items-center justify-center rounded-xl border-2 shadow-sm transition-transform ${
              kept[i]
                ? "-translate-y-1 border-sky-500 bg-sky-50 text-sky-900 dark:bg-sky-950 dark:text-sky-100"
                : "border-border bg-background text-foreground"
            }`}
          >
            <DieFace value={value} />
            {kept[i] && (
              <span className="absolute -bottom-2 rounded bg-sky-500 px-1 text-[10px] leading-4 text-white">고정</span>
            )}
          </button>
        ))}
      </div>
      {canAct && (
        <Button onClick={onRoll} disabled={rollsLeft === 0} className="w-full max-w-xs">
          {rollsLeft === 0 ? "점수표에 기록하세요" : `굴리기 (${rollsLeft}회 남음)`}
        </Button>
      )}
      {!canAct && rolled && <p className="text-sm text-muted-foreground">남은 굴림 {rollsLeft}회</p>}
    </div>
  );
}
