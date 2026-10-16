"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { downloadRecord, type GameRecord, type RecordCodec } from "../lib/record";

/** 끝난 대국의 기보를 내려받고 공유한다. 서버 저장은 없다(ADR-0002). */
export function RecordActions({
  record,
  codec,
}: {
  record: GameRecord;
  codec: RecordCodec;
}) {
  const [notice, setNotice] = useState<string | null>(null);

  const download = () => {
    const ext = record.game === "chess" ? "pgn" : "txt";
    const date = new Date().toISOString().slice(0, 10);
    downloadRecord(`scoophub-${record.game}-${date}.${ext}`, codec.toFile(record));
  };

  const share = async () => {
    const encoded = await codec.encodeShare(record);
    const url = `${window.location.origin}/game/review#${encoded}`;
    const text = "ScoopHub 기보 다시보기";
    if (navigator.share) {
      try {
        await navigator.share({ title: "ScoopHub 기보", text, url });
        return;
      } catch {
        // 공유 취소/실패 시 복사로 넘어간다
      }
    }
    try {
      await navigator.clipboard.writeText(url);
      setNotice("링크를 복사했다");
    } catch {
      setNotice("링크 복사에 실패했다");
    }
  };

  return (
    <div className="flex flex-col items-center gap-1">
      <div className="flex gap-2">
        <Button variant="outline" size="sm" onClick={download}>
          기보 내려받기
        </Button>
        <Button variant="outline" size="sm" onClick={share}>
          기보 공유
        </Button>
      </div>
      {notice && <p className="text-xs text-muted-foreground">{notice}</p>}
    </div>
  );
}
