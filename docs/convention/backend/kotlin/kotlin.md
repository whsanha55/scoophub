# Kotlin Convention

언어 스타일 규칙이다. 포맷은 `tooling/`의 ktlint 설정을 따른다.

## 1. 이름

| 대상 | 규칙 | 예시 |
|---|---|---|
| 클래스, 인터페이스 | `PascalCase` | `OrderService` |
| 함수, 변수 | `camelCase` | `findActiveOrders` |
| 상수 | `UPPER_SNAKE_CASE` | `MAX_OPEN_ORDERS` |
| 패키지 | 소문자, 단수형 | `order`, `settlement` |
| Entity | `*Entity` | `OrderEntity` |
| Enum | `*Enum` | `OrderStatusEnum` |
| Boolean | `is`, `has`, `can` | `isActive`, `hasPermission` |
| 컬렉션 | 복수형 | `orders` |

함수 접두사는 다음을 따른다.

| 목적 | 접두사 |
|---|---|
| 단건 조회 (없으면 예외) | `get` |
| 단건 조회 (없으면 null) | `find` |
| 목록 조회 | `findAll`, `search` |
| 생성 / 변경 / 삭제 | `create` / `update`, `change` / `delete` |
| 존재 여부 | `exists` |
| 검증 | `validate` |

## 2. 불변성과 타입

- `val`을 기본으로 쓴다.
- 공개 함수의 반환 타입은 명시한다. 지역 변수는 타입 추론을 쓴다.

## 3. Null

- 의미 없는 nullable 타입을 만들지 않는다.
- 운영 코드에서 `!!`를 쓰지 않는다. 테스트 코드는 허용한다.
- null은 `?.`, `?:`, `requireNotNull`, `checkNotNull`로 명시적으로 처리한다.

```kotlin
val order = orderRepository.findByIdOrNull(orderId)
    ?: throw OrderException(ErrorCodeEnum.ORDER_NOT_FOUND)

val price = requireNotNull(command.price) { "LIMIT 주문은 가격이 필요하다. orderId=$orderId" }
```

## 4. Scope Function

- 중첩해서 쓰지 않는다.
- 용도: 초기화는 `apply`, 부가 작업은 `also`, nullable 변환은 `let`.
- 단순한 코드를 scope function으로 감싸지 않는다.

## 5. 컬렉션

긴 체이닝은 연산 단위로 줄바꿈한다.

```kotlin
val activeSymbols = orders
    .filter { it.isActive }
    .map { it.symbol }
    .distinct()
```

## 6. 금액, 수량

- `BigDecimal`을 쓴다. `Double`, `Float`는 금지한다.
- 문자열로 생성한다. `BigDecimal("0.1")`은 허용, `BigDecimal(0.1)`은 금지.
- 값 비교는 `compareTo`로 한다. `equals`는 scale이 다르면 `false`다 (`1.0 != 1.00`).
- 나눗셈과 반올림에는 scale과 `RoundingMode`를 반드시 지정한다.
- scale과 `RoundingMode`는 도메인별 상수로 정의한다. 매직 넘버를 쓰지 않는다.

```kotlin
object FeePolicy {
    const val SCALE = 8
    val ROUNDING: RoundingMode = RoundingMode.HALF_UP
}

val fee = amount.multiply(rate).setScale(FeePolicy.SCALE, FeePolicy.ROUNDING)
```

## 7. 시간

- 시점은 `Instant`로 다룬다. `LocalDateTime`으로 시점을 표현하지 않는다.
- 현재 시각은 주입받은 `Clock`으로 구한다. 인자 없는 `now()`는 금지한다.
- JVM 기본 타임존에 의존하지 않는다. `ZoneId.systemDefault()`, `TimeZone.setDefault()`는 금지한다.
- 날짜 경계나 "매일 N시" 같은 달력 계산이 필요할 때만 `ZoneId`를 명시해 변환한다. `ZoneId`는 설정이나 도메인 값에서 받는다.
- 날짜 자체가 의미인 값(영업일 등)은 `LocalDate`를 쓴다.
- `ZonedDateTime`은 저장하거나 필드로 두지 않는다. 계산할 때만 쓴다.

```kotlin
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

val now = Instant.now(clock)
val businessDate = now.atZone(marketZone).toLocalDate()
```

## 8. 로깅

`KotlinLogging`을 쓴다. 로거는 파일 최상단에 선언한다.

```kotlin
private val log = KotlinLogging.logger {}

log.info { "주문 접수. orderId=$orderId, symbol=$symbol" }
log.error(e) { "정산 실패. outboxId=$outboxId" }
```

- 메시지는 람다로 넘긴다. 로그 레벨이 꺼져 있으면 문자열을 만들지 않는다.
- 추적에 필요한 식별자를 포함한다.
- 비밀번호, 토큰, 개인정보를 기록하지 않는다.
- 같은 예외를 여러 계층에서 중복 기록하지 않는다.
- `println`을 쓰지 않는다.

| 레벨 | 용도 |
|---|---|
| `DEBUG` | 개발, 장애 분석 정보 |
| `INFO` | 주요 비즈니스 이벤트 |
| `WARN` | 처리는 됐지만 확인이 필요한 상황 |
| `ERROR` | 요청 실패, 시스템 장애 |

## 9. 주석

- 무엇을 하는지가 아니라 왜 그렇게 했는지를 쓴다.
- 임시 해결책에는 이유와 제거 조건을 쓴다.
- 주석 처리한 코드는 남기지 않는다.
