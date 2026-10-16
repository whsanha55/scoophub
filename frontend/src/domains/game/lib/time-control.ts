// 보드게임 공통 시간 규칙(기본 시간 + 초읽기) 순수 계산.
// 같은 화면 대국은 브라우저가, 초대 대국은 서버가 이 계산을 쓴다.

export type Side = "white" | "black";

export interface TimeRule {
  /** 대국자마다 주어지는 기본 시간(ms). 0이면 초읽기만 쓴다. */
  baseMs: number;
  /** 초읽기 한 수마다 주어지는 시간(ms). 0이면 초읽기가 없다. */
  byoYomiMs: number;
  /** 초읽기를 넘길 수 있는 총 횟수. */
  byoYomiCount: number;
}

/** 둘 다 0이면 시간 제한이 없다. */
export const NO_TIME_LIMIT: TimeRule = {
  baseMs: 0,
  byoYomiMs: 0,
  byoYomiCount: 0,
};

export const CHESS_DEFAULT_RULE: TimeRule = {
  baseMs: 10 * 60_000,
  byoYomiMs: 30_000,
  byoYomiCount: 1,
};

export const JANGGI_DEFAULT_RULE: TimeRule = {
  baseMs: 5 * 60_000,
  byoYomiMs: 30_000,
  byoYomiCount: 3,
};

export interface PlayerClock {
  /** 기본 시간 남은 ms. 음수가 될 수 없다. */
  baseLeftMs: number;
  /** 초읽기에 진입했는지. */
  inByoYomi: boolean;
  /** 이번 초읽기 수의 남은 ms. */
  byoYomiLeftMs: number;
  /** 남은 초읽기 넘김 횟수. */
  byoYomiLeft: number;
  /** 시간패. 더 이상 수를 둘 수 없다. */
  flagged?: boolean;
}

export function isUntimed(rule: TimeRule): boolean {
  return rule.baseMs === 0 && rule.byoYomiMs === 0;
}

export function initClock(rule: TimeRule): PlayerClock {
  // 기본 시간이 0이면 첫 수부터 초읽기로 둔다.
  const startInByoYomi = rule.baseMs === 0 && rule.byoYomiMs > 0;
  return {
    baseLeftMs: rule.baseMs,
    inByoYomi: startInByoYomi,
    byoYomiLeftMs: rule.byoYomiMs,
    byoYomiLeft: rule.byoYomiCount,
  };
}

/** 시간이 흐른 뒤 시계를 갱신한다. 기본 시간 소진 시 초읽기로 넘어간다. */
export function tickClock(
  clock: PlayerClock,
  rule: TimeRule,
  elapsedMs: number,
): PlayerClock {
  if (isUntimed(rule)) return clock;
  if (!clock.inByoYomi) {
    const left = clock.baseLeftMs - elapsedMs;
    if (left > 0) return { ...clock, baseLeftMs: left };
    // 기본 시간 소진 → 초읽기 진입(잔여 무시, 첫 수 전체를 준다)
    if (rule.byoYomiMs > 0) {
      return { ...clock, baseLeftMs: 0, inByoYomi: true, byoYomiLeftMs: rule.byoYomiMs };
    }
    return { ...clock, baseLeftMs: 0 };
  }
  const left = clock.byoYomiLeftMs - elapsedMs;
  if (left > 0) return { ...clock, byoYomiLeftMs: left };
  // 이번 초읽기 시간 소진 → 넘김 1회 소비. 남은 넘김이 없으면 시간패.
  if (clock.byoYomiLeft > 0) {
    return { ...clock, byoYomiLeft: clock.byoYomiLeft - 1, byoYomiLeftMs: rule.byoYomiMs };
  }
  return { ...clock, byoYomiLeftMs: 0, flagged: true };
}

/** 수를 둬서 시계가 멈췄을 때 갱신한다. 초읽기에서는 다음 수 시간을 다시 채운다. */
export function clockAfterMove(clock: PlayerClock, rule: TimeRule): PlayerClock {
  if (!clock.inByoYomi) return clock;
  return { ...clock, byoYomiLeftMs: rule.byoYomiMs };
}

/** 시간패 여부. */
export function isFlagged(clock: PlayerClock, rule: TimeRule): boolean {
  if (isUntimed(rule)) return false;
  if (clock.flagged) return true;
  if (!clock.inByoYomi) {
    return clock.baseLeftMs <= 0 && rule.byoYomiMs === 0;
  }
  return false;
}

export function formatClock(ms: number): string {
  const total = Math.ceil(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  if (m >= 60) {
    const h = Math.floor(m / 60);
    return `${h}:${String(m % 60).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  }
  return `${m}:${String(s).padStart(2, "0")}`;
}
