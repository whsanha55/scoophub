"use client";

import { useState } from "react";
import { Plus, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { HotdealProfile } from "../types";

interface HotdealProfileManagerProps {
  profiles: HotdealProfile[];
  error: string | null;
  onAdd: (value: string) => Promise<boolean>;
  onDelete: (id: number) => Promise<boolean>;
}

// 관심 키워드 추가·삭제 (온보딩). 서버가 즉시 재평가하므로 부모가 피드를 다시 불러온다.
export function HotdealProfileManager({ profiles, error, onAdd, onDelete }: HotdealProfileManagerProps) {
  const [value, setValue] = useState("");
  const [pending, setPending] = useState(false);
  const keywords = profiles.filter((p) => p.kind === "KEYWORD");

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = value.trim();
    if (!trimmed || pending) return;
    setPending(true);
    if (await onAdd(trimmed)) setValue("");
    setPending(false);
  };

  const remove = async (id: number) => {
    if (pending) return;
    setPending(true);
    await onDelete(id);
    setPending(false);
  };

  return (
    <section className="space-y-2">
      <h2 className="text-sm font-medium">관심 키워드</h2>
      <form onSubmit={submit} className="flex gap-2 sm:max-w-sm">
        <Input
          placeholder="예: SSD, 모니터"
          aria-label="관심 키워드"
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
        <Button type="submit" disabled={pending || !value.trim()} className="cursor-pointer">
          <Plus /> 추가
        </Button>
      </form>
      {error && <p className="text-sm text-destructive">{error}</p>}
      {keywords.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          관심 키워드가 없어 인기 항목을 함께 보여줍니다. 키워드를 추가하면 바로 추천에 반영됩니다.
        </p>
      ) : (
        <div className="flex flex-wrap gap-1.5">
          {keywords.map((p) => (
            <Badge key={p.id} variant="outline" className="gap-1 pr-1">
              <span>{p.value}</span>
              <span className="text-muted-foreground" title="유효 가중치 (기본 + 학습)">
                {p.effective_weight.toFixed(1)}
              </span>
              <button
                type="button"
                aria-label={`${p.value} 삭제`}
                onClick={() => remove(p.id)}
                disabled={pending}
                className="cursor-pointer rounded-sm p-0.5 hover:bg-muted"
              >
                <X className="h-3 w-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}
    </section>
  );
}
