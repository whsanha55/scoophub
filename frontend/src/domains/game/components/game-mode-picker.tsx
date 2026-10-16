import Link from "next/link";
import type { ReactNode } from "react";
import { JoinByCode } from "./join-by-code";

export interface GameMode {
  title: string;
  description: string;
  href: string;
  icon: ReactNode;
}

/** 종목을 고른 뒤 대국 방식을 고르는 화면. */
export function GameModePicker({ gameId, title, modes }: { gameId: string; title: string; modes: GameMode[] }) {
  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <Link href="/game" className="text-sm text-muted-foreground hover:text-foreground">
          ← 게임 목록
        </Link>
        <h1 className="text-2xl font-bold">{title}</h1>
      </div>
      <p className="text-sm text-muted-foreground">어떻게 둘까요?</p>
      <div className="grid gap-3 sm:grid-cols-2">
        {modes.map((mode) => (
          <Link
            key={mode.href}
            href={mode.href}
            className="flex flex-col gap-2 rounded-xl border bg-card p-4 transition-colors hover:bg-accent"
          >
            <div className="flex items-center gap-2">
              <span className="text-2xl" aria-hidden>
                {mode.icon}
              </span>
              <span className="font-semibold">{mode.title}</span>
            </div>
            <p className="text-sm text-muted-foreground">{mode.description}</p>
          </Link>
        ))}
      </div>
      <JoinByCode gameId={gameId} />
    </div>
  );
}

/** 장기 종목 아이콘. 맞는 이모지가 없어 장기 기물 모양으로 그린다. */
export function JanggiIcon() {
  return (
    <span className="inline-flex h-7 w-7 items-center justify-center rounded-full border border-emerald-300/40 bg-emerald-700 text-sm font-bold leading-none text-emerald-50">
      楚
    </span>
  );
}
