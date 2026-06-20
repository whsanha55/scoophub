# stock/report.py — 주식 일간 분석 리포트 빌더 (#147).
"""
ReportBuilder: 분석 완료 후 다계층(시장/섹터/개별) 리포트를 조립해 NotifyRouter 로 발신.

역할:
  1. compute_actionable_levels — sigma(±1σ) + ATR 기반 목표가/매수가/손절가/불타기 산출
  2. ReportBuilder.run — group별 analysis_results(1D/1W/1M) 조회 → 계층별 텍스트 블록 조립
     → NotifyRouter.dispatch("stock", "daily-report", payload_key, msg) 연쇄

발신 경로는 크롤(dispatch_crawl_notify)과 별개. notify/__init__.py 의 stock 스킵 라인은 건드리지 않음.
"""
from __future__ import annotations

import html
import logging
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)

# Telegram 메시지 한도(HTML). 여유를 두고 분할.
_TELEGRAM_MAX = 4000

DISCLAIMER = (
    "⚠️ 본 리포트는 기술적 지표 기반 자동 분석이며 투자 권유가 아닙니다. "
    "최종 판단은 본인 책임입니다."
)

# ATR 기반 손절가 배수. 일반적 day/swing 가이드라인(1.5×ATR).
_STOP_ATR_MULT = 1.5


@dataclass
class ActionableLevels:
    """액션러블 거래 레벨. 산출 불가 시 None."""

    target_price: float | None = None      # 목표가 (+1σ)
    buy_zone: float | None = None          # 매수 구간 (하단: -1σ or BB 하단)
    stop_loss: float | None = None         # 손절가 (price - 1.5×ATR)
    momentum_fire: bool = False            # 불타기 진입 (price>EMA12 & MACD hist>0)

    def to_dict(self) -> dict:
        return asdict(self)


def compute_actionable_levels(
    price: float,
    sigma_range: object | None,
    tech_details: dict | None,
) -> ActionableLevels | None:
    """sigma range + 기술 지표로 액션러블 레벨 산출.

    Args:
        price: 현재가. 0 이하 → None(산출 불가).
        sigma_range: app.stock.models.SigmaRange (upper_1sigma/lower_1sigma 보유) or None.
        tech_details: 분석 technical_details dict (atr, ema12, macd_histogram 키 포함) or None.

    Returns:
        ActionableLevels. price==0 / sigma 미존재 → None.
    """
    if price <= 0:
        return None
    if sigma_range is None:
        return None

    upper_1 = getattr(sigma_range, "upper_1sigma", None)
    lower_1 = getattr(sigma_range, "lower_1sigma", None)
    if upper_1 is None or lower_1 is None:
        return None

    # 손절가: ATR 기반. tech_details 미제공 시 None.
    stop_loss = None
    atr_val = None
    ema12 = None
    macd_hist = None
    if tech_details:
        try:
            atr_val = float(tech_details.get("atr", 0) or 0)
            ema12 = float(tech_details.get("ema12", 0) or 0)
            macd_hist = float(tech_details.get("macd_histogram", 0) or 0)
        except (TypeError, ValueError):
            atr_val = atr_val or 0
            ema12 = ema12 or 0
            macd_hist = macd_hist or 0

    if atr_val and atr_val > 0:
        stop_loss = price - _STOP_ATR_MULT * atr_val

    # 불타기 진입: price>EMA12 & MACD hist>0 (상승 모멘텀)
    fire = bool(ema12 and macd_hist is not None and price > ema12 and macd_hist > 0)

    return ActionableLevels(
        target_price=upper_1,
        buy_zone=lower_1,
        stop_loss=stop_loss,
        momentum_fire=fire,
    )


# ── ReportBuilder ────────────────────────────────────────────────────────────


def _fmt_price(v: float | None) -> str:
    if v is None:
        return "N/A"
    return f"${v:,.2f}"


class ReportBuilder:
    """분석 결과 기반 일간 리포트 조립 + 발신 연쇄."""

    def __init__(self, db: "Database") -> None:
        self.db = db

    async def run(self, tickers: list[str] | None = None) -> str | None:
        """리포트 빌드 + 발신. 빈 데이터 시 None 반환(발신 스킵).

        Args:
            tickers: 특정 티커만. None 시 전체 group(market/sector/individual) 대상.
        """
        from app.core.notify import NotifyMessage
        from app.core.notify.router import NotifyRouter
        from app.stock.models import compute_sigma_range
        from app.stock.repository import AnalysisResultRepo, WatchlistRepo, WeeklyExpectedMoveRepo

        ar_repo = AnalysisResultRepo(self.db)
        wl_repo = WatchlistRepo(self.db)
        wem_repo = WeeklyExpectedMoveRepo(self.db)

        # group별 티커 분류. tickers 지정 시 해당 티커들의 group을 watchlist에서 조회.
        groups = await self._resolve_groups(wl_repo, tickers)
        if not groups:
            logger.info("ReportBuilder.run: no watchlist items — skip report")
            return None

        blocks: list[str] = []
        for group_name in ("market", "sector", "individual"):
            group_tickers = groups.get(group_name, [])
            if not group_tickers:
                continue
            block = await self._build_group_block(
                group_name, group_tickers, ar_repo, wem_repo, compute_sigma_range
            )
            if block:
                blocks.append(block)

        if not blocks:
            logger.info("ReportBuilder.run: no analysis data to report — skip")
            return None

        header = self._header()
        body = "\n\n".join(blocks)
        full = f"{header}\n\n{body}\n\n{DISCLAIMER}"

        # 날짜 기반 dedup 키 (하루 1회 발신).
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        payload_key = f"stock:daily-report:{today}"

        # 4096자 초과 시 분할. _split 이 이미 NotifyMessage 리스트 반환.
        messages = self._split(full)
        router = NotifyRouter(self.db)
        for message in messages:
            await router.dispatch(
                "stock", "daily-report", payload_key, message
            )
        return full

    async def _resolve_groups(
        self, wl_repo, tickers: list[str] | None
    ) -> dict[str, list[str]]:
        """group → ticker list 매핑. tickers 지정 시 해당 티커의 group 사용."""
        if tickers:
            # 지정 티커의 group을 watchlist에서 조회해서 분류.
            result: dict[str, list[str]] = {"market": [], "sector": [], "individual": []}
            for t in tickers:
                item = await wl_repo.find_by_ticker(t.upper())
                grp = (item.group if item else "individual") or "individual"
                result.setdefault(grp, []).append(t.upper())
            return {k: v for k, v in result.items() if v}

        groups: dict[str, list[str]] = {"market": [], "sector": [], "individual": []}
        for grp in ("market", "sector", "individual"):
            items = await wl_repo.find_all(active_only=True, group=grp)
            groups[grp] = [it.ticker for it in items]
        return {k: v for k, v in groups.items() if v}

    async def _build_group_block(
        self,
        group_name: str,
        tickers: list[str],
        ar_repo,
        wem_repo,
        compute_sigma_range,
    ) -> str:
        """단일 group에 대한 1D/1W/1M 분석 블록 조립. 빈 시 빈 문자열."""
        title_map = {
            "market": "🌍 시장층",
            "sector": "🏭 섹터층",
            "individual": "📈 개별종목",
        }
        title = title_map.get(group_name, group_name)

        # 1D 분석 행 조회 (주 기간). 1W/1M는 보조.
        rows_1d = await ar_repo.find_by_tickers(tickers, "1D")
        if not rows_1d:
            return ""

        lines = [f"<b>{title}</b>"]

        for row in rows_1d:
            ticker = row["ticker"]
            price = float(row.get("price", 0) or 0)
            if price <= 0:
                continue

            # 보조 기간 (1W/1M) — 별도 조회.
            aux_w = await self._aux_signal(ar_repo, ticker, "1W")
            aux_m = await self._aux_signal(ar_repo, ticker, "1M")

            # sigma range 산출 (WEM 기반).
            sigma_range = await self._sigma_range_for(
                wem_repo, compute_sigma_range, ticker, price
            )
            tech_details = row.get("technical_details") or {}
            levels = compute_actionable_levels(price, sigma_range, tech_details)

            lines.append(self._format_ticker(ticker, row, aux_w, aux_m, levels))

        if len(lines) <= 1:  # 제목만
            return ""
        return "\n".join(lines)

    async def _aux_signal(self, ar_repo, ticker: str, timeframe: str) -> str | None:
        """보조 기간(1W/1M) 시그널. 없으면 None."""
        try:
            rows = await ar_repo.find_by_tickers([ticker], timeframe)
        except Exception:
            return None
        if not rows:
            return None
        return rows[0].get("signal")

    async def _sigma_range_for(
        self, wem_repo, compute_sigma_range, ticker: str, price: float
    ):
        wem_list = await wem_repo.find_by_ticker(ticker, limit=1)
        if not wem_list:
            return None
        try:
            return compute_sigma_range(wem_list[0], price)
        except Exception as e:
            logger.warning("sigma range compute failed for %s: %s", ticker, e)
            return None

    def _header(self) -> str:
        now_kst = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        return f"<b>📊 주식 일간 분석 리포트</b>\n{now_kst}"

    def _format_ticker(
        self,
        ticker: str,
        row: dict,
        aux_w: str | None,
        aux_m: str | None,
        levels: ActionableLevels | None,
    ) -> str:
        signal = row.get("signal", "—")
        score = float(row.get("total_score", 0) or 0)
        conf = float(row.get("confidence", 0) or 0)
        change_rate = float(row.get("change_rate", 0) or 0)
        price = float(row.get("price", 0) or 0)

        chg = f"({change_rate:+.2f}%)" if change_rate else ""
        parts = [
            f"\n<b>{html.escape(ticker)}</b> {_fmt_price(price)} {chg}",
            f"  시그널: {signal}  점수: {score:+.1f}  신뢰도: {conf:.0f}%",
        ]
        aux_parts = []
        if aux_w:
            aux_parts.append(f"주봉 {aux_w}")
        if aux_m:
            aux_parts.append(f"월봉 {aux_m}")
        if aux_parts:
            parts.append("  다기간: " + " / ".join(aux_parts))

        if levels:
            lv_lines = []
            if levels.target_price is not None:
                lv_lines.append(f"목표 {_fmt_price(levels.target_price)}")
            if levels.buy_zone is not None:
                lv_lines.append(f"매수 {_fmt_price(levels.buy_zone)}")
            if levels.stop_loss is not None:
                lv_lines.append(f"손절 {_fmt_price(levels.stop_loss)}")
            if levels.momentum_fire:
                lv_lines.append("🔥불타기진입")
            if lv_lines:
                parts.append("  " + " | ".join(lv_lines))

        return "\n".join(parts)

    def _split(self, text: str) -> list:
        """4000자 초과 시 줄 단위 분할 → NotifyMessage 리스트."""
        from app.core.notify import NotifyMessage

        if len(text) <= _TELEGRAM_MAX:
            return [NotifyMessage(text=text)]

        messages: list = []
        buf = ""
        for line in text.split("\n"):
            if len(buf) + len(line) + 1 > _TELEGRAM_MAX and buf:
                messages.append(NotifyMessage(text=buf))
                buf = ""
            buf = (buf + "\n" + line) if buf else line
        if buf:
            messages.append(NotifyMessage(text=buf))
        return messages
