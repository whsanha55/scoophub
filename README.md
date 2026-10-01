# ScoopHub

뉴스·주식·날씨·개발 트렌드를 수집하고, 대시보드와 Telegram 알림으로 제공하는 개인용 정보 허브입니다.

## 주요 기능

- **뉴스**: Alpaca 실시간 증시 뉴스 수집, 한국어 요약, 중요도 분류와 알림
- **주식**: 관심 종목, 기간별 리포트, 분석과 시그널
- **날씨**: 현재 날씨, 예보와 대기질
- **기술 트렌드**: GitHub, arXiv, Dev.to/Hashnode, Hacker News, YouTube, 뉴스레터
- **시스템 관리**: 수집 로그, 스케줄, 설정, 알림 라우팅과 LLM 테스트

조회 화면은 로그인 없이 사용할 수 있습니다. Google OAuth로 로그인하며, 수집 실행과 관리 작업은 관리자(`SUPER_EMAILS`) 권한을 사용합니다.

## 구성

| 구성 | 기술 | 포트 |
|---|---|---|
| 백엔드 | Kotlin 2.4, Spring Boot 4.1, Java 25 | 20010 |
| 프론트엔드 | Next.js 16, React 19, TypeScript, Tailwind CSS 4 | 20020 |
| 데이터베이스 | PostgreSQL, Flyway | 기본 5432 |

```text
scoophub/
├── backend/             # API, 수집기, 스케줄러, 알림
│   ├── NEWS.md          # 실시간 뉴스 운영 안내
│   └── src/main/resources/db/migration/
├── frontend/            # 웹 대시보드
├── docs/convention/     # 개발 규칙
└── deploy.sh            # 백엔드 → 프론트엔드 배포
```

## 로컬 실행

Java 25, Node.js 22 이상, npm과 PostgreSQL이 필요합니다. 백엔드 테스트는 Docker에서 Testcontainers로 PostgreSQL을 실행합니다.

1. 백엔드 환경 파일을 만들고 DB 접속 정보와 `JWT_SECRET`(32바이트 이상)을 채웁니다. 로컬에서 자동 수집을 끄려면 `ENABLE_SCHEDULER=false`로 설정합니다.

   ```bash
   cp backend/.env.example backend/.env
   ```

2. PostgreSQL에 `.env`에 지정한 DB와 사용자를 준비한 뒤 백엔드를 실행합니다. Flyway가 기동 시 스키마를 갱신합니다.

   ```bash
   cd backend
   ./gradlew bootRun
   ```

3. 별도 터미널에서 프론트엔드를 실행합니다.

   ```bash
   cd frontend
   cp .env.example .env
   npm ci
   npm run dev
   ```

대시보드는 `http://localhost:20020`, API 문서는 `http://localhost:20010/docs`에서 확인합니다.
프론트엔드 `/api/*` 요청은 `src/proxy.ts`가 `API_URL`로 전달하며, 인증 쿠키를 Bearer 헤더로 변환합니다.

Google 로그인을 사용하려면 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `ALLOWED_EMAILS`를 설정하고 Google OAuth에 `OAUTH_REDIRECT_URI`를 등록합니다. 외부 서비스 키와 환경 변수는 [백엔드 환경 예제](backend/.env.example), 프론트엔드 구성은 [UI README](frontend/README.md)를 참고하세요.

## 검증

```bash
# 백엔드: 포맷 검사와 전체 테스트 (Java 25, Docker 필요)
cd backend
./gradlew check
```

```bash
# 프론트엔드: 린트, 타입 검사, 단위 테스트, 프로덕션 빌드
cd frontend
npm run lint
npm run typecheck
npm test
npm run build
```

## 실시간 뉴스 운영

Alpaca WebSocket → `news_article` → 5초 DB 워커 → 한국어 요약 → Telegram 순으로 처리합니다.
로컬 연결은 기본 비활성입니다. 운영 환경 변수와 지표 쿼리는 [뉴스 운영 안내](backend/NEWS.md)를 참고하세요.

## 배포

서버에서 루트 `./deploy.sh`를 실행하면 백엔드와 프론트엔드를 순서대로 빌드·기동하고 헬스체크합니다.
Docker Compose와 외부 `infra` 네트워크, 해당 네트워크의 `postgres` DB 컨테이너가 필요합니다. 백엔드 환경 파일은 `backend/.env`에 둡니다.

| 구성 | Compose 프로젝트 | 헬스체크 |
|---|---|---|
| 백엔드 | `scoophub` | `/docs` |
| 프론트엔드 | `scoophub-ui` | `:20020` |

프론트엔드는 `scoophub-app-1:20010`으로 API에 접근하므로 Compose 프로젝트 이름을 유지해야 합니다.

<!-- convention:start -->
## Convention

이 프로젝트는 [whsanha55/conventions](https://github.com/whsanha55/conventions) (`04a627c`)를 따릅니다. 문서는 `docs/convention/`, 프로젝트 예외는 [LOCAL.md](docs/convention/LOCAL.md)에 있습니다.
<!-- convention:end -->
