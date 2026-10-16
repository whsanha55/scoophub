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
  /** 장기 상차림 (초·한) */
  setup?: { cho: string; han: string };
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
/**
 * 장기 기보 텍스트 형식(자체 형식 — 사실상 표준이 없다):
 * ```
 * [ScoopHub Janggi]
 * Setup cho=마상마상 han=마상상마
 * Result cho|han|none
 * 1. 초 차 1,1→1,4
 * 2. 한 한수쉼
 * ```
 * 수 표기는 엔진 표기(기물명 from→to)를 그대로 쓴다.
 */
const JANGGI_HEADER = "[ScoopHub Janggi]";

export const janggiRecordCodec: RecordCodec = {
  game: "janggi",

  toFile(record: GameRecord): string {
    const lines = [JANGGI_HEADER];
    const setup = record.setup ?? { cho: "마상마상", han: "마상마상" };
    lines.push(`Setup cho=${setup.cho} han=${setup.han}`);
    lines.push(`Result ${record.result ?? "none"}`);
    record.moves.forEach((notation, i) => {
      const side = i % 2 === 0 ? "초" : "한";
      lines.push(`${i + 1}. ${side} ${notation}`);
    });
    return lines.join("\n") + "\n";
  },

  fromFile(text: string): GameRecord | null {
    if (!text.includes(JANGGI_HEADER)) return null;
    const setup = { cho: "마상마상", han: "마상마상" };
    let result: string | undefined;
    const moves: string[] = [];
    for (const rawLine of text.split("\n")) {
      const line = rawLine.trim();
      if (!line || line === JANGGI_HEADER) continue;
      const setupMatch = line.match(/^Setup\s+cho=(\S+)\s+han=(\S+)$/);
      if (setupMatch) {
        setup.cho = setupMatch[1];
        setup.han = setupMatch[2];
        continue;
      }
      const resultMatch = line.match(/^Result\s+(cho|han|none)$/);
      if (resultMatch) {
        result = resultMatch[1] === "none" ? undefined : resultMatch[1];
        continue;
      }
      const moveMatch = line.match(/^\d+\.\s+(초|한)\s+(.+)$/);
      if (moveMatch) moves.push(moveMatch[2]);
    }
    if (moves.length === 0) return null;
    return { game: "janggi", moves, result, setup };
  },

  async encodeShare(record: GameRecord): Promise<string> {
    return encodeShareRecord(record);
  },

  async decodeShare(encoded: string): Promise<GameRecord | null> {
    return decodeShareRecord(encoded);
  },
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
  const compact: Record<string, unknown> = {
    g: record.game,
    m: record.moves,
    r: record.result,
  };
  if (record.setup) {
    compact.s = { c: record.setup.cho, h: record.setup.han };
  }
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
    const parsed = JSON.parse(json) as {
      g: string;
      m: string[];
      r?: string;
      s?: { c: string; h: string };
    };
    if (!parsed.g || !Array.isArray(parsed.m)) return null;
    return {
      game: parsed.g,
      moves: parsed.m,
      result: parsed.r,
      setup: parsed.s ? { cho: parsed.s.c, han: parsed.s.h } : undefined,
    };
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

/** 게임 식별자 → 코덱. 새 게임은 여기에 꽂는다. */
export const RECORD_CODECS: Record<string, RecordCodec> = {
  chess: chessRecordCodec,
  janggi: janggiRecordCodec,
};
