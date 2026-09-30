# Local Convention

이 프로젝트에만 적용하는 규칙과 예외. `docs/convention/`의 다른 문서보다 우선한다.

## 백엔드 — Python(FastAPI) 이관 특례

이관 원칙: **API 경로·응답 JSON·DB 스키마를 legacy와 1:1 유지** (UI 무수정, 기존 DB 그대로).
다음 항목은 이 원칙이 `backend/common/api.md`, `backend/kotlin/spring.md`보다 우선한다.

- 성공 응답은 legacy 래핑 `ApiResponse(success, data, error, meta)` 를 유지한다. → api.md §3(래핑 금지, 목록 페이지 객체) 미적용.
- 에러 응답은 FastAPI 호환 `{"detail": "..."}` 바디를 유지한다. → api.md §4(RFC 9457 ProblemDetail, `code`, `requestId`, 에러 코드 enum) 및 spring.md §8(`ErrorCodeEnum`/`BaseException`) 미적용. 도메인은 `ResponseStatusException(status, detail)` 으로 던지고 `ApiExceptionHandler` 가 변환한다.
- URL 은 legacy `/api/...` 경로를 그대로 쓰고, 클래스 레벨 `@RequestMapping` prefix 를 허용한다. → api.md §1(서비스 prefix, 전체 경로 선언) 미적용.
- `X-Request-Id` 를 응답 본문에 넣지 않는다. 헤더/MDC 적용 여부는 별도 결정.
- `created_at`/`updated_at` 감사 필드는 DB 기본값(`NOW()`)을 쓴다. → spring.md §4(Clock 으로 채움) 중 감사 필드 부분만 미적용. 비즈니스 시각(`started_at`, `requested_at`, 만료 시각 등)은 Clock 을 주입받아 구한다.

## 백엔드 — 데이터 접근 레이어

- Controller는 Repository나 `JdbcClient`에 직접 의존하지 않는다. 항상 `{domain}/service/`의 Service를 거친다.
- SQL은 `{domain}/repository/`에만 둔다. 방식은 다음과 같다.
  - 단순 조회: Spring Data JPA 메서드 이름 쿼리나 `@Query`.
  - 동적 조건, JSONB, 집계, 부분 수정: `XxxQueryRepository`(`@Repository`)에서 `JdbcClient`를 쓴다. 값은 반드시 파라미터로 바인딩하고, 컬럼명·필드명은 코드 상수만 문자열에 넣는다.
- `JdbcClient` 조회 결과는 `{domain}/vo/`의 `XxxRow` 프로젝션으로 받는다. 응답 DTO는 `from(row)`로 변환하고, `ResultSet`을 DTO에 넘기지 않는다.
- 기존 예외: 크롤러, 배치 컴포넌트 내부 쿼리(`KalRoutesLoader`)는 아직 `JdbcClient`를 직접 쓴다. 수정할 때 `repository/`로 옮긴다.

## 백엔드 — Tooling 예외

- **detekt 미적용.** 최신(1.23.8)이 JDK 25 런타임을 지원하지 않는다 (`--jvm-target` 상한 22, 내장 Kotlin 2.0.21 컴파일러가 JDK 버전 파싱 실패). JDK 25 지원 버전 출시 시 재도입. `!!`/`now()`/`println` 금지 규칙은 코드 리뷰로 대신 지킨다. `config/detekt/detekt.yml` 은 재도입 대비 유지.
- ktlint 는 적용 중 (`./gradlew ktlintCheck` / `ktlintFormat`).
