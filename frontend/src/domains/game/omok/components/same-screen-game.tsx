"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { RuleStepper } from "../../components/rule-stepper";
import { ClockView } from "../../components/clock-view";
import {
  OMOK_DEFAULT_RULE,
  clockAfterMove,
  initClock,
  isFlagged,
  isUntimed,
  tickClock,
  type PlayerClock,
  type Side,
  type TimeRule,
} from "../../lib/time-control";
import { forbiddenPoints, omokEngine, winningLine, type OmokState } from "../engine";
import { OmokBoard, omokSideLabel } from "./omok-board";
import { OmokMoveList } from "./omok-move-list";

type Phase = "setup" | "playing" | "over";
type Clocks = Record<Side, PlayerClock>;

export function SameScreenOmokGame() {
  const [phase, setPhase] = useState<Phase>("setup");
  const [rule, setRule] = useState<TimeRule>(OMOK_DEFAULT_RULE);
  const [baseMin, setBaseMin] = useState(OMOK_DEFAULT_RULE.baseMs / 60_000);
  const [byoSec, setByoSec] = useState(OMOK_DEFAULT_RULE.byoYomiMs / 1000);
  const [byoCount, setByoCount] = useState(OMOK_DEFAULT_RULE.byoYomiCount);
  const [state, setState] = useState<OmokState>(() => omokEngine.initialState());
  const [clocks, setClocks] = useState<Clocks | null>(null);
  const [confirmingResign, setConfirmingResign] = useState(false);
  const [result, setResult] = useState<{ winner: Side | null; reason: string | null } | null>(null);
  const lastTickRef = useRef(0);

  const turn = omokEngine.turn(state);
  const history = omokEngine.notationHistory(state);
  const lastEntry = state.moves[state.moves.length - 1];

  const turnRef = useRef(turn);
  const ruleRef = useRef(rule);
  useEffect(() => {
    turnRef.current = turn;
    ruleRef.current = rule;
  });

  const start = () => {
    const effective: TimeRule = {
      baseMs: baseMin * 60_000,
      byoYomiMs: byoSec * 1000,
      byoYomiCount: byoSec > 0 ? byoCount : 0,
    };
    setRule(effective);
    setState(omokEngine.initialState());
    setClocks({ white: initClock(effective), black: initClock(effective) });
    setConfirmingResign(false);
    setResult(null);
    lastTickRef.current = Date.now();
    setPhase("playing");
  };

  // 브라우저 시계 (같은 화면 대국)
  useEffect(() => {
    if (phase !== "playing") return;
    const id = setInterval(() => {
      const now = Date.now();
      const elapsed = now - lastTickRef.current;
      lastTickRef.current = now;
      setClocks((prev) => {
        if (!prev) return prev;
        const mover = turnRef.current;
        const ruleNow = ruleRef.current;
        const next = { ...prev, [mover]: tickClock(prev[mover], ruleNow, elapsed) };
        if (isFlagged(next[mover], ruleNow)) {
          setResult({ winner: mover === "white" ? "black" : "white", reason: "시간패" });
          setPhase("over");
        }
        return next;
      });
    }, 250);
    return () => clearInterval(id);
  }, [phase]);

  const onPointTap = (point: string) => {
    if (phase !== "playing") return;
    const moved = omokEngine.applyMove(state, "", point);
    if (!moved) return;
    setState(moved.state);
    setConfirmingResign(false);
    setClocks((prev) => (prev ? { ...prev, [turn]: clockAfterMove(prev[turn], rule) } : prev));
    lastTickRef.current = Date.now();
    const after = omokEngine.status(moved.state);
    if (after.over) {
      setResult(after);
      setPhase("over");
    }
  };

  const undo = () => {
    if (phase !== "playing") return;
    const prev = omokEngine.undo(state);
    if (!prev) return;
    setState(prev);
    setConfirmingResign(false);
  };

  const resign = () => {
    if (phase !== "playing") return;
    setResult({ winner: turn === "white" ? "black" : "white", reason: "기권" });
    setPhase("over");
  };

  if (phase === "setup") {
    return (
      <div className="mx-auto flex w-full max-w-sm flex-col gap-4 rounded-xl border bg-card p-5">
        <p className="text-sm text-muted-foreground">
          흑이 먼저 둔다. 흑은 3-3·4-4·장목(6목 이상)을 둘 수 없다. 정확히 5목이면 이긴다.
        </p>
        <h2 className="font-semibold">시간 규칙</h2>
        <div className="flex flex-col gap-3">
          <RuleStepper label="기본 시간" value={baseMin} suffix="분" step={1} min={0} max={60} onChange={setBaseMin} />
          <RuleStepper
            label="초읽기"
            value={byoSec}
            suffix="초"
            step={5}
            min={0}
            max={60}
            onChange={(v) => {
              setByoSec(v);
              if (v === 0) setByoCount(0);
            }}
          />
          <RuleStepper label="초읽기 횟수" value={byoCount} suffix="회" step={1} min={0} max={5} onChange={setByoCount} />
        </div>
        {baseMin === 0 && byoSec === 0 && <p className="text-sm text-muted-foreground">시간 제한 없이 둔다</p>}
        <Button onClick={start}>대국 시작</Button>
      </div>
    );
  }

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-3">
      {clocks && (
        <ClockView
          clock={clocks.black}
          untimed={isUntimed(rule)}
          label={omokSideLabel("black")}
          active={phase === "playing" && turn === "black"}
        />
      )}
      <OmokBoard
        board={state.board}
        lastMove={lastEntry ? `${lastEntry.row},${lastEntry.col}` : null}
        winLine={winningLine(state)}
        forbidden={phase === "playing" ? forbiddenPoints(state) : []}
        onPointTap={onPointTap}
      />
      {clocks && (
        <ClockView
          clock={clocks.white}
          untimed={isUntimed(rule)}
          label={omokSideLabel("white")}
          active={phase === "playing" && turn === "white"}
        />
      )}
      <div className="flex w-full flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium">{phase === "playing" ? `${omokSideLabel(turn)} 차례` : null}</span>
        {phase === "playing" && (
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" onClick={undo} disabled={history.length === 0}>
              무르기
            </Button>
            {confirmingResign ? (
              <Button variant="destructive" size="sm" onClick={resign}>
                {omokSideLabel(turn)} 기권 확정
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
          <p className="text-lg font-bold">{result.winner ? `${omokSideLabel(result.winner)} 승리` : "무승부"}</p>
          <p className="text-sm text-muted-foreground">{result.reason}</p>
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
