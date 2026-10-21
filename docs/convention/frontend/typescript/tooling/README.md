# Tooling (TypeScript + React)

| 파일 | 프로젝트 설치 위치 | 용도 |
|---|---|---|
| `eslint.config.mjs` | 프로젝트 루트 | ESLint, TypeScript, React Hooks 규칙 |
| `.prettierrc.json` | 프로젝트 루트 | 포맷 규칙 |

아래 패키지를 개발 의존성으로 설치한다. 버전은 적용 시점의 최신 안정 버전을 확인한다.

```text
typescript eslint @eslint/js typescript-eslint eslint-plugin-react-hooks eslint-config-prettier prettier
```

`package.json` 스크립트 예시:

```json
{
  "scripts": {
    "typecheck": "tsc --noEmit",
    "lint": "eslint .",
    "format:check": "prettier --check .",
    "format": "prettier --write .",
    "test": "vitest run",
    "test:e2e": "playwright test"
  }
}
```

Vite 템플릿처럼 여러 `tsconfig`을 참조하는 프로젝트는 `typecheck`를 `tsc --build --noEmit` 등 해당 구조에 맞게 조정한다. Next.js를 쓰는 프로젝트는 제공되는 설정과 충돌하지 않게 ESLint 구성을 조정한다.

CI에서는 포맷 검사, 린트, 타입 검사, 테스트, 빌드를 실행한다. 사용하지 않는 테스트 도구의 스크립트는 추가하지 않는다.
