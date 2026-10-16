// 초대 대국 방 클라이언트. REST(만들기·확인) + 방마다 WebSocket 하나(ADR-0003).
"use client";

import type { PlayerClock, TimeRule } from "./time-control";
import { wsBase } from "./guest";

export interface RoomMoveEntry {
  side: "white" | "black";
  from: string;
  to: string;
  notation: string;
}

export interface ChatEntry {
  nickname: string;
  text: string;
  fromPlayer: boolean;
  system: boolean;
}

export interface RoomSnapshot {
  phase: "waiting" | "setup" | "playing" | "finished";
  gameId: string;
  timeRule: TimeRule;
  players: { white: string | null; black: string | null };
  youAre: "white" | "black" | null;
  role: "player" | "spectator";
  youAreHost: boolean;
  rematchVotes: string[];
  /** 장기 상차림 제출 현황 (진영 → 패턴) */
  setups: { white?: string; black?: string };
  spectators: string[];
  chat: ChatEntry[];
  turn: "white" | "black";
  moves: RoomMoveEntry[];
  clocks: { white: PlayerClock; black: PlayerClock };
  result: { winner: "white" | "black" | null; reason: string } | null;
  serverNow: number;
}

export type RoomMessage =
  | { type: "room"; payload: RoomSnapshot }
  | { type: "move"; payload: { move: RoomMoveEntry; turn: "white" | "black" } }
  | { type: "chat"; payload: ChatEntry }
  | { type: "game_end"; payload: { winner: string | null; reason: string } }
  | { type: "error"; payload: { message: string } }
  | { type: "pong"; payload: Record<string, never> };

function mapSnapshot(raw: Record<string, unknown>): RoomSnapshot {
  const rule = raw.time_rule as Record<string, number>;
  const clocks = (raw.clocks ?? {}) as Record<string, Record<string, number | boolean>>;
  const zeroClock: PlayerClock = {
    baseLeftMs: 0,
    inByoYomi: false,
    byoYomiLeftMs: 0,
    byoYomiLeft: 0,
    flagged: false,
  };
  const mapClock = (c: Record<string, number | boolean> | undefined): PlayerClock =>
    c
      ? {
          baseLeftMs: c.base_left_ms as number,
          inByoYomi: c.in_byo_yomi as boolean,
          byoYomiLeftMs: c.byo_yomi_left_ms as number,
          byoYomiLeft: c.byo_yomi_left as number,
          flagged: c.flagged as boolean,
        }
      : zeroClock;
  const playersRaw = (raw.players ?? {}) as Record<string, string>;
  return {
    phase: raw.phase as RoomSnapshot["phase"],
    gameId: raw.game_id as string,
    timeRule: {
      baseMs: rule.base_ms,
      byoYomiMs: rule.byo_yomi_ms,
      byoYomiCount: rule.byo_yomi_count,
    },
    players: { white: playersRaw.white ?? null, black: playersRaw.black ?? null },
    youAre: (raw.you_are as RoomSnapshot["youAre"]) ?? null,
    role: (raw.role as RoomSnapshot["role"]) ?? "spectator",
    youAreHost: (raw.you_are_host as boolean) ?? false,
    rematchVotes: (raw.rematch_votes as string[]) ?? [],
    setups: (raw.setups as RoomSnapshot["setups"]) ?? {},
    spectators: (raw.spectators as string[]) ?? [],
    chat: (raw.chat as ChatEntry[]) ?? [],
    turn: raw.turn as RoomSnapshot["turn"],
    moves: (raw.moves as RoomMoveEntry[]) ?? [],
    clocks: {
      white: mapClock(clocks.white),
      black: mapClock(clocks.black),
    },
    result: (raw.result as RoomSnapshot["result"]) ?? null,
    serverNow: raw.server_now as number,
  };
}

export type ConnectionState = "connecting" | "open" | "closed";

/** 방 하나의 WS 연결. 끊기면 지수 백오프로 다시 붙는다(ADR-0003). */
export class RoomSocket {
  private ws: WebSocket | null = null;
  private backoffMs = 500;
  private closedByUser = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly code: string,
    private readonly guestId: string,
    private readonly nickname: string,
    private readonly onMessage: (msg: RoomMessage) => void,
    private readonly onState: (state: ConnectionState) => void,
  ) {}

  connect() {
    this.closedByUser = false;
    this.open();
  }

  private open() {
    this.onState("connecting");
    const url = `${wsBase()}/ws/game/${this.code}?guest=${encodeURIComponent(this.guestId)}&nick=${encodeURIComponent(this.nickname)}`;
    const ws = new WebSocket(url);
    this.ws = ws;
    ws.onopen = () => {
      this.backoffMs = 500;
      this.onState("open");
    };
    ws.onmessage = (e) => {
      try {
        const raw = JSON.parse(e.data as string) as { type: string; payload: unknown };
        if (raw.type === "room") {
          this.onMessage({ type: "room", payload: mapSnapshot(raw.payload as Record<string, unknown>) });
        } else {
          this.onMessage(raw as RoomMessage);
        }
      } catch {
        // 잘못된 메시지는 무시한다
      }
    };
    ws.onclose = () => {
      this.onState("closed");
      this.ws = null;
      if (this.closedByUser) return;
      // 재접속: 게스트 ID 로 자리를 되찾는다
      this.reconnectTimer = setTimeout(() => this.open(), this.backoffMs);
      this.backoffMs = Math.min(this.backoffMs * 2, 15_000);
    };
    ws.onerror = () => {
      ws.close();
    };
  }

  send(message: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message));
    }
  }

  close() {
    this.closedByUser = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.ws?.close();
    this.onState("closed");
  }
}

// ---- REST ----

export interface CreateRoomInput {
  gameId: string;
  timeRule: TimeRule;
  sidePreference: "white" | "black" | "random";
  /** 시작 전 대국자별 준비(장기 상차림)가 필요한지 */
  requiresSetup?: boolean;
}

export async function createRoom(input: CreateRoomInput, guestId: string, nickname: string): Promise<string> {
  const res = await fetch("/api/game/rooms", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      game_id: input.gameId,
      time_rule: {
        base_ms: input.timeRule.baseMs,
        byo_yomi_ms: input.timeRule.byoYomiMs,
        byo_yomi_count: input.timeRule.byoYomiCount,
      },
      side_preference: input.sidePreference,
      is_setup_required: input.requiresSetup ?? false,
      guest_id: guestId,
      nickname,
    }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.detail ?? "방을 만들지 못했다");
  }
  const data = await res.json();
  return data.data.code as string;
}

export interface RoomSummary {
  exists: boolean;
  phase: RoomSnapshot["phase"] | null;
  gameId: string | null;
  full: boolean | null;
}

export async function checkRoom(code: string): Promise<RoomSummary> {
  const res = await fetch(`/api/game/rooms/${encodeURIComponent(code)}`);
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.detail ?? "방을 확인하지 못했다");
  }
  const data = (await res.json()).data;
  return {
    exists: data.exists as boolean,
    phase: (data.phase?.toLowerCase() as RoomSummary["phase"]) ?? null,
    gameId: (data.game_id as string) ?? null,
    full: (data.is_full as boolean) ?? null,
  };
}
