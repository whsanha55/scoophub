"""
Barchart 옵션 체인 스크래퍼 — 이식 가능한 독립 모듈.

Playwright로 세션(쿠키 + CSRF)을 1회 획득한 뒤,
REST API로 옵션 체인을 가져옵니다. 브라우저 상시 유지 불필요.

의존: playwright (pip install playwright && playwright install chromium)
"""
import asyncio
import json
import logging
import time
import urllib.request
import urllib.parse
from typing import Dict, List, Optional, Any

from playwright.async_api import async_playwright

logger = logging.getLogger(__name__)

# 기본 필드 — 필요시 확장 가능
DEFAULT_FIELDS = ",".join([
    "symbol", "baseSymbol", "strikePrice", "expirationDate",
    "moneyness", "bidPrice", "midpoint", "askPrice", "lastPrice",
    "priceChange", "percentChange", "volume", "openInterest",
    "openInterestChange", "delta", "impliedVolatility",
    "symbolType", "expirationType",
])


class BarchartSession:
    """Playwright 1회 실행으로 쿠키 + CSRF 토큰 획득. 이후 API 직접 호출."""

    BASE_URL = "https://www.barchart.com/proxies/core-api/v1"

    def __init__(self, user_agent: Optional[str] = None):
        self.user_agent = user_agent or (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        self.cookie_str: str = ""
        self.csrf_token: str = ""
        self._acquired_at: float = 0.0

    async def acquire(self, symbol: str = "SPY"):
        """페이지 1회 로드로 세션 획득."""
        logger.info("Acquiring Barchart session...")
        seg = self._symbol_type(symbol)
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"],
            )
            ctx = await browser.new_context(
                user_agent=self.user_agent,
                viewport={"width": 1280, "height": 800},
            )
            page = await ctx.new_page()

            # 이미지/CSS/폰트 차단 → 로딩 가속
            BLOCKED = {"image", "stylesheet", "font", "media"}
            async def _block(route):
                if route.request.resource_type in BLOCKED:
                    await route.abort()
                else:
                    await route.continue_()
            await page.route("**/*", _block)

            url = f"https://www.barchart.com/{seg}/quotes/{symbol}/overview"
            await page.goto(url, wait_until="domcontentloaded", timeout=60000)
            # CSRF 메타태그 렌더링 대기
            await asyncio.sleep(3)

            cookies = await ctx.cookies()
            self.cookie_str = "; ".join(f"{c['name']}={c['value']}" for c in cookies)
            self.csrf_token = await page.evaluate(
                "() => document.querySelector('meta[name=\"csrf-token\"]')?.getAttribute('content') || ''"
            )
            await browser.close()

        self._acquired_at = time.time()
        logger.info(f"Session acquired: {len(self.cookie_str)} chars cookie, csrf={self.csrf_token[:16]}...")

    @property
    def is_valid(self) -> bool:
        return bool(self.cookie_str and self.csrf_token)

    def _request(self, endpoint: str, params: Dict[str, str], referer: str) -> dict:
        qs = urllib.parse.urlencode(params)
        url = f"{self.BASE_URL}/{endpoint}?{qs}"
        req = urllib.request.Request(url, headers={
            "User-Agent": self.user_agent,
            "Accept": "application/json",
            "Cookie": self.cookie_str,
            "X-CSRF-TOKEN": self.csrf_token,
            "Referer": referer,
        })
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())

    # ── 옵션 체인 ──────────────────────────────────────────────

    def get_options_chain(
        self,
        symbol: str,
        expiration: str,
        expiration_type: str = "weekly",
        fields: Optional[str] = None,
    ) -> Dict[str, List[dict]]:
        """
        옵션 체인 조회.

        Args:
            symbol:           기초자산 티커 (예: "QQQ", "AAPL", "SPY")
            expiration:       만기일 "YYYY-MM-DD" (예: "2026-06-08")
            expiration_type:  "weekly" | "monthly"
            fields:           조회 필드 (기본: DEFAULT_FIELDS)

        Returns:
            {"calls": [...], "puts": [...], "total": int}
            각 항목은 fields에 요청한 필드 포함.
        """
        referer = (
            f"https://www.barchart.com/etfs-funds/quotes/{symbol}/options"
            f"?expiration={expiration}-{expiration_type[0]}"
        )
        data = self._request("options/get", {
            "baseSymbol": symbol,
            "fields": fields or DEFAULT_FIELDS,
            "expirationDate": expiration,
            "expirationType": expiration_type,
        }, referer)

        rows = data.get("data", [])
        calls = [r for r in rows if r.get("symbol", "").endswith("C")]
        puts = [r for r in rows if r.get("symbol", "").endswith("P")]
        total = data.get("total", 0)

        logger.info(f"[{symbol} {expiration}] {total} options: {len(calls)} calls, {len(puts)} puts")
        return {"calls": calls, "puts": puts, "total": total}

    # ── 익스피레이션 요약 ──────────────────────────────────────

    def get_expiration_summary(
        self,
        symbol: str,
        expiration: str,
        expiration_type: str = "weekly",
    ) -> dict:
        """특정 만기일의 콜/풋 볼륨, 미결제 약정 요약."""
        referer = f"https://www.barchart.com/etfs-funds/quotes/{symbol}/options"
        data = self._request("options-expirations/get", {
            "eq(expirationDate," + expiration + ")": "",
            "eq(expirationType," + expiration_type + ")": "",
            "fields": (
                "callVolume,putVolume,putCallVolumeRatio,"
                "callOpenInterest,putOpenInterest,putCallOpenInterestRatio,"
                "expirationDate,expirationType"
            ),
            "meta": "field.shortName,field.type,field.description",
            "baseSymbol": symbol,
        }, referer)

        items = data.get("data", [])
        return items[0] if items else {}

    # ── 심볼 타입 자동 감지 헬퍼 ────────────────────────────────

    @staticmethod
    def _symbol_type(symbol: str) -> str:
        """티커 → Barchart URL 경로 세그먼트 추정."""
        s = symbol.upper()
        # 주요 ETF
        if s in ("QQQ", "SPY", "IWM", "DIA", "GLD", "SLV", "TLT", "XLK", "XLF", "XLE",
                 "VXX", "UVXY", "HYG", "LQD", "EEM", "VTI", "VOO", "VEA", "BND"):
            return "etfs-funds"
        # 나스닥 4글자 이상은 보통 주식
        if len(s) >= 4:
            return "stocks"
        return "stocks"


# ── 편의 함수 ────────────────────────────────────────────────────

async def fetch_options(
    symbol: str,
    expiration: str,
    expiration_type: str = "weekly",
) -> Dict[str, Any]:
    """
    1회성 옵션 체인 조회. 세션 획득 + API 호출 후 즉시 종료.

    Usage:
        result = await fetch_options("QQQ", "2026-06-08")
        for call in result["calls"]:
            print(call["strikePrice"], call["lastPrice"])
    """
    session = BarchartSession()
    await session.acquire(symbol)
    chain = session.get_options_chain(symbol, expiration, expiration_type)
    summary = session.get_expiration_summary(symbol, expiration, expiration_type)
    chain["summary"] = summary
    return chain
