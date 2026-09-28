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

- 하나의 커밋에는 하나의 논리적 변경만 담는다.
- 빌드되지 않는 상태로 커밋하지 않는다.
- 포맷 변경과 기능 변경은 커밋을 분리한다.

## 3. 머지 전 체크리스트

- [ ] 빌드, 포맷 검사, 정적 분석, 테스트를 통과한다.
- [ ] 관련 테스트를 추가하거나 수정했다.
- [ ] Entity를 API에 직접 노출하지 않았다.
- [ ] 트랜잭션 범위와 N+1 여부를 확인했다.
- [ ] 민감 정보가 로그와 응답에 포함되지 않는다.
- [ ] API 변경을 Swagger에 반영했다.
- [ ] DB 변경이 있으면 마이그레이션을 추가했다.
