"use client";

import type { Side } from "../../lib/board-game";
import {
  BONUS,
  BONUS_THRESHOLD,
  CATEGORIES,
  CATEGORY_LABEL,
  UPPER_CATEGORIES,
  bonusOf,
  scoreCategory,
  totalOf,
  upperSum,
  type Category,
  type Sheet,
} from "../engine";

/** 진영 이름. white=선공, black=후공 */
export function yachtSideLabel(side: Side): string {
  return side === "white" ? "선공" : "후공";
}

export interface ScoreSheetProps {
  sheets: Record<Side, Sheet>;
  turn: Side;
  /** 열 제목 (닉네임 등). 없으면 선공/후공 */
  names?: Partial<Record<Side, string | null>>;
  /** 굴린 주사위. 있으면 차례인 쪽 빈 칸에 예상 점수를 보여준다 */
  dice: number[] | null;
  /** 있으면 예상 점수 칸을 눌러 기록한다 */
  onRecord?: (category: Category) => void;
  /** 보여줄 열. 혼자 하기는 한 열만 쓴다 */
  sides?: Side[];
}

const SIDES: Side[] = ["white", "black"];

/** 두 대국자의 점수표. 상단 소계·보너스·총점을 함께 보여준다 */
export function ScoreSheet({ sheets, turn, names, dice, onRecord, sides = SIDES }: ScoreSheetProps) {
  const cell = (side: Side, category: Category) => {
    const written = sheets[side][category];
    if (written !== undefined) return <span className="font-semibold">{written}</span>;
    if (side !== turn || !dice) return null;
    const preview = scoreCategory(dice, category);
    if (!onRecord) return <span className="text-sky-600/70 dark:text-sky-400/70">{preview}</span>;
    return (
      <button
        type="button"
        onClick={() => onRecord(category)}
        aria-label={`${CATEGORY_LABEL[category]}에 ${preview}점 기록`}
        className={`w-full rounded border border-dashed px-1 hover:bg-sky-600 hover:text-white ${
          preview > 0 ? "border-sky-400 text-sky-600 dark:text-sky-400" : "border-muted-foreground/30 text-muted-foreground"
        }`}
      >
        {preview}
      </button>
    );
  };

  const row = (category: Category) => (
    <tr key={category} className="border-t">
      <th scope="row" className="py-1 pr-2 text-left font-normal text-muted-foreground">
        {CATEGORY_LABEL[category]}
      </th>
      {sides.map((side) => (
        <td key={side} className={`py-1 text-center tabular-nums ${side === turn ? "bg-primary/5" : ""}`}>
          {cell(side, category)}
        </td>
      ))}
    </tr>
  );

  return (
    <table className="w-full table-fixed rounded-lg border bg-card text-sm">
      <thead>
        <tr>
          <th className="w-2/5" />
          {sides.map((side) => (
            <th
              key={side}
              scope="col"
              className={`truncate py-1.5 text-center font-semibold ${side === turn ? "bg-primary/10" : ""}`}
            >
              {names?.[side] ?? yachtSideLabel(side)}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {UPPER_CATEGORIES.map(row)}
        <tr className="border-t bg-muted/40 text-xs">
          <th scope="row" className="py-1 pr-2 text-left font-normal text-muted-foreground">
            소계 / 보너스 ({BONUS_THRESHOLD}↑ +{BONUS})
          </th>
          {sides.map((side) => (
            <td key={side} className="py-1 text-center tabular-nums">
              {upperSum(sheets[side])}/{BONUS_THRESHOLD} {bonusOf(sheets[side]) > 0 && <b>+{BONUS}</b>}
            </td>
          ))}
        </tr>
        {CATEGORIES.slice(UPPER_CATEGORIES.length).map(row)}
        <tr className="border-t-2 font-bold">
          <th scope="row" className="py-1.5 pr-2 text-left">
            총점
          </th>
          {sides.map((side) => (
            <td key={side} className="py-1.5 text-center tabular-nums">
              {totalOf(sheets[side])}
            </td>
          ))}
        </tr>
      </tbody>
    </table>
  );
}
