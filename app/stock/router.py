# stock/router.py — FastAPI routes for stock analysis & watchlist.
from __future__ import annotations

import json
import logging
from dataclasses import asdict
from datetime import datetime, timezone
from typing import TYPE_CHECKING

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

if TYPE_CHECKING:
    from app.stock.provider.router import ProviderRouter

from app.stock.schemas import (
    AnalyzeResponse,
    AnalyzeResult,
    SigmaDataOut,
    StockReport,
    StockSummary,
    TechnicalOut,
    WatchlistItemIn,
    WatchlistItemOut,
    WatchlistUpdateIn,
)

logger = logging.getLogger(__name__)

VALID_TIMEFRAMES = {"1D"}

router = APIRouter(prefix="/api")

# ── Provider router (module-level singleton) ────────────────────────────────

_provider_router: ProviderRouter | None = None


def set_provider_router(pr: ProviderRouter) -> None:
    global _provider_router
    _provider_router = pr


def get_provider_router() -> ProviderRouter:
    if _provider_router is None:
        raise RuntimeError("ProviderRouter not initialized. Call set_provider_router() first.")
    return _provider_router


# ── DB dependency ────────────────────────────────────────────────────────────


def _get_db() -> Database:
    raise NotImplementedError


# ── Helpers ──────────────────────────────────────────────────────────────────


async def _fetch_sigma_enrichment(ticker: str, db: Database, price: float) -> dict | None:
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


async def _run_analysis_for_tickers(
    tickers: list[str],
    db: Database,
) -> AnalyzeResponse:
    """Run technical analysis for given tickers and save results."""
    logger.info("_run_analysis_for_tickers() 진입 — tickers=%s", tickers)
    from app.stock.repository import AnalysisResultRepo, WatchlistRepo
    from app.stock.signal import generate_report

    provider = get_provider_router()
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

            # Resolve exchange from watchlist
            wl_item = await wl_repo.find_by_ticker(ticker.upper())
            exchange = wl_item.exchange if wl_item else "NAS"

            # Generate analysis report
            report = generate_report(ticker.upper(), price, change, change_rate, candles)

            # Save to DB
            details_dict = asdict(report.technical_details)
            sigma_data = await _fetch_sigma_enrichment(ticker.upper(), db, price)
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

            results.append(AnalyzeResult(ticker=ticker, status="ok"))
            ok += 1

        except Exception as e:
            logger.warning("Analysis failed for %s: %s", ticker, e)
            results.append(AnalyzeResult(ticker=ticker, status="error", detail=str(e)))
            errors += 1

    logger.info("_run_analysis_for_tickers() 완료 — total=%d, ok=%d, errors=%d", len(tickers), ok, errors)
    return AnalyzeResponse(total=len(tickers), ok=ok, errors=errors, results=results)


def _analysis_row_to_report(row: dict) -> StockReport:
    """Convert analysis_results row dict to StockReport schema."""
    tech_scores = row.get("technical_scores", {})
    if isinstance(tech_scores, str):
        tech_scores = json.loads(tech_scores)
    tech_details = row.get("technical_details", {})
    if isinstance(tech_details, str):
        tech_details = json.loads(tech_details)

    technical = TechnicalOut(
        signal=row["signal"],
        total_score=float(row["total_score"]),
        confidence=float(row["confidence"]),
        market_regime=row["market_regime"],
        technical_scores=tech_scores,
        technical_details=tech_details,
    )

    analyzed_at = row.get("analyzed_at")
    data_date = analyzed_at.isoformat() if isinstance(analyzed_at, datetime) else str(analyzed_at) if analyzed_at else None

    is_stale = None
    if analyzed_at and isinstance(analyzed_at, datetime):
        aware_at = analyzed_at if analyzed_at.tzinfo else analyzed_at.replace(tzinfo=timezone.utc)
        hours_diff = (datetime.now(timezone.utc) - aware_at).total_seconds() / 3600
        is_stale = hours_diff > 24

    # Read persisted sigma_data from technical_details
    sigma = None
    sigma_snapshot = tech_details.get("sigma_data")
    if sigma_snapshot:
        wem = sigma_snapshot.get("weekly_expected_move")
        if wem:
            sigma = {
                "sigma_position": wem.get("sigma_position"),
                "sigma_signal": wem.get("sigma_signal"),
                "sigma_confidence": wem.get("sigma_confidence"),
                "expected_move_pct": wem.get("expected_move_pct"),
                "expected_move_high": wem.get("expected_move_high"),
                "expected_move_low": wem.get("expected_move_low"),
                "source": "persisted_snapshot",
            }

    return StockReport(
        ticker=row["ticker"],
        exchange=row.get("exchange", "NAS"),
        price=float(row.get("price", 0)),
        change=float(row.get("change", 0)),
        change_rate=float(row.get("change_rate", 0)),
        technical=technical,
        sigma=sigma,
        data_date=data_date,
        is_stale=is_stale,
    )


async def _enrich_sigma_fallback(
    report: StockReport, ticker: str, wem_repo, limit: int = 4
) -> None:
    """Enrich report.sigma with live WEM data when no persisted snapshot."""
    from app.stock.models import compute_sigma_range, generate_sigma_signal

    wem_list = await wem_repo.find_by_ticker(ticker, limit=limit)
    if not wem_list:
        return
    wem = wem_list[0]
    sigma_range = compute_sigma_range(wem, report.price)
    sigma_signal = generate_sigma_signal(sigma_range)
    report.sigma = {
        "sigma_position": sigma_signal.sigma_position.value,
        "sigma_signal": sigma_signal.signal.value,
        "sigma_confidence": sigma_signal.confidence,
        "expected_move_pct": wem.expected_move_pct,
        "expected_move_high": wem.expected_move_high,
        "expected_move_low": wem.expected_move_low,
        "source": "usstocksigma_html",
        "weekly_moves": [
            {
                "week_start": str(w.week_start) if w.week_start else None,
                "week_end": str(w.week_end) if w.week_end else None,
                "expected_move_pct": w.expected_move_pct,
                "expected_move_high": w.expected_move_high,
                "expected_move_low": w.expected_move_low,
            }
            for w in wem_list
        ],
    }


def _watchlist_item_to_out(item) -> WatchlistItemOut:
    """Convert WatchlistItem dataclass to WatchlistItemOut schema."""
    added_at = item.added_at
    added_at_str = added_at.isoformat() if isinstance(added_at, datetime) else str(added_at) if added_at else ""
    return WatchlistItemOut(
        id=str(item.id) if item.id else "",
        ticker=item.ticker,
        exchange=item.exchange,
        name=item.name,
        memo=item.memo,
        added_at=added_at_str,
        is_active=item.is_active,
    )


# ── Stock Analysis ───────────────────────────────────────────────────────────


@router.post(
    "/crawling/stock/analyze",
    tags=["Stock Crawling"],
    summary="분석 수동 실행",
    description=(
        "watchlist 종목의 기술 분석 수동 실행 + 결과 저장.\n\n"
        "- `tickers` 생략 시 활성 watchlist 전체 대상\n"
        "- 자동 스케줄: 화-토 06:00 (KST, 미국장 종료 직후)"
    ),
)
async def analyze(
    tickers: list[str] | None = Query(None, description="분석할 티커 목록. 생략 시 전체 watchlist"),
    db: Database = Depends(_get_db),
):
    logger.info("analyze 엔드포인트 진입 — tickers=%s", tickers)
    from app.stock.repository import WatchlistRepo

    wl_repo = WatchlistRepo(db)

    if tickers:
        target_tickers = [t.upper() for t in tickers]
    else:
        items = await wl_repo.find_all(active_only=True)
        target_tickers = [it.ticker for it in items]

    if not target_tickers:
        return ApiResponse(success=True, data=AnalyzeResponse(total=0, ok=0, errors=0, results=[]))

    resp = await _run_analysis_for_tickers(target_tickers, db)
    return ApiResponse(success=True, data=resp)


# ── Stock Report ─────────────────────────────────────────────────────────────


@router.get(
    "/stock/report",
    tags=["Stock"],
    summary="종목 리포트",
    description=(
        "저장된 분석 결과 + Sigma 데이터로 종목 리포트 반환.\n\n"
        "- `tickers`: 콤마로 구분 (예: `AAPL,MSFT,GOOGL`)\n"
        "- Sigma: 최근 4주 예상 변동폭 + 현재 가격 위치\n"
        "- `is_stale=true`: 마지막 분석이 24시간 이전"
    ),
)
async def stock_report(
    tickers: str = Query("", description="조회할 티커 목록 (콤마 구분, 예: AAPL,MSFT,GOOGL)"),
    timeframe: str = Query("1D", description="시간 프레임 (현재 1D만 지원)"),
    db: Database = Depends(_get_db),
):
    """저장된 분석 결과로 종목 리포트 반환."""
    logger.info("stock_report 엔드포인트 진입 — tickers=%s, timeframe=%s", tickers, timeframe)
    if timeframe not in VALID_TIMEFRAMES:
        return JSONResponse(
            status_code=400,
            content=ApiResponse(success=False, error=ErrorDetail(
                code="INVALID_TIMEFRAME",
                message=f"Invalid timeframe '{timeframe}'",
                detail=f"Valid values: {sorted(VALID_TIMEFRAMES)}",
            )).model_dump(mode="json"),
        )

    if not tickers:
        return ApiResponse(success=True, data=[])

    ticker_list = [t.strip().upper() for t in tickers.split(",") if t.strip()]
    if not ticker_list:
        return ApiResponse(success=True, data=[])

    from app.stock.repository import AnalysisResultRepo, WeeklyExpectedMoveRepo

    repo = AnalysisResultRepo(db)
    wem_repo = WeeklyExpectedMoveRepo(db)
    rows = await repo.find_by_tickers(ticker_list, timeframe)

    reports: list[StockReport] = []
    for row in rows:
        report = _analysis_row_to_report(row)
        if report.sigma is None:
            await _enrich_sigma_fallback(report, row["ticker"], wem_repo)

        reports.append(report)

    return ApiResponse(success=True, data=reports)


@router.get(
    "/stock/report/all",
    tags=["Stock"],
    summary="전체 리포트",
    description=(
        "전체 종목 리포트 반환.\n\n"
        "- `summarize=false` (기본): 기술 분석 + Sigma 전체 데이터\n"
        "- `summarize=true`: 시그널/점수/위치만 요약"
    ),
)
async def stock_report_all(
    summarize: bool = Query(False, description="true 시 시그널/점수/위치만 요약 반환"),
    timeframe: str = Query("1D", description="시간 프레임 (현재 1D만 지원)"),
    db: Database = Depends(_get_db),
):
    """전체 종목 리포트 반환. summarize=True 시 요약 버전."""
    logger.info("stock_report_all 엔드포인트 진입 — summarize=%s, timeframe=%s", summarize, timeframe)
    if timeframe not in VALID_TIMEFRAMES:
        return JSONResponse(
            status_code=400,
            content=ApiResponse(success=False, error=ErrorDetail(
                code="INVALID_TIMEFRAME",
                message=f"Invalid timeframe '{timeframe}'",
                detail=f"Valid values: {sorted(VALID_TIMEFRAMES)}",
            )).model_dump(mode="json"),
        )

    from app.stock.repository import AnalysisResultRepo, WeeklyExpectedMoveRepo

    repo = AnalysisResultRepo(db)
    wem_repo = WeeklyExpectedMoveRepo(db)
    rows = await repo.find_all(timeframe)

    if not summarize:
        reports: list[StockReport] = []
        for row in rows:
            report = _analysis_row_to_report(row)
            if report.sigma is None:
                await _enrich_sigma_fallback(report, row["ticker"], wem_repo)
            reports.append(report)
        return ApiResponse(success=True, data=reports)

    # Summarized version
    summaries: list[StockSummary] = []
    for row in rows:
        report = _analysis_row_to_report(row)
        if report.sigma is None:
            await _enrich_sigma_fallback(report, row["ticker"], wem_repo, limit=1)

        sigma = report.sigma or {}
        summaries.append(StockSummary(
            ticker=report.ticker,
            exchange=report.exchange,
            price=report.price,
            change=report.change,
            change_rate=report.change_rate,
            signal=row["signal"],
            total_score=float(row["total_score"]),
            confidence=float(row["confidence"]),
            market_regime=row["market_regime"],
            sigma_position=sigma.get("sigma_position", "NEAR_CENTER"),
            sigma_signal=sigma.get("sigma_signal", "NEUTRAL"),
            sigma_confidence=sigma.get("sigma_confidence", 0.0),
            expected_move_pct=sigma.get("expected_move_pct", 0.0),
            data_date=report.data_date,
            is_stale=report.is_stale,
        ))

    return ApiResponse(success=True, data=summaries)


# ── Sigma (Options IV) ────────────────────────────────────────────────────────


@router.get(
    "/stock/sigma",
    tags=["Stock"],
    summary="시그마 조회",
    description=(
        "ATM straddle 기반 시그마 데이터 조회.\n\n"
        "- `ticker`: 단일 티커 (예: AAPL)\n"
        "- 데이터 출처: `yfinance_straddle`"
    ),
)
async def get_sigma(
    ticker: str = Query(..., description="주식 티커 (예: AAPL)"),
    db: Database = Depends(_get_db),
):
    from app.stock.repository import SigmaRepo

    repo = SigmaRepo(db)
    result = await repo.get_latest(ticker.upper())
    if not result:
        return ApiResponse(success=True, data=None)

    created_at = None
    if result.created_at:
        created_at = result.created_at.isoformat() if isinstance(result.created_at, datetime) else str(result.created_at)
    snapshot_at = None
    if result.snapshot_at:
        snapshot_at = result.snapshot_at.isoformat() if isinstance(result.snapshot_at, datetime) else str(result.snapshot_at)
    return ApiResponse(success=True, data=SigmaDataOut(
        ticker=result.ticker,
        current_price=result.current_price,
        expiry_date=str(result.expiry_date) if result.expiry_date else None,
        atm_strike=result.atm_strike,
        atm_call=result.atm_call,
        atm_put=result.atm_put,
        expected_move=result.expected_move,
        expected_move_pct=result.expected_move_pct,
        snapshot_date=str(result.snapshot_date) if result.snapshot_date else None,
        snapshot_at=snapshot_at,
        source=result.source,
        total_call_volume=result.total_call_volume,
        total_put_volume=result.total_put_volume,
        put_call_volume_ratio=result.put_call_volume_ratio,
        atm_call_volume=result.atm_call_volume,
        atm_put_volume=result.atm_put_volume,
        created_at=created_at,
    ))


# ── Market Status ────────────────────────────────────────────────────────────


@router.get(
    "/stock/market-status",
    tags=["Stock"],
    summary="시장 상태",
    description="US 주식 시장(NYSE/NASDAQ) 열림 여부. 기준: 평일 14:30–21:00 UTC (9:30 AM–4:00 PM ET).",
)
async def market_status():
    logger.info("market_status 엔드포인트 진입")
    from datetime import time as dt_time

    now_utc = datetime.now(timezone.utc)
    is_weekday = now_utc.weekday() < 5
    hour = now_utc.hour
    minute = now_utc.minute
    # US market hours: 14:30–21:00 UTC (9:30 AM–4:00 PM ET)
    is_market_hours = (hour == 14 and minute >= 30) or (15 <= hour <= 20)
    is_open = is_weekday and is_market_hours

    return ApiResponse(success=True, data={
        "is_open": is_open,
        "is_weekday": is_weekday,
        "current_utc": now_utc.isoformat(),
    })


# ── Watchlist ────────────────────────────────────────────────────────────────


@router.get(
    "/stock/watchlist",
    tags=["Stock Watchlist"],
    summary="관심종목 전체 조회",
    description="watchlist에 등록된 전체 종목 목록을 반환합니다.",
)
async def get_watchlist(db: Database = Depends(_get_db)):
    logger.info("get_watchlist 엔드포인트 진입")
    from app.stock.repository import WatchlistRepo

    repo = WatchlistRepo(db)
    items = await repo.find_all()
    data = [_watchlist_item_to_out(it) for it in items]
    return ApiResponse(success=True, data=data)


@router.post(
    "/stock/watchlist",
    tags=["Stock Watchlist"],
    summary="관심종목 추가",
    description="watchlist에 새 종목을 추가합니다. 티커는 자동 대문자 변환.",
)
async def add_watchlist(item: WatchlistItemIn, db: Database = Depends(_get_db)):
    logger.info("add_watchlist 엔드포인트 진입 — ticker=%s", item.ticker)
    from app.stock.models import WatchlistItem
    from app.stock.repository import WatchlistRepo

    repo = WatchlistRepo(db)
    new_item = WatchlistItem(
        ticker=item.ticker,
        exchange=item.exchange,
        name=item.name,
        memo=item.memo,
        is_active=True,
    )
    created = await repo.add(new_item)
    if created is None:
        return ApiResponse(success=False, error=ErrorDetail(code="duplicate", message=f"{item.ticker} already in watchlist"))
    return ApiResponse(success=True, data=_watchlist_item_to_out(created))


@router.put(
    "/stock/watchlist/{item_id}",
    tags=["Stock Watchlist"],
    summary="관심종목 수정",
    description="watchlist 종목의 필드를 부분 수정합니다. `is_active=false`로 비활성화 가능.",
)
async def update_watchlist(
    item_id: int,
    item: WatchlistUpdateIn,
    db: Database = Depends(_get_db),
):
    logger.info("update_watchlist 엔드포인트 진입 — item_id=%d", item_id)
    from app.stock.repository import WatchlistRepo

    repo = WatchlistRepo(db)
    updates = item.model_dump(exclude_none=True)
    if not updates:
        existing = await repo.find_by_id(item_id)
        if not existing:
            return JSONResponse(
                status_code=404,
                content=ApiResponse(success=False, error=ErrorDetail(code="NOT_FOUND", message=f"Watchlist item {item_id} not found")).model_dump(mode="json"),
            )
        return ApiResponse(success=True, data=_watchlist_item_to_out(existing))

    updated = await repo.update(item_id, **updates)
    if updated is None:
        return JSONResponse(
            status_code=404,
            content=ApiResponse(success=False, error=ErrorDetail(code="NOT_FOUND", message=f"Watchlist item {item_id} not found")).model_dump(mode="json"),
        )
    return ApiResponse(success=True, data=_watchlist_item_to_out(updated))


@router.delete(
    "/stock/watchlist/{item_id}",
    tags=["Stock Watchlist"],
    summary="관심종목 삭제",
    description="watchlist에서 종목을 영구 삭제합니다.",
)
async def delete_watchlist(item_id: int, db: Database = Depends(_get_db)):
    logger.info("delete_watchlist 엔드포인트 진입 — item_id=%d", item_id)
    from app.stock.repository import WatchlistRepo

    repo = WatchlistRepo(db)
    deleted = await repo.remove(item_id)
    if not deleted:
        return JSONResponse(
            status_code=404,
            content=ApiResponse(success=False, error=ErrorDetail(code="NOT_FOUND", message=f"Watchlist item {item_id} not found")).model_dump(mode="json"),
        )
    return ApiResponse(success=True, data={"deleted": item_id})


# ── Crawl Helpers (shared with analyze auto-crawl) ─────────────────────────


async def _do_crawl_sigma(db: Database) -> int:
    """Crawl sigma data. Returns items_new count."""
    logger.info("_do_crawl_sigma() 진입")
    from app.stock.crawler import SigmaCrawler

    result = await SigmaCrawler(db).run()
    return result.items_new if result else 0


async def _do_sync_candles(db: Database) -> int:
    """Sync candle data for active watchlist. Returns total saved count."""
    logger.info("_do_sync_candles() 진입")
    from app.stock.repository import CandleRepo, WatchlistRepo

    provider = get_provider_router()
    wl_repo = WatchlistRepo(db)
    candle_repo = CandleRepo(db)

    items = await wl_repo.find_all(active_only=True)
    if not items:
        return 0

    total_saved = 0
    for item in items:
        try:
            candles = await provider.chart(item.ticker, "1D")
            if candles:
                saved = await candle_repo.save_batch(candles)
                total_saved += saved
        except Exception as e:
            logger.warning("Candle sync failed for %s: %s", item.ticker, e)

    return total_saved


# ── Manual Crawl Triggers ────────────────────────────────────────────────────


@router.post(
    "/crawling/stock/sigma",
    tags=["Stock Crawling"],
    summary="Sigma(1σ) 주간 예상 변동폭 크롤 (월 03:00 자동 / 수동 트리거)",
    description=(
        "usstocksigma.com에서 이번 주 **예상 주간 변동폭(1σ)** 데이터를 크롤링합니다.\n\n"
        "### 크롤링 데이터\n"
        "| 항목 | 설명 |\n"
        "|------|------|\n"
        "| Ticker | 미국 주식 티커 (예: AAPL, TSLA) |\n"
        "| 예상 변동률 | 주간 예상 변동률 (% Weekly Expected Move) |\n"
        "| -1σ 가격 | 현재가 기준 하방 1표준편차 가격 |\n"
        "| +1σ 가격 | 현재가 기준 상방 1표준편차 가격 |\n\n"
        "### 자동 스케줄\n"
        "- 매주 월요일 03:00(KST) 스케줄러가 자동 실행합니다.\n"
        "- 본 엔드포인트는 스케줄과 별개로 즉시 재크롤이 필요할 때 쓰는 수동 트리거입니다."
    ),
    responses={
        200: {
            "description": "크롤링 결과",
            "content": {
                "application/json": {
                    "example": {
                        "success": True,
                        "data": {
                            "crawler": "stock_sigma",
                            "items_fetched": 150,
                            "items_new": 12,
                            "errors": None,
                        },
                    }
                }
            },
        },
    },
)
async def crawling_sigma(db: Database = Depends(_get_db)):
    from app.stock.crawler import SigmaCrawler

    result = await SigmaCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="Sigma 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "stock_sigma",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })


@router.post(
    "/crawling/stock/sigma/compute",
    tags=["Stock Crawling"],
    summary="Sigma 즉시 계산 (ATM straddle 기반)",
    description=(
        "yfinance 옵션 체인 ATM straddle 기반 sigma 즉시 계산 + 저장.\n\n"
        "- `tickers` 생략 시 활성 watchlist 전체 대상\n"
        "- 결과: 각 ticker별 ATM straddle expected move 저장\n"
        "- 자동 스케줄: 화-토 22:30 UTC (미국장 종료 후)"
    ),
)
async def compute_sigma(
    tickers: list[str] | None = Query(None, description="계산할 티커 목록. 생략 시 전체 watchlist"),
    db: Database = Depends(_get_db),
):
    from app.stock.repository import SigmaRepo, WatchlistRepo
    from app.stock.sigma import compute_sigma_from_options

    provider = get_provider_router()
    wl_repo = WatchlistRepo(db)
    sigma_repo = SigmaRepo(db)

    if tickers:
        target_tickers = [t.upper() for t in tickers]
    else:
        items = await wl_repo.find_all(active_only=True)
        target_tickers = [it.ticker for it in items]

    if not target_tickers:
        return ApiResponse(success=True, data={"saved": 0, "tickers": []})

    snapshot_at = datetime.now(timezone.utc)
    saved = 0
    errors = 0
    for ticker in target_tickers:
        try:
            quote = await provider.quote(ticker)
            price = float(quote.get("regularMarketPrice", 0))
            if price <= 0:
                errors += 1
                continue
            results = await compute_sigma_from_options(provider, ticker, price, snapshot_at=snapshot_at)
            for result in results:
                await sigma_repo.save(result)
                saved += 1
        except Exception:
            logger.exception("Sigma compute failed for %s", ticker)
            errors += 1

    return ApiResponse(success=True, data={
        "saved": saved,
        "errors": errors,
        "tickers": target_tickers,
    })


@router.post(
    "/crawling/stock/sync",
    tags=["Stock Crawling"],
    summary="캔들 동기화 수동 실행",
    description=(
        "watchlist 종목의 일봉(OHLCV) 캔들 데이터를 yfinance에서 동기화.\n\n"
        "- 자동 스케줄: 60분 간격\n"
        "- 대상: 활성 watchlist 전체 종목"
    ),
)
async def crawling_sync(db: Database = Depends(_get_db)):
    total_saved = await _do_sync_candles(db)
    return ApiResponse(success=True, data={
        "synced": total_saved,
    })
