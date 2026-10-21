"use client";

import { useState } from "react";
import { Minus, Plus, RotateCcw, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { WEIGHT_MAX, WEIGHT_MIN, stepWeight } from "../lib/feed";
import type { HotdealProfile, ProfileKind } from "../types";

interface HotdealProfileManagerProps {
  profiles: HotdealProfile[];
  error: string | null;
  onAdd: (kind: ProfileKind, value: string) => Promise<boolean>;
  onDelete: (id: number) => Promise<boolean>;
  onChangeWeight: (id: number, seedWeight: number) => Promise<boolean>;
  onResetLearned: () => Promise<boolean>;
}

// 관심·제외 키워드와 가중치 관리. 서버가 즉시 재평가·판정하므로 부모가 피드를 다시 불러온다.
export function HotdealProfileManager({
  profiles,
  error,
  onAdd,
  onDelete,
  onChangeWeight,
  onResetLearned,
}: HotdealProfileManagerProps) {
  const [pending, setPending] = useState(false);
  const keywords = profiles.filter((p) => p.kind === "KEYWORD");
  const excludes = profiles.filter((p) => p.kind === "EXCLUDE");
  const hasLearned = keywords.some((p) => p.learned_weight !== 0);

  // 요청이 겹치지 않게 한 번에 하나만 보낸다.
  const run = async (action: () => Promise<boolean>): Promise<boolean> => {
    if (pending) return false;
    setPending(true);
    try {
      return await action();
    } finally {
      setPending(false);
    }
  };

  const reset = () => {
    if (window.confirm("피드백으로 쌓인 학습값을 모두 0으로 되돌릴까요? 좋아요·싫어요 기록은 유지됩니다.")) {
      run(onResetLearned);
    }
  };

  return (
    <section className="space-y-4">
      <div className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-medium">관심 키워드</h2>
          {hasLearned && (
            <Button variant="ghost" size="xs" onClick={reset} disabled={pending} className="cursor-pointer">
              <RotateCcw /> 학습값 초기화
            </Button>
          )}
        </div>
        <KeywordForm placeholder="예: SSD, 모니터" label="관심 키워드" disabled={pending}
          onSubmit={(value) => run(() => onAdd("KEYWORD", value))} />
        {keywords.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            관심 키워드가 없어 인기 항목을 함께 보여줍니다. 키워드를 추가하면 바로 추천에 반영됩니다.
          </p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {keywords.map((p) => (
              <Badge key={p.id} variant="outline" className="h-6 gap-1 pr-1">
                <span>{p.value}</span>
                <button
                  type="button"
                  aria-label={`${p.value} 가중치 내리기`}
                  onClick={() => run(() => onChangeWeight(p.id, stepWeight(p.seed_weight, -1)))}
                  disabled={pending || p.seed_weight <= WEIGHT_MIN}
                  className="cursor-pointer rounded-sm p-0.5 hover:bg-muted disabled:opacity-40"
                >
                  <Minus className="h-3 w-3" />
                </button>
                <span
                  className="tabular-nums text-muted-foreground"
                  title={`기본 ${p.seed_weight.toFixed(1)} + 학습 ${p.learned_weight.toFixed(2)}`}
                >
                  {p.effective_weight.toFixed(1)}
                </span>
                <button
                  type="button"
                  aria-label={`${p.value} 가중치 올리기`}
                  onClick={() => run(() => onChangeWeight(p.id, stepWeight(p.seed_weight, 1)))}
                  disabled={pending || p.seed_weight >= WEIGHT_MAX}
                  className="cursor-pointer rounded-sm p-0.5 hover:bg-muted disabled:opacity-40"
                >
                  <Plus className="h-3 w-3" />
                </button>
                <RemoveButton label={p.value} disabled={pending} onClick={() => run(() => onDelete(p.id))} />
              </Badge>
            ))}
          </div>
        )}
      </div>

      <div className="space-y-2">
        <h2 className="text-sm font-medium">제외 키워드</h2>
        <KeywordForm placeholder="예: 중고, 리퍼" label="제외 키워드" disabled={pending}
          onSubmit={(value) => run(() => onAdd("EXCLUDE", value))} />
        {excludes.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {excludes.map((p) => (
              <Badge key={p.id} variant="secondary" className="h-6 gap-1 pr-1">
                <span>{p.value}</span>
                <RemoveButton label={p.value} disabled={pending} onClick={() => run(() => onDelete(p.id))} />
              </Badge>
            ))}
          </div>
        )}
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}
    </section>
  );
}

function KeywordForm({
  placeholder,
  label,
  disabled,
  onSubmit,
}: {
  placeholder: string;
  label: string;
  disabled: boolean;
  onSubmit: (value: string) => Promise<boolean>;
}) {
  const [value, setValue] = useState("");

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = value.trim();
    if (!trimmed || disabled) return;
    if (await onSubmit(trimmed)) setValue("");
  };

  return (
    <form onSubmit={submit} className="flex gap-2 sm:max-w-sm">
      <Input placeholder={placeholder} aria-label={label} value={value} onChange={(e) => setValue(e.target.value)} />
      <Button type="submit" disabled={disabled || !value.trim()} className="cursor-pointer">
        <Plus /> 추가
      </Button>
    </form>
  );
}

function RemoveButton({ label, disabled, onClick }: { label: string; disabled: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-label={`${label} 삭제`}
      onClick={onClick}
      disabled={disabled}
      className="cursor-pointer rounded-sm p-0.5 hover:bg-muted"
    >
      <X className="h-3 w-3" />
    </button>
  );
}
