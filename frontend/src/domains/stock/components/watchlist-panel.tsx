"use client";

import { useEffect, useState } from "react";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Plus, Trash2, Edit3 } from "lucide-react";
import { useWatchlist } from "../hooks/use-stock";
import type { WatchlistCreateInput, WatchlistGroup } from "../types";

const GROUP_LABELS: Record<WatchlistGroup, string> = {
  market: "시장",
  sector: "섹터",
  individual: "개별",
};

// 시스템 관리(super) 영역에서만 사용 — 관심종목(주식 테마) CRUD.
export function WatchlistPanel() {
  const { items, fetchWatchlist, addItem, deleteItem, updateItem } = useWatchlist();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<WatchlistCreateInput>({
    ticker: "",
    exchange: "NAS",
    name: "",
    memo: "",
    group: "individual",
  });
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editMemo, setEditMemo] = useState("");

  useEffect(() => {
    fetchWatchlist();
  }, [fetchWatchlist]);

  const handleAdd = async () => {
    if (!form.ticker || !form.name) return;
    await addItem(form);
    setForm({ ticker: "", exchange: "NAS", name: "", memo: "", group: "individual" });
    setOpen(false);
  };

  const handleUpdateMemo = async (id: string) => {
    await updateItem(id, { memo: editMemo });
    setEditingId(null);
    setEditMemo("");
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-lg">Watchlist</CardTitle>
        <Dialog open={open} onOpenChange={setOpen}>
          <DialogTrigger render={<Button size="sm" className="cursor-pointer transition-colors duration-200" />}>
              <Plus className="mr-1 h-4 w-4" />
              추가
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Watchlist 추가</DialogTitle>
            </DialogHeader>
            <div className="space-y-3">
              <Input
                placeholder="Ticker (e.g. AAPL)"
                value={form.ticker}
                onChange={(e) => setForm({ ...form, ticker: e.target.value.toUpperCase() })}
              />
              <Input
                placeholder="Name (e.g. Apple Inc.)"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
              <div className="flex gap-2">
                {["NAS", "NYS", "KRX"].map((ex) => (
                  <Button
                    key={ex}
                    size="sm"
                    variant={form.exchange === ex ? "default" : "outline"}
                    onClick={() => setForm({ ...form, exchange: ex })}
                    className="cursor-pointer transition-colors duration-200"
                  >
                    {ex}
                  </Button>
                ))}
              </div>
              <div className="flex gap-2">
                {(["market", "sector", "individual"] as const).map((g) => (
                  <Button
                    key={g}
                    size="sm"
                    variant={form.group === g ? "default" : "outline"}
                    onClick={() => setForm({ ...form, group: g })}
                    className="cursor-pointer transition-colors duration-200"
                  >
                    {GROUP_LABELS[g]}
                  </Button>
                ))}
              </div>
              <Input
                placeholder="Memo (optional)"
                value={form.memo}
                onChange={(e) => setForm({ ...form, memo: e.target.value })}
              />
              <Button
                onClick={handleAdd}
                disabled={!form.ticker || !form.name}
                className="w-full cursor-pointer transition-colors duration-200"
              >
                추가
              </Button>
            </div>
          </DialogContent>
        </Dialog>
      </CardHeader>
      <CardContent>
        <div className="space-y-2">
          {items.length === 0 && (
            <p className="text-sm text-muted-foreground text-center py-4">
              Watchlist가 비어있습니다
            </p>
          )}
          {items.map((item) => (
            <div
              key={item.id}
              className="flex items-center justify-between rounded-lg border border-border p-3 transition-colors duration-200 hover:bg-accent/50"
            >
              <div className="flex items-center gap-3">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-semibold">
                      {item.ticker}
                    </span>
                    <Badge variant="outline" className="text-xs">
                      {item.exchange}
                    </Badge>
                    {item.group && (
                      <Badge variant="secondary" className="text-xs">
                        {GROUP_LABELS[item.group]}
                      </Badge>
                    )}
                    {!item.is_active && (
                      <Badge variant="secondary" className="text-xs">비활성</Badge>
                    )}
                  </div>
                  <span className="text-xs text-muted-foreground">
                    {item.name}
                  </span>
                  {editingId === item.id ? (
                    <div className="space-y-1 mt-1">
                      <div className="flex gap-1">
                        {(["market", "sector", "individual"] as const).map((g) => (
                          <Button
                            key={g}
                            size="sm"
                            variant={(item.group ?? "individual") === g ? "default" : "outline"}
                            onClick={() => updateItem(item.id, { group: g })}
                            className="h-7 text-xs cursor-pointer transition-colors duration-200"
                          >
                            {GROUP_LABELS[g]}
                          </Button>
                        ))}
                      </div>
                      <div className="flex gap-1">
                        <Input
                          size={1}
                          value={editMemo}
                          onChange={(e) => setEditMemo(e.target.value)}
                          className="h-7 text-xs"
                          onKeyDown={(e) => e.key === "Enter" && handleUpdateMemo(item.id)}
                        />
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => handleUpdateMemo(item.id)}
                          className="h-7 cursor-pointer"
                        >
                          저장
                        </Button>
                      </div>
                    </div>
                  ) : (
                    item.memo && (
                      <p className="text-xs text-muted-foreground">{item.memo}</p>
                    )
                  )}
                </div>
              </div>
              <div className="flex gap-1">
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => {
                    setEditingId(item.id);
                    setEditMemo(item.memo);
                  }}
                  className="cursor-pointer transition-colors duration-200"
                >
                  <Edit3 className="h-3.5 w-3.5" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => deleteItem(item.id)}
                  className="cursor-pointer text-destructive transition-colors duration-200 hover:text-destructive"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
