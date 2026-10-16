"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { checkRoom } from "@/domains/game/lib/room-client";

export default function JoinRoomPage() {
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
      const summary = await checkRoom(trimmed);
      if (!summary.exists) {
        setError("없는 초대코드다");
        setBusy(false);
        return;
      }
      router.push(`/game/room/${trimmed}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "입장하지 못했다");
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto flex w-full max-w-sm flex-col gap-4">
      <h1 className="text-2xl font-bold">초대코드로 입장</h1>
      <div className="flex flex-col gap-4 rounded-xl border bg-card p-5">
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
          className="text-center text-2xl font-bold tracking-[0.3em]"
        />
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button onClick={join} disabled={busy}>
          {busy ? "확인 중…" : "입장"}
        </Button>
        <p className="text-sm text-muted-foreground">공유받은 주소로 열었다면 코드 입력 없이 바로 들어간다.</p>
      </div>
    </div>
  );
}
