"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { RuleStepper } from "../../components/rule-stepper";
import { ClockView } from "../../components/clock-view";
import {
  YACHT_DEFAULT_RULE,
  clockAfterMove,
  initClock,
  isFlagged,
  isUntimed,
  tickClock,
  type PlayerClock,
  type Side,
  type TimeRule,
} from "../../lib/time-control";
import {
  applyKeep,
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

type Phase = "setup" | "playing" | "over";
type Clocks = Record<Side, PlayerClock>;

export function SameScreenYachtGame() {
  const [phase, setPhase] = useState<Phase>("setup");
  const [rule, setRule] = useState<TimeRule>(YACHT_DEFAULT_RULE);
  const [baseMin, setBaseMin] = useState(YACHT_DEFAULT_RULE.baseMs / 60_000);
  const [byoSec, setByoSec] = useState(YACHT_DEFAULT_RULE.byoYomiMs / 1000);
  const [byoCount, setByoCount] = useState(YACHT_DEFAULT_RULE.byoYomiCount);
  const [state, setState] = useState<YachtState>(initialState);
  const [clocks, setClocks] = useState<Clocks | null>(null);
  const [confirmingResign, setConfirmingResign] = useState(false);
  const [result, setResult] = useState<{ winner: Side | null; reason: string | null } | null>(null);
  const lastTickRef = useRef(0);

  const turn = state.turn;
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
    setState(initialState());
    setClocks({ white: initClock(effective), black: initClock(effective) });
    setConfirmingResign(false);
    setResult(null);
    lastTickRef.current = Date.now();
    setPhase("playing");
  };

  // 브라우저 시계 (같은 화면 대국). 굴리기부터 기록까지 자기 차례 전체에 흐른다
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

  const roll = () => {
    if (phase !== "playing") return;
    const next = applyRoll(state, rollDice(state.dice, state.kept));
    if (next) setState(next);
  };

  const toggleKeep = (index: number) => {
    if (phase !== "playing") return;
    const next = applyKeep(state, state.kept.map((k, i) => (i === index ? !k : k)));
    if (next) setState(next);
  };

  const onRecord = (category: Category) => {
    if (phase !== "playing") return;
    const recorded = record(state, category);
    if (!recorded) return;
    setState(recorded.state);
    setConfirmingResign(false);
    setClocks((prev) => (prev ? { ...prev, [turn]: clockAfterMove(prev[turn], rule) } : prev));
    lastTickRef.current = Date.now();
    const after = status(recorded.state);
    if (after.over) {
      setResult(after);
      setPhase("over");
    }
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
          한 차례에 주사위를 3번까지 굴리고, 점수표 빈 칸 하나에 기록하면 차례가 넘어간다. 12칸을 다 채우면 총점으로 겨룬다.
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
        <div className="grid grid-cols-2 gap-2">
          {(["white", "black"] as const).map((side) => (
            <ClockView
              key={side}
              clock={clocks[side]}
              untimed={isUntimed(rule)}
              label={yachtSideLabel(side)}
              active={phase === "playing" && turn === side}
            />
          ))}
        </div>
      )}
      <DiceTray
        dice={state.dice}
        kept={state.kept}
        rollsLeft={state.rollsLeft}
        canAct={phase === "playing"}
        onToggleKeep={toggleKeep}
        onRoll={roll}
      />
      <div className="flex w-full flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium">{phase === "playing" ? `${yachtSideLabel(turn)} 차례` : null}</span>
        {phase === "playing" &&
          (confirmingResign ? (
            <Button variant="destructive" size="sm" onClick={resign}>
              {yachtSideLabel(turn)} 기권 확정
            </Button>
          ) : (
            <Button variant="outline" size="sm" onClick={() => setConfirmingResign(true)}>
              기권
            </Button>
          ))}
      </div>
      <ScoreSheet
        sheets={state.sheets}
        turn={turn}
        dice={phase === "playing" && hasRolled(state) ? state.dice : null}
        onRecord={phase === "playing" ? onRecord : undefined}
      />
      {phase === "over" && result && (
        <div className="flex flex-col items-center gap-2 rounded-xl border bg-card p-5 text-center">
          <p className="text-lg font-bold">{result.winner ? `${yachtSideLabel(result.winner)} 승리` : "무승부"}</p>
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
