"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { RefreshCw, Plus, Edit3, Trash2, Send } from "lucide-react";
import { useNotifyRoutes } from "../hooks/use-notify-routes";
import { useNotifyLog } from "../hooks/use-notify-log";
import type {
  NotifyRoute,
  NotifyRouteCreate,
  NotifyRoutePatch,
  NotifyTestResult,
} from "../types";

interface RouteForm {
  category: string;
  purpose: string;
  channel: string;
  chat_id: string;
  topic_id: string;
  topic_name: string;
  enabled: boolean;
}

const EMPTY_FORM: RouteForm = {
  category: "",
  purpose: "",
  channel: "telegram",
  chat_id: "",
  topic_id: "",
  topic_name: "",
  enabled: true,
};

function emptyRoute(form: RouteForm): NotifyRouteCreate {
  const topicIdRaw = form.topic_id.trim();
  return {
    category: form.category.trim(),
    purpose: form.purpose.trim(),
    channel: form.channel,
    chat_id: form.chat_id.trim(),
    topic_id: topicIdRaw === "" ? null : Number(topicIdRaw),
    topic_name: form.topic_name.trim(),
    enabled: form.enabled,
  };
}

function describeLogError(error: string | null): string | null {
  if (!error) return null;
  if (error.includes("create_topic")) {
    return "토픽 자동생성 실패 — topic_name 또는 topic_id 확인";
  }
  if (error.includes("unsupported") || error.includes("channel")) {
    return "지원하지 않는 채널 (현재 telegram만 발신 지원)";
  }
  return error;
}

export function NotifyPanel() {
  const {
    routes,
    loading,
    error,
    fetchRoutes,
    createRoute,
    patchRoute,
    deleteRoute,
    testRoute,
  } = useNotifyRoutes();
  const { logs, fetchLogs } = useNotifyLog();

  const [editing, setEditing] = useState<NotifyRoute | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<RouteForm>(EMPTY_FORM);
  const [saving, setSaving] = useState<string | number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<number, NotifyTestResult>>({});
  const [logFilter, setLogFilter] = useState<{ route_id: string; status: string }>({
    route_id: "all",
    status: "all",
  });

  useEffect(() => {
    fetchRoutes();
    fetchLogs();
  }, [fetchRoutes, fetchLogs]);

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setActionError(null);
    setDialogOpen(true);
  };

  const openEdit = (route: NotifyRoute) => {
    setEditing(route);
    setForm({
      category: route.category,
      purpose: route.purpose,
      channel: route.channel,
      chat_id: route.chat_id,
      topic_id: route.topic_id == null ? "" : String(route.topic_id),
      topic_name: route.topic_name,
      enabled: route.enabled,
    });
    setActionError(null);
    setDialogOpen(true);
  };

  const handleSubmit = async () => {
    if (!form.chat_id.trim()) {
      setActionError("chat_id는 필수입니다");
      return;
    }
    const topicIdRaw = form.topic_id.trim();
    if (topicIdRaw !== "" && (Number.isNaN(Number(topicIdRaw)) || Number(topicIdRaw) < 0)) {
      setActionError("topic_id는 0 이상 숫자 또는 빈칸이어야 합니다");
      return;
    }
    setSaving(editing ? editing.id : "create");
    setActionError(null);
    try {
      if (editing) {
        const patch: NotifyRoutePatch = {
          ...emptyRoute(form),
        };
        await patchRoute(editing.id, patch);
      } else {
        await createRoute(emptyRoute(form));
      }
      setDialogOpen(false);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "라우트 저장 실패");
    } finally {
      setSaving(null);
    }
  };

  const handleToggle = async (route: NotifyRoute) => {
    setSaving(route.id);
    setActionError(null);
    try {
      await patchRoute(route.id, { enabled: !route.enabled });
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "라우트 토글 실패");
    } finally {
      setSaving(null);
    }
  };

  const handleDelete = async (route: NotifyRoute) => {
    setSaving(route.id);
    setActionError(null);
    try {
      await deleteRoute(route.id);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "라우트 삭제 실패");
    } finally {
      setSaving(null);
    }
  };

  const handleTest = async (route: NotifyRoute) => {
    setSaving(`test:${route.id}`);
    setActionError(null);
    try {
      const result = await testRoute(route.id);
      setTestResults((prev) => ({ ...prev, [route.id]: result }));
    } catch (err) {
      const message = err instanceof Error ? err.message : "발신 테스트 실패";
      setTestResults((prev) => ({
        ...prev,
        [route.id]: { route_id: route.id, status: "error", error: message },
      }));
    } finally {
      setSaving(null);
    }
  };

  const handleLogSearch = () => {
    fetchLogs({
      route_id: logFilter.route_id === "all" ? undefined : Number(logFilter.route_id),
      status: logFilter.status === "all" ? undefined : (logFilter.status as "success" | "error"),
    });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">발신 라우팅</h2>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            fetchRoutes();
            fetchLogs();
          }}
          disabled={loading}
        >
          <RefreshCw className="h-4 w-4 mr-1" />
          새로고침
        </Button>
      </div>

      {error && <p className="text-destructive text-sm">{error}</p>}
      {actionError && <p className="text-destructive text-sm">{actionError}</p>}

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-medium text-muted-foreground">라우트</h3>
          <Button
            size="sm"
            onClick={openCreate}
            className="cursor-pointer transition-colors duration-200"
          >
            <Plus className="h-4 w-4 mr-1" />
            라우트 생성
          </Button>
        </div>

        {loading && routes.length === 0 && (
          <div className="space-y-2">
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        )}

        {!loading && routes.length === 0 && (
          <p className="text-muted-foreground text-sm">라우트가 없습니다.</p>
        )}

        <div className="grid gap-3">
          {routes.map((route) => {
            const isSaving = saving === route.id;
            const isTesting = saving === `test:${route.id}`;
            const testResult = testResults[route.id];
            return (
              <Card key={route.id}>
                <CardHeader className="pb-2">
                  <CardTitle className="text-base flex items-center justify-between">
                    <span className="flex items-center gap-2">
                      <span>{route.category || "*"}</span>
                      <Badge variant="outline" className="text-xs">
                        {route.purpose || "wildcard"}
                      </Badge>
                      <Badge variant="secondary" className="text-xs">
                        {route.channel}
                      </Badge>
                    </span>
                    <Badge variant={route.enabled ? "default" : "secondary"}>
                      {route.enabled ? "활성" : "비활성"}
                    </Badge>
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-3 text-sm">
                  <div className="grid grid-cols-2 gap-2 text-muted-foreground">
                    <div>
                      chat_id:{" "}
                      <span className="text-foreground font-medium">{route.chat_id}</span>
                    </div>
                    <div>
                      topic_id:{" "}
                      <span className="text-foreground font-medium">
                        {route.topic_id == null ? "-" : route.topic_id}
                      </span>
                    </div>
                    <div>
                      topic_name:{" "}
                      <span className="text-foreground font-medium">
                        {route.topic_name || "-"}
                      </span>
                    </div>
                  </div>

                  {route.topic_id == null && route.topic_name && (
                    <p className="text-xs text-muted-foreground">
                      topic_id 미지정 — 발신 시 &ldquo;{route.topic_name}&rdquo; 토픽 자동생성 대상
                    </p>
                  )}

                  {testResult && (
                    <p
                      className={`text-xs ${
                        testResult.status === "success"
                          ? "text-green-600 dark:text-green-400"
                          : "text-destructive"
                      }`}
                    >
                      발신 테스트: {testResult.status}
                      {testResult.error ? ` — ${testResult.error}` : ""}
                    </p>
                  )}

                  <div className="flex flex-wrap items-center gap-2">
                    <Button
                      size="sm"
                      variant={route.enabled ? "destructive" : "default"}
                      disabled={isSaving || isTesting}
                      onClick={() => handleToggle(route)}
                      className="cursor-pointer transition-colors duration-200"
                    >
                      {route.enabled ? "비활성화" : "활성화"}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={isSaving || isTesting}
                      onClick={() => openEdit(route)}
                      className="cursor-pointer transition-colors duration-200"
                    >
                      <Edit3 className="h-3.5 w-3.5 mr-1" />
                      수정
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={isSaving || isTesting}
                      onClick={() => handleTest(route)}
                      className="cursor-pointer transition-colors duration-200"
                    >
                      <Send className="h-3.5 w-3.5 mr-1" />
                      발신 테스트
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={isSaving || isTesting}
                      onClick={() => handleDelete(route)}
                      className="cursor-pointer text-destructive transition-colors duration-200 hover:text-destructive"
                    >
                      <Trash2 className="h-3.5 w-3.5 mr-1" />
                      삭제
                    </Button>
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      </div>

      <div className="space-y-2 pt-4 border-t">
        <h3 className="text-sm font-medium text-muted-foreground">발신 이력</h3>

        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={logFilter.route_id}
            onValueChange={(v) =>
              setLogFilter((prev) => ({ ...prev, route_id: v ?? "all" }))
            }
          >
            <SelectTrigger className="w-40">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">전체 라우트</SelectItem>
              {routes.map((route) => (
                <SelectItem key={route.id} value={String(route.id)}>
                  #{route.id} {route.category || "*"}
                  {route.purpose ? `/${route.purpose}` : ""}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select
            value={logFilter.status}
            onValueChange={(v) =>
              setLogFilter((prev) => ({ ...prev, status: v ?? "all" }))
            }
          >
            <SelectTrigger className="w-32">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">전체 상태</SelectItem>
              <SelectItem value="success">success</SelectItem>
              <SelectItem value="error">error</SelectItem>
            </SelectContent>
          </Select>

          <Button
            onClick={handleLogSearch}
            className="cursor-pointer transition-colors duration-200"
          >
            <RefreshCw className="h-4 w-4 mr-1" />
            조회
          </Button>
        </div>

        {!loading && logs.length === 0 && (
          <p className="text-muted-foreground text-sm">발신 이력이 없습니다.</p>
        )}

        <div className="grid gap-3">
          {logs.map((log) => {
            const isTest = log.payload_key.startsWith("test:");
            const diag = describeLogError(log.error);
            return (
              <Card key={log.id}>
                <CardContent className="space-y-2 text-sm pt-4">
                  <div className="flex items-center justify-between">
                    <span className="flex items-center gap-2">
                      <Badge
                        variant={log.status === "error" ? "destructive" : "default"}
                        className={
                          log.status === "success"
                            ? "bg-green-500/15 text-green-600 dark:text-green-400"
                            : ""
                        }
                      >
                        {log.status}
                      </Badge>
                      {isTest && (
                        <Badge variant="secondary" className="text-xs">
                          테스트 발신
                        </Badge>
                      )}
                      <Badge variant="outline" className="text-xs">
                        route #{log.route_id}
                      </Badge>
                    </span>
                    <span className="text-xs text-muted-foreground whitespace-nowrap">
                      {new Date(log.sent_at).toLocaleString("ko-KR")}
                    </span>
                  </div>
                  <div className="text-muted-foreground">
                    payload_key:{" "}
                    <span className="text-foreground font-medium">{log.payload_key}</span>
                  </div>
                  {diag && (
                    <p className="text-xs text-destructive">{diag}</p>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </div>
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing ? "라우트 수정" : "라우트 생성"}</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <Input
              placeholder="category (news/weather/stock/... 빈칸=wildcard)"
              value={form.category}
              onChange={(e) => setForm({ ...form, category: e.target.value })}
            />
            <Input
              placeholder="purpose (빈칸=wildcard)"
              value={form.purpose}
              onChange={(e) => setForm({ ...form, purpose: e.target.value })}
            />
            <Select
              value={form.channel}
              onValueChange={(v) => setForm({ ...form, channel: v ?? "telegram" })}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="telegram">telegram</SelectItem>
                <SelectItem value="discord">discord (발신 미지원)</SelectItem>
                <SelectItem value="email">email (발신 미지원)</SelectItem>
              </SelectContent>
            </Select>
            <Input
              placeholder="chat_id (필수)"
              value={form.chat_id}
              onChange={(e) => setForm({ ...form, chat_id: e.target.value })}
            />
            <Input
              type="number"
              placeholder="topic_id (빈칸=null, 자동생성 대상)"
              value={form.topic_id}
              onChange={(e) => setForm({ ...form, topic_id: e.target.value })}
            />
            <Input
              placeholder="topic_name"
              value={form.topic_name}
              onChange={(e) => setForm({ ...form, topic_name: e.target.value })}
            />
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant={form.enabled ? "destructive" : "default"}
                onClick={() => setForm({ ...form, enabled: !form.enabled })}
                className="cursor-pointer transition-colors duration-200"
              >
                {form.enabled ? "활성 → 비활성" : "비활성 → 활성"}
              </Button>
              <span className="text-xs text-muted-foreground">
                {form.enabled ? "활성 상태" : "비활성 상태"}
              </span>
            </div>
            <Button
              onClick={handleSubmit}
              disabled={!form.chat_id.trim() || saving !== null}
              className="w-full cursor-pointer transition-colors duration-200"
            >
              {editing ? "수정 저장" : "생성"}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
