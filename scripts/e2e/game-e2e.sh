#!/usr/bin/env bash
# 게임: 게스트 발급 → 방 만들기 → 초대코드 확인(게임마다 따로) → WebSocket 두 게스트 대국 → 검증 실패·한도 초과
# WebSocket 단계는 node(22+, 내장 WebSocket)로 돈다.
source "$(dirname "$0")/lib.sh"
command -v node >/dev/null 2>&1 || fail "필요한 도구가 없다: node"

IP="CF-Connecting-IP: 10.9.$((RANDOM % 250)).$((RANDOM % 250))"

room_body() {
  jq -nc --arg game "$1" --arg guest "$2" '{
    game_id: $game,
    time_rule: {base_ms: 600000, byo_yomi_ms: 30000, byo_yomi_count: 1},
    side_preference: "white", guest_id: $guest, nickname: "방장"}'
}

step "게스트 발급(방장)" 200 POST /api/game/guests
HOST_ID="$(json .data.guest_id)"
step "게스트 발급(상대)" 200 POST /api/game/guests
GUEST_ID="$(json .data.guest_id)"

step "체스 방 만들기" 200 POST /api/game/rooms "$(room_body chess "$HOST_ID")" "$IP"
CODE="$(json .data.code)"
[[ "$CODE" =~ ^[0-9]{6}$ ]] || fail "초대코드가 6자리 숫자가 아니다: $CODE"

step "체스 초대코드 확인" 200 GET "/api/game/chess/rooms/$CODE" "" "$IP"
expect '.data.exists' 'true'
expect '.data.game_id' 'chess'
expect '.data.player_count' '1'
step "같은 코드를 장기로 확인하면 없다" 200 GET "/api/game/janggi/rooms/$CODE" "" "$IP"
expect '.data.exists' 'false'

echo "==> WebSocket 대국: /ws/game/chess/$CODE"
WS_BASE="${BASE_URL/http/ws}" CODE="$CODE" HOST_ID="$HOST_ID" GUEST_ID="$GUEST_ID" node --input-type=module -e '
const { WS_BASE, CODE, HOST_ID, GUEST_ID } = process.env;
const fail = (m) => { console.error("FAIL: " + m); process.exit(1); };
const timer = setTimeout(() => fail("WebSocket 시나리오가 10초 안에 끝나지 않았다"), 10_000);

function connect(guest, nick) {
  const ws = new WebSocket(`${WS_BASE}/ws/game/chess/${CODE}?guest=${guest}&nick=${nick}`);
  const inbox = [];
  const waiters = [];
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    const i = waiters.findIndex((w) => w.match(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg); else inbox.push(msg);
  };
  const opened = new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error("연결 실패")); });
  return {
    opened,
    send: (obj) => ws.send(JSON.stringify(obj)),
    next: (match) => {
      const i = inbox.findIndex(match);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve) => waiters.push({ match, resolve }));
    },
    close: () => ws.close(),
  };
}

const playing = (m) => m.type === "room" && m.payload.phase === "playing";
const host = connect(HOST_ID, "host");
await host.opened;
const guest = connect(GUEST_ID, "guest");
await guest.opened;

const started = await guest.next(playing);
await host.next(playing);
if (started.payload.you_are !== "black") fail(`상대 진영이 black 이 아니다: ${started.payload.you_are}`);
console.log("    대국 시작: host=white guest=black");

guest.send({ type: "move", payload: { from: "e7", to: "e5", notation: "e5" } });
const rejected = await guest.next((m) => m.type === "error");
if (rejected.payload.message !== "지금은 둘 수 없습니다") fail(`차례가 아닌 수의 에러가 다르다: ${rejected.payload.message}`);
console.log("    차례가 아닌 수는 거부: " + rejected.payload.message);

host.send({ type: "move", payload: { from: "e2", to: "e4", notation: "e4" } });
const moved = await guest.next((m) => m.type === "move");
if (moved.payload.move.from !== "e2" || moved.payload.move.to !== "e4") fail("상대가 받은 수가 다르다");
if (moved.payload.turn !== "black") fail(`수 다음 차례가 black 이 아니다: ${moved.payload.turn}`);
console.log("    e2→e4 가 상대에게 전달됐다");

host.send({ type: "chat", payload: { text: "잘 부탁해" } });
const chat = await guest.next((m) => m.type === "chat" && !m.payload.system);
if (chat.payload.text !== "잘 부탁해" || chat.payload.from_player !== true) fail("채팅이 대국자 채팅으로 오지 않았다");
console.log("    채팅 전달: from_player=true");

host.close();
guest.close();
clearTimeout(timer);
'

step "WebSocket 대국 뒤 방 요약" 200 GET "/api/game/chess/rooms/$CODE" "" "$IP"
expect '.data.player_count' '2'
expect '.data.phase' 'playing'

step "오목 방 만들기" 200 POST /api/game/rooms "$(room_body omok "$HOST_ID")" "$IP"
OMOK_CODE="$(json .data.code)"
step "오목 초대코드 확인" 200 GET "/api/game/omok/rooms/$OMOK_CODE" "" "$IP"
expect '.data.exists' 'true'
expect '.data.game_id' 'omok'
step "같은 코드를 체스로 확인하면 없다" 200 GET "/api/game/chess/rooms/$OMOK_CODE" "" "$IP"
expect '.data.exists' 'false'

echo "==> WebSocket 오목 착수: /ws/game/omok/$OMOK_CODE"
WS_BASE="${BASE_URL/http/ws}" CODE="$OMOK_CODE" HOST_ID="$HOST_ID" GUEST_ID="$GUEST_ID" node --input-type=module -e '
const { WS_BASE, CODE, HOST_ID, GUEST_ID } = process.env;
const fail = (m) => { console.error("FAIL: " + m); process.exit(1); };
const timer = setTimeout(() => fail("오목 WebSocket 시나리오가 10초 안에 끝나지 않았다"), 10_000);

function connect(guest, nick) {
  const ws = new WebSocket(`${WS_BASE}/ws/game/omok/${CODE}?guest=${guest}&nick=${nick}`);
  const inbox = [];
  const waiters = [];
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    const i = waiters.findIndex((w) => w.match(msg));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(msg); else inbox.push(msg);
  };
  const opened = new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error("연결 실패")); });
  return {
    opened,
    send: (obj) => ws.send(JSON.stringify(obj)),
    next: (match) => {
      const i = inbox.findIndex(match);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve) => waiters.push({ match, resolve }));
    },
    close: () => ws.close(),
  };
}

const playing = (m) => m.type === "room" && m.payload.phase === "playing";
const host = connect(HOST_ID, "host");
await host.opened;
const guest = connect(GUEST_ID, "guest");
await guest.opened;
await guest.next(playing);
await host.next(playing);
console.log("    오목 대국 시작 (상차림 없음)");

// 오목은 놓기만 하므로 from 을 비워 보낸다
host.send({ type: "move", payload: { from: "", to: "7,7", notation: "H8" } });
const moved = await guest.next((m) => m.type === "move");
if (moved.payload.move.from !== "" || moved.payload.move.to !== "7,7") fail(`상대가 받은 착수가 다르다: ${JSON.stringify(moved.payload.move)}`);
if (moved.payload.turn !== "black") fail(`착수 다음 차례가 black 이 아니다: ${moved.payload.turn}`);
console.log("    from 빈 착수 7,7(H8) 이 상대에게 전달됐다");

host.close();
guest.close();
clearTimeout(timer);
'

step "게임 종류가 빈 방 만들기" 400 POST /api/game/rooms "$(room_body "" "$HOST_ID")" "$IP"

LIMIT_IP="CF-Connecting-IP: 10.8.$((RANDOM % 250)).$((RANDOM % 250))"
for _ in 1 2 3 4 5 6 7 8 9 10; do request GET /api/game/chess/rooms/000000 "" "$LIMIT_IP"; done
step "초대코드 확인 한도 초과" 429 GET /api/game/chess/rooms/000000 "" "$LIMIT_IP"
expect '.detail' '초대코드 확인이 너무 잦습니다. 잠시 뒤 시도하세요'

ok "game"
