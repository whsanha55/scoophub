# ScoopHub UI

뉴스, 날씨, 주식 등 다양한 정보를 한 곳에서 제공하는 개인 대시보드입니다.

## 기술 스택

| 영역 | 기술 |
|------|------|
| 프레임워크 | Next.js 16 (App Router) |
| 언어 | TypeScript |
| 스타일 | Tailwind CSS v4 |
| UI 컴포넌트 | shadcn/ui, Base UI |
| 아이콘 | Lucide React |
| 테마 | next-themes (다크/라이트) |

## 프로젝트 구조

```
src/
├── app/
│   ├── (app)/          # 공개 조회 페이지 (news, stock, weather, ... 도메인별)
│   ├── login/          # 로그인
│   ├── auth/callback/  # OAuth 토큰을 HttpOnly 쿠키로 저장
│   └── api/auth/       # 로그아웃 route handler
├── domains/            # 도메인별 컴포넌트/타입/API
├── shared/             # 공통 컴포넌트/라이브러리/타입
├── components/ui/      # shadcn UI 컴포넌트
├── hooks/              # 공통 훅
├── lib/                # 유틸리티
└── proxy.ts            # API 프록시와 인증 헤더 주입
```

## 환경 변수

`.env` 파일을 생성하고 ScoopHub API 서버 주소를 설정합니다.

```bash
cp .env.example .env
```

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `API_URL` | ScoopHub API 서버 주소 | `http://localhost:20010` |
| `AUTH_COOKIE_NAME` | JWT를 저장하는 HttpOnly 쿠키명 | `access_token` |

`src/proxy.ts`가 `/api/*` 요청을 `API_URL`로 전달하고 인증 쿠키를 Bearer 헤더로 변환합니다. 로그아웃은 프론트엔드 route handler가 처리합니다. `next.config.ts`는 `/docs`와 `/openapi.json`을 백엔드로 전달합니다.

조회는 비로그인 상태에서도 가능합니다. 수집 버튼은 관리자에게만 표시하며, API 권한은 백엔드에서 검증합니다.
루트(`/`)와 제거된 대한항공 보너스 좌석 URL(`/kal-bonus`)은 `/news`로 이동합니다.

## 실행

```bash
# 개발
npm ci
npm run dev

# 프로덕션
npm run build
npm start
```

## 검증

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

단위 테스트는 Node.js 내장 테스트 러너를 사용합니다(Node.js 22 이상).

## 배포

`deploy.sh` 가 `docker compose up -d --build` 후 `:20020` 헬스체크를 한다. 전체 배포 흐름은 [루트 README](../README.md#배포)를 참고한다.

```bash
docker compose logs -f ui   # 로그
```
