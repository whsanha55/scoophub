# stock/sigma.py — Compute sigma from Yahoo Finance options chain IV.
from __future__ import annotations

import logging
import math
from datetime import date, datetime, timezone
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.stock.provider.router import ProviderRouter

from app.stock.models import SigmaResult

logger = logging.getLogger(__name__)


def extract_atm_iv(
    calls: list[dict],
    puts: list[dict],
    current_price: float,
) -> float:
    """Extract ATM implied volatility by averaging nearest-strike call+put IVs.

    Finds the call and put whose strikes are closest to current_price,
    then returns the average of their implied volatilities.
    """
    if not calls or not puts or current_price <= 0:
        return 0.0

    # Find nearest call by strike distance
    nearest_call = min(calls, key=lambda c: abs(c["strike"] - current_price))
    nearest_put = min(puts, key=lambda p: abs(p["strike"] - current_price))

    call_iv = nearest_call.get("impliedVolatility", 0)
    put_iv = nearest_put.get("impliedVolatility", 0)

    if call_iv <= 0 and put_iv <= 0:
        return 0.0

    # If one side has no IV, use the other
    if call_iv <= 0:
        return put_iv
    if put_iv <= 0:
        return call_iv

    return (call_iv + put_iv) / 2


def compute_dte(expiry_str: str) -> int:
    """Compute days to expiry (calendar days) from expiry string (YYYY-MM-DD)."""
    try:
        expiry_date = date.fromisoformat(expiry_str[:10])
        today = datetime.now(timezone.utc).date()
        delta = (expiry_date - today).days
        return max(delta, 0)
    except (ValueError, IndexError):
        return 0


async def compute_sigma_from_options(
    provider: ProviderRouter,
    ticker: str,
    current_price: float,
) -> SigmaResult | None:
    """Compute daily & weekly sigma from options chain ATM IV.

    Formulas:
        daily_sigma = price × IV × √(1/252)
        weekly_sigma = price × IV × √(5/252)
        expected_move = price × IV × √(DTE/365)
    """
    if current_price <= 0:
        return None

    chain = await provider.options_chain(ticker)
    if not chain or not chain.get("calls") or not chain.get("puts"):
        logger.warning("No options chain data for %s", ticker)
        return None

    atm_iv = extract_atm_iv(chain["calls"], chain["puts"], current_price)
    if atm_iv <= 0:
        logger.warning("ATM IV is 0 for %s", ticker)
        return None

    dte = compute_dte(chain["expiry"])
    expiry_date = None
    try:
        expiry_date = date.fromisoformat(chain["expiry"][:10])
    except (ValueError, IndexError):
        pass

    # Daily sigma: price × IV × √(1/252)
    daily_sigma = current_price * atm_iv * math.sqrt(1 / 252)
    daily_sigma_pct = (daily_sigma / current_price) * 100

    # Expected move from DTE: price × IV × √(DTE/365)
    if dte > 0:
        expected_move = current_price * atm_iv * math.sqrt(dte / 365)
    else:
        expected_move = daily_sigma  # fallback to 1-day

    expected_move_pct = (expected_move / current_price) * 100

    return SigmaResult(
        ticker=ticker,
        current_price=current_price,
        atm_iv=atm_iv,
        dte=dte,
        daily_sigma=round(daily_sigma, 4),
        daily_sigma_pct=round(daily_sigma_pct, 4),
        expected_move_high=round(current_price + expected_move, 4),
        expected_move_low=round(current_price - expected_move, 4),
        expected_move_pct=round(expected_move_pct, 4),
        sigma_type="daily",
        expiry_date=expiry_date,
        source="yfinance_options",
    )
