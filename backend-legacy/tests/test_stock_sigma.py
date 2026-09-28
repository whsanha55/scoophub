"""compute_sigma_range 회귀 — weekly expected move(±1σ) 경계 정확성.

crawler(cols[3]/cols[4])가 high/low를 ±1σ로 제공하므로 half_range 자체가 1σ.
이전 `sigma = half_range/2` 버그(upper_1sigma가 실제 0.5σ) 방지.
"""

from app.stock.models import (
    SigmaPosition,
    WeeklyExpectedMove,
    compute_sigma_range,
)


def _wem(high: float, low: float) -> WeeklyExpectedMove:
    return WeeklyExpectedMove(
        ticker="AAPL", expected_move_high=high, expected_move_low=low
    )


def test_sigma_1sigma_bounds_match_weekly_high_low():
    """upper/lower_1sigma가 weekly high/low(±1σ)와 일치해야 함."""
    rng = compute_sigma_range(_wem(110, 90), current_price=100)
    assert rng.center == 100
    assert rng.upper_1sigma == 110  # +1σ == weekly high
    assert rng.lower_1sigma == 90  # -1σ == weekly low
    assert rng.upper_2sigma == 120
    assert rng.lower_2sigma == 80


def test_sigma_position_extremes_outside_weekly_range():
    """가격이 ±1σ 범위 밖이면 극단 position."""
    assert (
        compute_sigma_range(_wem(110, 90), current_price=115).sigma_position
        == SigmaPosition.ABOVE_1SIGMA
    )
    assert (
        compute_sigma_range(_wem(110, 90), current_price=85).sigma_position
        == SigmaPosition.BELOW_1SIGMA
    )


def test_sigma_position_center():
    """center 부근은 NEAR_CENTER."""
    assert (
        compute_sigma_range(_wem(110, 90), current_price=100).sigma_position
        == SigmaPosition.NEAR_CENTER
    )
