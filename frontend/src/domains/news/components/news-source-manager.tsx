"use client";

import { useEffect, useState } from "react";
import { useNewsSources } from "../hooks/use-news-sources";
import type { NewsSource, NewsSourceCreateParams, NewsSourceUpdateParams } from "../types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Power, Trash2, Pencil, X, Check } from "lucide-react";
import { useAuth } from "@/shared/hooks/use-auth";

function EditRow({
  source,
  onSave,
  onCancel,
}: {
  source: NewsSource;
  onSave: (id: number, params: NewsSourceUpdateParams) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(source.name);
  const [url, setUrl] = useState(source.url);

  return (
    <tr className="border-b border-border">
      <td className="px-3 py-2">
        <Input
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="h-8 text-sm"
          aria-label="소스 이름"
        />
      </td>
      <td className="px-3 py-2">
        <Input
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          className="h-8 text-sm"
          aria-label="소스 URL"
        />
      </td>
      <td className="px-3 py-2">
        <Badge variant={source.active ? "default" : "secondary"}>
          {source.active ? "활성" : "비활성"}
        </Badge>
      </td>
      <td className="px-3 py-2 text-right">
        <div className="flex items-center justify-end gap-1">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onSave(source.id, { name, url })}
            className="h-8 w-8 p-0 cursor-pointer"
            title="저장"
          >
            <Check className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={onCancel}
            className="h-8 w-8 p-0 cursor-pointer"
            title="취소"
          >
            <X className="h-4 w-4" />
          </Button>
        </div>
      </td>
    </tr>
  );
}

function SourceRow({
  source,
  onToggle,
  onDelete,
  onEdit,
  showActions,
}: {
  source: NewsSource;
  onToggle: (source: NewsSource) => void;
  onDelete: (id: number) => void;
  onEdit: (source: NewsSource) => void;
  showActions: boolean;
}) {
  return (
    <tr className="border-b border-border transition-colors duration-150 hover:bg-muted/50">
      <td className="px-3 py-2 text-sm font-medium">{source.name}</td>
      <td className="max-w-[300px] truncate px-3 py-2 text-sm text-muted-foreground">
        {source.url}
      </td>
      <td className="px-3 py-2">
        <Badge variant={source.active ? "default" : "secondary"}>
          {source.active ? "활성" : "비활성"}
        </Badge>
      </td>
      {showActions && (
        <td className="px-3 py-2 text-right">
          <div className="flex items-center justify-end gap-1">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onToggle(source)}
              className="h-8 w-8 p-0 cursor-pointer"
              title={source.active ? "비활성화" : "활성화"}
            >
              <Power className={`h-4 w-4 ${source.active ? "text-green-500" : "text-muted-foreground"}`} />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onEdit(source)}
              className="h-8 w-8 p-0 cursor-pointer"
              title="수정"
            >
              <Pencil className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onDelete(source.id)}
              className="h-8 w-8 p-0 cursor-pointer text-destructive hover:text-destructive"
              title="삭제"
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        </td>
      )}
    </tr>
  );
}

export function NewsSourceManager() {
  const { sources, loading, error, fetchSources, addSource, updateSource, deleteSource } = useNewsSources();
  const { user } = useAuth();
  const isSuper = !!user?.is_super;

  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);

  useEffect(() => {
    fetchSources();
  }, [fetchSources]);

  const handleAdd = async () => {
    if (!name.trim() || !url.trim()) return;
    const params: NewsSourceCreateParams = { name: name.trim(), url: url.trim() };
    const result = await addSource(params);
    if (result) {
      setName("");
      setUrl("");
    }
  };

  const handleToggle = async (source: NewsSource) => {
    await updateSource(source.id, { active: !source.active });
  };

  const handleDelete = async (id: number) => {
    await deleteSource(id);
  };

  const handleEditSave = async (id: number, params: NewsSourceUpdateParams) => {
    await updateSource(id, params);
    setEditingId(null);
  };

  return (
    <div className="space-y-4">
      {/* Add form — super만 */}
      {isSuper && (
        <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
          <div className="flex-1">
            <label className="mb-1 block text-xs text-muted-foreground">이름</label>
            <Input
              placeholder="소스 이름"
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleAdd()}
              aria-label="소스 이름"
            />
          </div>
          <div className="flex-1">
            <label className="mb-1 block text-xs text-muted-foreground">URL</label>
            <Input
              placeholder="https://example.com/feed"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleAdd()}
              aria-label="소스 URL"
            />
          </div>
          <Button onClick={handleAdd} disabled={!name.trim() || !url.trim()} className="cursor-pointer">
            추가
          </Button>
        </div>
      )}

      {error && (
        <div className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      )}

      {/* Table */}
      {loading && !sources ? (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-12 rounded-md" />
          ))}
        </div>
      ) : sources && sources.length > 0 ? (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-muted/50">
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">이름</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">URL</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">상태</th>
                {isSuper && (
                  <th className="px-3 py-2 text-right text-xs font-medium text-muted-foreground">관리</th>
                )}
              </tr>
            </thead>
            <tbody>
              {sources.map((source) =>
                editingId === source.id ? (
                  <EditRow
                    key={source.id}
                    source={source}
                    onSave={handleEditSave}
                    onCancel={() => setEditingId(null)}
                  />
                ) : (
                  <SourceRow
                    key={source.id}
                    source={source}
                    onToggle={handleToggle}
                    onDelete={handleDelete}
                    onEdit={(s) => setEditingId(s.id)}
                    showActions={isSuper}
                  />
                )
              )}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="py-12 text-center text-muted-foreground">
          등록된 소스가 없습니다
        </div>
      )}
    </div>
  );
}
