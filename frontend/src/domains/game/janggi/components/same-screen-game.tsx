"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useLocalValue } from "../../lib/local-value";
import { RuleStepper } from "../../components/rule-stepper";
import {
  JANGGI_DEFAULT_RULE,
  clockAfterMove,
  initClock,
  isFlagged,
  isUntimed,
  tickClock,
  type PlayerClock,
  type Side,
  type TimeRule,
} from "../../lib/time-control";
import { janggiEngine, SETUP_PATTERNS, type JanggiState } from "../engine";
import { ClockView } from "../../components/clock-view";
import { JanggiBoard } from "./janggi-board";

const ROTATE_KEY = "game.board.rotate";

type Phase = "setup" | "playing" | "over";
type Clocks = Record<Side, PlayerClock>;

function sideLabel(side: Side): string {
  return side === "white" ? "초" : "한";
}

export function SameScreenJanggiGame() {
  const [phase, setPhase] = useState<Phase>("setup");
  const [rule, setRule] = useState<TimeRule>(JANGGI_DEFAULT_RULE);
  const [baseMin, setBaseMin] = useState(JANGGI_DEFAULT_RULE.baseMs / 60_000);
  const [byoSec, setByoSec] = useState(JANGGI_DEFAULT_RULE.byoYomiMs / 1000);
  const [byoCount, setByoCount] = useState(JANGGI_DEFAULT_RULE.byoYomiCount);
  const [choSetup, setChoSetup] = useState<string>("마상마상");
  const [hanSetup, setHanSetup] = useState<string>("마상마상");
  const [state, setState] = useState<JanggiState>(() => janggiEngine.initialState());
  const [clocks, setClocks] = useState<Clocks | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [confirmingResign, setConfirmingResign] = useState(false);
  const [result, setResult] = useState<{ over: boolean; winner: Side | null; reason: string | null } | null>(
    null,
  );
  const [rotate, setRotate] = useLocalValue(ROTATE_KEY, true);
  const lastTickRef = useRef(0);
  const scoreRef = useRef(0);

  const turn = janggiEngine.turn(state);
  const history = janggiEngine.notationHistory(state);
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
    setState(janggiEngine.initialState({ cho: choSetup, han: hanSetup }));
    setClocks({ white: initClock(effective), black: initClock(effective) });
    setSelected(null);
    setConfirmingResign(false);
    scoreRef.current = 0;
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
          const winner: Side = mover === "white" ? "black" : "white";
          setResult({ over: true, winner, reason: "시간패" });
          setPhase("over");
        }
        return next;
      });
    }, 250);
    return () => clearInterval(id);
  }, [phase]);

  const checkAfter = (next: JanggiState) => {
    const after = janggiEngine.status(next);
    if (after.over) {
      setResult(after);
      setPhase("over");
    }
  };

  const onPointTap = (point: string) => {
    if (phase !== "playing") return;
    const [r, c] = point.split(",").map(Number);
    const targets = selected ? janggiEngine.legalTargets(state, selected) : [];
    if (selected && targets.includes(point)) {
      const moved = janggiEngine.applyMove(state, selected, point);
      if (moved) {
        setState(moved.state);
        setSelected(null);
        setConfirmingResign(false);
        if (clocks) {
          setClocks((prev) => ({ ...prev!, [turn]: clockAfterMove(prev![turn], rule) }));
        }
        lastTickRef.current = Date.now();
        checkAfter(moved.state);
      }
      return;
    }
    const piece = state.board[r][c];
    if (piece && piece.side === turn) {
      setSelected(point);
    } else {
      setSelected(null);
    }
  };

  const pass = () => {
    if (phase !== "playing") return;
    const passed = janggiEngine.pass(state);
    if (!passed) return;
    setState(passed.state);
    setSelected(null);
    setConfirmingResign(false);
    if (clocks) {
      setClocks((prev) => ({ ...prev!, [turn]: clockAfterMove(prev![turn], rule) }));
    }
    lastTickRef.current = Date.now();
    checkAfter(passed.state);
  };

  const declareBigjang = () => {
    if (phase !== "playing") return;
    const result = janggiEngine.declareBigjang(state);
    if (result) {
      setResult(result);
      setPhase("over");
    }
  };

  const undo = () => {
    if (phase !== "playing") return;
    const prev = janggiEngine.undo(state);
    if (!prev) return;
    setState(prev);
    setSelected(null);
    setConfirmingResign(false);
  };

  const resign = () => {
    if (phase !== "playing") return;
    const winner: Side = turn === "white" ? "black" : "white";
    setResult({ over: true, winner, reason: "기권" });
    setPhase("over");
  };

  const orientation: Side = rotate && phase === "playing" ? turn : "white";
  const topSide: Side = orientation === "white" ? "black" : "white";
  const targets = selected ? janggiEngine.legalTargets(state, selected) : [];
  const checkSquare = (() => {
    if (!janggiEngine.inCheck(state)) return null;
    for (const [point, piece] of janggiEngine.pieces(state)) {
      if (piece.type === "K" && piece.side === turn) return point;
    }
    return null;
  })();

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col items-center gap-3">
      {phase === "setup" ? (
        <div className="flex w-full max-w-sm flex-col gap-4 rounded-xl border bg-card p-5">
          <h2 className="font-semibold">상차림</h2>
          {(
            [
              ["초 (선수)", choSetup, setChoSetup],
              ["한 (후수, 덤 1.5점)", hanSetup, setHanSetup],
            ] as const
          ).map(([label, value, setter]) => (
            <div key={label} className="flex flex-col gap-1.5">
              <span className="text-sm text-muted-foreground">{label}</span>
              <div className="flex flex-wrap gap-1">
                {SETUP_PATTERNS.map((pattern) => (
                  <Button
                    key={pattern}
                    variant={value === pattern ? "default" : "outline"}
                    size="sm"
                    onClick={() => setter(pattern)}
                  >
                    {pattern}
                  </Button>
                ))}
              </div>
            </div>
          ))}
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
          {baseMin === 0 && byoSec === 0 && (
            <p className="text-sm text-muted-foreground">시간 제한 없이 둔다</p>
          )}
          <Button onClick={start}>대국 시작</Button>
        </div>
      ) : (
        <>
          {clocks && (
            <ClockView
              clock={clocks[topSide]}
              untimed={isUntimed(rule)}
              label={sideLabel(topSide)}
              active={phase === "playing" && turn === topSide}
            />
          )}
          <JanggiBoard
            board={state.board}
            orientation={orientation}
            selected={selected}
            targets={targets}
            lastMove={
              lastEntry && !lastEntry.pass
                ? { from: `${lastEntry.from.row},${lastEntry.from.col}`, to: `${lastEntry.to.row},${lastEntry.to.col}` }
                : null
            }
            checkSquare={checkSquare}
            onPointTap={onPointTap}
          />
          {clocks && (
            <ClockView
              clock={clocks[orientation]}
              untimed={isUntimed(rule)}
              label={sideLabel(orientation)}
              active={phase === "playing" && turn === orientation}
            />
          )}
          <div className="flex w-full flex-wrap items-center justify-between gap-2">
            <span className="text-sm font-medium">
              {phase === "playing" ? (
                <>
                  {sideLabel(turn)} 차례
                  {janggiEngine.inCheck(state) && (
                    <span className="ml-1 font-bold text-red-500">장군!</span>
                  )}
                </>
              ) : null}
            </span>
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" size="sm" onClick={() => setRotate(!rotate)} aria-pressed={rotate}>
                판 회전 {rotate ? "켜짐" : "꺼짐"}
              </Button>
              {phase === "playing" && (
                <>
                  <Button variant="outline" size="sm" onClick={pass}>
                    한수쉼
                  </Button>
                  <Button variant="outline" size="sm" onClick={declareBigjang}>
                    빅장
                  </Button>
                  <Button variant="outline" size="sm" onClick={undo} disabled={history.length === 0}>
                    무르기
                  </Button>
                  {confirmingResign ? (
                    <Button variant="destructive" size="sm" onClick={resign}>
                      {sideLabel(turn)} 기권 확정
                    </Button>
                  ) : (
                    <Button variant="outline" size="sm" onClick={() => setConfirmingResign(true)}>
                      기권
                    </Button>
                  )}
                </>
              )}
            </div>
          </div>
          {history.length > 0 && (
            <div className="max-h-36 w-full overflow-y-auto rounded-lg border bg-card p-2">
              <ol className="flex flex-wrap gap-x-3 text-sm tabular-nums">
                {history.map((n, i) => (
                  <li key={i}>
                    <span className="mr-1 text-muted-foreground">{i + 1}.</span>
                    {n}
                  </li>
                ))}
              </ol>
            </div>
          )}
          {phase === "over" && result && (
            <div className="flex flex-col items-center gap-2 rounded-xl border bg-card p-5 text-center">
              <p className="text-lg font-bold">
                {result.winner ? `${sideLabel(result.winner)} 승리` : "무승부"}
              </p>
              <p className="text-sm text-muted-foreground">{result.reason}</p>
              <div className="mt-1 flex gap-2">
                <Button onClick={start}>다시하기</Button>
                <Button variant="outline" onClick={() => setPhase("setup")}>
                  설정 바꾸기
                </Button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
