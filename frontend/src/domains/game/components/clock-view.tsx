"use client";

import { formatClock, type PlayerClock } from "../lib/time-control";

/** 대국자 한 명의 시계. 차례인 쪽을 강조하고, 10초 이하로 남으면 빨갛게 보인다. */
export function ClockView({
  clock,
  label,
  active,
  untimed,
}: {
  clock: PlayerClock;
  label: string;
  active: boolean;
  untimed: boolean;
}) {
  const ms = clock.inByoYomi ? clock.byoYomiLeftMs : clock.baseLeftMs;
  return (
    <div
      className={`flex items-center justify-between rounded-lg border px-3 py-1.5 ${
        active ? "border-primary bg-primary/10" : "border-border"
      }`}
    >
      <span className="truncate text-sm font-semibold">{label}</span>
      {untimed ? (
        <span className="text-sm text-muted-foreground">무제한</span>
      ) : (
        <span className="flex items-baseline gap-2">
          {clock.inByoYomi && (
            <span className="text-xs text-muted-foreground">초읽기 ×{clock.byoYomiLeft}</span>
          )}
          <span className={`text-lg font-bold tabular-nums ${ms <= 10_000 ? "text-red-500" : ""}`}>
            {formatClock(ms)}
          </span>
        </span>
      )}
    </div>
  );
}
