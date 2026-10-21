"use client";

import type { RoomSnapshot } from "../../lib/room-client";
import { InviteGameRoom, useInviteRoom } from "../../components/invite-game-room";
import { forbiddenPoints, omokEngine, winningLine, type OmokState } from "../engine";
import { OmokBoard } from "./omok-board";
import { omokSideLabel } from "./same-screen-game";

function replayOmok(moves: RoomSnapshot["moves"]): OmokState {
  let state = omokEngine.initialState();
  for (const m of moves) {
    const r = omokEngine.applyMove(state, m.from, m.to);
    if (!r) break; // 조작된 기보는 알 수 없는 시점에서 멈춘다 (ADR-0001 감수)
    state = r.state;
  }
  return state;
}

export function InviteOmokGame({ gameId, code }: { gameId: string; code: string }) {
  const room = useInviteRoom(gameId, code);
  const { snapshot, canMove, send, sendFinish } = room;

  const state = snapshot ? replayOmok(snapshot.moves) : omokEngine.initialState();
  const lastEntry = snapshot?.moves[snapshot.moves.length - 1] ?? null;

  const onPointTap = (point: string) => {
    if (!canMove) return;
    const moved = omokEngine.applyMove(state, "", point);
    if (!moved) return;
    // 오목은 놓기만 하므로 from 은 비운다. 규칙 판정은 클라이언트가 한다 (ADR-0001)
    send({ type: "move", payload: { from: "", to: point, notation: moved.notation } });
    const after = omokEngine.status(moved.state);
    if (after.over) sendFinish(after.winner, after.reason);
  };

  return (
    <InviteGameRoom
      gameId={gameId}
      code={code}
      room={room}
      play={{
        label: "오목",
        sideLabel: omokSideLabel,
        // 흑·백 돌은 위아래가 없어 판을 뒤집지 않는다
        board: () => (
          <OmokBoard
            board={state.board}
            lastMove={lastEntry?.to ?? null}
            winLine={winningLine(state)}
            forbidden={snapshot?.phase === "playing" ? forbiddenPoints(state) : []}
            onPointTap={onPointTap}
          />
        ),
        captured: () => null,
        inCheck: false,
      }}
    />
  );
}
