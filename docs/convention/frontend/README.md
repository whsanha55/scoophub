# Frontend

개인 웹 프로젝트의 기본 스택은 TypeScript + React + Vite, Tailwind CSS + shadcn/ui다. 서버 렌더링이 필요한 프로젝트는 `LOCAL.md`에 이유를 적고 Next.js 같은 프레임워크를 선택한다.

| 경로 | 내용 |
|---|---|
| `common/design-system.md` | 토큰, 컴포넌트, 접근성 |
| `common/api.md` | 백엔드 API 연동과 오류 처리 |
| `typescript/typescript.md` | 언어와 타입 규칙 |
| `typescript/react.md` | 컴포넌트와 상태 관리 |
| `typescript/test.md` | 테스트 범위와 도구 |
| `typescript/tooling/` | ESLint, Prettier, 실행 명령 |

TanStack Query는 서버 데이터의 캐시·동기화가 필요할 때, React Hook Form + Zod는 복잡한 입력·검증이 필요할 때 사용한다. 사용하지 않는 도구를 프로젝트에 미리 설치하지 않는다.
