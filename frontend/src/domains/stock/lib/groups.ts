// #259 — 관심종목 테마 그룹. 키는 백엔드 stock_watchlist."group" (V39 CHECK 제약)과 같다.
// 배열 순서가 /stock 목록 섹션 순서다. individual 은 신규 추가·미분류 종목의 기본값.
export const WATCHLIST_GROUPS = [
  { key: "etf_index", label: "지수 ETF" },
  { key: "etf_sector", label: "섹터·테마 ETF" },
  { key: "bigtech_software", label: "빅테크·소프트웨어" },
  { key: "semiconductor", label: "반도체" },
  { key: "semi_equipment", label: "반도체 장비·설계" },
  { key: "ai_infra", label: "AI 인프라" },
  { key: "internet_consumer", label: "인터넷·소비" },
  { key: "healthcare", label: "헬스케어" },
  { key: "industrial_energy", label: "산업·에너지" },
  { key: "individual", label: "기타" },
] as const;

export type WatchlistGroup = (typeof WATCHLIST_GROUPS)[number]["key"];

export const DEFAULT_GROUP: WatchlistGroup = "individual";

export function groupLabel(key: string | null | undefined): string {
  return WATCHLIST_GROUPS.find((g) => g.key === key)?.label ?? key ?? "기타";
}

// 그룹 키 순서대로 섹션을 만든다. 모르는 키는 뒤에 붙이고, group 이 없으면 individual 로 본다.
export function groupByTheme<T extends { group?: string | null }>(items: T[]): { key: string; label: string; items: T[] }[] {
  const buckets = new Map<string, T[]>();
  for (const item of items) {
    const key = item.group || DEFAULT_GROUP;
    buckets.set(key, [...(buckets.get(key) ?? []), item]);
  }
  const order = WATCHLIST_GROUPS.map((g) => g.key as string);
  const rank = (k: string) => (order.includes(k) ? order.indexOf(k) : order.length);
  return [...buckets.keys()]
    .sort((a, b) => rank(a) - rank(b))
    .map((key) => ({ key, label: groupLabel(key), items: buckets.get(key) ?? [] }));
}
