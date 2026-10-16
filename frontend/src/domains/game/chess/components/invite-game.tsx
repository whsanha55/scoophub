"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { ensureGuestId, ensureNickname } from "../../lib/guest";
import {
  RoomSocket,
  type ChatEntry,
  type ConnectionState,
  type RoomSnapshot,
} from "../../lib/room-client";
import {
  formatClock,
  isUntimed,
  tickClock,
  type PlayerClock,
  type Side,
} from "../../lib/time-control";
import { chessEngine, type ChessState } from "../engine";
import { ChessBoard } from "./chess-board";
import { RuleStepper } from "../../components/rule-stepper";
import { RecordActions } from "../../components/record-actions";
import { CHESS_RESULT, chessRecordCodec } from "../../lib/record";

function replayChess(moves: RoomSnapshot["moves"]): ChessState {
  let state = chessEngine.initialState();
  for (const m of moves) {
    const r = chessEngine.applyMove(state, m.from, m.to);
    if (!r) break; // 조작된 기보는 알 수 없는 시점에서 멈춘다 (ADR-0001 감수)
    state = r.state;
  }
  return state;
}

function ClockView({
  clock,
  label,
  active,
  untimed,
}: {
  clock: PlayerClock;
  label: string;
  active: boolean;
  untimed: boolean;
}) {
  const ms = clock.inByoYomi ? clock.byoYomiLeftMs : clock.baseLeftMs;
  return (
    <div
      className={`flex items-center justify-between rounded-lg border px-3 py-1.5 ${
        active ? "border-primary bg-primary/10" : "border-border"
      }`}
    >
      <span className="truncate text-sm font-semibold">{label}</span>
      {untimed ? (
        <span className="text-sm text-muted-foreground">무제한</span>
      ) : (
        <span className="flex items-baseline gap-2">
          {clock.inByoYomi && (
            <span className="text-xs text-muted-foreground">초읽기 ×{clock.byoYomiLeft}</span>
          )}
          <span className={`text-lg font-bold tabular-nums ${ms <= 10_000 ? "text-red-500" : ""}`}>
            {formatClock(ms)}
          </span>
        </span>
      )}
    </div>
  );
}

export function InviteChessGame({ code }: { code: string }) {
  const router = useRouter();
  const [snapshot, setSnapshot] = useState<RoomSnapshot | null>(null);
  const [conn, setConn] = useState<ConnectionState>("connecting");
  const [fatal, setFatal] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [confirmingResign, setConfirmingResign] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [clocks, setClocks] = useState<{ white: PlayerClock; black: PlayerClock } | null>(null);
  const [chat, setChat] = useState<ChatEntry[]>([]);
  const [chatInput, setChatInput] = useState("");
  const [flipped, setFlipped] = useState(false);
  const [ruleDraft, setRuleDraft] = useState({ baseMin: 10, byoSec: 30, byoCount: 1 });
  const socketRef = useRef<RoomSocket | null>(null);
  const lastTickRef = useRef(0);
  const snapRef = useRef<RoomSnapshot | null>(null);

  useEffect(() => {
    snapRef.current = snapshot;
  }, [snapshot]);

  // 접속: 게스트 ID 가 없으면 발급받고 방에 붙는다.
  useEffect(() => {
    let cancelled = false;
    let socket: RoomSocket | null = null;
    (async () => {
      try {
        const guestId = await ensureGuestId();
        const nickname = await ensureNickname();
        if (cancelled) return;
        socket = new RoomSocket(
          code,
          guestId,
          nickname,
          (msg) => {
            if (msg.type === "room") {
              setSnapshot(msg.payload);
              setChat(msg.payload.chat);
              setClocks(msg.payload.clocks);
              lastTickRef.current = Date.now();
            } else if (msg.type === "error") {
              if (msg.payload.message.includes("찼습니다")) {
                // 정원 초과로 더는 재접속할 의미가 없다
                socketRef.current?.close();
                setFatal(msg.payload.message);
                return;
              }
              setNotice(msg.payload.message);
            } else if (msg.type === "game_end") {
              setNotice(null);
            } else if (msg.type === "chat") {
              setChat((prev) => [...prev.slice(-80), msg.payload]);
            }
          },
          (state) => setConn(state),
        );
        socket.connect();
        socketRef.current = socket;
      } catch (e) {
        if (!cancelled) setFatal(e instanceof Error ? e.message : "방에 들어갈 수 없다");
      }
    })();
    return () => {
      cancelled = true;
      socket?.close();
    };
  }, [code]);

  // 서버 시계 보간: 마지막 스냅샷 이후 흐른 시간을 활성 진영에 반영한다.
  useEffect(() => {
    if (!snapshot || snapshot.phase !== "playing" || isUntimed(snapshot.timeRule)) return;
    const id = setInterval(() => {
      const s = snapRef.current;
      if (!s || s.phase !== "playing") return;
      const now = Date.now();
      const elapsed = now - lastTickRef.current;
      lastTickRef.current = now;
      setClocks((prev) => {
        if (!prev) return prev;
        return { ...prev, [s.turn]: tickClock(prev[s.turn], s.timeRule, elapsed) };
      });
    }, 250);
    return () => clearInterval(id);
    // 스냅샷이 갱신될 때마다 조건을 다시 평가한다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshot?.phase, snapshot?.serverNow]);

  const send = useCallback((message: unknown) => {
    socketRef.current?.send(message);
  }, []);

  const state = snapshot ? replayChess(snapshot.moves) : null;
  const mySide = snapshot?.youAre ?? null;
  const turn = snapshot?.turn ?? "white";
  const pieces = state ? chessEngine.pieces(state) : new Map();
  const targets = selected ? chessEngine.legalTargets(state ?? chessEngine.initialState(), selected) : [];
  const lastEntry = snapshot?.moves[snapshot.moves.length - 1] ?? null;
  const checkSquare = (() => {
    if (!state || !chessEngine.inCheck(state)) return null;
    for (const [square, piece] of pieces) {
      if (piece.type === "k" && piece.side === turn) return square;
    }
    return null;
  })();

  const onSquareTap = (square: string) => {
    if (!snapshot || snapshot.phase !== "playing" || !state) return;
    if (!mySide || mySide !== turn) return; // 관전자는 둘 수 없다
    if (selected) {
      if (targets.includes(square)) {
        const r = chessEngine.applyMove(state, selected, square);
        if (r) {
          // 규칙 판정은 클라이언트가 하고, 서버는 순서와 시계만 관리한다 (ADR-0001)
          send({ type: "move", payload: { from: selected, to: square, notation: r.notation } });
        }
        setSelected(null);
        return;
      }
      const piece = pieces.get(square);
      if (piece && piece.side === mySide) {
        setSelected(square);
        return;
      }
      setSelected(null);
      return;
    }
    const piece = pieces.get(square);
    if (piece && piece.side === mySide) setSelected(square);
  };

  const shareUrl = typeof window !== "undefined" ? `${window.location.origin}/game/room/${code}` : "";

  const share = async () => {
    const text = `체스 초대 대국 코드 ${code}`;
    if (navigator.share) {
      try {
        await navigator.share({ title: "ScoopHub 체스", text, url: shareUrl });
        return;
      } catch {
        // 공유 취소/실패 시 복사로 넘어간다
      }
    }
    try {
      await navigator.clipboard.writeText(shareUrl);
      setNotice("링크를 복사했다");
    } catch {
      setNotice("링크 복사에 실패했다");
    }
  };

  const myNickname = mySide ? snapshot?.players[mySide] : null;
  const voted = !!(myNickname && snapshot?.rematchVotes.includes(myNickname));
  const playersPresent = !!(snapshot?.players.white && snapshot?.players.black);
  const updateRuleDraft = (key: "baseMin" | "byoSec" | "byoCount", value: number) => {
    setRuleDraft((prev) => {
      const next = { ...prev, [key]: value };
      send({
        type: "time_rule",
        payload: {
          rule: {
            base_ms: next.baseMin * 60_000,
            byo_yomi_ms: next.byoSec * 1000,
            byo_yomi_count: next.byoSec > 0 ? next.byoCount : 0,
          },
        },
      });
      return next;
    });
  };

  if (fatal) {
    return (
      <div className="mx-auto flex max-w-md flex-col items-center gap-3 py-12 text-center">
        <p className="text-muted-foreground">{fatal}</p>
        <Button onClick={() => router.push("/game")}>게임 목록으로</Button>
      </div>
    );
  }

  if (!snapshot) {
    return (
      <div className="mx-auto flex w-full max-w-xl flex-col gap-3">
        {notice && (
          <div className="rounded-lg border bg-muted px-3 py-1.5 text-sm">{notice}</div>
        )}
        <div className="flex justify-center py-12 text-muted-foreground">
          {conn === "connecting" ? "방에 접속하는 중…" : "다시 접속하는 중…"}
        </div>
      </div>
    );
  }

  const isSpectator = snapshot.role === "spectator";
  // 관전자는 백 쪽이 아래로 고정, 직접 뒤집어 볼 수 있다
  const myView: Side = isSpectator ? (flipped ? "black" : "white") : (mySide ?? "white");
  const opponentSide: Side = myView === "white" ? "black" : "white";
  const opponentLabel =
    snapshot.players[opponentSide] ??
    (snapshot.phase === "waiting" ? "상대를 기다리는 중…" : "");

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-3">
      {conn !== "open" && (
        <div className="rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-1.5 text-sm text-destructive">
          연결이 끊겼다. 다시 접속하는 중… 끊겨도 시계는 흐른다.
        </div>
      )}
      {notice && (
        <div className="flex items-center justify-between gap-2 rounded-lg border bg-muted px-3 py-1.5 text-sm">
          <span>{notice}</span>
          <button className="text-muted-foreground" onClick={() => setNotice(null)} aria-label="알림 닫기">
            ✕
          </button>
        </div>
      )}

      {snapshot.phase === "waiting" ? (
        <div className="mx-auto flex w-full max-w-sm flex-col items-center gap-4 rounded-xl border bg-card p-6">
          <p className="text-sm text-muted-foreground">초대코드</p>
          <p className="text-4xl font-bold tracking-[0.3em] tabular-nums">{code}</p>
          <div className="flex gap-2">
            <Button onClick={share}>초대 링크 공유</Button>
            <Button
              variant="outline"
              onClick={async () => {
                await navigator.clipboard.writeText(code);
                setNotice("코드를 복사했다");
              }}
            >
              코드 복사
            </Button>
          </div>
          <p className="text-sm text-muted-foreground">
            같은 화면이 아닌 서로 다른 기기에서, 이 코드로 들어오면 대국이 시작된다.
          </p>
          <Button variant="outline" size="sm" onClick={() => router.push("/game")}>
            나가기
          </Button>
        </div>
      ) : (
        <>
          {clocks && (
            <ClockView
              clock={clocks[opponentSide]}
              label={opponentLabel}
              active={snapshot.phase === "playing" && turn === opponentSide}
              untimed={isUntimed(snapshot.timeRule)}
            />
          )}
          <ChessBoard
            pieces={pieces}
            orientation={myView}
            selected={selected}
            targets={targets}
            lastMove={lastEntry ? { from: lastEntry.from, to: lastEntry.to } : null}
            checkSquare={checkSquare}
            onSquareTap={onSquareTap}
          />
          {clocks && mySide && (
            <ClockView
              clock={clocks[mySide]}
              label={`${snapshot.players[mySide]} (나)`}
              active={snapshot.phase === "playing" && turn === mySide}
              untimed={isUntimed(snapshot.timeRule)}
            />
          )}
          {clocks && !mySide && (
            <ClockView
              clock={clocks[myView]}
              label={snapshot.players[myView] ?? ""}
              active={snapshot.phase === "playing" && turn === myView}
              untimed={isUntimed(snapshot.timeRule)}
            />
          )}
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-sm font-medium">
              {snapshot.phase === "playing" ? (
                <>
                  {isSpectator
                    ? `${turn === "white" ? "백" : "흑"} 차례 · 관전 ${snapshot.spectators.length + 1}명`
                    : turn === mySide
                      ? "내 차례"
                      : `${opponentLabel} 차례`}
                  {chessEngine.inCheck(state ?? chessEngine.initialState()) && (
                    <span className="ml-1 font-bold text-red-500">체크!</span>
                  )}
                </>
              ) : null}
            </span>
            <div className="flex gap-2">
              {isSpectator && (
                <Button variant="outline" size="sm" onClick={() => setFlipped((f) => !f)}>
                  뒤집기
                </Button>
              )}
              {snapshot.phase === "playing" && (
                <div className="flex gap-2">
                {isUntimed(snapshot.timeRule) && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      send({ type: "claim_abandon_win" });
                      setNotice("상대가 5분 이상 끊겨야 승리 처리된다");
                    }}
                  >
                    승리 처리
                  </Button>
                )}
                  {confirmingResign ? (
                    <Button
                      variant="destructive"
                      size="sm"
                      onClick={() => send({ type: "resign" })}
                    >
                      기권 확정
                    </Button>
                  ) : (
                    <Button variant="outline" size="sm" onClick={() => setConfirmingResign(true)}>
                      기권
                    </Button>
                  )}
                </div>
              )}
            </div>
          </div>
          {snapshot.moves.length > 0 && (
            <div className="max-h-40 overflow-y-auto rounded-lg border bg-card p-2">
              <ol className="grid grid-cols-[2rem_1fr_1fr] items-center gap-x-2 gap-y-0.5 text-sm tabular-nums">
                {Array.from({ length: Math.ceil(snapshot.moves.length / 2) }, (_, i) => (
                  <li key={i} className="col-span-3 grid grid-cols-subgrid">
                    <span className="text-muted-foreground">{i + 1}.</span>
                    <span>{snapshot.moves[i * 2]?.notation ?? ""}</span>
                    <span>{snapshot.moves[i * 2 + 1]?.notation ?? ""}</span>
                  </li>
                ))}
              </ol>
            </div>
          )}
          <div className="flex flex-col gap-2 rounded-lg border bg-card p-2">
            <div className="flex max-h-48 flex-col gap-1 overflow-y-auto" aria-live="polite">
              {chat.length === 0 && (
                <p className="py-2 text-center text-sm text-muted-foreground">채팅이 없다</p>
              )}
              {chat.map((entry, i) =>
                entry.system ? (
                  <p key={i} className="text-center text-xs text-muted-foreground">
                    {entry.text}
                  </p>
                ) : (
                  <p key={i} className="text-sm">
                    <span
                      className={`mr-1.5 rounded px-1 py-0.5 text-xs ${
                        entry.fromPlayer ? "bg-primary/15 text-primary" : "bg-muted text-muted-foreground"
                      }`}
                    >
                      {entry.fromPlayer ? "대국자" : "관전"}
                    </span>
                    <span className="font-semibold">{entry.nickname}</span>{" "}
                    {entry.text}
                  </p>
                ),
              )}
            </div>
            <div className="flex gap-2">
              <input
                className="flex-1 rounded-md border bg-background px-2 py-1 text-sm outline-none focus-visible:ring-1 focus-visible:ring-ring"
                placeholder="메시지 (200자까지)"
                aria-label="채팅 메시지"
                maxLength={200}
                value={chatInput}
                onChange={(e) => setChatInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && chatInput.trim()) {
                    send({ type: "chat", payload: { text: chatInput.trim() } });
                    setChatInput("");
                  }
                }}
              />
              <Button
                size="sm"
                disabled={!chatInput.trim()}
                onClick={() => {
                  if (!chatInput.trim()) return;
                  send({ type: "chat", payload: { text: chatInput.trim() } });
                  setChatInput("");
                }}
              >
                보내기
              </Button>
            </div>
          </div>
          {snapshot.phase === "finished" && snapshot.result && (
            <div className="flex flex-col items-center gap-3 rounded-xl border bg-card p-5 text-center">
              <p className="text-lg font-bold">
                {snapshot.result.winner
                  ? snapshot.result.winner === mySide
                    ? "승리"
                    : "패배"
                  : "무승부"}
              </p>
              <p className="text-sm text-muted-foreground">{snapshot.result.reason}</p>
              {mySide ? (
                <>
                  {playersPresent ? (
                    <>
                      <Button onClick={() => send({ type: "rematch" })}>
                        {voted ? "한 판 더 — 상대 동의 기다리는 중" : "한 판 더"}
                      </Button>
                      {snapshot.rematchVotes.length === 1 && (
                        <p className="text-sm text-muted-foreground">
                          {snapshot.rematchVotes[0]} 님이 기다리는 중
                        </p>
                      )}
                      {snapshot.youAreHost && (
                        <div className="flex w-full flex-col gap-2 rounded-lg border p-3 text-left">
                          <p className="text-xs text-muted-foreground">
                            재대국 시간 규칙 (시작 전에 바꿀 수 있다)
                          </p>
                          <RuleStepper label="기본 시간" value={ruleDraft.baseMin} suffix="분" step={1} min={0} max={60} onChange={(v) => updateRuleDraft("baseMin", v)} />
                          <RuleStepper label="초읽기" value={ruleDraft.byoSec} suffix="초" step={5} min={0} max={60} onChange={(v) => updateRuleDraft("byoSec", v)} />
                          <RuleStepper label="초읽기 횟수" value={ruleDraft.byoCount} suffix="회" step={1} min={ruleDraft.byoSec > 0 ? 1 : 0} max={5} onChange={(v) => updateRuleDraft("byoCount", v)} />
                        </div>
                      )}
                    </>
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      상대가 방을 나가서 재대국을 할 수 없다
                    </p>
                  )}
                </>
              ) : (
                <p className="text-sm text-muted-foreground">관전 중인 대국이 끝났다</p>
              )}
              <RecordActions
                record={{
                  game: "chess",
                  moves: snapshot.moves.map((m) => m.notation),
                  result: snapshot.result?.winner
                    ? CHESS_RESULT[snapshot.result.winner]
                    : snapshot.result
                      ? CHESS_RESULT.draw
                      : undefined,
                }}
                codec={chessRecordCodec}
              />
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  send({ type: "leave" });
                  router.push("/game");
                }}
              >
                나가기
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
