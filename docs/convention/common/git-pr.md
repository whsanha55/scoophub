# Git / PR Convention

## 0. 티켓 번호

```text
{prefix}-{번호}
```

- `prefix`는 프로젝트마다 정하고, 영문 대문자 최대 4글자로 쓴다.

| 프로젝트 | prefix | 티켓 예시 |
|---|---|---|
| 주문호가 | `ORDB` | `ORDB-123` |
| 결제 | `PAY` | `PAY-45` |
| 지갑 | `WLT` | `WLT-7` |

## 1. 브랜치

```text
{type}/{ticket}-{설명}
```

```text
feature/ORDB-123-order-cancel
fix/PAY-45-fee-rounding
refactor/ORDB-124-order-service
hotfix/WLT-7-settlement-timeout
```

- 설명은 소문자와 하이픈으로 쓴다.

## 2. 커밋 메시지

```text
[{ticket}] {type}: {한글 제목}
```

```text
[ORDB-123] feat: 주문 취소 API 추가
[PAY-45] fix: 수수료 반올림 오류 수정
```

| type | 용도 |
|---|---|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `test` | 테스트 추가, 수정 |
| `docs` | 문서 |
| `chore` | 빌드, 설정, 의존성 |

- 제목은 50자 이내로 쓰고 마침표를 찍지 않는다. "추가", "수정", "제거"처럼 명사형으로 끝낸다.
- 제목에는 무엇을 바꿨는지 쓴다. "버그 수정", "리팩토링"처럼 뭉뚱그리지 않는다.
- 본문은 선택이다. 제목과 한 줄 띄우고, 방법보다 이유를 적는다. 한 줄은 72자 안팎으로 끊는다.
- 티켓이 없는 저장소는 `{type}: {한글 제목}`으로 쓴다.
- 하나의 커밋에는 하나의 논리적 변경만 담는다.
- 빌드되지 않는 상태로 커밋하지 않는다.
- 포맷 변경과 기능 변경은 커밋을 분리한다.

```text
[PAY-45] fix: 수수료 반올림 오류 수정

소수점 셋째 자리에서 HALF_UP으로 반올림하던 것을
정책에 맞게 HALF_EVEN으로 바꾼다.
```

금지:

- `Co-Authored-By` 등 AI나 도구의 서명 트레일러
- `wip`, `수정`, `.` 같은 의미 없는 메시지

## 3. Push

- `main`에 직접 push하지 않는다. 브랜치 보호 규칙으로 막는다.
- push 전에 `git pull --rebase origin main`으로 최신 `main` 위에 올린다.
- 강제 push는 내 작업 브랜치에서만 `--force-with-lease`로 한다. `main`에는 절대 하지 않는다.

## 4. PR

- 제목은 커밋 메시지 형식과 같다. squash 병합 시 PR 제목이 `main`의 커밋 제목이 된다.
- 한 PR에는 한 주제만 담는다. 변경이 400줄을 넘으면 나누는 것을 검토한다.
- 작업 중이면 Draft로 올린다.
- 병합 전에 diff를 직접 훑고 머지 전 체크리스트(6절)를 확인한다.
- squash 병합하고, 병합한 브랜치는 삭제한다.

## 5. PR 본문

[`tooling/pull_request_template.md`](tooling/pull_request_template.md)를 프로젝트의 `.github/pull_request_template.md`로 둔다.

```markdown
## 변경 내용

## 이유

## 확인 방법

## 영향
```

- 섹션을 비우지 않는다. 해당 없으면 "없음"이라고 쓴다.
- 이유에 관련 티켓을 적는다.
- 영향에는 API, DB, 설정 변경 여부와 배포 시 주의점을 적는다.
- "Generated with Claude Code" 등 도구 문구를 넣지 않는다.

## 6. 머지 전 체크리스트

- [ ] 빌드, 포맷 검사, 정적 분석, 테스트를 통과한다.
- [ ] 관련 테스트를 추가하거나 수정했다.
- [ ] Entity를 API에 직접 노출하지 않았다.
- [ ] 트랜잭션 범위와 N+1 여부를 확인했다.
- [ ] 민감 정보가 로그와 응답에 포함되지 않는다.
- [ ] API 변경을 Swagger에 반영했다.
- [ ] DB 변경이 있으면 마이그레이션을 추가했다.
