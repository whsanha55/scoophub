# ScoopHub

개인용 정보 수집 허브 — 뉴스·주식·날씨·개발 트렌드 등을 크롤링해 한 화면에서 보여준다.

Python(FastAPI) 백엔드를 Kotlin(Spring Boot)으로 이관 중이다. 진행 상황은 [MIGRATION_PLAN.md](MIGRATION_PLAN.md) 참고.

## 구조

```
scoophub/
├── backend/          # Kotlin 2.4 + Spring Boot 4.1 + Java 25 (이관 중, 뼈대만)
│   └── src/main/resources/db/migration/   # Flyway SQL (legacy 와 공용)
├── backend-legacy/   # Python FastAPI — 현재 운영 백엔드 (이관 완료 시 삭제)
└── frontend/         # Next.js 16
```

| 구성 | 포트 | compose 프로젝트명 |
|---|---|---|
| backend-legacy | 20010 | `scoophub` |
| frontend | 20020 | `scoophub-ui` |

> compose 프로젝트명은 `docker-compose.yml` 의 `name:` 으로 고정한다. frontend 가 백엔드를 `scoophub-app-1` 컨테이너명으로 호출하므로 바꾸지 말 것.

## 로컬 실행

### backend-legacy (Python)

```bash
cd backend-legacy
cp .env.example .env   # 없으면 기존 .env 사용
./run_local.sh         # Flyway migrate → uvicorn --reload (:20010)
```

Flyway SQL 은 `backend/src/main/resources/db/migration/` 을 참조한다.

### backend (Kotlin)

```bash
cd backend
./gradlew bootRun
```

### frontend

```bash
cd frontend
npm install
npm run dev            # :20020, /api/* 는 API_URL 로 프록시
```

## 배포

서버에서 각 디렉터리의 `deploy.sh` 가 `docker compose up -d --build` 후 헬스체크한다.

- `backend-legacy/deploy.sh` — `/docs` 응답 확인
- `frontend/deploy.sh` — `:20020` 응답 확인

백엔드 `.env` 는 `backend-legacy/.env` 에 둔다.
