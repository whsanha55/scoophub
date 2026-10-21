# React Convention

별도 백엔드를 사용하는 클라이언트 중심 웹 앱은 Vite를 기본으로 한다. 서버 렌더링이나 서버 기능이 필요한 프로젝트는 프레임워크를 프로젝트별로 정한다.

## 1. 스타일과 컴포넌트

- Tailwind CSS + shadcn/ui를 기본으로 쓴다. 디자인 토큰은 [`common/design-system.md`](../common/design-system.md)를 따른다.
- shadcn/ui 컴포넌트는 프로젝트 코드로 가져와 관리한다. 토큰은 CSS 변수로 정의하고 Tailwind 테마 변수로 노출한다.
- 라이트·다크 모드를 지원하면 같은 의미 토큰의 값만 모드별로 바꾼다.

## 2. React 컴포넌트

- 컴포넌트는 화면의 역할에 맞춰 나눈다. 같은 상태를 공유해야 할 때 가까운 공통 부모로 올린다.
- props를 직접 변경하지 않는다. 파생 가능한 값은 별도 상태로 저장하지 않는다.
- 부수 효과는 외부 시스템과 동기화할 때 사용한다. 이벤트로 처리할 수 있는 동작을 effect로 옮기지 않는다.
- effect에서 구독·타이머·이벤트 리스너를 만들면 클린업 함수에서 해제한다.
- `use()`에는 렌더링 중 새로 만든 Promise를 넘기지 않는다. 상위에서 만든 Promise를 props로 받는다.
- 훅은 최상위에서 호출하고 의존성 경고를 임의로 끄지 않는다.

## 3. 상태

| 상태 | 우선 위치 |
|---|---|
| 입력·열림 여부 등 화면 상태 | 해당 컴포넌트 |
| 공유하는 화면 상태 | 가까운 공통 부모 또는 Context |
| URL로 공유·복원할 상태 | URL |
| 서버에서 가져온 데이터 | 프레임워크 로더 또는 TanStack Query |

전역 상태 라이브러리는 여러 화면이 실제로 공유하는 클라이언트 상태가 생겼을 때 고른다.

단순한 서버 요청은 프레임워크 기본 데이터 로딩 또는 `fetch`로 처리한다. 캐시 무효화·동기화가 필요하면 TanStack Query를 사용한다.

## 4. 폼

- 간단한 폼은 React 기본 기능(`<form action>`, `useActionState`, `useFormStatus`)으로 구현한다. `useFormStatus`는 폼 안의 자식 컴포넌트에서 호출한다.
- 필드·조건부 검증이 복잡한 폼은 React Hook Form + Zod를 사용한다. 클라이언트 검증만 믿지 않고 서버 오류도 필드에 표시한다.
- 제출 중 중복 제출을 막고, 성공·실패 결과를 사용자에게 알린다.

참고: [React의 상태 관리](https://react.dev/learn/managing-state), [Effect 사용](https://react.dev/learn/you-might-not-need-an-effect), [Tailwind 테마 변수](https://tailwindcss.com/docs/theme), [shadcn/ui 테마](https://ui.shadcn.com/docs/theming), [TanStack Query](https://tanstack.com/query/latest/docs/framework/react/overview), [Zod](https://zod.dev/).
