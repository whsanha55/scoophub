"use client";

import type { RoomSnapshot } from "../../lib/room-client";
import { InviteGameRoom, useInviteRoom } from "../../components/invite-game-room";
import {
  CATEGORIES,
  applyKeep,
  applyRecord,
  applyRoll,
  hasRolled,
  initialState,
  record,
  rollDice,
  status,
  type Category,
  type YachtState,
} from "../engine";
import { DiceTray } from "./dice-tray";
import { ScoreSheet, yachtSideLabel } from "./score-sheet";

const isCategory = (value: string): value is Category => (CATEGORIES as readonly string[]).includes(value);

/**
 * 기록(수)으로 점수표를 채우고, 직전 기록 이후 행동 로그로 이번 차례 주사위를 되살린다(ADR-0004).
 * 차례가 아닌 쪽의 행동, 4번째 굴림, 잘못된 값은 엔진이 거부해 건너뛴다.
 */
function replayYacht(snapshot: RoomSnapshot): YachtState {
  let state = initialState();
  for (const m of snapshot.moves) {
    const next = m.side === state.turn && isCategory(m.from) ? applyRecord(state, m.from, Number(m.to)) : null;
    if (!next) break; // 조작된 기록은 알 수 없는 시점에서 멈춘다 (ADR-0001 감수)
    state = next;
  }
  for (const { side, action } of snapshot.actions) {
    if (side !== state.turn) continue;
    const next =
      action.kind === "roll" ? applyRoll(state, action.dice) : action.kind === "keep" ? applyKeep(state, action.kept) : null;
    if (next) state = next;
  }
  return state;
}

export function InviteYachtGame({ gameId, code }: { gameId: string; code: string }) {
  const room = useInviteRoom(gameId, code);
  const { snapshot, canMove, send, sendFinish } = room;
  const state = snapshot ? replayYacht(snapshot) : initialState();

  // 굴림·고정은 차례를 넘기지 않는 중간 행동이다. 주사위는 굴리는 쪽이 만든다(ADR-0004)
  const roll = () => {
    if (!canMove || state.rollsLeft === 0) return;
    send({ type: "game_action", payload: { kind: "roll", dice: rollDice(state.dice, state.kept) } });
  };

  const toggleKeep = (index: number) => {
    if (!canMove || !hasRolled(state)) return;
    send({ type: "game_action", payload: { kind: "keep", kept: state.kept.map((k, i) => (i === index ? !k : k)) } });
  };

  // 기록만 수(move)로 보낸다: from=칸, to=점수. 차례가 넘어가고 시계가 멈춘다
  const onRecord = (category: Category) => {
    if (!canMove) return;
    const recorded = record(state, category);
    if (!recorded) return;
    send({ type: "move", payload: { from: category, to: String(recorded.score), notation: recorded.notation } });
    const after = status(recorded.state);
    if (after.over) sendFinish(after.winner, after.reason);
  };

  return (
    <InviteGameRoom
      gameId={gameId}
      code={code}
      room={room}
      play={{
        label: "야추다이스",
        sideLabel: yachtSideLabel,
        board: () => (
          <div className="flex flex-col gap-3">
            <DiceTray
              dice={state.dice}
              kept={state.kept}
              rollsLeft={state.rollsLeft}
              canAct={canMove}
              onToggleKeep={toggleKeep}
              onRoll={roll}
            />
            <ScoreSheet
              sheets={state.sheets}
              turn={state.turn}
              names={snapshot?.players}
              dice={snapshot?.phase === "playing" && hasRolled(state) ? state.dice : null}
              onRecord={canMove ? onRecord : undefined}
            />
          </div>
        ),
        captured: () => null,
        inCheck: false,
      }}
    />
  );
}
