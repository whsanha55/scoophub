"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { ChessReview } from "@/domains/game/chess/components/chess-review";
import { JanggiReview } from "@/domains/game/janggi/components/janggi-review";
import { RECORD_CODECS, type GameRecord } from "@/domains/game/lib/record";

export default function ReviewPage() {
  const [record, setRecord] = useState<GameRecord | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = () => {
      const hash = window.location.hash.slice(1);
      if (!hash) return;
      (async () => {
        const decoded = await RECORD_CODECS.chess.decodeShare(hash);
        if (decoded) {
          setRecord(decoded);
          setError(null);
        } else {
          setError("링크에서 기보를 읽을 수 없다");
        }
      })();
    };
    load();
    // 같은 경로에서 다른 공유 링크를 열 때도 따라간다
    window.addEventListener("hashchange", load);
    return () => window.removeEventListener("hashchange", load);
  }, []);

  const onFile = async (file: File) => {
    const text = await file.text();
    // 체스 PGN → 장기 형식 순서로 시도한다
    const parsed =
      RECORD_CODECS.chess.fromFile(text) ?? RECORD_CODECS.janggi.fromFile(text);
    if (parsed) {
      setRecord(parsed);
      setError(null);
    } else {
      setError("기보 파일을 읽을 수 없다");
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">기보 다시보기</h1>
      {record ? (
        record.game === "janggi" ? (
          <JanggiReview
            moves={record.moves}
            setup={record.setup ?? { cho: "마상마상", han: "마상마상" }}
            result={record.result === "cho" ? "white" : record.result === "han" ? "black" : record.result === "none" ? undefined : record.result}
          />
        ) : (
          <ChessReview moves={record.moves} result={record.result} />
        )
      ) : (
        <div className="mx-auto flex w-full max-w-sm flex-col items-center gap-3 rounded-xl border bg-card p-6">
          <p className="text-sm text-muted-foreground">
            PGN·장기 기보 파일을 불러오거나 공유 링크로 연다
          </p>
          <label className="inline-flex h-9 cursor-pointer items-center rounded-md border bg-background px-4 text-sm font-medium transition-colors hover:bg-accent">
            파일 불러오기
            <input
              type="file"
              accept=".pgn,text/plain"
              className="hidden"
              aria-label="PGN 파일 선택"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) onFile(file);
              }}
            />
          </label>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
      )}
      {record && (
        <div className="flex justify-center">
          <Button variant="outline" size="sm" onClick={() => setRecord(null)}>
            다른 기보 보기
          </Button>
        </div>
      )}
    </div>
  );
}
