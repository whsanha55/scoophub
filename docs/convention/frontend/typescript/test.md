# Test Convention (Frontend)

## 1. 도구

| 대상 | 도구 |
|---|---|
| 순수 로직, 컴포넌트 | Vitest + Testing Library |
| 핵심 사용자 흐름 | Playwright |

## 2. 원칙

- 구현 세부보다 사용자가 관찰하는 결과를 검증한다. 컴포넌트 테스트는 역할·레이블로 요소를 찾는다.
- 계산·변환 로직은 단위 테스트로 검증한다. 로그인, 주요 제출·변경 흐름은 브라우저에서 검증한다.
- 로딩, 빈 결과, 오류처럼 사용자 행동이 달라지는 상태를 테스트한다.
- 테스트 간 실행 순서와 실제 외부 서비스에 의존하지 않는다.
- 스냅샷은 UI 동작 검증을 대신하지 않는다.

참고: [Vitest](https://vitest.dev/guide/), [Testing Library](https://testing-library.com/docs/react-testing-library/intro/), [Playwright](https://playwright.dev/docs/intro).
