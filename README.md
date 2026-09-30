# ScoopHub

개인용 정보 수집 허브 — 뉴스·주식·날씨·개발 트렌드 등을 크롤링해 한 화면에서 보여준다.

## 구조

```
scoophub/
├── backend/          # Kotlin 2.4 + Spring Boot 4.1 + Java 25
│   └── src/main/resources/db/migration/   # Flyway SQL (앱 기동 시 migrate)
└── frontend/         # Next.js 16
```

| 구성 | 포트 | compose 프로젝트명 |
|---|---|---|
| backend | 20010 | `scoophub` |
| frontend | 20020 | `scoophub-ui` |

> compose 프로젝트명은 `docker-compose.yml` 의 `name:` 으로 고정한다. frontend 가 백엔드를 `scoophub-app-1` 컨테이너명으로 호출하므로 바꾸지 말 것.

## 로컬 실행

### backend (Kotlin)

```bash
cd backend
./gradlew bootRun      # 기동 시 Flyway migrate (:20010), 접속정보는 backend/.env
```

### frontend

```bash
cd frontend
npm install
npm run dev            # :20020, /api/* 는 API_URL 로 프록시
```

## 증시 속보 뉴스

Alpaca WebSocket → `news_article` → 5초 DB 워커 → GLM 한국어 요약 → 텔레그램으로 처리한다.
로컬 연결은 기본 비활성이다. 운영 환경변수, 지표 쿼리, RSS 병행 운영 종료 절차는
[뉴스 운영 안내](backend/NEWS.md)를 참고한다.

## 배포

서버에서 루트 `deploy.sh` 를 실행하면 backend → frontend 순으로 각 디렉터리의 `deploy.sh` 를 실행한다. 각 스크립트는 `docker compose up -d --build` 후 헬스체크한다.

- `backend/deploy.sh` — `/docs` 응답 확인
- `frontend/deploy.sh` — `:20020` 응답 확인

백엔드 `.env` 는 `backend/.env` 에 둔다.

<!-- convention:start -->
## Convention

이 프로젝트는 [whsanha55/conventions](https://github.com/whsanha55/conventions) (`04a627c`)를 따른다. 문서는 `docs/convention/`에 있고, 프로젝트 예외는 `docs/convention/LOCAL.md`에 적는다.
<!-- convention:end -->
