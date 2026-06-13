# app/kal_bonus/cabin.py
"""frontBookingClass 코드 → cabin_label 매핑.

의미 파싱은 API의 bookingClass(내부 RBD)가 아닌 frontBookingClass 기준.
"""

# frontBookingClass 코드 룰 — issue #88 검증 결과
CABIN_LABELS: dict[str, str] = {
    "E": "일반석 보너스",
    "R": "프리미엄석 보너스",
    "G": "프리미엄석 좌석승급",
    "P": "프레스티지석 보너스",
    "U": "프레스티지석 좌석승급",
    "F": "일등석 보너스/좌석승급",
    "Ø": "운항편 없음",
}

# 보너스 좌석 "가용" 신호로 의미있는 등급(Ø 제외)
BONUS_CABIN_CODES = tuple(c for c in CABIN_LABELS if c != "Ø")


def map_cabin(front_booking_class: str | None) -> str:
    """frontBookingClass 코드 → cabin_label. 미정의 코드는 원문 그대로 반환."""
    if not front_booking_class:
        return ""
    return CABIN_LABELS.get(front_booking_class, front_booking_class)
