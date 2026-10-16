"use client";

// 게스트 신원. 로그인 없이 localStorage 의 guestId(서버 발급)로 재접속 자리를 찾는다.
import { useSyncExternalStore } from "react";

const GUEST_KEY = "game.guestId";
const NICK_KEY = "game.nickname";

const listeners = new Set<() => void>();

function readGuestId(): string | null {
  try {
    return localStorage.getItem(GUEST_KEY);
  } catch {
    return null;
  }
}

function readNickname(): string {
  try {
    return localStorage.getItem(NICK_KEY) ?? "";
  } catch {
    return "";
  }
}

export function setGuestId(id: string) {
  localStorage.setItem(GUEST_KEY, id);
  listeners.forEach((l) => l());
}

export function setNickname(nick: string) {
  localStorage.setItem(NICK_KEY, nick);
  listeners.forEach((l) => l());
}

/** 서버에서 게스트 ID를 발급받는다 (이미 있으면 재사용). */
export async function ensureGuestId(): Promise<string> {
  const existing = readGuestId();
  if (existing) return existing;
  const res = await fetch("/api/game/guests", { method: "POST" });
  if (!res.ok) throw new Error("게스트 ID 발급에 실패했다");
  const body = await res.json();
  const id = body?.data?.guest_id as string | undefined;
  if (!id) throw new Error("게스트 ID 발급 응답이 잘못됐다");
  setGuestId(id);
  return id;
}

/** 닉네임. 비어 있으면 게스트+4자리 숫자 기본값을 채운다. */
export async function ensureNickname(): Promise<string> {
  const existing = readNickname().trim();
  if (existing) return existing;
  const fallback = "게스트" + Math.floor(1000 + Math.random() * 9000);
  setNickname(fallback);
  return fallback;
}

export function useGuest(): { guestId: string | null; nickname: string } {
  const guestId = useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange);
      window.addEventListener("storage", onChange);
      return () => {
        listeners.delete(onChange);
        window.removeEventListener("storage", onChange);
      };
    },
    () => readGuestId(),
    () => null,
  );
  const nickname = useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange);
      return () => listeners.delete(onChange);
    },
    () => readNickname(),
    () => "",
  );
  return { guestId, nickname };
}

/** 초대 대국 WebSocket 주소. 운영은 같은 호스트의 /ws*(Caddy → 백엔드), 로컬은 백엔드 직접. */
export function wsBase(): string {
  const override = process.env.NEXT_PUBLIC_WS_BASE;
  if (override) return override;
  if (typeof window !== "undefined" && window.location.protocol === "https:") {
    return `wss://${window.location.host}`;
  }
  return "ws://localhost:20010";
}
