# tests/test_stock_report.py — T4(액션러블 레벨) + T5(ReportBuilder) 단위 테스트.
"""액션러블 레벨 산출 정확성 + ReportBuilder 조립/발신 연쇄 검증.
"""
import pytest

from app.stock.models import SigmaRange, WeeklyExpectedMove, compute_sigma_range
from app.stock.report import (
    REPORT_LINK,
    ReportBuilder,
    compute_actionable_levels,
)
from app.stock.router import _analysis_row_to_report, _enrich_report_levels


# ── T4: compute_actionable_levels ──────────────────────────────────────────


def _wem(high: float, low: float) -> WeeklyExpectedMove:
    return WeeklyExpectedMove(ticker="AAPL", expected_move_high=high, expected_move_low=low)


def _sigma(high: float, low: float, price: float) -> SigmaRange:
    return compute_sigma_range(_wem(high, low), price)


def test_actionable_levels_basic_levels():
    """목표가=+1σ, 매수=-1σ, 손절=진입가(buy_zone)-1.5×ATR."""
    sr = _sigma(110, 90, price=100)
    levels = compute_actionable_levels(
        price=100,
        sigma_range=sr,
        tech_details={"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.5},
    )
    assert levels is not None
    assert levels.target_price == 110  # upper_1sigma
    assert levels.buy_zone == 90       # lower_1sigma
    assert levels.stop_loss == pytest.approx(90 - 1.5 * 4.0)  # 84.0 (진입가 기준)
    assert levels.momentum_fire is True  # price(100) > ema12(99) & macd_hist>0


def test_actionable_levels_no_momentum_when_below_ema():
    """price < EMA12 → momentum_fire False."""
    sr = _sigma(110, 90, price=95)
    levels = compute_actionable_levels(
        price=95,
        sigma_range=sr,
        tech_details={"atr": 3.0, "ema12": 100.0, "macd_histogram": 0.5},
    )
    assert levels.momentum_fire is False


def test_actionable_levels_no_momentum_when_macd_negative():
    """MACD hist <= 0 → momentum_fire False."""
    sr = _sigma(110, 90, price=105)
    levels = compute_actionable_levels(
        price=105,
        sigma_range=sr,
        tech_details={"atr": 3.0, "ema12": 100.0, "macd_histogram": -0.3},
    )
    assert levels.momentum_fire is False


def test_actionable_levels_stop_loss_none_without_atr():
    """ATR 미제공/0 → stop_loss None."""
    sr = _sigma(110, 90, price=100)
    levels = compute_actionable_levels(price=100, sigma_range=sr, tech_details={})
    assert levels.stop_loss is None
    assert levels.target_price == 110
    assert levels.buy_zone == 90


def test_actionable_levels_price_zero_returns_none():
    sr = _sigma(110, 90, price=100)
    assert compute_actionable_levels(0, sr, {}) is None


def test_actionable_levels_no_sigma_returns_none():
    assert compute_actionable_levels(100, None, {}) is None


def test_actionable_levels_bb_fallback_when_no_sigma():
    """sigma 부재 시 BB 밴드로 목표/진입가 폴백."""
    levels = compute_actionable_levels(
        price=100,
        sigma_range=None,
        tech_details={"atr": 4.0, "bb_upper": 108.0, "bb_lower": 92.0, "ema12": 99.0, "macd_histogram": 0.5},
    )
    assert levels is not None
    assert levels.target_price == 108.0  # bb_upper 폴백
    assert levels.buy_zone == 92.0       # bb_lower 폴백
    assert levels.stop_loss == pytest.approx(92.0 - 1.5 * 4.0)  # 86.0, 진입가(BB) 기준
    assert levels.momentum_fire is True


def test_actionable_levels_sigma_preferred_over_bb():
    """sigma 있으면 BB 무시하고 sigma ±1σ 사용."""
    sr = _sigma(110, 90, price=100)
    levels = compute_actionable_levels(
        price=100,
        sigma_range=sr,
        tech_details={"atr": 4.0, "bb_upper": 108.0, "bb_lower": 92.0, "ema12": 99.0, "macd_histogram": 0.5},
    )
    assert levels.target_price == 110  # sigma upper, not bb_upper
    assert levels.buy_zone == 90       # sigma lower, not bb_lower


def test_actionable_levels_to_dict():
    sr = _sigma(110, 90, price=100)
    levels = compute_actionable_levels(
        100, sr, {"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.5}
    )
    d = levels.to_dict()
    assert d["target_price"] == 110
    assert d["momentum_fire"] is True


# ── T5: ReportBuilder 조립 로직 (순수 함수 단위) ──────────────────────────


def _make_row(ticker: str, price: float = 100.0, signal: str = "BUY") -> dict:
    return {
        "ticker": ticker,
        "price": price,
        "signal": signal,
        "total_score": 3.5,
        "confidence": 65.0,
        "change_rate": 1.2,
        "technical_details": {
            "atr": 4.0,
            "ema12": 99.0,
            "macd_histogram": 0.3,
            # 분석 저장 시 내려온 sigma_data(straddle 일일 fresh) — ReportBuilder 가 이 스냅샷 사용.
            "sigma_data": {"straddle": {"expected_move": 10.0}},
        },
    }


def test_report_format_ticker_includes_levels_and_signals():
    rb = ReportBuilder(db=object())
    sr = _sigma(110, 90, price=100)
    levels = compute_actionable_levels(
        100, sr, {"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.3}
    )
    text = rb._format_ticker("AAPL", _make_row("AAPL"), "BUY", "HOLD", levels)
    assert "AAPL" in text
    assert "BUY" in text
    assert "주봉 BUY" in text and "월봉 HOLD" in text
    assert "목표" in text and "매수" in text and "손절" in text
    assert "불타기진입" in text  # momentum_fire True


def test_report_format_ticker_no_levels():
    rb = ReportBuilder(db=object())
    text = rb._format_ticker("AAPL", _make_row("AAPL"), None, None, None)
    assert "AAPL" in text
    assert "다기간" not in text
    assert "목표" not in text


def test_report_split_short_returns_single():
    rb = ReportBuilder(db=object())
    msgs = rb._split("short text")
    assert len(msgs) == 1
    assert msgs[0].text == "short text"


def test_report_split_long_splits_by_line():
    rb = ReportBuilder(db=object())
    # 4000자 초과 생성 (줄 단위 분할 검증)
    long_line = "x" * 300
    text = "\n".join([long_line] * 20)  # 300*20 + 줄바꿈 = 6000+ 자
    msgs = rb._split(text)
    assert len(msgs) >= 2
    # 각 메시지는 4000자 이하
    for m in msgs:
        assert len(m.text) <= 4000
    # 전체 내용 보존 (순서대로 이어 붙이면 원본 복원)
    rejoined = "\n".join(m.text for m in msgs)
    assert "x" * 300 in rejoined


# ── _sigma_range_from_snapshot (straddle 우선, WEM fallback) ────────────────


def test_sigma_range_from_snapshot_straddle():
    """straddle.expected_move → 현재가 기준 ±1σ."""
    from app.stock.report import _sigma_range_from_snapshot

    sr = _sigma_range_from_snapshot({"straddle": {"expected_move": 10.0}}, price=100.0)
    assert sr is not None
    assert sr.upper_1sigma == pytest.approx(110.0)
    assert sr.lower_1sigma == pytest.approx(90.0)


def test_sigma_range_from_snapshot_wem_fallback():
    """straddle 부재 시 WEM snapshot 의 ±1σ 사용."""
    from app.stock.report import _sigma_range_from_snapshot

    sr = _sigma_range_from_snapshot(
        {"weekly_expected_move": {"upper_1sigma": 110.0, "lower_1sigma": 90.0, "center": 100.0}},
        price=100.0,
    )
    assert sr is not None
    assert sr.upper_1sigma == pytest.approx(110.0)
    assert sr.lower_1sigma == pytest.approx(90.0)


def test_sigma_range_from_snapshot_none_when_empty():
    """straddle/WEM 모두 없으면 None (levels 산출 스킵)."""
    from app.stock.report import _sigma_range_from_snapshot

    assert _sigma_range_from_snapshot({}, price=100.0) is None
    assert _sigma_range_from_snapshot({"straddle": {}}, price=100.0) is None


def test_report_header_has_title():
    rb = ReportBuilder(db=object())
    header = rb._header()
    assert "주식 일간 분석 리포트" in header


# ── T5: ReportBuilder.run 통합 (의존성 주입) ───────────────────────────────


class _FakeRepo:
    """AnalysisResultRepo stub."""

    def __init__(self, rows_1d, aux=None):
        self._rows_1d = rows_1d
        self._aux = aux or {}

    async def find_by_tickers(self, tickers, timeframe="1D"):
        src = self._rows_1d if timeframe == "1D" else self._aux
        out = []
        for t in tickers:
            out.extend(src.get(t, []))
        return out


class _FakeWlRepo:
    def __init__(self, groups):
        self._groups = groups
        self._flat = {}
        for grp, tklist in groups.items():
            for t in tklist:
                self._flat[t] = grp

    async def find_all(self, active_only=False, group=None):
        if group is None:
            return []
        return []  # 사용 안 함 — _resolve_groups 가 group 인자로 호출

    async def find_by_ticker(self, ticker):
        return None


class _DispatchRecorder:
    def __init__(self):
        self.calls = []

    async def dispatch(self, category, purpose, payload_key, message):
        self.calls.append((category, purpose, payload_key, message.text))


async def _run_builder(monkeypatch, groups, rows_1d, aux=None, split=None):
    """ReportBuilder.run 을 의존성 주입으로 실행. dispatch 기록 반환.

    split: _split 오버라이드(분할 테스트용). None 이면 원본 분할 로직.
    """
    rb = ReportBuilder(db=object())
    fake_ar = _FakeRepo(rows_1d, aux)
    rec = _DispatchRecorder()

    # 원본 _build_group_block 을 미리 저장 (패치 후에도 실제 로직 호출용)
    orig_build = rb._build_group_block

    # _resolve_groups: groups dict 그대로 반환
    async def _resolve(wl, tickers):
        return {k: v for k, v in groups.items() if v}
    monkeypatch.setattr(rb, "_resolve_groups", _resolve)

    # _build_group_block: 원본 로직 사용하되 repo 주입 (재귀 방지: orig_build 호출)
    async def _build(gn, tk, ar):
        return await orig_build(gn, tk, fake_ar)
    monkeypatch.setattr(rb, "_build_group_block", _build)

    if split is not None:
        monkeypatch.setattr(rb, "_split", split)

    # NotifyRouter 치환 — run() 내에서 from app.core.notify.router import NotifyRouter 로 가져오므로
    # 해당 모듈의 심볼을 패치해야 함.
    import app.core.notify.router as router_mod
    monkeypatch.setattr(
        router_mod, "NotifyRouter", lambda db: rec, raising=False
    )
    result = await rb.run()
    return result, rec


@pytest.mark.asyncio
async def test_builder_run_dispatches_with_disclaimer(monkeypatch):
    groups = {"individual": ["AAPL"], "market": [], "sector": []}
    rows = {"AAPL": [_make_row("AAPL")]}
    result, rec = await _run_builder(monkeypatch, groups, rows)
    assert result is not None
    assert "AAPL" in result
    assert REPORT_LINK in result
    assert len(rec.calls) == 1
    cat, pur, key, txt = rec.calls[0]
    assert cat == "stock" and pur == "daily-report"
    assert key.startswith("stock:daily-report:")
    assert ":part-" not in key  # 단일 메시지는 suffix 없음
    assert "개별종목" in txt


@pytest.mark.asyncio
async def test_builder_run_no_data_returns_none(monkeypatch):
    groups = {"individual": ["AAPL"]}
    rows = {}  # 분석 행 없음
    result, rec = await _run_builder(monkeypatch, groups, rows)
    assert result is None
    assert rec.calls == []


@pytest.mark.asyncio
async def test_builder_run_multi_group(monkeypatch):
    groups = {
        "market": ["^NDX"],
        "sector": ["XLK"],
        "individual": ["AAPL"],
    }
    rows = {
        "^NDX": [_make_row("^NDX", price=15000)],
        "XLK": [_make_row("XLK", price=200)],
        "AAPL": [_make_row("AAPL")],
    }
    result, rec = await _run_builder(monkeypatch, groups, rows)
    assert result is not None
    assert "시장층" in result and "섹터층" in result and "개별종목" in result
    assert "^NDX" in result and "XLK" in result and "AAPL" in result


@pytest.mark.asyncio
async def test_builder_run_split_dispatches_part_keys(monkeypatch):
    """4000자 초과 분할 시 파트별 payload_key 로 발송 — NotifyRouter dedup 잘림 방지 (#184)."""
    from app.core.notify import NotifyMessage

    groups = {"individual": ["AAPL"]}
    rows = {"AAPL": [_make_row("AAPL")]}
    # _split 을 2개 메시지로 강제 → run() 의 part-suffix 루프 검증
    def _forced(text):
        return [NotifyMessage(text="A"), NotifyMessage(text="B")]

    result, rec = await _run_builder(monkeypatch, groups, rows, split=_forced)

    assert len(rec.calls) == 2
    keys = [c[2] for c in rec.calls]
    assert all(":part-" in k for k in keys)
    assert keys[0].endswith(":part-1") and keys[1].endswith(":part-2")
    assert keys[0] != keys[1]


# ── T6(#149): _analysis_row_to_report + _enrich_report_levels ────────────────


def _make_row_with_sigma(ticker: str, price: float = 100.0) -> dict:
    """sigma_data 가 technical_details 에 저장된 분석 행."""
    return {
        "ticker": ticker,
        "exchange": "NAS",
        "price": price,
        "change": 1.0,
        "change_rate": 1.0,
        "signal": "BUY",
        "total_score": 3.5,
        "confidence": 65.0,
        "market_regime": "bull",
        "technical_scores": {"rsi": 10},
        "technical_details": {
            "atr": 4.0,
            "ema12": 99.0,
            "macd_histogram": 0.5,
            "sigma_data": {
                "weekly_expected_move": {
                    "upper_1sigma": 110.0,
                    "lower_1sigma": 90.0,
                    "expected_move_pct": 10.0,
                    "sigma_position": "NEAR_CENTER",
                    "sigma_signal": "NEUTRAL",
                    "sigma_confidence": 0.3,
                    "expected_move_high": 110.0,
                    "expected_move_low": 90.0,
                },
            },
        },
        "analyzed_at": None,
    }


class _FakeWlRepoWithGroup:
    """WatchlistRepo stub — find_by_ticker 가 group 을 가진 item 반환."""

    def __init__(self, group_map: dict[str, str]):
        self._group_map = group_map

    async def find_by_ticker(self, ticker):
        from app.stock.models import WatchlistItem

        grp = self._group_map.get(ticker)
        if grp is None:
            return None
        return WatchlistItem(ticker=ticker, group=grp)


class _FakeWemRepoEmpty:
    """WEMRepo stub — sigma_data 가 이미 행에 있어 find_by_ticker 호출 안 됨."""

    async def find_by_ticker(self, ticker, limit=1):
        return []


def test_analysis_row_to_report_base_fields():
    """_analysis_row_to_report 동기 부분 — actionable_levels 미포함(None)."""

    row = _make_row_with_sigma("AAPL")
    report = _analysis_row_to_report(row)
    assert report.ticker == "AAPL"
    assert report.price == 100.0
    # 동기 변환만 수행 → actionable_levels/group 은 아직 None
    assert report.actionable_levels is None
    assert report.group is None
    assert report.hit_rate is None  # 스키마 한계 (#148 T8)
    # sigma 는 저장된 snapshot 으로 채워짐
    assert report.sigma is not None


@pytest.mark.asyncio
async def test_enrich_report_levels_populates_actionable_and_group():
    """sigma_data + tech_details 있을 때 actionable_levels + group 산출."""

    row = _make_row_with_sigma("AAPL", price=100.0)
    report = _analysis_row_to_report(row)
    wl_repo = _FakeWlRepoWithGroup({"AAPL": "individual"})
    wem_repo = _FakeWemRepoEmpty()

    await _enrich_report_levels(report, row, wl_repo, wem_repo)

    assert report.actionable_levels is not None
    assert report.actionable_levels.target_price == 110.0  # upper_1sigma
    assert report.actionable_levels.buy_zone == 90.0       # lower_1sigma
    assert report.actionable_levels.stop_loss == pytest.approx(90 - 1.5 * 4.0)  # 84.0 (진입가 기준)
    assert report.actionable_levels.momentum_fire is True  # price>ema12 & macd>0
    assert report.group == "individual"


@pytest.mark.asyncio
async def test_enrich_report_levels_no_sigma_data_falls_back_to_wem():
    """sigma_data 없으면 WEMRepo 로 sigma range 산출."""

    row = {
        "ticker": "AAPL",
        "exchange": "NAS",
        "price": 100.0,
        "change": 1.0,
        "change_rate": 1.0,
        "signal": "BUY",
        "total_score": 3.5,
        "confidence": 65.0,
        "market_regime": "bull",
        "technical_scores": {},
        "technical_details": {"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.5},
        "analyzed_at": None,
    }
    report = _analysis_row_to_report(row)

    class _WemRepoWithData:
        async def find_by_ticker(self, ticker, limit=1):
            return [WeeklyExpectedMove(
                ticker=ticker, expected_move_high=110, expected_move_low=90
            )]

    wl_repo = _FakeWlRepoWithGroup({"AAPL": "sector"})
    await _enrich_report_levels(report, row, wl_repo, _WemRepoWithData())

    assert report.actionable_levels is not None
    assert report.actionable_levels.target_price == 110.0
    assert report.actionable_levels.buy_zone == 90.0
    assert report.group == "sector"


@pytest.mark.asyncio
async def test_enrich_report_levels_none_when_no_sigma_and_no_wem():
    """sigma_data 도 WEM 도 없으면 actionable_levels=None (정상 스킵)."""

    row = {
        "ticker": "AAPL",
        "exchange": "NAS",
        "price": 100.0,
        "change": 0.0,
        "change_rate": 0.0,
        "signal": "NEUTRAL",
        "total_score": 0.0,
        "confidence": 50.0,
        "market_regime": "neutral",
        "technical_scores": {},
        "technical_details": {"atr": 4.0, "ema12": 99.0, "macd_histogram": 0.5},
        "analyzed_at": None,
    }
    report = _analysis_row_to_report(row)
    wl_repo = _FakeWlRepoWithGroup({})  # group 매핑 없음
    await _enrich_report_levels(report, row, wl_repo, _FakeWemRepoEmpty())

    assert report.actionable_levels is None
    assert report.group is None  # watchlist 에도 없음


@pytest.mark.asyncio
async def test_enrich_report_levels_group_none_when_not_in_watchlist():
    """watchlist 에 없는 종목 → group=None."""

    row = _make_row_with_sigma("UNKNOWN")
    report = _analysis_row_to_report(row)
    wl_repo = _FakeWlRepoWithGroup({})  # 빈 매핑
    await _enrich_report_levels(report, row, wl_repo, _FakeWemRepoEmpty())

    # actionable_levels 는 sigma_data 있으므로 산출됨
    assert report.actionable_levels is not None
    assert report.group is None
