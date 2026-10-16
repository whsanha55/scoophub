"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { ensureGuestId, ensureNickname } from "@/domains/game/lib/guest";
import { createRoom } from "@/domains/game/lib/room-client";
import { CHESS_DEFAULT_RULE, JANGGI_DEFAULT_RULE } from "@/domains/game/lib/time-control";

const GAMES = [
  { id: "chess", label: "체스", sides: [
    { value: "random", label: "무작위" },
    { value: "white", label: "백" },
    { value: "black", label: "흑" },
  ] },
  { id: "janggi", label: "장기", sides: [
    { value: "random", label: "무작위" },
    { value: "white", label: "초" },
    { value: "black", label: "한" },
  ] },
] as const;

type GameId = (typeof GAMES)[number]["id"];

export default function NewRoomPage() {
  const router = useRouter();
  const [gameId, setGameId] = useState<GameId>("chess");
  const [baseMin, setBaseMin] = useState(CHESS_DEFAULT_RULE.baseMs / 60_000);
  const [byoSec, setByoSec] = useState(CHESS_DEFAULT_RULE.byoYomiMs / 1000);
  const [byoCount, setByoCount] = useState(CHESS_DEFAULT_RULE.byoYomiCount);
  const [side, setSide] = useState<string>("random");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const game = GAMES.find((g) => g.id === gameId)!;

  const switchGame = (id: GameId) => {
    setGameId(id);
    const rule = id === "janggi" ? JANGGI_DEFAULT_RULE : CHESS_DEFAULT_RULE;
    setBaseMin(rule.baseMs / 60_000);
    setByoSec(rule.byoYomiMs / 1000);
    setByoCount(rule.byoYomiCount);
    setSide("random");
  };

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const guestId = await ensureGuestId();
      const nickname = await ensureNickname();
      const code = await createRoom(
        {
          gameId,
          timeRule: {
            baseMs: baseMin * 60_000,
            byoYomiMs: byoSec * 1000,
            byoYomiCount: byoSec > 0 ? byoCount : 0,
          },
          sidePreference: side as "white" | "black" | "random",
          requiresSetup: gameId === "janggi",
        },
        guestId,
        nickname,
      );
      router.push(`/game/room/${code}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "방을 만들지 못했다");
      setBusy(false);
    }
  };

  const stepper = (label: string, value: number, suffix: string, set: (v: number) => void, step: number, min: number, max: number) => (
    <div className="flex items-center justify-between gap-2">
      <span className="text-sm text-muted-foreground">{label}</span>
      <div className="flex items-center gap-1.5">
        <Button variant="outline" size="icon-sm" aria-label={`${label} 감소`} disabled={value <= min} onClick={() => set(Math.max(min, value - step))}>
          −
        </Button>
        <span className="w-16 text-center text-sm font-semibold tabular-nums">
          {value}
          {suffix}
        </span>
        <Button variant="outline" size="icon-sm" aria-label={`${label} 증가`} disabled={value >= max} onClick={() => set(Math.min(max, value + step))}>
          +
        </Button>
      </div>
    </div>
  );

  return (
    <div className="mx-auto flex w-full max-w-sm flex-col gap-4">
      <h1 className="text-2xl font-bold">초대 대국 만들기</h1>
      <div className="flex flex-col gap-4 rounded-xl border bg-card p-5">
        <h2 className="font-semibold">게임</h2>
        <div className="flex gap-1">
          {GAMES.map((g) => (
            <Button key={g.id} variant={gameId === g.id ? "default" : "outline"} size="sm" onClick={() => switchGame(g.id)}>
              {g.label}
            </Button>
          ))}
        </div>
        <h2 className="font-semibold">내 진영</h2>
        <div className="flex gap-1">
          {game.sides.map((s) => (
            <Button key={s.value} variant={side === s.value ? "default" : "outline"} size="sm" onClick={() => setSide(s.value)}>
              {s.label}
            </Button>
          ))}
        </div>
        <h2 className="font-semibold">시간 규칙</h2>
        <div className="flex flex-col gap-3">
          {stepper("기본 시간", baseMin, "분", setBaseMin, 1, 0, 60)}
          {stepper("초읽기", byoSec, "초", setByoSec, 5, 0, 60)}
          {stepper("초읽기 횟수", byoCount, "회", (v) => { setByoCount(v); if (byoSec === 0) setByoSec(5); }, 1, byoSec > 0 ? 1 : 0, 5)}
        </div>
        {baseMin === 0 && byoSec === 0 && <p className="text-sm text-muted-foreground">시간 제한 없이 둔다</p>}
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button onClick={create} disabled={busy}>
          {busy ? "만드는 중…" : "방 만들기"}
        </Button>
      </div>
    </div>
  );
}
