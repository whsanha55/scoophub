// 대국 기보 공통 형식과 저장·공유 코덱.
// 게임마다 코덱을 바꿔 끼운다(장기 형식은 장기 이슈에서 추가).
import { Chess } from "chess.js";

export interface GameRecord {
  /** 게임 식별자 (chess, janggi) */
  game: string;
  /** 수 순서 표기 (체스 SAN, 장기는 자체 표기) */
  moves: string[];
  /** PGN 결과 표기: 1-0 / 0-1 / 1/2-1/2 / *(진행 중) */
  result?: string;
  resultReason?: string;
}

export interface RecordCodec {
  game: string;
  /** 파일 내용 (체스: PGN) */
  toFile(record: GameRecord): string;
  fromFile(text: string): GameRecord | null;
  /** URL 해시에 담을 압축 문자열 */
  encodeShare(record: GameRecord): Promise<string>;
  decodeShare(encoded: string): Promise<GameRecord | null>;
}

export const CHESS_RESULT: Record<string, string> = {
  white: "1-0",
  black: "0-1",
  draw: "1/2-1/2",
};

/** 체스 PGN 코덱. chess.js 로 생성·파싱한다. */
export const chessRecordCodec: RecordCodec = {
  game: "chess",

  toFile(record: GameRecord): string {
    const game = new Chess();
    for (const san of record.moves) {
      if (!game.move(san)) break;
    }
    game.header(
      "Event",
      "ScoopHub 체스",
      "Result",
      record.result ?? "*",
      "White",
      "백",
      "Black",
      "흑",
    );
    return game.pgn({ maxWidth: 72, newline: "\n" });
  },

  fromFile(text: string): GameRecord | null {
    if (!text.trim()) return null;
    try {
      const game = new Chess();
      game.loadPgn(text);
      const moves = game.history();
      if (moves.length === 0) return null;
      const headers = game.header();
      return {
        game: "chess",
        moves: moves,
        result: (headers.Result as string) ?? undefined,
      };
    } catch {
      return null;
    }
  },

  async encodeShare(record: GameRecord): Promise<string> {
    return encodeShareRecord(record);
  },

  async decodeShare(encoded: string): Promise<GameRecord | null> {
    return decodeShareRecord(encoded);
  },
};

/** 게임 식별자 → 코덱. 새 게임은 여기에 꽂는다. */
export const RECORD_CODECS: Record<string, RecordCodec> = {
  chess: chessRecordCodec,
};

// ---- URL 해시 압축(모든 게임 공통) ----

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlToBytes(text: string): Uint8Array {
  const normalized = text.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/** JSON → deflate → base64url. 해시 접두어 r= 을 붙인다. */
export async function encodeShareRecord(record: GameRecord): Promise<string> {
  const compact = {
    g: record.game,
    m: record.moves,
    r: record.result,
  };
  const json = JSON.stringify(compact);
  const stream = new Blob([json as BlobPart])
    .stream()
    .pipeThrough(new CompressionStream("deflate-raw"));
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  return "r=" + bytesToBase64Url(bytes);
}

export async function decodeShareRecord(encoded: string): Promise<GameRecord | null> {
  const body = encoded.startsWith("r=") ? encoded.slice(2) : encoded;
  try {
    const stream = new Blob([base64UrlToBytes(body) as unknown as BlobPart])
      .stream()
      .pipeThrough(new DecompressionStream("deflate-raw"));
    const json = await new Response(stream).text();
    const parsed = JSON.parse(json) as { g: string; m: string[]; r?: string };
    if (!parsed.g || !Array.isArray(parsed.m)) return null;
    return { game: parsed.g, moves: parsed.m, result: parsed.r };
  } catch {
    return null;
  }
}

/** 기보 파일 내려받기 */
export function downloadRecord(filename: string, content: string) {
  const blob = new Blob([content], { type: "application/x-chess-pgn" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
