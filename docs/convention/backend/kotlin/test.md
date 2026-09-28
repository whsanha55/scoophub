# Test Convention (Kotlin)

## 1. 도구

| 용도 | 도구 |
|---|---|
| 테스트 러너 | JUnit 5 |
| Mock | MockK, Spring 빈 교체는 springmockk (`@MockkBean`) |
| 검증 | AssertJ |

## 2. 이름과 구조

- 메서드 이름은 백틱 한글 문장으로 쓴다.
- 본문은 `given` / `when` / `then` 주석으로 나눈다.

```kotlin
@Test
fun `취소할 수 없는 상태의 주문을 취소하면 예외가 발생한다`() {
    // given
    val order = createOrder(status = OrderStatusEnum.FILLED)
    every { orderRepository.findByIdOrNull(1L) } returns order

    // when
    val exception = assertThrows<OrderException> { orderService.cancel(1L) }

    // then
    assertThat(exception.errorCode).isEqualTo(ErrorCodeEnum.ORDER_NOT_CANCELABLE)
}
```

## 3. 원칙

- 구현 세부보다 외부에서 관찰 가능한 동작을 검증한다.
- 하나의 테스트는 하나의 시나리오만 검증한다.
- 테스트 간 실행 순서에 의존하지 않는다.
- 시간은 `Clock.fixed(...)`를 주입해 고정한다.
- `BigDecimal`은 `isEqualByComparingTo`로 비교한다.

```kotlin
assertThat(fee).isEqualByComparingTo("1.25")
```

## 4. 범위별 선택

| 대상 | 방식 |
|---|---|
| 도메인, 계산 로직 | 순수 단위 테스트 (Spring Context 없음) |
| Service | 단위 테스트 + MockK |
| Controller | `@WebMvcTest` + `@MockkBean` (요청 검증, 응답 형태, HTTP 상태) |
| Repository | `@DataJpaTest` |
| 핵심 API 흐름 | `@SpringBootTest` 통합 테스트 |

## 5. Mock

- 테스트 대상 바깥의 의존성만 mock으로 만든다.
- 값 객체와 DTO는 mock으로 만들지 않는다.
- 상태로 검증할 수 있으면 상태 검증을 우선한다. 호출 횟수 검증은 꼭 필요할 때만 한다.
