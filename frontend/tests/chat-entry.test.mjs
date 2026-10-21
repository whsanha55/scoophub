import assert from "node:assert/strict";
import { test } from "node:test";
import { mapChat } from "../src/domains/game/lib/chat-entry.ts";

test("대국자가 보낸 채팅은 fromPlayer 가 true 다", () => {
  // given
  const raw = { nickname: "QA봇", text: "안녕", from_player: true, system: false };

  // when
  const entry = mapChat(raw);

  // then
  assert.deepEqual(entry, { nickname: "QA봇", text: "안녕", fromPlayer: true, system: false });
});

test("관전자 채팅과 시스템 알림은 fromPlayer 가 false 다", () => {
  // given
  const spectator = { nickname: "구경꾼", text: "화이팅", from_player: false, system: false };
  const notice = { nickname: "", text: "QA봇 입장", from_player: false, system: true };

  // when
  // then
  assert.equal(mapChat(spectator).fromPlayer, false);
  assert.equal(mapChat(notice).system, true);
});
