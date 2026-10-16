"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { checkRoom } from "../lib/room-client";

/** 그 게임의 초대코드로 방에 들어간다. 초대코드는 게임마다 따로다. */
export function JoinByCode({ gameId }: { gameId: string }) {
  const router = useRouter();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const join = async () => {
    const trimmed = code.trim();
    if (!/^\d{6}$/.test(trimmed)) {
      setError("초대코드는 6자리 숫자다");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const summary = await checkRoom(gameId, trimmed);
      if (!summary.exists) {
        setError("없는 초대코드다");
        setBusy(false);
        return;
      }
      router.push(`/game/room/${gameId}/${trimmed}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "입장하지 못했다");
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-3 rounded-xl border bg-card p-4">
      <div className="flex items-center gap-2">
        <span className="text-2xl" aria-hidden>
          🔑
        </span>
        <span className="font-semibold">초대코드로 입장</span>
      </div>
      <div className="flex gap-2">
        <Input
          inputMode="numeric"
          pattern="\d*"
          maxLength={6}
          placeholder="123456"
          aria-label="초대코드"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
          onKeyDown={(e) => {
            if (e.key === "Enter") join();
          }}
          className="text-center text-lg font-bold tracking-[0.3em]"
        />
        <Button onClick={join} disabled={busy}>
          {busy ? "확인 중…" : "입장"}
        </Button>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <p className="text-sm text-muted-foreground">공유받은 주소로 열었다면 코드 입력 없이 바로 들어간다.</p>
    </div>
  );
}
