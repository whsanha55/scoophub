import assert from "node:assert/strict";
import { test } from "node:test";
import {
  chessRecordCodec,
  encodeShareRecord,
  decodeShareRecord,
  CHESS_RESULT,
  RECORD_CODECS,
} from "../src/domains/game/lib/record.ts";

const SAMPLE_MOVES = [
  "e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7",
  "Re1", "b5", "Bb3", "d6", "c3", "O-O", "h3",
];

test("PGN 왕복: toFile → fromFile 로 수순과 결과가 돌아온다", () => {
  const record = { game: "chess", moves: SAMPLE_MOVES, result: "1-0" };
  const pgn = chessRecordCodec.toFile(record);
  assert.ok(pgn.includes("[Event \"ScoopHub 체스\"]"));
  assert.ok(pgn.includes("[Result \"1-0\"]"));
  assert.ok(pgn.includes("1. e4 e5 2. Nf3"));

  const parsed = chessRecordCodec.fromFile(pgn);
  assert.deepEqual(parsed.moves, SAMPLE_MOVES);
  assert.equal(parsed.game, "chess");
  assert.equal(parsed.result, "1-0");
});

test("PGN 파싱: 잘못된 파일은 null", () => {
  assert.equal(chessRecordCodec.fromFile("이건 PGN이 아니다"), null);
  assert.equal(chessRecordCodec.fromFile(""), null);
});

test("PGN 결과 없는 기보는 진행 중 표기(*)로 나온다", () => {
  const pgn = chessRecordCodec.toFile({ game: "chess", moves: ["e4", "e5"] });
  assert.ok(pgn.includes("[Result \"*\"]"));
  const parsed = chessRecordCodec.fromFile(pgn);
  assert.equal(parsed.result, "*");
});

test("URL 해시 왕복: 압축 → 복원으로 수순이 돌아온다", async () => {
  const record = { game: "chess", moves: SAMPLE_MOVES, result: CHESS_RESULT.white };
  const encoded = await encodeShareRecord(record);
  assert.ok(encoded.startsWith("r="));
  assert.ok(!encoded.includes(" "));

  const decoded = await decodeShareRecord(encoded);
  assert.deepEqual(decoded.moves, SAMPLE_MOVES);
  assert.equal(decoded.game, "chess");
  assert.equal(decoded.result, "1-0");
});

test("URL 해시: 긴 기보는 원본보다 짧아진다", async () => {
  const fake = Array.from({ length: 200 }, (_, i) => `N${i % 2 ? "f3" : "g1"}`);
  const encoded = await encodeShareRecord({ game: "chess", moves: fake });
  assert.ok(encoded.length < JSON.stringify(fake).length);
});

test("URL 해시: 깨진 문자열은 null", async () => {
  assert.equal(await decodeShareRecord("r=!!!broken!!!"), null);
  assert.equal(await decodeShareRecord(""), null);
});

test("코덱 등록: 체스 코덱이 조회된다", () => {
  assert.equal(RECORD_CODECS.chess, chessRecordCodec);
});
