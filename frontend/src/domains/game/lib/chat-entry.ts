import type { ChatEntry } from "./room-client";

/** 서버 채팅 항목(snake_case) → ChatEntry */
export function mapChat(raw: Record<string, unknown>): ChatEntry {
  return {
    nickname: raw.nickname as string,
    text: raw.text as string,
    fromPlayer: (raw.from_player as boolean) ?? false,
    system: (raw.system as boolean) ?? false,
  };
}
