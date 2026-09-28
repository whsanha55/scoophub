"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { RefreshCw, Save } from "lucide-react";
import { useCrawlConfig } from "../hooks/use-config";
import type { CrawlConfigEntry } from "../types";

function ConfigForm({
  entry,
  disabled,
  onSave,
  onError,
}: {
  entry: CrawlConfigEntry;
  disabled: boolean;
  onSave: (crawler: string, params: Record<string, unknown>) => void;
  onError: (msg: string | null) => void;
}) {
  // 부모가 key={params 직렬화}로 리마운트시키므로 초기값만 사용.
  const [draft, setDraft] = useState(() => JSON.stringify(entry.params, null, 2));

  const handleSaveClick = () => {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(draft);
    } catch (err) {
      onError(err instanceof Error ? `JSON 파싱 실패: ${err.message}` : "JSON 파싱 실패");
      return;
    }
    onError(null);
    onSave(entry.crawler, parsed);
  };

  return (
    <div className="space-y-2">
      <textarea
        className="w-full rounded-md border border-border bg-background p-2 font-mono text-xs min-h-28"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        spellCheck={false}
      />
      <Button
        size="sm"
        variant="outline"
        disabled={disabled}
        onClick={handleSaveClick}
        className="cursor-pointer transition-colors duration-200"
      >
        <Save className="h-4 w-4 mr-1" />
        저장 (live reload)
      </Button>
    </div>
  );
}

export function ConfigPanel() {
  const { configs, loading, error, fetchConfigs, patchConfig } = useCrawlConfig();
  const [saving, setSaving] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    fetchConfigs();
  }, [fetchConfigs]);

  const handleSave = async (crawler: string, params: Record<string, unknown>) => {
    setSaving(crawler);
    try {
      await patchConfig(crawler, params);
      setActionError(null);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "config 저장 실패");
    } finally {
      setSaving(null);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">크롤 config</h2>
        <Button variant="outline" size="sm" onClick={fetchConfigs} disabled={loading}>
          <RefreshCw className="h-4 w-4 mr-1" />
          새로고침
        </Button>
      </div>

      {loading && configs.length === 0 && (
        <div className="space-y-2">
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-32 w-full" />
        </div>
      )}

      {error && <p className="text-destructive text-sm">{error}</p>}

      {actionError && <p className="text-destructive text-sm">{actionError}</p>}

      {!loading && !error && configs.length === 0 && (
        <p className="text-muted-foreground text-sm">config이 없습니다.</p>
      )}

      <div className="grid gap-3">
        {configs.map((c) => (
          <Card key={c.crawler}>
            <CardHeader className="pb-2">
              <CardTitle className="text-base flex items-center gap-2">
                <span>{c.crawler}</span>
                <Badge variant="outline" className="text-xs">
                  {Object.keys(c.params).length} keys
                </Badge>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <ConfigForm
                key={JSON.stringify(c.params)}
                entry={c}
                disabled={saving === c.crawler}
                onSave={handleSave}
                onError={setActionError}
              />
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
