// 야추다이스 규칙 엔진. 순수 계산이고, 주사위 무작위성만 굴리는 쪽이 만든다(ADR-0004).
// 점수 규칙: 닌텐도 「세계의 아소비 대전 51」 야추 기준 (이슈 #315).
// 두 대국자가 번갈아 차례를 갖는다. 한 차례에 최대 3번 굴리고, 점수표의 빈 칸 하나에 기록하면 차례가 넘어간다.
// Side white=선공, black=후공.
import type { GameStatus, Side } from "../lib/board-game";

export const CATEGORIES = [
  "ones",
  "twos",
  "threes",
  "fours",
  "fives",
  "sixes",
  "choice",
  "fourKind",
  "fullHouse",
  "smallStraight",
  "largeStraight",
  "yacht",
] as const;
export type Category = (typeof CATEGORIES)[number];

/** 점수표 상단 6칸. 합계가 [BONUS_THRESHOLD] 이상이면 보너스를 준다 */
export const UPPER_CATEGORIES: readonly Category[] = CATEGORIES.slice(0, 6);

export const CATEGORY_LABEL: Record<Category, string> = {
  ones: "에이스",
  twos: "듀스",
  threes: "트리플",
  fours: "쿼드",
  fives: "파이브",
  sixes: "식스",
  choice: "초이스",
  fourKind: "포카드",
  fullHouse: "풀하우스",
  smallStraight: "스몰 스트레이트",
  largeStraight: "라지 스트레이트",
  yacht: "야추",
};

export const DICE_COUNT = 5;
export const MAX_ROLLS = 3;
export const BONUS_THRESHOLD = 63;
export const BONUS = 35;

export type Sheet = Partial<Record<Category, number>>;

export interface YachtState {
  sheets: Record<Side, Sheet>;
  turn: Side;
  /** 이번 차례 주사위 눈. 아직 굴리지 않았으면 0 */
  dice: number[];
  /** 다음 굴림에서 제외할(고정한) 주사위 */
  kept: boolean[];
  /** 이번 차례에 남은 굴림 횟수 */
  rollsLeft: number;
}

const sum = (dice: number[]) => dice.reduce((a, b) => a + b, 0);

function counts(dice: number[]): number[] {
  const c = [0, 0, 0, 0, 0, 0, 0];
  for (const d of dice) c[d]++;
  return c;
}

function hasRun(dice: number[], length: number): boolean {
  const faces = new Set(dice);
  for (let start = 1; start + length - 1 <= 6; start++) {
    let ok = true;
    for (let f = start; f < start + length; f++) if (!faces.has(f)) ok = false;
    if (ok) return true;
  }
  return false;
}

/** 이 주사위로 그 칸에 기록하면 얻는 점수 */
export function scoreCategory(dice: number[], category: Category): number {
  const c = counts(dice);
  const upperFace = UPPER_CATEGORIES.indexOf(category) + 1;
  if (upperFace > 0) return c[upperFace] * upperFace;
  switch (category) {
    case "choice":
      return sum(dice);
    case "fourKind":
      return c.some((n) => n >= 4) ? sum(dice) : 0;
    case "fullHouse": {
      const groups = c.filter((n) => n > 0).sort();
      return groups.length === 2 && groups[0] === 2 && groups[1] === 3 ? sum(dice) : 0;
    }
    case "smallStraight":
      return hasRun(dice, 4) ? 15 : 0;
    case "largeStraight":
      return hasRun(dice, 5) ? 30 : 0;
    case "yacht":
      return c.some((n) => n === 5) ? 50 : 0;
    default:
      return 0;
  }
}

export function upperSum(sheet: Sheet): number {
  return UPPER_CATEGORIES.reduce((total, cat) => total + (sheet[cat] ?? 0), 0);
}

export function bonusOf(sheet: Sheet): number {
  return upperSum(sheet) >= BONUS_THRESHOLD ? BONUS : 0;
}

export function totalOf(sheet: Sheet): number {
  return CATEGORIES.reduce((total, cat) => total + (sheet[cat] ?? 0), 0) + bonusOf(sheet);
}

/** 주사위 하나. crypto 난수에서 6으로 나눠떨어지지 않는 꼬리를 버려 치우침을 없앤다 */
export function randomDie(): number {
  const buf = new Uint8Array(1);
  for (;;) {
    crypto.getRandomValues(buf);
    if (buf[0] < 252) return (buf[0] % 6) + 1;
  }
}

/** 고정하지 않은 주사위만 새로 굴린 5개 눈 */
export function rollDice(dice: number[], kept: boolean[], die: () => number = randomDie): number[] {
  return Array.from({ length: DICE_COUNT }, (_, i) => (kept[i] && dice[i] > 0 ? dice[i] : die()));
}

export function initialState(): YachtState {
  return {
    sheets: { white: {}, black: {} },
    turn: "white",
    dice: Array(DICE_COUNT).fill(0),
    kept: Array(DICE_COUNT).fill(false),
    rollsLeft: MAX_ROLLS,
  };
}

export function hasRolled(state: YachtState): boolean {
  return state.rollsLeft < MAX_ROLLS;
}

const isFull = (sheet: Sheet) => CATEGORIES.every((cat) => sheet[cat] !== undefined);

/**
 * 굴림 결과를 반영한다. 굴릴 수 없거나 눈이 잘못됐으면 null.
 * 고정한 주사위는 받은 값과 상관없이 이전 눈을 유지한다(조작된 행동 일부를 무시, ADR-0004).
 */
export function applyRoll(state: YachtState, dice: unknown): YachtState | null {
  if (state.rollsLeft <= 0 || status(state).over) return null;
  if (!Array.isArray(dice) || dice.length !== DICE_COUNT) return null;
  if (!dice.every((d) => Number.isInteger(d) && d >= 1 && d <= 6)) return null;
  const next = dice.map((d: number, i) => (state.kept[i] && state.dice[i] > 0 ? state.dice[i] : d));
  return { ...state, dice: next, rollsLeft: state.rollsLeft - 1 };
}

/** 고정 상태를 바꾼다. 한 번이라도 굴린 뒤에만 할 수 있다 */
export function applyKeep(state: YachtState, kept: unknown): YachtState | null {
  if (!hasRolled(state) || status(state).over) return null;
  if (!Array.isArray(kept) || kept.length !== DICE_COUNT || !kept.every((k) => typeof k === "boolean")) return null;
  return { ...state, kept: [...kept] };
}

/** 차례인 쪽 점수표 빈 칸에 점수를 적고 차례를 넘긴다. 초대 대국에서는 받은 점수를 그대로 쓴다 */
export function applyRecord(state: YachtState, category: Category, score: number): YachtState | null {
  if (!CATEGORIES.includes(category) || !Number.isInteger(score) || score < 0) return null;
  if (status(state).over || state.sheets[state.turn][category] !== undefined) return null;
  const sheets = { ...state.sheets, [state.turn]: { ...state.sheets[state.turn], [category]: score } };
  return {
    sheets,
    turn: state.turn === "white" ? "black" : "white",
    dice: Array(DICE_COUNT).fill(0),
    kept: Array(DICE_COUNT).fill(false),
    rollsLeft: MAX_ROLLS,
  };
}

/** 이번 주사위로 기록한다. 굴리기 전에는 기록할 수 없다 */
export function record(
  state: YachtState,
  category: Category,
): { state: YachtState; score: number; notation: string } | null {
  if (!hasRolled(state)) return null;
  const score = scoreCategory(state.dice, category);
  const next = applyRecord(state, category, score);
  return next && { state: next, score, notation: `${CATEGORY_LABEL[category]} ${score}` };
}

/** 양쪽 점수표가 다 차면 끝난다. 총점이 높은 쪽이 이기고, 같으면 무승부 */
export function status(state: YachtState): GameStatus {
  if (!isFull(state.sheets.white) || !isFull(state.sheets.black)) {
    return { over: false, winner: null, reason: null };
  }
  const white = totalOf(state.sheets.white);
  const black = totalOf(state.sheets.black);
  const winner = white === black ? null : white > black ? "white" : "black";
  return { over: true, winner, reason: `총점 ${white} : ${black}` };
}
