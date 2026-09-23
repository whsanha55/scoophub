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
├── app/                # Next.js App Router 페이지
│   ├── page.tsx        # 대시보드
│   ├── news/           # 뉴스 페이지
│   ├── weather/        # 날씨 페이지
│   ├── stock/          # 주식 페이지
│   └── ...             # 기타 도메인 페이지
├── domains/            # 도메인별 컴포넌트/타입/훅
│   ├── news/
│   ├── weather/
│   ├── stock/
│   └── ...             # 기타 도메인
├── shared/             # 공통 컴포넌트/라이브러리/타입
├── components/ui/      # shadcn UI 컴포넌트
└── lib/                # 유틸리티
```

## 환경 변수

`.env` 파일을 생성하고 ScoopHub API 서버 주소를 설정합니다.

```bash
cp .env.example .env
```

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `API_URL` | ScoopHub API 서버 주소 | `http://localhost:20010` |

프론트엔드의 `/api/*` 요청은 `next.config.ts`의 `rewrites` 설정을 통해 `API_URL`로 프록시됩니다.

## 실행

```bash
# 개발
npm install
npm run dev

# 프로덕션
npm run build
npm start
```

## 배포 (Docker Compose)

```bash
# 빌드 및 실행
docker compose up -d --build

# 업데이트
docker compose up -d --build

# 로그
docker compose logs -f ui

# 중지
docker compose down
```
