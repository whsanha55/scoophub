# stock/analysis_service.py — 분석 파이프라인 서비스 (router/scheduler 공유).
"""티커 분석 + 저장 + 리포트 발신 연쇄. router 엔드포인트와 scheduler 양쪽이 호출.

router.py 의 read-path 헬퍼(_analysis_row_to_report 등)는 여기서 다루지 않는다.
"""
from __future__ import annotations

import logging
from dataclasses import asdict
from typing import TYPE_CHECKING

from app.core.database import Database
from app.stock.schemas import AnalyzeResponse, AnalyzeResult

if TYPE_CHECKING:
    from app.stock.provider.router import ProviderRouter

logger = logging.getLogger(__name__)


async def run_analysis_for_tickers(
    tickers: list[str],
    db: Database,
    provider: "ProviderRouter",
) -> AnalyzeResponse:
    """Run technical analysis for given tickers and save results."""
    logger.info("run_analysis_for_tickers() 진입 — tickers=%s", tickers)
    from app.stock.repository import AnalysisResultRepo, WatchlistRepo
    from app.stock.signal import generate_report

    repo = AnalysisResultRepo(db)
    wl_repo = WatchlistRepo(db)

    results: list[AnalyzeResult] = []
    ok = 0
    errors = 0

    for ticker in tickers:
        try:
            # Fetch current price
            quote = await provider.quote(ticker.upper())
            price = float(quote.get("regularMarketPrice", 0))
            change = float(quote.get("regularMarketChange", 0))
            change_rate = float(quote.get("regularMarketChangePercent", 0))

            if price == 0:
                results.append(AnalyzeResult(ticker=ticker, status="error", detail="Price unavailable — provider returned no data"))
                errors += 1
                continue

            # Fetch daily candles
            candles = await provider.chart(ticker.upper(), "1D")

            # 빈 캔들(provider 실패) 시 가짜 분석이 'ok'로 영속화되는 것 방지
            if not candles:
                results.append(AnalyzeResult(ticker=ticker, status="error", detail="No candle data — provider returned empty"))
                errors += 1
                continue

            # Resolve exchange from watchlist
            wl_item = await wl_repo.find_by_ticker(ticker.upper())
            exchange = wl_item.exchange if wl_item else "NAS"

            # Generate analysis report
            report = generate_report(ticker.upper(), price, change, change_rate, candles)

            # Save to DB
            details_dict = asdict(report.technical_details)
            sigma_data = await fetch_sigma_enrichment(ticker.upper(), db, price)
            if sigma_data:
                details_dict["sigma_data"] = sigma_data
            await repo.save(
                ticker=ticker.upper(),
                exchange=exchange,
                timeframe="1D",
                signal=report.signal.value,
                total_score=report.total_score,
                confidence=report.confidence,
                market_regime=report.market_regime.value,
                price=price,
                change=change,
                change_rate=change_rate,
                technical_scores=report.technical_scores,
                technical_details=details_dict,
            )

            # 다중 기간 분석(1W/1M): 1D 캔들 resample → 분석 → 평면 저장.
            # resample 캔들 부족 시 빈 리스트 → 스킵(가짜 분석 영속화 방지).
            await save_multi_timeframe(
                ticker.upper(), exchange, candles, price, change, change_rate, db, repo
            )

            results.append(AnalyzeResult(ticker=ticker, status="ok"))
            ok += 1

        except Exception as e:
            logger.warning("Analysis failed for %s: %s", ticker, e)
            results.append(AnalyzeResult(ticker=ticker, status="error", detail=str(e)))
            errors += 1

    logger.info("run_analysis_for_tickers() 완료 — total=%d, ok=%d, errors=%d", len(tickers), ok, errors)

    # 발신 연쇄: 분석 종료 후 다계층 리포트 빌드 + 발신 (성공 1건 이상 시).
    # 발신 실패해도 분석 결과는 이미 저장됨(분석과 발신 분리).
    if ok > 0:
        try:
            from app.stock.report import ReportBuilder

            await ReportBuilder(db).run(tickers=tickers)
        except Exception as e:
            logger.warning("daily report dispatch failed (non-fatal): %s", e)

    return AnalyzeResponse(total=len(tickers), ok=ok, errors=errors, results=results)


async def fetch_sigma_enrichment(ticker: str, db: Database, price: float) -> dict | None:
    """Fetch sigma + WEM snapshot for persistence at analysis time.

    Returns dict matching issue #49 JSON schema, or None if no data.
    """
    from app.stock.repository import SigmaRepo, WeeklyExpectedMoveRepo

    sigma_repo = SigmaRepo(db)
    wem_repo = WeeklyExpectedMoveRepo(db)

    sigma_data: dict = {}

    # 1. stock_sigma (ATM straddle, nearest expiry)
    sigma_result = await sigma_repo.get_latest(ticker)
    if sigma_result:
        sigma_data["straddle"] = {
            "expiry_date": str(sigma_result.expiry_date) if sigma_result.expiry_date else None,
            "atm_strike": sigma_result.atm_strike,
            "atm_call": sigma_result.atm_call,
            "atm_put": sigma_result.atm_put,
            "expected_move": sigma_result.expected_move,
            "expected_move_pct": sigma_result.expected_move_pct,
            "put_call_volume_ratio": sigma_result.put_call_volume_ratio,
            "total_call_volume": sigma_result.total_call_volume,
            "total_put_volume": sigma_result.total_put_volume,
            "snapshot_date": str(sigma_result.snapshot_date) if sigma_result.snapshot_date else None,
        }

    # 2. stock_weekly_expected_moves
    wem_list = await wem_repo.find_by_ticker(ticker, limit=1)
    if wem_list:
        wem = wem_list[0]
        from app.stock.models import compute_sigma_range, generate_sigma_signal

        sigma_range = compute_sigma_range(wem, price)
        sigma_signal = generate_sigma_signal(sigma_range)
        sigma_data["weekly_expected_move"] = {
            "week_start": str(wem.week_start) if wem.week_start else None,
            "week_end": str(wem.week_end) if wem.week_end else None,
            "expected_move_high": wem.expected_move_high,
            "expected_move_low": wem.expected_move_low,
            "expected_move_pct": wem.expected_move_pct,
            "sigma_position": sigma_signal.sigma_position.value,
            "sigma_signal": sigma_signal.signal.value,
            "sigma_confidence": sigma_signal.confidence,
            "center": sigma_range.center,
            "upper_1sigma": sigma_range.upper_1sigma,
            "lower_1sigma": sigma_range.lower_1sigma,
        }

    return sigma_data if sigma_data else None


async def save_multi_timeframe(
    ticker: str,
    exchange: str,
    daily_candles: list,
    price: float,
    change: float,
    change_rate: float,
    db: Database,
    repo,
) -> None:
    """1D 캔들 → 1W/1M resample → 분석 → analysis_results 평면 저장.

    resample 캔들 부족(resample_xxx 가 빈 리스트 반환) 시 해당 기간은 스킵.
    보조 기간의 technical_details 에 sigma_data 는 미포함(1D 에서만 풍부).
    """
    from app.stock.resample import resample_monthly, resample_weekly
    from app.stock.signal import generate_report

    for rule, resample_fn in (("1W", resample_weekly), ("1M", resample_monthly)):
        try:
            resampled = resample_fn(list(daily_candles))
            if not resampled:
                continue
            report = generate_report(ticker, price, change, change_rate, resampled)
            await repo.save(
                ticker=ticker,
                exchange=exchange,
                timeframe=rule,
                signal=report.signal.value,
                total_score=report.total_score,
                confidence=report.confidence,
                market_regime=report.market_regime.value,
                price=price,
                change=change,
                change_rate=change_rate,
                technical_scores=report.technical_scores,
                technical_details=asdict(report.technical_details),
            )
        except Exception as e:
            logger.warning("multi-timeframe %s analysis failed for %s: %s", rule, ticker, e)
