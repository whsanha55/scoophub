# ScoopHub 모노레포 + Kotlin 전환 계획

> 목표: Python(FastAPI) 백엔드를 **Spring Boot 4.1 + Kotlin 2.4 + Java 25** 로 이관하고, UI 와 함께 하나의 레포(`whsanha55/scoophub`)로 관리한다.
> 원칙: **API 경로·응답 JSON 형식·DB 스키마를 1:1 유지** → UI(frontend) 무수정, 기존 DB 그대로 사용.

각 항목은 체크 후 순서대로 진행한다. 의견/변경은 항목 옆에 메모.

---

## 0. 확정된 결정사항

| 항목 | 결정 |
|---|---|
| 히스토리 | 두 레포 커밋 히스토리 보존 (원본 해시 유지, force push 불필요) |
| 원격 레포 | 기존 `whsanha55/scoophub` 재사용 → `chore/monorepo` 브랜치 PR 로 main 반영, `scoophub-ui` 는 이후 archive |
| DB 접근 | **Spring Data JPA** (upsert 등 PG 전용 쿼리는 `@Query(nativeQuery = true)`) |
| 웹 스택 | Spring MVC + 가상 스레드 (`spring.threads.virtual.enabled=true`) — WebFlux/코루틴 미사용 |
| 스키마 | Flyway SQL(V1~V20) 그대로 재사용. Spring Boot 가 기동 시 migrate |

---

## 1. 현재 상태 (완료)

- [x] 로컬 `chore/monorepo` 브랜치 생성 (push 안 함)
- [x] Python 백엔드 → `backend-legacy/` (커밋됨)
- [x] scoophub-ui → `frontend/` 히스토리 병합 (커밋됨, 총 302 커밋)
- [x] Flyway SQL → `backend/src/main/resources/db/migration/` 이동 (**미커밋**)
  - legacy 참조 경로 3곳 수정: `docker-compose.yml`, `run_local.sh`, `tests/conftest.py`
- [x] `backend/` 더미 뼈대 (**미커밋**) — Gradle 9.7.1 wrapper, `build.gradle.kts`, `ScoophubApplication.kt`, 빈 `application.yml`. `compileKotlin` 통과 확인

현재 디렉터리 구조:

```
scoophub/
├── backend/          # Kotlin Spring Boot (신규, 뼈대만)
├── backend-legacy/   # Python FastAPI (이관 완료 시 삭제)
├── frontend/         # Next.js 16
└── MIGRATION_PLAN.md
```

---

## 2. 모노레포 마무리 (P0)

- [x] 루트 `README.md` — 구조, 로컬 실행법(backend / frontend), 배포 방법
- [x] 루트 `.gitignore` 정리 — frontend `.idea/`, `*.iml`, `__pycache__` 추적 해제
- [x] 미커밋 변경(마이그레이션 이동 + backend 뼈대) 커밋
- [x] compose 프로젝트명 고정 — `backend-legacy`: `name: scoophub`, `frontend`: `name: scoophub-ui` (디렉터리명 변경으로 컨테이너명 `scoophub-app-1` 이 바뀌어 frontend `API_URL` 이 깨지는 것 방지)
- [ ] 서버 백엔드 `.env` 를 레포 루트 → `backend-legacy/.env` 로 이동
- [ ] `chore/monorepo` push → PR → main 머지
- [ ] 배포 스크립트 경로 확인: reins 보드 agent 가 `deploy.sh` 를 어디서 호출하는지 → `backend-legacy/deploy.sh`, `frontend/deploy.sh` 로 경로 변경 필요
- [ ] `scoophub-ui` 레포 archive (README 에 이전 안내)

> ⚠️ 결정 필요: 배포 agent 가 레포 루트의 `deploy.sh` 를 고정 호출한다면 루트에 서브 디렉터리로 위임하는 `deploy.sh` 를 둬야 함.

---

## 3. 백엔드 공통 기반 (P1)

Python `app/core`, `app/config.py`, `app/main.py` 대응.

### 3.1 설정
- [ ] `application.yml` — 기존 env 이름 그대로 사용 (`DB_HOST`, `JWT_SECRET`, `ENABLE_SCHEDULER` …)
- [ ] 로컬 `.env` 재사용: `spring.config.import: optional:file:.env[.properties]`
- [ ] `@ConfigurationProperties` 로 앱 설정 클래스 (`ScoophubProperties`)
- [ ] Jackson 전역 `SNAKE_CASE` (Python 응답 필드명 유지), 날짜 ISO-8601
- [ ] JPA: `ddl-auto: none`, `open-in-view: false`
- [ ] Flyway: `baseline-on-migrate: true`, `baseline-version: 0` (기존 flyway 컨테이너 설정과 동일)
- [ ] springdoc: `/docs`, `/openapi.json` 경로 유지 (frontend rewrite + deploy.sh 헬스체크가 `/docs` 사용)
- [ ] CORS: `CORS_ORIGINS`

### 3.2 공통 응답/에러
- [ ] `ApiResponse<T>(success, data, error, meta)` / `ErrorDetail` / `ResponseMeta(requested_at, total, returned, months)`
- [ ] HTTP 에러 바디 `{"detail": "..."}` (FastAPI `HTTPException` 호환)

### 3.3 인증 (`app/core/auth.py`, `app/auth`)
- [ ] `GET /api/auth/login` — Google 동의화면 redirect + `oauth_state` HttpOnly 쿠키
- [ ] `GET /api/auth/callback` — state 검증 → code 교환(RestClient) → `ALLOWED_EMAILS` 체크 → users upsert → JWT 발급 → `AUTH_REDIRECT_URL?token=` redirect
- [ ] `GET /api/auth/me` — 401(비로그인) / 200
- [ ] JWT HS256 (`sub`, `is_super`, `iat`, `exp`) — Nimbus 사용
- [ ] Spring Security: 커스텀 Bearer 필터. **토큰이 잘못돼도 공개 GET 은 통과**해야 함 (기본 resource-server 는 만료 토큰에 전부 401 → UI 가 만료 쿠키로 전체 깨짐)
- [ ] super 전용: `@PreAuthorize` — 비로그인 401, 비-super 403
- [ ] `AUTH_BYPASS` (로컬 전용, 기동 시 경고 로그)
- [ ] `users` 엔티티 + native upsert

> ⚠️ 주의: Nimbus 는 HS256 시크릿 **32바이트 이상** 강제. 운영 `JWT_SECRET` 이 짧으면 교체 필요(교체 시 기존 로그인 1회 만료될 뿐 영향 없음).

### 3.4 크롤 공통 (`base_crawler.py`, `crawl_data/repo.py`)
- [ ] `CrawlResult(itemsFetched, itemsNew, errors, newArticleIds)`
- [ ] `BaseCrawler.run()` — fetch → `crawl_logs` 기록(success / partial / error) → notify hook (news 제외)
- [ ] `CrawlLog` 엔티티
- [ ] `CrawlData` 엔티티 + `CrawlDataRepository` (native upsert `ON CONFLICT (category, purpose, key)`, latest, get, query_path)
- [ ] JSONB 매핑 방식 결정: Hibernate 7.4 가 Jackson 2 를, Spring Boot 4 가 Jackson 3 을 씀 → `@JdbcTypeCode(SqlTypes.JSON)` 동작 검증, 안 되면 `String` 컬럼 + 직접 파싱
- [ ] 수동 크롤 트리거 응답 공통 헬퍼 (`POST /api/crawling/{domain}` → crawler, crawler_detail, items_fetched, items_new, errors)

### 3.5 스케줄러 (`base_scheduler.py`)
- [ ] `ScheduledJob` 인터페이스 (crawler, jobId, run) — 크롤러 외 stock 분석 잡도 수용
- [ ] 기동 시 `crawl_schedule` 조회 → Spring `TaskScheduler` 등록
  - cron: 5필드 crontab → Spring 6필드(`"0 " + expr`), `Asia/Seoul`, 다중 expr 은 OR 트리거
  - interval: `schedule_minutes`, 첫 실행은 interval 후 (APScheduler 동일)
  - `enabled=false` → 미등록
- [ ] 동시 실행 방지 (APScheduler `max_instances=1`, `coalesce`)
- [ ] `crawl_config.params` 로딩
- [ ] `ENABLE_SCHEDULER=false` 지원
- [ ] 런타임 재스케줄 (system 스케줄 PATCH 에서 사용) — ScheduledFuture 보관

### 3.6 알림 (`core/notify`, 약 1,100줄)
- [ ] Telegram 발신 클라이언트
- [ ] NotifyRouter (라우트 테이블, payload_key dedup, 발신 로그)
- [ ] AutoTopicProvisioner
- [ ] card 포맷 + 카테고리별 enrich (news importance≥4, weather 하루 1회 KST 7시+, kal 조건, 그 외 top5)
- [ ] 비동기 발신 (크롤 블록 X)

### 3.7 LLM 클라이언트 (`core/llm`)
- [ ] OpenRouter 호환 chat 호출 (RestClient)

### 3.8 테스트 기반
- [ ] Testcontainers PostgreSQL + `@ServiceConnection` — 전체 마이그레이션 적용 검증
- [ ] 외부 HTTP 는 `MockRestServiceServer`

---

## 4. 도메인 이관 (P2) — 쉬운 것부터, 템플릿 확정 후 반복

Python 줄 수 기준. 각 도메인 공통 체크리스트:
**크롤러 → 조회 API → 수동 트리거 → 스케줄 등록 → 테스트(Python 테스트 케이스 포팅) → 응답 JSON 을 legacy 와 diff 비교**

| 순서 | 도메인 | Python 줄 | 비고 |
|---|---|---|---|
| - [ ] 1 | weather | 396 | **템플릿 도메인**. wttr.in + Open-Meteo, crawl_data snapshot |
| - [ ] 2 | hacker_news | 300 | Firebase API |
| - [ ] 3 | github_trending | 249 | `gtrending` 대체 → HTML 스크래핑(Jsoup) |
| - [ ] 4 | arxiv | 269 | `arxiv` 대체 → Atom API 직접 호출 |
| - [ ] 5 | devto_hashnode | 266 | REST/GraphQL |
| - [ ] 6 | tech_newsletter | 272 | `feedparser` 대체 → Rome |
| - [ ] 7 | product_hunt | 311 | GraphQL |
| - [ ] 8 | youtube_trending | 295 | YouTube Data API (REST 직접) |
| - [ ] 9 | system | 745 | health, crawl logs, 스케줄/설정 관리, notify 라우트 관리 API |
| - [ ] 10 | news | 1,117 | RSS + dedup + LLM summarizer + 필터 룰 + sources 관리 |
| - [ ] 11 | kal_bonus | 556 | **Playwright(Java)** + Xvfb headful (Akamai 우회) |
| - [ ] 12 | stock | 4,162 | 가장 큼, 아래 별도 |

### stock 세부 (위험도 높음)
- [ ] `yfinance` 대체: Yahoo chart/options HTTP API 직접 호출 (crumb/cookie 처리 필요할 수 있음)
- [ ] `technical.py`(535줄) 지표 계산 포팅 — Python 결과와 수치 비교 테스트 필수
- [ ] resample, sigma, signal, report, analysis_service
- [ ] repository 6종 → JPA
- [ ] 스케줄 4종 (stock_sync, stock_daily_sigma, stock-sigma-scan, stock_analyze)
- [ ] router 875줄 — 엔드포인트 다수

---

## 5. 배포/전환 (P3)

- [ ] `backend/Dockerfile` (멀티스테이지: Gradle 빌드 → JRE 25). kal_bonus 이관 시 Playwright chromium + Xvfb 추가
- [ ] `backend/docker-compose.yml` — 포트 20010 유지, flyway 컨테이너 제거(앱이 migrate)
- [ ] `backend/deploy.sh`
- [ ] 전환 방식 결정:
  - A. 전 도메인 이관 완료 후 한 번에 교체 (단순, 권장)
  - B. 도메인 단위 점진 교체 (legacy 와 동시 기동 → **스케줄 중복 실행** 위험 → 한쪽 `ENABLE_SCHEDULER=false` / 잡 단위 비활성 필요, 라우팅 분기 필요)
- [ ] 운영 DB 로 staging 기동 → Flyway validate 통과 확인 (체크섬 동일해야 함)
- [ ] 교체 후 `backend-legacy/` 삭제

---

## 6. 결정 필요 항목 (작업 전 확인)

1. 배포 agent 의 deploy.sh 호출 경로 (2장)
2. 전환 방식 A / B (5장)
3. JSONB 매핑 방식 — 3.4 검증 결과 보고 결정
4. 운영 `JWT_SECRET` 길이 32바이트 이상인지
5. ~~frontend 에 커밋된 `.idea/`, `.claude/skills/**/__pycache__` 정리 여부~~ → 정리함
