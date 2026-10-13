import assert from "node:assert/strict";
import { test } from "node:test";
import { formatSigma } from "../src/domains/stock/lib/sigma.ts";

test("σ 위치는 부호와 소수 둘째 자리로 표시한다", () => {
  assert.equal(formatSigma(0.564), "+0.56σ");
  assert.equal(formatSigma(-1.2), "−1.20σ");
  assert.equal(formatSigma(0), "0.00σ");
});
