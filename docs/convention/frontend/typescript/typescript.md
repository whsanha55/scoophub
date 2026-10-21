# TypeScript Convention

`tsconfig`의 `strict`와 `noUncheckedIndexedAccess`를 켠다. 포맷은 `tooling/`의 Prettier 설정을 따른다.

## 1. 이름과 파일

| 대상 | 규칙 | 예시 |
|---|---|---|
| 컴포넌트, 타입 | `PascalCase` | `OrderList`, `OrderStatus` |
| 함수, 변수, 훅 | `camelCase` | `formatPrice`, `useOrders` |
| 상수 | `UPPER_SNAKE_CASE` | `MAX_PAGE_SIZE` |
| 파일 | 컴포넌트는 `PascalCase.tsx`, 그 밖에는 `kebab-case.ts` | `OrderList.tsx`, `format-price.ts` |

## 2. 타입

- 공개 함수의 입력·반환 타입과 컴포넌트 props 타입을 명시한다. 컴포넌트 반환값과 지역 변수는 타입 추론을 쓴다.
- `any`와 타입 단언(`as`)으로 오류를 숨기지 않는다. 외부 데이터는 `unknown`으로 받고 검증 후 사용한다.
- 상태가 서로 배타적이면 문자열 나열보다 판별 가능한 유니온 타입으로 표현한다.
- `null`과 `undefined`의 의미를 혼용하지 않는다. 값이 없을 수 있는 이유를 타입에 반영한다.
- 배열·객체 인덱스 접근 결과는 `undefined`일 수 있다고 보고 확인 후 사용한다.

## 3. 코드

- `const`를 기본으로 쓰고 재할당이 필요할 때만 `let`을 쓴다.
- import는 깊은 상대 경로(`../../../`) 대신 `@/` 경로 별칭을 쓴다.
- 도메인 의미가 있는 값은 이름을 붙인다. 단순한 코드에 추상화를 추가하지 않는다.
- 주석은 동작보다 이유를 설명한다. 주석 처리한 코드는 남기지 않는다.
- 도구·스킬·에이전트 이름(예: `ponytail:`)이나 리뷰 번호 같은 작업 표시를 주석에 쓰지 않는다.

참고: [TypeScript strict](https://www.typescriptlang.org/tsconfig/strict), [noUncheckedIndexedAccess](https://www.typescriptlang.org/tsconfig/noUncheckedIndexedAccess).
