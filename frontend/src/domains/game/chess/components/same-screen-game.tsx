"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { useLocalValue } from "../../lib/local-value";
import { ChessBoard } from "./chess-board";
import {
  CHESS_DEFAULT_RULE,
  clockAfterMove,
  formatClock,
  initClock,
  isFlagged,
  isUntimed,
  tickClock,
  type PlayerClock,
  type Side,
  type TimeRule,
} from "../../lib/time-control";
import { chessEngine, hasMatingMaterial, type ChessState } from "../engine";
import { RecordActions } from "../../components/record-actions";
import { CHESS_RESULT, chessRecordCodec } from "../../lib/record";
import type { GameStatus } from "../../lib/board-game";

const ROTATE_KEY = "game.board.rotate";

type Phase = "setup" | "playing" | "over";
type Clocks = Record<Side, PlayerClock>;

function ClockView({
  clock,
  rule,
  label,
  active,
}: {
  clock: PlayerClock;
  rule: TimeRule;
  label: string;
  active: boolean;
}) {
  const ms = clock.inByoYomi ? clock.byoYomiLeftMs : clock.baseLeftMs;
  return (
    <div
      className={`flex items-center justify-between rounded-lg border px-3 py-1.5 ${
        active ? "border-primary bg-primary/10" : "border-border"
      }`}
    >
      <span className="text-sm font-semibold">{label}</span>
      {isUntimed(rule) ? (
        <span className="text-sm text-muted-foreground">무제한</span>
      ) : (
        <span className="flex items-baseline gap-2">
          {clock.inByoYomi && (
            <span className="text-xs text-muted-foreground">
              초읽기 ×{clock.byoYomiLeft}
            </span>
          )}
          <span className={`text-lg font-bold tabular-nums ${ms <= 10_000 ? "text-red-500" : ""}`}>
            {formatClock(ms)}
          </span>
        </span>
      )}
    </div>
  );
}

function RuleStepper({
  label,
  value,
  suffix,
  step,
  min,
  max,
  onChange,
}: {
  label: string;
  value: number;
  suffix: string;
  step: number;
  min: number;
  max: number;
  onChange: (v: number) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-sm text-muted-foreground">{label}</span>
      <div className="flex items-center gap-1.5">
        <Button
          variant="outline"
          size="icon-sm"
          aria-label={`${label} 감소`}
          disabled={value <= min}
          onClick={() => onChange(Math.max(min, value - step))}
        >
          −
        </Button>
        <span className="w-16 text-center text-sm font-semibold tabular-nums">
          {value}
          {suffix}
        </span>
        <Button
          variant="outline"
          size="icon-sm"
          aria-label={`${label} 증가`}
          disabled={value >= max}
          onClick={() => onChange(Math.min(max, value + step))}
        >
          +
        </Button>
      </div>
    </div>
  );
}

export function SameScreenChessGame() {
  const [phase, setPhase] = useState<Phase>("setup");
  const [rule, setRule] = useState<TimeRule>(CHESS_DEFAULT_RULE);
  const [baseMin, setBaseMin] = useState(10);
  const [byoSec, setByoSec] = useState(30);
  const [byoCount, setByoCount] = useState(1);
  const [state, setState] = useState<ChessState>(() => chessEngine.initialState());
  const [clocks, setClocks] = useState<Clocks | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [lastMove, setLastMove] = useState<{ from: string; to: string } | null>(null);
  const [result, setResult] = useState<GameStatus | null>(null);
  const [confirmingResign, setConfirmingResign] = useState(false);
  const [rotate, setRotate] = useLocalValue(ROTATE_KEY, true);
  const lastTickRef = useRef(0);

  const turn = chessEngine.turn(state);
  const pieces = chessEngine.pieces(state);
  const targets = selected ? chessEngine.legalTargets(state, selected) : [];
  const history = chessEngine.notationHistory(state);

  // 시계 인터벌은 매 틱 최신 값을 ref로 읽는다.
  const turnRef = useRef(turn);
  const ruleRef = useRef(rule);
  const stateRef = useRef(state);
  useEffect(() => {
    turnRef.current = turn;
    ruleRef.current = rule;
    stateRef.current = state;
  });

  const checkSquare = (() => {
    if (!chessEngine.inCheck(state)) return null;
    for (const [square, piece] of pieces) {
      if (piece.type === "k" && piece.side === turn) return square;
    }
    return null;
  })();

  const start = () => {
    const effective: TimeRule = {
      baseMs: baseMin * 60_000,
      byoYomiMs: byoSec * 1000,
      byoYomiCount: byoSec > 0 ? byoCount : 0,
    };
    setRule(effective);
    setState(chessEngine.initialState());
    setClocks({ white: initClock(effective), black: initClock(effective) });
    setSelected(null);
    setLastMove(null);
    setResult(null);
    setConfirmingResign(false);
    lastTickRef.current = Date.now();
    setPhase("playing");
  };

  // 브라우저 시계 (같은 화면 대국). 매 틱 상태를 다시 읽지 않도록 ref만 쓴다.
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
          // 상대에게 메이트할 기물이 없으면 시간패 대신 무승부
          const board = stateRef.current.game.board();
          const opponent: Side = mover === "white" ? "black" : "white";
          const winner = hasMatingMaterial(board) ? opponent : null;
          setResult({
            over: true,
            winner,
            reason: winner ? "시간패" : "시간 초과 무승부(메이트 불가)",
          });
          setPhase("over");
        }
        return next;
      });
    }, 250);
    return () => clearInterval(id);
  }, [phase]);

  const onSquareTap = (square: string) => {
    if (phase !== "playing") return;
    if (selected) {
      if (targets.includes(square)) {
        const r = chessEngine.applyMove(state, selected, square);
        if (r) {
          setState(r.state);
          setLastMove({ from: selected, to: square });
          setSelected(null);
          setConfirmingResign(false);
          if (clocks) {
            setClocks((prev) => ({ ...prev!, [turn]: clockAfterMove(prev![turn], rule) }));
          }
          lastTickRef.current = Date.now();
          const after = chessEngine.status(r.state);
          if (after.over) {
            setResult(after);
            setPhase("over");
          }
        }
        return;
      }
      const piece = pieces.get(square);
      if (piece && piece.side === turn) {
        setSelected(square);
        return;
      }
      setSelected(null);
      return;
    }
    const piece = pieces.get(square);
    if (piece && piece.side === turn) setSelected(square);
  };

  const undo = () => {
    if (phase !== "playing") return;
    const prev = chessEngine.undo(state);
    if (!prev) return;
    setState(prev);
    setSelected(null);
    setConfirmingResign(false);
    // 마지막 수 하이라이트는 되돌린 수와 맞지 않게 되므로 끈다
    setLastMove(null);
  };

  const resign = () => {
    if (phase !== "playing") return;
    const winner: Side = turn === "white" ? "black" : "white";
    setResult({ over: true, winner, reason: "기권" });
    setPhase("over");
  };

  const orientation: Side = rotate && phase === "playing" ? turn : "white";
  const topSide: Side = orientation === "white" ? "black" : "white";

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-3">
      {phase === "setup" ? (
        <div className="mx-auto flex w-full max-w-sm flex-col gap-4 rounded-xl border bg-card p-5">
          <h2 className="font-semibold">시간 규칙</h2>
          <div className="flex flex-col gap-3">
            <RuleStepper
              label="기본 시간"
              value={baseMin}
              suffix="분"
              step={1}
              min={0}
              max={60}
              onChange={setBaseMin}
            />
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
            <RuleStepper
              label="초읽기 횟수"
              value={byoCount}
              suffix="회"
              step={1}
              min={0}
              max={5}
              onChange={setByoCount}
            />
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
              rule={rule}
              label={topSide === "white" ? "백" : "흑"}
              active={phase === "playing" && turn === topSide}
            />
          )}
          <div className="relative">
            <ChessBoard
              pieces={pieces}
              orientation={orientation}
              rotatePieces
              selected={selected}
              targets={targets}
              lastMove={lastMove}
              checkSquare={checkSquare}
              onSquareTap={onSquareTap}
            />
            {phase === "over" && result && (
              <div className="absolute inset-0 z-20 flex items-center justify-center rounded-lg bg-background/70 p-4 backdrop-blur-sm">
                <div className="flex flex-col items-center gap-2 rounded-xl border bg-card p-5 text-center shadow-lg">
                  <p className="text-lg font-bold">
                    {result.winner
                      ? `${result.winner === "white" ? "백" : "흑"} 승리`
                      : "무승부"}
                  </p>
                  <p className="text-sm text-muted-foreground">{result.reason}</p>
                  <div className="mt-1 flex gap-2">
                    <Button onClick={start}>다시하기</Button>
                    <Button variant="outline" onClick={() => setPhase("setup")}>
                      시간 규칙 바꾸기
                    </Button>
                  </div>
                  <RecordActions
                    record={{
                      game: "chess",
                      moves: history,
                      result: result?.winner
                        ? CHESS_RESULT[result.winner]
                        : result
                          ? CHESS_RESULT.draw
                          : undefined,
                    }}
                    codec={chessRecordCodec}
                  />
                </div>
              </div>
            )}
          </div>
          {clocks && (
            <ClockView
              clock={clocks[orientation]}
              rule={rule}
              label={orientation === "white" ? "백" : "흑"}
              active={phase === "playing" && turn === orientation}
            />
          )}
          <div className="flex items-center justify-between gap-2">
            <span className="text-sm font-medium">
              {phase === "playing" ? (
                <>
                  {turn === "white" ? "백" : "흑"} 차례
                  {chessEngine.inCheck(state) && (
                    <span className="ml-1 font-bold text-red-500">체크!</span>
                  )}
                </>
              ) : null}
            </span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setRotate(!rotate)}
                aria-pressed={rotate}
              >
                판 회전 {rotate ? "켜짐" : "꺼짐"}
              </Button>
              {phase === "playing" && (
                <>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={undo}
                    disabled={history.length === 0}
                  >
                    무르기
                  </Button>
                  {confirmingResign ? (
                    <Button variant="destructive" size="sm" onClick={resign}>
                      {turn === "white" ? "백" : "흑"} 기권 확정
                    </Button>
                  ) : (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setConfirmingResign(true)}
                    >
                      기권
                    </Button>
                  )}
                </>
              )}
            </div>
          </div>
          {history.length > 0 && (
            <div className="max-h-40 overflow-y-auto rounded-lg border bg-card p-2">
              <ol className="grid grid-cols-[2rem_1fr_1fr] items-center gap-x-2 gap-y-0.5 text-sm tabular-nums">
                {Array.from({ length: Math.ceil(history.length / 2) }, (_, i) => (
                  <li key={i} className="col-span-3 grid grid-cols-subgrid">
                    <span className="text-muted-foreground">{i + 1}.</span>
                    <span>{history[i * 2]}</span>
                    <span>{history[i * 2 + 1] ?? ""}</span>
                  </li>
                ))}
              </ol>
            </div>
          )}
        </>
      )}
    </div>
  );
}
