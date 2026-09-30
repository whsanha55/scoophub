import assert from "node:assert/strict";
import { afterEach, mock, test } from "node:test";
import { requestCrawl } from "../src/shared/lib/crawl-api.ts";

afterEach(() => mock.restoreAll());

function respond(body, status = 200) {
  return mock.method(globalThis, "fetch", async () =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    }),
  );
}

test("수집 요청 성공 시 POST 경로를 인코딩하고 결과를 허용한다", async () => {
  const fetchMock = respond({ success: true, data: null });
  await requestCrawl("arxiv", "수집 실패");
  assert.deepEqual(fetchMock.mock.calls[0].arguments, ["/api/crawling/arxiv", { method: "POST" }]);
  await requestCrawl("invalid/path", "수집 실패");
  assert.equal(fetchMock.mock.calls[1].arguments[0], "/api/crawling/invalid%2Fpath");
});

test("200 응답이라도 실패 envelope의 메시지를 표시한다", async () => {
  respond({ success: false, error: { message: "수집기 비활성" } });
  await assert.rejects(requestCrawl("arxiv", "수집 실패"), /수집기 비활성/);
});

test("권한 오류의 detail을 표시한다", async () => {
  respond({ detail: "관리자 권한 필요" }, 403);
  await assert.rejects(requestCrawl("arxiv", "수집 실패"), /관리자 권한 필요/);
});

test("HTTP 실패를 성공 envelope로 덮어쓰지 않는다", async () => {
  respond({ success: true }, 500);
  await assert.rejects(requestCrawl("arxiv", "수집 실패"), /수집 실패 \(HTTP 500\)/);
});

test("JSON이 아닌 오류 응답은 HTTP 상태와 기본 메시지를 표시한다", async () => {
  mock.method(globalThis, "fetch", async () => new Response("Bad Gateway", { status: 502 }));
  await assert.rejects(requestCrawl("arxiv", "수집 실패"), /수집 실패 \(HTTP 502\)/);
});

test("네트워크 실패를 호출자에게 전달한다", async () => {
  mock.method(globalThis, "fetch", async () => { throw new Error("Network unavailable"); });
  await assert.rejects(requestCrawl("arxiv", "수집 실패"), /Network unavailable/);
});
