# stock/sigma.py — Compute sigma from ATM straddle prices (Yahoo Finance options chain).
from __future__ import annotations

import logging
from datetime import date, datetime, timezone
from typing import TYPE_CHECKING
from zoneinfo import ZoneInfo

if TYPE_CHECKING:
    from app.stock.provider.router import ProviderRouter

from app.stock.models import SigmaResult

logger = logging.getLogger(__name__)

ET = ZoneInfo("America/New_York")


def _option_price(opt: dict) -> float:
    """Get option price: bid/ask mid if available, else lastPrice."""
    bid = opt.get("bid", 0) or 0
    ask = opt.get("ask", 0) or 0
    if bid > 0 and ask > 0:
        return (bid + ask) / 2
    return float(opt.get("lastPrice", 0) or 0)


def _find_atm_strike(calls: list[dict], puts: list[dict], current_price: float) -> float | None:
    """Find ATM strike: closest common strike to current_price."""
    call_strikes = {c["strike"] for c in calls}
    put_strikes = {p["strike"] for p in puts}
    common = call_strikes & put_strikes
    if not common:
        return None
    return min(common, key=lambda s: abs(s - current_price))


def _et_date_from_utc(utc_dt: datetime) -> date:
    """Convert UTC datetime to ET trading date."""
    et_dt = utc_dt.astimezone(ET)
    return et_dt.date()


async def compute_sigma_from_options(
    provider: ProviderRouter,
    ticker: str,
    current_price: float,
    snapshot_at: datetime | None = None,
) -> list[SigmaResult]:
    """Compute sigma from ATM straddle prices for each expiry.

    Returns a list of SigmaResult (one per expiry). Empty list on failure.

    Formula:
        expected_move = ATM_call_price + ATM_put_price
        expected_move_pct = expected_move / current_price * 100
    """
    if current_price <= 0:
        return []

    if snapshot_at is None:
        snapshot_at = datetime.now(timezone.utc)

    chain = await provider.options_chain(ticker)
    if not chain or not chain.get("calls") or not chain.get("puts"):
        logger.warning("No options chain data for %s", ticker)
        return []

    calls = chain["calls"]
    puts = chain["puts"]

    # Find ATM strike from common strikes
    atm_strike = _find_atm_strike(calls, puts, current_price)
    if atm_strike is None:
        logger.warning("No common strikes for %s", ticker)
        return []

    # Find ATM call and put options
    atm_call = next((c for c in calls if c["strike"] == atm_strike), None)
    atm_put = next((p for p in puts if p["strike"] == atm_strike), None)

    if atm_call is None or atm_put is None:
        logger.warning("ATM options not found for %s at strike %s", ticker, atm_strike)
        return []

    # Get straddle prices
    call_price = _option_price(atm_call)
    put_price = _option_price(atm_put)

    # Liquidity filter: skip if no price
    if call_price <= 0 or put_price <= 0:
        logger.warning("ATM straddle has zero price for %s (call=%.4f, put=%.4f)", ticker, call_price, put_price)
        return []

    expected_move = call_price + put_price
    expected_move_pct = (expected_move / current_price) * 100

    # Volume aggregation (int coercion for float/nullable)
    total_call_volume = sum(int(c.get("volume", 0) or 0) for c in calls)
    total_put_volume = sum(int(p.get("volume", 0) or 0) for p in puts)
    atm_call_volume = int(atm_call.get("volume", 0) or 0)
    atm_put_volume = int(atm_put.get("volume", 0) or 0)

    # Put-Call Ratio
    pcr = (total_put_volume / total_call_volume) if total_call_volume > 0 else None

    # Expiry date
    expiry_date: date | None = None
    try:
        expiry_date = date.fromisoformat(chain["expiry"][:10])
    except (ValueError, IndexError, TypeError):
        pass

    # Snapshot date (ET trading day)
    snapshot_date = _et_date_from_utc(snapshot_at)

    return [SigmaResult(
        ticker=ticker,
        current_price=current_price,
        expiry_date=expiry_date,
        atm_strike=atm_strike,
        atm_call=round(call_price, 4),
        atm_put=round(put_price, 4),
        expected_move=round(expected_move, 4),
        expected_move_pct=round(expected_move_pct, 4),
        snapshot_date=snapshot_date,
        snapshot_at=snapshot_at,
        source="yfinance_straddle",
        total_call_volume=total_call_volume,
        total_put_volume=total_put_volume,
        put_call_volume_ratio=round(pcr, 4) if pcr is not None else None,
        atm_call_volume=atm_call_volume,
        atm_put_volume=atm_put_volume,
    )]
