# Alpaca 뉴스 운영

## 활성화

1. Alpaca 대시보드에서 노출된 키를 폐기하고 새 키를 발급한다.
2. 운영 `backend/.env`에 `ALPACA_API_KEY`, `ALPACA_API_SECRET`, `ALPACA_STREAM_ENABLED=true`를 넣는다.
   `ALPACA_WORKER_ENABLED`를 생략하면 stream 설정을 따른다. 명시했다면 함께 true로 바꾼다.
3. 기존 `LLM_API_URL`, `LLM_API_KEY`, `LLM_MODEL=glm-5.1` 설정을 사용한다.
   URL은 Chat Completions 전체 경로여야 한다. 뉴스 호출은 20초 타임아웃과 thinking disabled를 사용한다.
4. 시스템 발신 라우팅에서 활성 `category=news`, `purpose=alpaca` 또는 빈 purpose 라우트와 텔레그램 토픽을 확인한다.
   마이그레이션이 기존 뉴스 rss 라우트를 alpaca로 전환하고 발송 이력을 보존한다. 라우트가 없거나 발송이 실패하면 pushed로 기록하지 않는다.
5. 기존 배포 절차로 배포한다. WebSocket은 단일 앱 인스턴스에서만 켠다. 로컬·운영 동시 연결은 피한다.

로컬 기본값은 연결·워커 모두 false다. 키를 쓰지 않고 검증할 때는 Gradle 테스트를 실행한다.

프로토콜은 [Alpaca 공식 뉴스 문서](https://docs.alpaca.markets/us/docs/streaming-real-time-news)를 따른다.
GLM 옵션은 [Z.ai Thinking Mode](https://docs.z.ai/guides/capabilities/thinking-mode)를 참고한다.

## 처리와 장애

- 모든 기사를 ID로 UPSERT한다. 최신 원문 수정은 처리 상태·요약·최초 발행/수신 시각을 보존한다.
- 단일 워커가 5초마다 `published_at, id` 순으로 최대 20건씩 조회하고 회차 안에서 큐를 비울 때까지 반복한다.
- 잡음과 종목/거시 정보가 없는 기사는 filtered, 15분 초과는 skipped/stale이다.
  잡음 규칙은 주가 움직임 설명(`Why Is … Stock`, `Shares of … trading higher/lower`), `Stock Market Today`, 의견 기사(`buying opportunity`, `Here Are the`)를 포함한다.
- LLM에 최근 2시간 발송 요약(최대 30건)을 함께 넘긴다. 같은 사건으로 판정한 기사는 skipped/duplicate다.
- 중요도 4 이상이면서 거시 카테고리이거나 관심 종목(`stock_watchlist`, 나스닥100 포함)이 붙은 기사만 보낸다. 그 외 4점 이상은 skipped/out-of-scope다. 결과를 먼저 저장하므로 발송 재시도는 LLM을 재호출하지 않는다.
- 실패 1회 후 30초, 2회 후 2분 대기하며 3회째 failed가 된다.
- LLM 3회 실패한 관심 종목 기사는 원문 헤드라인을 한 번 시도하고 failed에 결과를 기록한다.
- 한 회차의 푸시를 카드 묶음으로 보내면서 기사별 `news:alpaca:{id}` 성공 키를 남긴다.
  뉴스는 chat당 최소 3.1초 간격으로 보내며 HTML 카드 크기를 제한한다.
  카드는 한국어 요약을 굵은 제목으로 쓰고 영어 헤드라인은 원문 링크로만 남긴다. 요약이 없으면 헤드라인을 쓴다.
- 급증은 관심 종목의 최근 30분 3건 이상이다. 기본 제외 종목은 SPY/BTCUSD다.
  notify_log 성공 시각으로 실제 2시간 쿨다운을 적용하여 버킷 경계에서 다시 보내지 않는다.
- ping은 30초 간격, pong 대기는 10초다. 인증·구독에도 10초 제한을 둔다.
  재접속 대기는 1초부터 최대 60초이며, 인증·전체 뉴스 구독을 매번 다시 수행한다.
- 5분 이상 끊김은 성공할 때까지 1분 간격으로 알림을 재시도한다. 알렸던 끊김만 복구 알림을 보낸다.
- WS 단절 구간은 REST로 보충하지 않는다. 저장 실패로 연결을 닫은 경우의 기사도 보충하지 않는다.

발송 API가 성공했지만 응답이나 DB 로그 기록을 잃으면 재발송 가능성이 남는다.
Telegram Bot API는 기사 키를 통한 원격 멱등 발송을 지원하지 않아 엄밀한 exactly-once를 보장할 수 없다.
성공 로그가 남은 기사는 수정·재시작·부분 발송 실패 재시도에서도 중복을 억제한다.

## API와 화면

`GET /api/news`는 `from`, `to`(타임존을 포함한 ISO 시각), `minutes`, `category`, `min_importance`,
`symbol`, `limit`(1~100), `page`(1부터)를 받는다. 기본 기간은 최근 30분이다.
발행 시각 역순이며 동률이면 ID 역순이다. 단건 ID는 BIGINT다.

응답은 기존 ApiResponse 래핑을 유지한다. `headline`, `symbols`, `summary_ko`, `status`,
`source_updated_at`, `decision_reason`을 제공하며 RSS 전용 필드를 제거했다.
뉴스 화면은 한국어 요약이 없으면 원문 요약을 표시하며, 원문 HTML을 직접 렌더링하지 않는다.

## RSS 뉴스 완전 제거

뉴스는 Alpaca만 사용한다. RSS 뉴스 수집·중복 판단·요약·소스 관리·수동 실행 API를 제거했다.
V22 마이그레이션은 기존 `feed_news`와 뉴스 전용 수집 스케줄·설정·소스 데이터를 삭제한다.
기존 데이터는 이관하지 않으며 Alpaca 비활성화 시에도 RSS로 복귀하지 않는다.
기존 뉴스 `rss` 발신 라우트는 `alpaca`로 바꾸고 성공 이력을 보존한다.

이미 적용된 Flyway V1~V20 파일은 체크섬을 보존하기 위해 수정하지 않는다.
새 DB도 전체 마이그레이션 후에는 RSS 뉴스 테이블·설정이 남지 않는다.
TechNewsletterCrawler가 사용하는 RssClient·Rome 의존성·rome.properties는 계속 사용한다.

## 운영 지표

아래 쿼리를 운영 DB에서 실행한다. 전송 지연 기준은 Alpaca 발행 시각(published_at)이다.
여러 라우트로 보냈다면 가장 먼저 성공한 발신 시각을 사용한다.

```sql
WITH delivered AS (
    SELECT a.id, a.published_at, MIN(l.sent_at) AS sent_at
    FROM news_article a JOIN notify_log l ON l.payload_key = 'news:alpaca:' || a.id
    WHERE l.status = 'success' AND a.published_at >= NOW() - INTERVAL '1 day'
    GROUP BY a.id, a.published_at
)
SELECT COUNT(*) AS pushed_articles,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM sent_at - published_at)) AS p95_seconds
FROM delivered;

SELECT status, decision_reason, COUNT(*)
FROM news_article WHERE created_at >= NOW() - INTERVAL '1 day'
GROUP BY status, decision_reason ORDER BY COUNT(*) DESC;

SELECT COUNT(*) AS pending,
       MIN(published_at) AS oldest_pending,
       COUNT(*) FILTER (WHERE next_attempt_at > NOW()) AS waiting_retry
FROM news_article WHERE status = 'pending';
```

하루 10~30건, p95 60초, 무료 플랜 연결 한도는 실제 장중 운영에서 확인해야 한다.
호출량은 `News LLM batch` 로그로 확인한다. 배치 크기·제외 종목·필터의 가설은 운영 자료로 조정한다.
