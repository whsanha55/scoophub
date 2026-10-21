import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CATEGORIES,
  applyKeep,
  applyRecord,
  applyRoll,
  bonusOf,
  initialState,
  record,
  rollDice,
  randomDie,
  scoreCategory,
  status,
  totalOf,
  upperSum,
} from "../src/domains/game/yacht/engine.ts";

// ---------- 칸별 점수 ----------

test("상단 6칸: 그 눈의 합", () => {
  const dice = [1, 1, 3, 6, 6];
  assert.equal(scoreCategory(dice, "ones"), 2);
  assert.equal(scoreCategory(dice, "twos"), 0);
  assert.equal(scoreCategory(dice, "threes"), 3);
  assert.equal(scoreCategory(dice, "sixes"), 12);
});

test("초이스: 주사위 5개 합", () => {
  assert.equal(scoreCategory([1, 2, 3, 4, 6], "choice"), 16);
});

test("포카드: 같은 눈 4개 이상이면 주사위 합, 아니면 0", () => {
  assert.equal(scoreCategory([5, 5, 5, 5, 2], "fourKind"), 22);
  assert.equal(scoreCategory([4, 4, 4, 4, 4], "fourKind"), 20);
  assert.equal(scoreCategory([5, 5, 5, 2, 2], "fourKind"), 0);
});

test("풀하우스: 3+2면 주사위 합, 5개 같은 눈은 아니다", () => {
  assert.equal(scoreCategory([2, 2, 3, 3, 3], "fullHouse"), 13);
  assert.equal(scoreCategory([6, 6, 6, 6, 6], "fullHouse"), 0);
  assert.equal(scoreCategory([2, 2, 3, 3, 4], "fullHouse"), 0);
});

test("스몰 스트레이트: 4연속이면 15 (순서·중복 무관)", () => {
  assert.equal(scoreCategory([3, 1, 2, 4, 4], "smallStraight"), 15);
  assert.equal(scoreCategory([6, 5, 4, 3, 1], "smallStraight"), 15);
  assert.equal(scoreCategory([1, 2, 3, 5, 6], "smallStraight"), 0);
});

test("라지 스트레이트: 5연속이면 30, 라지는 스몰도 만족한다", () => {
  assert.equal(scoreCategory([2, 3, 4, 5, 6], "largeStraight"), 30);
  assert.equal(scoreCategory([5, 4, 3, 2, 1], "largeStraight"), 30);
  assert.equal(scoreCategory([1, 2, 3, 4, 6], "largeStraight"), 0);
  assert.equal(scoreCategory([1, 2, 3, 4, 5], "smallStraight"), 15);
});

test("야추: 5개 같은 눈이면 50", () => {
  assert.equal(scoreCategory([3, 3, 3, 3, 3], "yacht"), 50);
  assert.equal(scoreCategory([3, 3, 3, 3, 2], "yacht"), 0);
});

// ---------- 보너스·총점 ----------

test("상단 합계가 63 이상이면 보너스 35", () => {
  const sheet = { ones: 3, twos: 6, threes: 9, fours: 12, fives: 15, sixes: 18 };
  assert.equal(upperSum(sheet), 63);
  assert.equal(bonusOf(sheet), 35);
  assert.equal(totalOf(sheet), 98);
  assert.equal(bonusOf({ ...sheet, sixes: 12 }), 0);
});

// ---------- 굴림·고정·기록 ----------

test("주사위는 1~6만 나온다", () => {
  for (let i = 0; i < 600; i++) {
    const d = randomDie();
    assert.ok(Number.isInteger(d) && d >= 1 && d <= 6);
  }
});

test("고정한 주사위는 다시 굴리지 않는다", () => {
  let n = 0;
  const die = () => [6, 6, 6][n++];
  assert.deepEqual(rollDice([1, 2, 3, 4, 5], [true, false, true, false, true], die), [1, 6, 3, 6, 5]);
});

test("한 차례에 3번까지 굴리고, 굴린 뒤에만 고정·기록할 수 있다", () => {
  let state = initialState();
  assert.equal(applyKeep(state, [true, false, false, false, false]), null, "굴리기 전 고정 불가");
  assert.equal(record(state, "choice"), null, "굴리기 전 기록 불가");
  state = applyRoll(state, [1, 2, 3, 4, 5]);
  state = applyRoll(state, [1, 2, 3, 4, 5]);
  state = applyRoll(state, [1, 2, 3, 4, 5]);
  assert.equal(state.rollsLeft, 0);
  assert.equal(applyRoll(state, [1, 2, 3, 4, 5]), null, "4번째 굴림 불가");
});

test("굴림 결과를 받을 때 고정한 주사위는 이전 눈을 유지한다 (조작 무시)", () => {
  let state = applyRoll(initialState(), [2, 2, 3, 4, 5]);
  state = applyKeep(state, [true, true, false, false, false]);
  state = applyRoll(state, [6, 6, 6, 6, 6]);
  assert.deepEqual(state.dice, [2, 2, 6, 6, 6]);
});

test("잘못된 굴림·고정 값은 거부한다", () => {
  const state = initialState();
  assert.equal(applyRoll(state, [0, 1, 2, 3, 4]), null);
  assert.equal(applyRoll(state, [1, 2, 3]), null);
  assert.equal(applyRoll(state, "12345"), null);
  const rolled = applyRoll(state, [1, 2, 3, 4, 5]);
  assert.equal(applyKeep(rolled, [1, 0, 0, 0, 0]), null);
});

test("기록하면 점수가 적히고 차례가 넘어가며 주사위·고정·굴림이 초기화된다", () => {
  let state = applyRoll(initialState(), [2, 2, 3, 3, 3]);
  state = applyKeep(state, [true, false, false, false, false]);
  const recorded = record(state, "fullHouse");
  assert.equal(recorded.score, 13);
  assert.equal(recorded.notation, "풀하우스 13");
  assert.equal(recorded.state.sheets.white.fullHouse, 13);
  assert.equal(recorded.state.turn, "black");
  assert.deepEqual(recorded.state.dice, [0, 0, 0, 0, 0]);
  assert.deepEqual(recorded.state.kept, [false, false, false, false, false]);
  assert.equal(recorded.state.rollsLeft, 3);
});

test("0점 기록도 칸을 채우고, 이미 채운 칸에는 다시 기록할 수 없다", () => {
  let state = applyRoll(initialState(), [1, 2, 3, 5, 6]);
  state = record(state, "yacht").state;
  assert.equal(state.sheets.white.yacht, 0);
  state = record(applyRoll(state, [1, 1, 1, 1, 1]), "ones").state; // 후공
  state = applyRoll(state, [3, 3, 3, 3, 3]);
  assert.equal(record(state, "yacht"), null);
});

test("초대 대국 재생: 받은 점수를 그대로 적고, 잘못된 칸·점수는 거부한다", () => {
  const state = initialState();
  assert.equal(applyRecord(state, "choice", 24).sheets.white.choice, 24);
  assert.equal(applyRecord(state, "nope", 10), null);
  assert.equal(applyRecord(state, "choice", -1), null);
  assert.equal(applyRecord(state, "choice", 1.5), null);
});

// ---------- 종료 ----------

function fullSheet(score) {
  return Object.fromEntries(CATEGORIES.map((c) => [c, score]));
}

test("양쪽 점수표가 다 차면 총점이 높은 쪽이 이긴다", () => {
  const state = { ...initialState(), sheets: { white: fullSheet(10), black: fullSheet(5) } };
  // 상단 합계 60 → 보너스 없음
  assert.deepEqual(status(state), { over: true, winner: "white", reason: "총점 120 : 60" });
});

test("동점이면 무승부, 한쪽이라도 빈 칸이 있으면 끝나지 않는다", () => {
  const tie = { ...initialState(), sheets: { white: fullSheet(5), black: fullSheet(5) } };
  assert.equal(status(tie).winner, null);
  assert.equal(status(tie).over, true);
  const partial = fullSheet(5);
  delete partial.yacht;
  const ongoing = { ...initialState(), sheets: { white: fullSheet(5), black: partial } };
  assert.equal(status(ongoing).over, false);
});

test("마지막 칸 기록으로 대국이 끝나면 더 굴릴 수 없다", () => {
  const partial = fullSheet(5);
  delete partial.yacht;
  let state = { ...initialState(), turn: "black", sheets: { white: fullSheet(5), black: partial } };
  state = applyRoll(state, [4, 4, 4, 4, 4]);
  const done = record(state, "yacht").state;
  assert.equal(status(done).winner, "black");
  assert.equal(applyRoll(done, [1, 2, 3, 4, 5]), null);
});
