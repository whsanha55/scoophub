# API Convention

언어와 무관한 REST API 규칙이다.

## 1. URL

```text
/{service}/{resource}            # 사용자 API
/{service}/admin/{resource}      # 관리자 API (외부 노출 차단)
/ws/{service}/{resource}         # WebSocket
```

- 모든 경로는 서비스명 prefix로 시작한다.
- 리소스는 소문자, 하이픈, 명사를 사용한다. URL에 동사를 쓰지 않는다.
- 버전은 기본적으로 붙이지 않는다. 하위 호환이 깨질 때만 `/{service}/v2/...`를 추가한다.
- 컨트롤러 메서드마다 **전체 경로**를 선언한다. 클래스 레벨 prefix 매핑을 쓰지 않는다.

```text
GET    /order/orders
GET    /order/orders/{orderId}
POST   /order/orders
PATCH  /order/orders/{orderId}
DELETE /order/orders/{orderId}
```

## 2. 인증

- 인증 방식(세션, JWT, 게이트웨이 등)은 프로젝트마다 정한다.
- 인증 처리는 필터나 인터셉터에서 하고, Controller는 인증된 사용자 식별자만 받는다.
- 관리자 API(`/{service}/admin/**`)는 외부에서 호출할 수 없게 막는다.
- 인증 관련 헤더 이름은 상수로 관리한다.

## 3. 성공 응답

감싸지 않고 리소스를 그대로 반환한다. 성공 여부는 HTTP 상태로 판단한다.

| 상황 | HTTP 상태 | 본문 |
|---|---:|---|
| 조회, 변경 성공 | 200 | 리소스 |
| 생성 성공 | 201 | 생성된 리소스 |
| 본문 없는 성공 (삭제 등) | 204 | 없음 |

- 목록은 최상위 배열로 반환하지 않는다. 페이지 정보를 함께 담은 객체로 반환한다.
- WebSocket 메시지는 `{ "type": ..., "data": ... }` 구조를 쓴다.

```json
{
  "items": [ { "id": 1 } ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

## 4. 에러 응답

[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) 형식을 쓴다. `Content-Type`은 `application/problem+json`이다.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "취소할 수 없는 주문입니다.",
  "instance": "/order/orders/123",
  "code": "ORDER_NOT_CANCELABLE",
  "requestId": "7f3c9a1e-..."
}
```

| 필드 | 구분 | 내용 |
|---|---|---|
| `type` | 표준 | 에러 문서 URI. 없으면 `about:blank` |
| `title` | 표준 | HTTP 상태 이름 |
| `status` | 표준 | HTTP 상태 코드 |
| `detail` | 표준 | 사용자에게 보여줄 메시지 |
| `instance` | 표준 | 요청 경로 |
| `code` | 확장 | 에러 코드 (`UPPER_SNAKE_CASE` 문자열) |
| `requestId` | 확장 | 요청 식별자 |
| `errors` | 확장 | 검증 실패 시 `[{ "field": ..., "message": ... }]` |

### 에러 코드

- 에러는 코드, HTTP 상태, 메시지를 한 곳(에러 코드 enum)에 정의한다.
- 코드는 `ORDER_NOT_FOUND`처럼 의미가 드러나는 문자열로 쓴다.
- Spring 기본 예외(검증 실패, 404 등)도 같은 형식으로 응답하고 `code`, `requestId`를 붙인다.

| 상황 | HTTP 상태 | 공통 코드 |
|---|---:|---|
| 잘못된 요청, 검증 실패 | 400 | `INVALID_REQUEST` |
| 인증 실패 | 401 | `UNAUTHORIZED` |
| 권한 부족 | 403 | `FORBIDDEN` |
| 리소스 없음 | 404 | `NOT_FOUND` 또는 도메인 코드 |
| 상태 충돌 | 409 | 도메인 코드 |
| 서버 오류 | 500 | `INTERNAL_ERROR` |
| 외부 시스템 장애, 일시적 처리 불가 | 503 | `EXTERNAL_API_ERROR` 등 |

### 원칙

- `detail`에는 사용자 메시지만 넣는다. 내부 원인은 로그에 남긴다.
- 스택 트레이스와 내부 구현을 응답에 노출하지 않는다.
- 예상하지 못한 예외는 전역 핸들러에서 기록하고 `INTERNAL_ERROR`로 응답한다.

## 5. Request ID

- 요청 헤더 `X-Request-Id`가 있으면 그 값을 쓰고, 없으면 새로 만든다 (UUID).
- 필터에서 처리하고, MDC(`requestId`)에 넣어 모든 로그에 남긴다.
- 모든 응답에 `X-Request-Id` 헤더를 넣는다. 에러 응답은 본문 `requestId`에도 넣는다.

## 6. 요청 검증

- 요청 DTO는 Bean Validation으로 검증하고, 실패 시 `400`과 `errors` 필드로 필드별 메시지를 응답한다.
- 도메인 규칙 검증은 Service 또는 Domain에서 한다.

## 7. Swagger (OpenAPI)

필수로 작성한다.

| 대상 | 필수 항목 |
|---|---|
| Controller | `@Tag(name, description)` |
| API 메서드 | `@Operation(summary)` |
| 요청·응답 DTO 필드 | `@Schema(description, example)` |
