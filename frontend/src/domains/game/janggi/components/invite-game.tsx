"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import type { RoomSnapshot } from "../../lib/room-client";
import type { Side } from "../../lib/time-control";
import { InviteGameRoom, useInviteRoom } from "../../components/invite-game-room";
import { CapturedPiecesView } from "../../components/captured-pieces";
import { janggiEngine, SETUP_PATTERNS, type JanggiState } from "../engine";
import { JanggiBoard, janggiCapturedGlyph } from "./janggi-board";

function replayJanggi(snapshot: RoomSnapshot): JanggiState {
  const setups = snapshot.setups ?? {};
  let state = janggiEngine.initialState({
    cho: setups.white ?? "마상마상",
    han: setups.black ?? "마상마상",
  });
  for (const m of snapshot.moves) {
    const coords = m.notation?.match(/(\d+),(\d+)→(\d+),(\d+)/);
    if (m.notation === "한수쉼") {
      const r = janggiEngine.pass(state);
      if (!r) break;
      state = r.state;
    } else if (coords) {
      const r = janggiEngine.applyMovePos(
        state,
        { row: Number(coords[1]), col: Number(coords[2]) },
        { row: Number(coords[3]), col: Number(coords[4]) },
      );
      if (!r) break;
      state = r.state;
    } else {
      break;
    }
  }
  return state;
}

const sideLabel = (side: Side) => (side === "white" ? "초" : "한");

export function InviteJanggiGame({ gameId, code }: { gameId: string; code: string }) {
  const room = useInviteRoom(gameId, code);
  const { snapshot, mySide, turn, canMove, send, sendFinish } = room;
  const [selected, setSelected] = useState<string | null>(null);

  const state = snapshot ? replayJanggi(snapshot) : null;
  const lastEntry = snapshot?.moves[snapshot.moves.length - 1] ?? null;
  const targets = state && selected !== null ? janggiEngine.legalTargets(state, selected) : [];
  const checkSquare = (() => {
    if (!state || !janggiEngine.inCheck(state)) return null;
    for (const [point, piece] of janggiEngine.pieces(state)) {
      if (piece.type === "K" && piece.side === turn) return point;
    }
    return null;
  })();
  const captured = state ? janggiEngine.capturedPieces(state) : null;
  const scores = state ? janggiEngine.scores(state) : null;

  const onPointTap = (point: string) => {
    if (!canMove || !state) return;
    const [r, c] = point.split(",").map(Number);
    if (selected) {
      if (targets.includes(point)) {
        const moved = janggiEngine.applyMovePos(
          state,
          { row: Number(selected.split(",")[0]), col: Number(selected.split(",")[1]) },
          { row: r, col: c },
        );
        if (moved) {
          send({ type: "move", payload: { from: selected, to: point, notation: moved.notation } });
          const after = janggiEngine.status(moved.state);
          if (after.over) sendFinish(after.winner, after.reason);
        }
        setSelected(null);
        return;
      }
      const piece = state.board[r][c];
      if (piece && piece.side === mySide) {
        setSelected(point);
        return;
      }
      setSelected(null);
      return;
    }
    const piece = state.board[r][c];
    if (piece && piece.side === mySide) setSelected(point);
  };

  const pass = () => {
    if (!state || snapshot?.phase !== "playing") return;
    const passed = janggiEngine.pass(state);
    if (!passed) return;
    send({ type: "move", payload: { from: "-1,-1", to: "-1,-1", notation: "한수쉼" } });
    setSelected(null);
  };

  const declareBigjang = () => {
    if (!state || snapshot?.phase !== "playing") return;
    const result = janggiEngine.declareBigjang(state);
    if (result) sendFinish(result.winner, result.reason);
  };

  const setupPanel = snapshot && (
    <div className="mx-auto flex w-full max-w-sm flex-col gap-4 rounded-xl border bg-card p-6">
      <h2 className="font-semibold">상차림 고르기</h2>
      <p className="text-sm text-muted-foreground">
        두 대국자 모두 고르면 대국이 시작된다.
      </p>
      {mySide ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm text-muted-foreground">
            내 진영: {sideLabel(mySide)} {snapshot.setups[mySide] ? "(제출함)" : ""}
          </span>
          <div className="flex flex-wrap gap-1">
            {SETUP_PATTERNS.map((pattern) => (
              <Button
                key={pattern}
                variant={snapshot.setups[mySide] === pattern ? "default" : "outline"}
                size="sm"
                onClick={() => send({ type: "setup", payload: { pattern } })}
              >
                {pattern}
              </Button>
            ))}
          </div>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">관전자는 상차림에 참여하지 않는다</p>
      )}
    </div>
  );

  return (
    <InviteGameRoom
      gameId={gameId}
      code={code}
      room={room}
      play={{
        label: "장기",
        sideLabel,
        board: (orientation) => (
          <JanggiBoard
            board={(state ?? janggiEngine.initialState()).board}
            orientation={orientation}
            selected={selected}
            targets={targets}
            lastMove={lastEntry ? { from: lastEntry.from, to: lastEntry.to } : null}
            checkSquare={checkSquare}
            onPointTap={onPointTap}
          />
        ),
        captured: (side) =>
          captured &&
          scores && (
            <CapturedPiecesView pieces={captured[side]} render={janggiCapturedGlyph} score={scores[side]} />
          ),
        // 기존 동작 유지: 상태줄 "체크!"는 체스에만 있었다. 장군은 보드 강조로만 보인다
        inCheck: false,
        actions: (
          <>
            <Button variant="outline" size="sm" onClick={pass}>
              한수쉼
            </Button>
            <Button variant="outline" size="sm" onClick={declareBigjang}>
              빅장
            </Button>
          </>
        ),
        setupPanel,
      }}
    />
  );
}
