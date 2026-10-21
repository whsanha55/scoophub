"use client";

import { SIZE, type Board } from "../engine";

export interface OmokBoardProps {
  board: Board;
  /** 마지막 착수 "r,c" */
  lastMove: string | null;
  /** 5목을 이룬 돌들 */
  winLine: string[] | null;
  /** 지금 둘 수 없는 금수 점. X로 표시하고 누르지 못하게 한다 */
  forbidden: string[];
  onPointTap: (point: string) => void;
}

// 화점: 천원과 네 귀
const STAR_POINTS = [
  [3, 3],
  [3, 11],
  [7, 7],
  [11, 3],
  [11, 11],
];

/** 15×15 교차점 오목판. 흑·백 돌은 위아래가 없어 판을 뒤집지 않는다. */
export function OmokBoard({ board, lastMove, winLine, forbidden, onPointTap }: OmokBoardProps) {
  const last = SIZE - 1;
  const forbiddenSet = new Set(forbidden);
  const winSet = new Set(winLine ?? []);

  return (
    <div className="relative mx-auto aspect-square w-full max-w-[min(92vw,520px)] select-none overflow-hidden rounded-lg border-2 border-amber-900/60 bg-[#e3bd7a] shadow-sm">
      <svg
        viewBox={`0 0 ${last} ${last}`}
        className="absolute inset-[4%] h-[92%] w-[92%] overflow-visible"
        preserveAspectRatio="none"
        aria-hidden
      >
        <g stroke="#6b4518" strokeWidth="0.04">
          {Array.from({ length: SIZE }, (_, i) => (
            <line key={`v${i}`} x1={i} y1={0} x2={i} y2={last} />
          ))}
          {Array.from({ length: SIZE }, (_, i) => (
            <line key={`h${i}`} x1={0} y1={i} x2={last} y2={i} />
          ))}
        </g>
        {STAR_POINTS.map(([r, c]) => (
          <circle key={`${r},${c}`} cx={c} cy={r} r={0.12} fill="#6b4518" />
        ))}
      </svg>
      <div className="absolute inset-[4%]">
        {board.map((row, r) =>
          row.map((stone, c) => {
            const point = `${r},${c}`;
            const isForbidden = forbiddenSet.has(point);
            return (
              <button
                key={point}
                type="button"
                disabled={isForbidden}
                aria-label={`${point}${stone ? ` ${stone === "white" ? "흑" : "백"}` : isForbidden ? " 금수" : ""}`}
                className="absolute flex h-[6.4%] w-[6.4%] -translate-x-1/2 -translate-y-1/2 items-center justify-center"
                style={{ left: `${(c / last) * 100}%`, top: `${(r / last) * 100}%` }}
                onPointerDown={(e) => {
                  e.preventDefault();
                  if (!isForbidden) onPointTap(point);
                }}
              >
                {stone && (
                  <span
                    className={`relative flex h-[92%] w-[92%] items-center justify-center rounded-full shadow-[1px_1px_2px_rgba(0,0,0,0.45)] ${
                      stone === "white"
                        ? "bg-[radial-gradient(circle_at_35%_30%,#555,#111_70%)]"
                        : "bg-[radial-gradient(circle_at_35%_30%,#fff,#d6d6d6_75%)]"
                    } ${winSet.has(point) ? "ring-2 ring-red-500 ring-offset-1 ring-offset-[#e3bd7a]" : ""}`}
                  >
                    {lastMove === point && <span className="h-1/3 w-1/3 rounded-full bg-red-500" />}
                  </span>
                )}
                {!stone && isForbidden && (
                  <span className="text-[min(4.5vw,1.25rem)] font-bold leading-none text-red-600">✕</span>
                )}
              </button>
            );
          }),
        )}
      </div>
    </div>
  );
}
