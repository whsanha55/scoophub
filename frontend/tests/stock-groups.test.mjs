import assert from "node:assert/strict";
import { test } from "node:test";
import { groupByTheme, groupLabel } from "../src/domains/stock/lib/groups.ts";

test("테마 그룹은 정의 순서로 섹션을 만들고 그룹이 없으면 기타로 묶는다", () => {
  const sections = groupByTheme([
    { ticker: "NVDA", group: "semiconductor" },
    { ticker: "QQQ", group: "etf_index" },
    { ticker: "AMD", group: "semiconductor" },
    { ticker: "NEW", group: null },
    { ticker: "OLD", group: "unknown_key" },
  ]);

  assert.deepEqual(
    sections.map((s) => [s.key, s.label, s.items.map((i) => i.ticker)]),
    [
      ["etf_index", "지수 ETF", ["QQQ"]],
      ["semiconductor", "반도체", ["NVDA", "AMD"]],
      ["individual", "기타", ["NEW"]],
      ["unknown_key", "unknown_key", ["OLD"]],
    ],
  );
});

test("그룹 라벨은 모르는 키를 그대로, 빈 값은 기타로 보여준다", () => {
  assert.equal(groupLabel("ai_infra"), "AI 인프라");
  assert.equal(groupLabel("market"), "market");
  assert.equal(groupLabel(null), "기타");
});
