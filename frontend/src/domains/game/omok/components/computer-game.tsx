"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import type { Side } from "../../lib/board-game";
import { computerMove, type Difficulty } from "../computer";
import { FIRST, forbiddenPoints, omokEngine, winningLine, type OmokState } from "../engine";
import { OmokBoard, omokSideLabel } from "./omok-board";
import { OmokMoveList } from "./omok-move-list";

type Phase = "setup" | "playing" | "over";

/** white=선수(흑돌), black=후수(백돌) */
const SIDES: Side[] = ["white", "black"];
const DIFFICULTIES: { id: Difficulty; label: string }[] = [
  { id: "easy", label: "쉬움" },
  { id: "hard", label: "어려움" },
];
/** 내 돌이 먼저 그려진 뒤 컴퓨터 돌이 놓이도록 잠깐 기다린다 */
const COMPUTER_DELAY_MS = 300;

export function ComputerOmokGame() {
  const [phase, setPhase] = useState<Phase>("setup");
  const [humanSide, setHumanSide] = useState<Side>(FIRST);
  const [difficulty, setDifficulty] = useState<Difficulty>("easy");
  const [state, setState] = useState<OmokState>(() => omokEngine.initialState());
  const [confirmingResign, setConfirmingResign] = useState(false);
  const [result, setResult] = useState<{ winner: Side | null; reason: string | null } | null>(null);

  const computerSide: Side = humanSide === "white" ? "black" : "white";
  const turn = omokEngine.turn(state);
  const myTurn = phase === "playing" && turn === humanSide;
  const history = omokEngine.notationHistory(state);
  const lastEntry = state.moves[state.moves.length - 1];
  const myMoveCount = state.moves.filter((m) => m.side === humanSide).length;
  const difficultyLabel = DIFFICULTIES.find((d) => d.id === difficulty)?.label;

  // 컴퓨터 차례면 잠시 뒤 둔다. 무르기·기권·다시하기로 국면이 바뀌면 기다리던 수를 취소한다
  useEffect(() => {
    if (phase !== "playing" || turn !== computerSide) return;
    const id = setTimeout(() => {
      const point = computerMove(state, difficulty);
      const moved = point ? omokEngine.applyMove(state, "", point) : null;
      if (!moved) return;
      setState(moved.state);
      const after = omokEngine.status(moved.state);
      if (after.over) {
        setResult(after);
        setPhase("over");
      }
    }, COMPUTER_DELAY_MS);
    return () => clearTimeout(id);
  }, [phase, state, turn, computerSide, difficulty]);

  const start = () => {
    setState(omokEngine.initialState());
    setConfirmingResign(false);
    setResult(null);
    setPhase("playing");
  };

  const onPointTap = (point: string) => {
    if (!myTurn) return;
    const moved = omokEngine.applyMove(state, "", point);
    if (!moved) return;
    setState(moved.state);
    setConfirmingResign(false);
    const after = omokEngine.status(moved.state);
    if (after.over) {
      setResult(after);
      setPhase("over");
    }
  };

  /** 컴퓨터의 마지막 수와 내 마지막 수를 함께 되돌려 다시 내 차례로 만든다 */
  const undo = () => {
    if (!myTurn) return;
    const withoutComputer = omokEngine.undo(state);
    const withoutMine = withoutComputer && omokEngine.undo(withoutComputer);
    if (!withoutMine) return;
    setState(withoutMine);
    setConfirmingResign(false);
  };

  const resign = () => {
    if (phase !== "playing") return;
    setResult({ winner: computerSide, reason: "기권" });
    setPhase("over");
  };

  if (phase === "setup") {
    return (
      <div className="mx-auto flex w-full max-w-sm flex-col gap-4 rounded-xl border bg-card p-5">
        <p className="text-sm text-muted-foreground">
          흑이 먼저 둔다. 흑은 3-3·4-4·장목(6목 이상)을 둘 수 없다. 정확히 5목이면 이긴다. 컴퓨터가 흑이어도 같다.
        </p>
        <div className="flex flex-col gap-2">
          <h2 className="font-semibold">내 돌</h2>
          <div role="group" aria-label="내 돌" className="flex gap-2">
            {SIDES.map((side) => (
              <Button
                key={side}
                variant={side === humanSide ? "default" : "outline"}
                aria-pressed={side === humanSide}
                onClick={() => setHumanSide(side)}
              >
                {omokSideLabel(side)}
                {side === FIRST ? " (먼저 둔다)" : ""}
              </Button>
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-2">
          <h2 className="font-semibold">난이도</h2>
          <div role="group" aria-label="난이도" className="flex gap-2">
            {DIFFICULTIES.map(({ id, label }) => (
              <Button
                key={id}
                variant={id === difficulty ? "default" : "outline"}
                aria-pressed={id === difficulty}
                onClick={() => setDifficulty(id)}
              >
                {label}
              </Button>
            ))}
          </div>
        </div>
        <Button onClick={start}>대국 시작</Button>
      </div>
    );
  }

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-3">
      <p className="text-sm text-muted-foreground">
        나 {omokSideLabel(humanSide)} · 컴퓨터 {omokSideLabel(computerSide)} ({difficultyLabel})
      </p>
      <OmokBoard
        board={state.board}
        lastMove={lastEntry ? `${lastEntry.row},${lastEntry.col}` : null}
        winLine={winningLine(state)}
        forbidden={myTurn ? forbiddenPoints(state) : []}
        onPointTap={onPointTap}
      />
      <div className="flex w-full flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium" aria-live="polite">
          {phase === "playing" ? (myTurn ? `내 차례 (${omokSideLabel(turn)})` : "컴퓨터가 두는 중…") : null}
        </span>
        {phase === "playing" && (
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" onClick={undo} disabled={!myTurn || myMoveCount === 0}>
              무르기
            </Button>
            {confirmingResign ? (
              <Button variant="destructive" size="sm" onClick={resign}>
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
      <OmokMoveList history={history} />
      {phase === "over" && result && (
        <div className="flex flex-col items-center gap-2 rounded-xl border bg-card p-5 text-center">
          <p className="text-lg font-bold">
            {result.winner === null ? "무승부" : result.winner === humanSide ? "내가 이겼다" : "컴퓨터가 이겼다"}
          </p>
          <p className="text-sm text-muted-foreground">
            {result.winner ? `${omokSideLabel(result.winner)} 승리 · ` : ""}
            {result.reason}
          </p>
          <div className="mt-1 flex gap-2">
            <Button onClick={start}>다시하기</Button>
            <Button variant="outline" onClick={() => setPhase("setup")}>
              설정 바꾸기
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
