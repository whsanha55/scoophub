// 보드게임 공통 인터페이스. 체스·장기 규칙이 이 뒤에 꽂히고,
// 보드 UI·시간 규칙·초대 대국 방은 게임 종류를 모른 채 이 인터페이스만 쓴다.
// (ADR-0001: 규칙 판정은 클라이언트에서만 한다)

export type Side = "white" | "black";

export interface PieceInfo {
  /** 체스: p n b r q k / 장기: K R B N A C P (대문자=초(선수), 소문자=한(후수)) */
  type: string;
  side: Side;
}

export interface GameStatus {
  over: boolean;
  /** 승자. 무승부면 null. */
  winner: Side | null;
  /** 종료 사유 표기 (체크메이트, 시간패, ...) */
  reason: string | null;
}

/** 규칙 상태는 게임마다 다르고 어댑터가 소유한다. 불투명 토큰으로만 주고받는다. */
export interface BoardGameEngine<S> {
  initialState(): S;
  /** 현재 차례 */
  turn(state: S): Side;
  /** 칸 → 기물. 체스는 "e4", 장기는 "0,1" 같은 게임별 좌표. */
  pieces(state: S): Map<string, PieceInfo>;
  /** 진영별로 그 진영이 잡은 상대 기물. 표기 순서는 어댑터가 정한다. */
  capturedPieces(state: S): Record<Side, PieceInfo[]>;
  /** from에서 둘 수 있는 칸 목록 */
  legalTargets(state: S, from: string): string[];
  /** 수를 둔다. 불법수면 null. 반환 상태는 원본과 별개 값이다. */
  applyMove(state: S, from: string, to: string): { state: S; notation: string } | null;
  /** 무르기: 직전 수를 되돌린다. 되돌릴 수 없으면 null. */
  undo(state: S): S | null;
  status(state: S): GameStatus;
  inCheck(state: S): boolean;
  /** 기보 표기 목록 (둔 순서대로) */
  notationHistory(state: S): string[];
}
