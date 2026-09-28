# Spring Convention (Kotlin)

API 응답, 에러 코드 규칙은 [`backend/common/api.md`](../common/api.md)를 따른다.

## 1. 패키지

도메인 기준으로 나눈다. Controller는 도메인 루트에 둔다.

```text
com.whsanha55.{service}
├── global/
│   ├── base/          # BaseException, BaseEntity
│   ├── config/
│   ├── constants/
│   ├── exception/     # ErrorCodeEnum, GlobalExceptionHandler
│   └── filter/        # RequestIdFilter
├── external/
│   └── {target}/      # 외부 시스템별
│       ├── client/
│       ├── config/
│       └── dto/
└── order/
    ├── OrderController.kt
    ├── facade/
    ├── service/
    ├── repository/
    ├── entity/
    ├── enums/
    ├── dto/           # API 요청, 응답, Command
    ├── vo/            # 내부 값 객체, 조회 프로젝션
    └── exception/
```

- `common`, `util` 패키지를 만들지 않는다. 특정 도메인에서만 쓰는 코드는 해당 도메인에 둔다.
- 도메인 간 순환 의존을 만들지 않는다.

## 2. 레이어

```text
Controller → (Facade) → Service → Repository
```

| 레이어 | 책임 |
|---|---|
| Controller | HTTP 입출력, 요청 검증, 사용자 식별, 호출할 Facade나 Service 선택 |
| Facade | 여러 Service 조합, 외부 API 호출, 트랜잭션 경계 밖 작업 |
| Service | 유스케이스 실행, 트랜잭션, Repository 호출 |
| Repository | 데이터 접근만 담당 |

- Facade는 필요할 때만 만든다. 단일 Service 호출로 끝나면 Controller가 Service를 직접 호출한다.
- Controller에 비즈니스 로직을 쓰지 않는다.
- 의존성은 생성자로 주입한다. 필드 주입과 `lateinit` 주입은 금지한다.

## 3. DTO

- 요청 DTO와 응답 DTO를 분리하고, 재사용하지 않는다.
- `data class`로 선언하고, 비즈니스 로직을 넣지 않는다.
- Bean Validation 애너테이션은 `@field:` 대상으로 선언한다.
- 변환 방식:
  - 응답: `companion object`의 `from()`
  - 요청: `toCommand()`, `toEntity()` 등 `toXxx()`

```kotlin
data class OrderRequest(
    @field:Schema(description = "심볼", example = "USD")
    @field:NotBlank
    val symbol: String,

    @field:Schema(description = "수량", example = "10")
    @field:Positive
    val quantity: BigDecimal,
) {
    fun toCommand(userId: String) = OrderCommand(userId = userId, symbol = symbol, quantity = quantity)
}

data class OrderResponse(
    val id: Long,
    val status: OrderStatusEnum,
    val orderedAt: Instant,
) {
    companion object {
        fun from(order: OrderEntity) = OrderResponse(
            id = requireNotNull(order.id),
            status = order.status,
            orderedAt = order.orderedAt,
        )
    }
}
```

## 4. Entity

- `data class`를 쓰지 않는다.
- 생성자는 public이고, 호출할 때 named argument를 쓴다.
- 변경 가능한 필드는 클래스 본문에 `var`로 두고 `protected set`으로 막는다. 상태는 도메인 메서드로만 바꾼다.
- 시각 필드 기본값에 `now()`를 쓰지 않는다. 생성하는 쪽에서 `Clock`으로 구한 값을 넘긴다.
- 연관관계는 지연 로딩을 쓴다. 양방향 연관관계는 필요한 경우에만 쓴다.
- `toString()`에 연관관계를 포함하지 않는다.

```kotlin
@Entity
@Table(name = "orders")
class OrderEntity(
    @Column(nullable = false, length = 16)
    val userId: String,

    @Column(nullable = false, precision = 26, scale = 8)
    val quantity: BigDecimal,

    @Column(nullable = false)
    val orderedAt: Instant,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: OrderStatusEnum = OrderStatusEnum.RECEIVED
        protected set

    fun accept() {
        status = OrderStatusEnum.ACCEPTED
    }
}
```

`createdAt`, `updatedAt` 같은 감사(auditing) 필드도 `Clock` 기준으로 채운다.

```kotlin
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
@Configuration
class JpaConfig {
    @Bean
    fun auditingDateTimeProvider(clock: Clock) = DateTimeProvider { Optional.of(Instant.now(clock)) }
}
```

## 5. 트랜잭션

- 데이터를 쓰는 Service 메서드에만 `@Transactional`을 선언한다.
- 조회 메서드에는 선언하지 않는다. 클래스 레벨 `readOnly`도 쓰지 않는다.
- Controller, Facade, Repository에는 선언하지 않는다.
- 트랜잭션 안에서 외부 API 호출이나 느린 I/O를 하지 않는다. 이런 작업은 Facade에서 한다.
- 같은 클래스 안의 메서드를 호출하면 `@Transactional`이 적용되지 않는다 (프록시 자기 호출).

## 6. 조회와 OSIV

- `spring.jpa.open-in-view: false`로 설정한다.
- 트랜잭션 밖에서 지연 로딩을 하지 않는다. 연관 엔티티가 필요하면 fetch join이나 프로젝션으로 한 번에 조회한다.
- 복잡한 조회는 QueryDSL이나 `@Query`를 쓴다. 메서드 이름 기반 쿼리가 길어지면 명시적 쿼리로 바꾼다.
- 대량 데이터에 조건 없는 `findAll()`을 쓰지 않는다.

## 7. DB 시간 컬럼

`Instant`를 UTC로 저장한다.

| DB | 컬럼 타입 | 필수 설정 |
|---|---|---|
| PostgreSQL | `timestamptz` | 없음 |
| MySQL | `DATETIME(6)` | 아래 설정 |

MySQL:

```yaml
spring:
  datasource:
    url: jdbc:mysql://host:3306/db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
  jpa:
    properties:
      hibernate.jdbc.time_zone: UTC
```

- PostgreSQL에서 타임존 없는 `timestamp`를 쓰지 않는다.
- MySQL에서 `TIMESTAMP` 타입을 쓰지 않는다 (2038년 한계).

## 8. 예외

- 에러는 `ErrorCodeEnum`에 HTTP 상태, 메시지와 함께 정의한다. 응답의 `code`는 enum 이름이다.
- 도메인별 예외 클래스 하나를 두고, 에러는 `ErrorCodeEnum` 항목으로 구분한다.
- `Exception`, `RuntimeException`을 직접 던지지 않는다.
- 일반 제어 흐름에 예외를 쓰지 않는다.

```kotlin
enum class ErrorCodeEnum(val status: HttpStatus, val message: String) {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값을 확인해주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 시도해주세요."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_NOT_CANCELABLE(HttpStatus.CONFLICT, "취소할 수 없는 주문입니다."),
}

open class BaseException(val errorCode: ErrorCodeEnum) : RuntimeException(errorCode.message)

class OrderException(errorCode: ErrorCodeEnum) : BaseException(errorCode)
```

에러 응답은 `ProblemDetail`로 만든다 ([`backend/common/api.md`](../common/api.md) 4절).

- `GlobalExceptionHandler`는 `ResponseEntityExceptionHandler`를 상속한다. Spring 기본 예외도 `ProblemDetail`로 응답된다.
- `handleExceptionInternal`을 오버라이드해 Spring 기본 예외에도 `code`, `requestId`를 붙인다.
- 검증 실패는 `handleMethodArgumentNotValid`를 오버라이드해 `errors`를 붙인다.

```kotlin
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(BaseException::class)
    fun handleBaseException(e: BaseException): ProblemDetail = problemOf(e.errorCode)

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ProblemDetail {
        log.error(e) { "처리되지 않은 예외" }
        return problemOf(ErrorCodeEnum.INTERNAL_ERROR)
    }

    private fun problemOf(errorCode: ErrorCodeEnum) =
        ProblemDetail.forStatusAndDetail(errorCode.status, errorCode.message).apply {
            setProperty("code", errorCode.name)
            setProperty("requestId", MDC.get(REQUEST_ID))
        }
}
```

## 9. 외부 API 연동

- `external/{target}/` 아래에 두고, `RestClient`를 쓰는 구체 클래스(`@Component`)로 만든다.
- 인터페이스는 구현체가 둘 이상일 때만 만든다.
- connect timeout과 read timeout을 반드시 설정한다.
- 외부 DTO는 `external/` 밖으로 내보내지 않는다. 클라이언트에서 내부 타입으로 변환해 반환한다.
- 외부 응답 실패와 HTTP 오류는 도메인 예외로 변환한다.

## 10. 설정

- 환경별 설정은 `application-{profile}.yml`로 나눈다.
- 비밀번호와 API Key를 Git에 커밋하지 않는다. 환경 변수나 Config Server로 주입한다.
- 운영 환경에서 `ddl-auto`는 `none` 또는 `validate`만 쓴다.
- 설정값이 여러 개면 `@ConfigurationProperties` 데이터 클래스로 묶는다.
