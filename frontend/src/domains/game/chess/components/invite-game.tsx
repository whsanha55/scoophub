"use client";

import { useState } from "react";
import type { RoomSnapshot } from "../../lib/room-client";
import { InviteGameRoom, useInviteRoom } from "../../components/invite-game-room";
import { CapturedPiecesView } from "../../components/captured-pieces";
import { chessEngine, hasMatingMaterial, type ChessState } from "../engine";
import { ChessBoard, chessCapturedGlyph } from "./chess-board";

function replayChess(moves: RoomSnapshot["moves"]): ChessState {
  let state = chessEngine.initialState();
  for (const m of moves) {
    const r = chessEngine.applyMove(state, m.from, m.to);
    if (!r) break; // 조작된 기보는 알 수 없는 시점에서 멈춘다 (ADR-0001 감수)
    state = r.state;
  }
  return state;
}

export function InviteChessGame({ gameId, code }: { gameId: string; code: string }) {
  const room = useInviteRoom(gameId, code);
  const { snapshot, mySide, turn, canMove, send, sendFinish } = room;
  const [selected, setSelected] = useState<string | null>(null);

  const state = snapshot ? replayChess(snapshot.moves) : null;
  const pieces = state ? chessEngine.pieces(state) : new Map();
  const lastEntry = snapshot?.moves[snapshot.moves.length - 1] ?? null;
  const checkSquare = (() => {
    if (!state || !chessEngine.inCheck(state)) return null;
    for (const [square, piece] of pieces) {
      if (piece.type === "k" && piece.side === turn) return square;
    }
    return null;
  })();
  const targets = selected !== null && state ? chessEngine.legalTargets(state, selected) : [];
  const captured = state ? chessEngine.capturedPieces(state) : null;

  const onSquareTap = (square: string) => {
    if (!canMove || !state) return;
    if (selected) {
      if (targets.includes(square)) {
        const r = chessEngine.applyMove(state, selected, square);
        if (r) {
          // 규칙 판정은 클라이언트가 하고, 서버는 순서와 시계만 관리한다 (ADR-0001).
          // 메이트할 기물 유무를 함께 보내 서버가 시간패를 무승부로 바꿀 수 있게 한다.
          const board = r.state.game.board();
          send({
            type: "move",
            payload: {
              from: selected,
              to: square,
              notation: r.notation,
              mating_material: { white: hasMatingMaterial(board, "w"), black: hasMatingMaterial(board, "b") },
            },
          });
          const after = chessEngine.status(r.state);
          if (after.over) sendFinish(after.winner, after.reason);
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

  return (
    <InviteGameRoom
      gameId={gameId}
      code={code}
      room={room}
      play={{
        label: "체스",
        sideLabel: (side) => (side === "white" ? "백" : "흑"),
        board: (orientation) => (
          <ChessBoard
            pieces={pieces}
            orientation={orientation}
            selected={selected}
            targets={targets}
            lastMove={lastEntry ? { from: lastEntry.from, to: lastEntry.to } : null}
            checkSquare={checkSquare}
            onSquareTap={onSquareTap}
          />
        ),
        captured: (side) =>
          captured && <CapturedPiecesView pieces={captured[side]} render={chessCapturedGlyph} />,
        inCheck: chessEngine.inCheck(state ?? chessEngine.initialState()),
      }}
    />
  );
}
