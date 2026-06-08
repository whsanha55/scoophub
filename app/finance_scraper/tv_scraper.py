import asyncio
import logging
import re
import json
import random
import time
from typing import Dict, Any, Optional, List
from playwright.async_api import (
    async_playwright, Browser, BrowserContext, Page, WebSocket, Playwright,
)

logger = logging.getLogger(__name__)


class TradingViewScraper:
    """단일 종목 1탭 스크래퍼. 공유 BrowserContext에 탭만 추가한다."""

    def __init__(self, symbol: str, data_store: Dict[str, Any], browser_context: BrowserContext):
        self.symbol = symbol.strip().upper()
        self.data_store = data_store
        self.browser_context = browser_context
        self.page: Optional[Page] = None
        self.task: Optional[asyncio.Task] = None
        self._is_running = False
        self.last_parsed_time = asyncio.get_event_loop().time()

    # --- TV 프로토콜 파싱 ---
    def _parse_packets(self, raw_data: str) -> list:
        """~m~<길이>~m~<JSON> 스트림에서 개별 JSON 패킷 분리."""
        return [p.strip() for p in re.split(r"~m~\d+~m~", raw_data) if p.strip()]

    def _handle_frame(self, payload: Any):
        """가로챈 소켓 프레임 → 최신 시세 추출."""
        try:
            if not isinstance(payload, str):
                return
            for packet in self._parse_packets(payload):
                # 하트비트(핑퐁) 스킵
                if packet.startswith("~h~") or '{"m":"h"' in packet:
                    continue
                try:
                    data = json.loads(packet)
                except json.JSONDecodeError:
                    continue
                # qsd = Quote Symbol Data (실시간 시세 패킷)
                if data.get("m") != "qsd" or len(data.get("p", [])) <= 1:
                    continue

                payload_data = data["p"][1]
                ticker_name = payload_data.get("n", "")
                if self.symbol not in ticker_name and ticker_name not in self.symbol:
                    continue

                values = payload_data.get("v", {})
                current = self.data_store.get(self.symbol, {
                    "symbol": self.symbol, "price": 0.0, "volume": 0.0,
                    "timestamp": int(time.time() * 1000), "raw": {},
                })
                if "lp" in values:      # lp = Last Price (현재가)
                    current["price"] = values["lp"]
                if "volume" in values:
                    current["volume"] = values["volume"]
                current["timestamp"] = int(time.time() * 1000)
                current["raw"] = payload_data
                current["last_updated"] = asyncio.get_event_loop().time()
                self.last_parsed_time = current["last_updated"]
                self.data_store[self.symbol] = current

                ch, chp = values.get("ch", 0.0), values.get("chp", 0.0)
                logger.info(
                    f"[{self.symbol}] tick: price={current['price']} "
                    f"vol={current['volume']} chg={chp:.2f}% ({ch})"
                )
        except Exception as e:
            logger.error(f"[{self.symbol}] frame parse error: {e}")

    async def _on_websocket(self, ws: WebSocket):
        if "websocket" in ws.url or "tradingview.com" in ws.url:
            logger.info(f"[{self.symbol}] socket detected: {ws.url[:60]}...")
            ws.on("framereceived", lambda p: self._handle_frame(p))

    async def _run_loop(self):
        self.last_parsed_time = asyncio.get_event_loop().time()
        BLOCKED = {"image", "media", "font", "stylesheet"}

        while self._is_running:
            self.page = None
            try:
                self.page = await self.browser_context.new_page()
                self.page.on("websocket", lambda ws: asyncio.create_task(self._on_websocket(ws)))

                async def _block(route):
                    if route.request.resource_type in BLOCKED:
                        await route.abort()
                    else:
                        await route.continue_()
                await self.page.route("**/*", _block)

                url = f"https://www.tradingview.com/chart/?symbol={self.symbol}"
                logger.info(f"[{self.symbol}] open tab: {url}")
                await self.page.goto(url, wait_until="domcontentloaded", timeout=60000)
                self.last_parsed_time = asyncio.get_event_loop().time()

                while self._is_running:
                    await asyncio.sleep(5)
                    # Anti-Idle: 마우스 미세 이동
                    if self.page and not self.page.is_closed():
                        try:
                            await self.page.mouse.move(random.randint(100, 700), random.randint(100, 500))
                        except Exception:
                            pass
                    # Watchdog: 30초 무반응 시 탭 재기동
                    if asyncio.get_event_loop().time() - self.last_parsed_time > 30.0:
                        logger.warning(f"[{self.symbol}] watchdog: 30s idle, restarting tab")
                        raise TimeoutError("socket inactivity")
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"[{self.symbol}] tab broke, retry in 3s: {e}")
                await asyncio.sleep(3.0)
            finally:
                if self.page and not self.page.is_closed():
                    try:
                        await self.page.close()
                    except Exception:
                        pass
                self.page = None

    async def start(self):
        self._is_running = True
        self.task = asyncio.create_task(self._run_loop())
        logger.info(f"[{self.symbol}] scraper started")

    async def stop(self):
        self._is_running = False
        if self.task:
            self.task.cancel()
            try:
                await self.task
            except asyncio.CancelledError:
                pass
        if self.page and not self.page.is_closed():
            try:
                await self.page.close()
            except Exception:
                pass
        logger.info(f"[{self.symbol}] scraper stopped")


class TradingViewManager:
    """공유 브라우저 1개 + 종목별 탭 관리 + 크래시 자동 복구."""

    def __init__(self, symbols: Optional[List[str]] = None):
        self.symbols = symbols or []
        self.data: Dict[str, Any] = {}             # symbol -> 최신 시세
        self.scrapers: Dict[str, TradingViewScraper] = {}
        self._pw: Optional[Playwright] = None
        self._browser: Optional[Browser] = None
        self._ctx: Optional[BrowserContext] = None
        self._health_task: Optional[asyncio.Task] = None

    async def _create_browser(self):
        self._pw = await async_playwright().start()
        self._browser = await self._pw.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage",
                "--disable-gpu", "--blink-settings=imagesEnabled=false",
                "--mute-audio", "--no-first-run",
            ],
        )
        self._ctx = await self._browser.new_context(
            user_agent=("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        "AppleWebKit/537.36 (KHTML, like Gecko) "
                        "Chrome/120.0.0.0 Safari/537.36"),
            viewport={"width": 1280, "height": 800},
        )

    async def _destroy_browser(self):
        for obj, closer in ((self._ctx, "close"), (self._browser, "close"), (self._pw, "stop")):
            if obj:
                try:
                    await getattr(obj, closer)()
                except Exception:
                    pass
        self._ctx = self._browser = self._pw = None

    async def subscribe(self, symbol: str):
        s = symbol.strip().upper()
        if s in self.scrapers or not self._ctx:
            return
        scraper = TradingViewScraper(s, self.data, self._ctx)
        await scraper.start()
        self.scrapers[s] = scraper

    async def unsubscribe(self, symbol: str):
        s = symbol.strip().upper()
        scraper = self.scrapers.pop(s, None)
        if scraper:
            await scraper.stop()
        self.data.pop(s, None)

    def get(self, symbol: str) -> Optional[Dict[str, Any]]:
        d = self.data.get(symbol.strip().upper())
        if not d:
            return None
        out = d.copy()
        out.pop("last_updated", None)        # 내부 관리 필드 제거
        return out

    async def _health_loop(self):
        while True:
            await asyncio.sleep(15)
            if self._browser is None:
                continue
            try:
                _ = self._browser.contexts                # 살아있는지 touch
            except Exception as e:
                logger.error(f"browser crashed: {e}, recovering...")
                for sc in self.scrapers.values():
                    try:
                        await sc.stop()
                    except Exception:
                        pass
                self.scrapers.clear()
                await self._destroy_browser()
                await self._create_browser()
                for sym in list(self.data.keys()) + self.symbols:
                    await self.subscribe(sym)
                logger.info("browser recovered")

    async def start(self):
        await self._create_browser()
        for sym in self.symbols:
            await self.subscribe(sym)
            await asyncio.sleep(1.0)               # 탭 생성 간격 (부하 분산)
        self._health_task = asyncio.create_task(self._health_loop())

    async def stop(self):
        if self._health_task:
            self._health_task.cancel()
        for sc in self.scrapers.values():
            await sc.stop()
        await self._destroy_browser()
