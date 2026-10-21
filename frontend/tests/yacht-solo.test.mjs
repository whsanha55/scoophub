import assert from "node:assert/strict";
import { test } from "node:test";
import {
  CATEGORIES,
  MAX_ROLLS,
  initialSoloState,
  isSoloOver,
  soloRecord,
  soloRoll,
  soloToggleKeep,
} from "../src/domains/game/yacht/engine.ts";

const six = () => 6;

test("굴리기 전에는 고정도 기록도 할 수 없다", () => {
  const s = initialSoloState();
  assert.equal(soloToggleKeep(s, 0), null);
  assert.equal(soloRecord(s, "ones"), null);
});

test("한 차례에 3번까지 굴리고, 고정한 주사위는 그대로 둔다", () => {
  let s = soloRoll(initialSoloState(), () => 2);
  s = soloToggleKeep(s, 0);
  s = soloRoll(s, six);
  assert.deepEqual(s.dice, [2, 6, 6, 6, 6]);
  s = soloRoll(s, six);
  assert.equal(s.rollsLeft, 0);
  assert.equal(soloRoll(s, six), null);
});

test("기록하면 점수가 적히고 다음 차례가 새로 시작된다", () => {
  const rolled = soloRoll(initialSoloState(), six);
  const s = soloRecord(rolled, "yacht");
  assert.equal(s.sheet.yacht, 50);
  assert.equal(s.rollsLeft, MAX_ROLLS);
  assert.deepEqual(s.kept, [false, false, false, false, false]);
  assert.equal(soloRecord(soloRoll(s, six), "yacht"), null, "이미 적힌 칸에는 다시 기록할 수 없다");
});

test("12칸을 다 채우면 끝나고 더 굴릴 수 없다", () => {
  let s = initialSoloState();
  for (const category of CATEGORIES) {
    assert.equal(isSoloOver(s), false);
    s = soloRecord(soloRoll(s, six), category);
  }
  assert.equal(isSoloOver(s), true);
  assert.equal(soloRoll(s, six), null);
});
