"use client";

// 초대 대국 공용 셸. 접속·시계·채팅·재대국 같은 방 로직과 화면을 게임 종류와 분리한다.
// 게임별 화면은 useInviteRoom 으로 방 상태를 받고, 보드 등 게임마다 다른 부분을 play 슬롯으로 넘긴다.

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { ensureGuestId, ensureNickname } from "../lib/guest";
import {
  RoomSocket,
  type ChatEntry,
  type ConnectionState,
  type RoomSnapshot,
} from "../lib/room-client";
import { isUntimed, tickClock, type PlayerClock, type Side } from "../lib/time-control";
import { RuleStepper } from "./rule-stepper";
import { ClockView } from "./clock-view";

export function useInviteRoom(gameId: string, code: string) {
  const [snapshot, setSnapshot] = useState<RoomSnapshot | null>(null);
  const [conn, setConn] = useState<ConnectionState>("connecting");
  const [fatal, setFatal] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [clocks, setClocks] = useState<{ white: PlayerClock; black: PlayerClock } | null>(null);
  const [chat, setChat] = useState<ChatEntry[]>([]);
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
          gameId,
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
            } else if (msg.type === "game_action") {
              // 중간 행동은 그 메시지만 온다. 다음 방 스냅샷(수 기록 뒤)이 오면 서버 로그로 바뀐다
              setSnapshot((prev) => (prev ? { ...prev, actions: [...prev.actions, msg.payload] } : prev));
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
  }, [gameId, code]);

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

  const sendFinish = useCallback(
    (winner: Side | null, reason: string | null) => {
      send({ type: "finish", payload: { winner, reason: reason ?? "" } });
    },
    [send],
  );

  const mySide = snapshot?.youAre ?? null;
  const turn = snapshot?.turn ?? "white";
  // 대국 중이고 내 차례인 대국자만 둘 수 있다. 관전자는 둘 수 없다
  const canMove = snapshot?.phase === "playing" && !!mySide && mySide === turn;

  return { snapshot, conn, fatal, notice, setNotice, clocks, chat, send, sendFinish, mySide, turn, canMove };
}

export type InviteRoom = ReturnType<typeof useInviteRoom>;

/** 게임마다 다른 부분. 셸은 이 슬롯만 보고 게임 종류를 모른다. */
export interface InvitePlay {
  /** 공유 문구에 쓰는 게임 이름 (체스, 장기) */
  label: string;
  /** 진영 이름 (백/흑, 초/한) */
  sideLabel: (side: Side) => string;
  /** orientation 쪽이 아래로 오는 보드 */
  board: (orientation: Side) => ReactNode;
  /** 그 진영이 잡은 기물 칸 */
  captured: (side: Side) => ReactNode;
  /** 상태줄 "체크!" 표시 여부 */
  inCheck: boolean;
  /** 대국 중 대국자에게만 보이는 게임 전용 버튼 */
  actions?: ReactNode;
  /** setup 단계 화면 (상차림 등) */
  setupPanel?: ReactNode;
}

export function InviteGameRoom({
  gameId,
  code,
  room,
  play,
}: {
  gameId: string;
  code: string;
  room: InviteRoom;
  play: InvitePlay;
}) {
  const router = useRouter();
  const { snapshot, conn, fatal, notice, setNotice, clocks, chat, send, mySide, turn } = room;
  const [confirmingResign, setConfirmingResign] = useState(false);
  const [chatInput, setChatInput] = useState("");
  const [flipped, setFlipped] = useState(false);
  const [ruleDraft, setRuleDraft] = useState({ baseMin: 10, byoSec: 30, byoCount: 1 });

  const shareUrl = typeof window !== "undefined" ? `${window.location.origin}/game/room/${gameId}/${code}` : "";

  const share = async () => {
    const text = `${play.label} 초대 대국 코드 ${code}`;
    if (navigator.share) {
      try {
        await navigator.share({ title: `ScoopHub ${play.label}`, text, url: shareUrl });
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
            <Button onClick={share}>초대코드 공유</Button>
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
      ) : snapshot.phase === "setup" ? (
        play.setupPanel
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
          {play.captured(opponentSide)}
          {play.board(myView)}
          {play.captured(mySide ?? myView)}
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
                    ? `${play.sideLabel(turn)} 차례 · 관전 ${snapshot.spectators.length + 1}명`
                    : turn === mySide
                      ? "내 차례"
                      : `${opponentLabel} 차례`}
                  {play.inCheck && (
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
                {mySide && play.actions}
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
