# app/core/base_rss_crawler.py
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import TYPE_CHECKING

import feedparser
import httpx

from app.core.base_crawler import BaseCrawler, CrawlResult

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


class BaseRssCrawler(BaseCrawler):
    """RSS 피드 파싱 공통 로직을 제공하는 기반 크롤러.

    서브클래스는 ``fetch()`` 만 구현하면 되며,
    ``parse_feed``, ``get_existing_urls``, ``upsert_entry`` 헬퍼를 사용할 수 있습니다.
    """

    async def parse_feed(
        self,
        url_or_content: str,
        *,
        use_httpx: bool = False,
        headers: dict | None = None,
    ) -> tuple[list[dict], list[str]]:
        """RSS 피드를 파싱해 엔트리 리스트를 반환합니다.

        Parameters
        ----------
        url_or_content:
            RSS 피드 URL 또는 이미 가져온 XML 문자열.
        use_httpx:
            ``True`` 면 ``httpx.AsyncClient`` 로 HTTP GET 후 파싱합니다.
            ``False`` 면 ``url_or_content`` 를 그대로 ``feedparser.parse`` 에 전달합니다.
        headers:
            ``use_httpx=True`` 일 때 사용할 추가 요청 헤더
            (예: ``If-None-Match``, ``If-Modified-Since``).

        Returns
        -------
        (entries, errors)
            ``entries`` 는 각 엔트리의 dict 리스트.
            ``errors`` 는 발생한 에러 메시지 리스트.
        """
        fetched_at = datetime.now(timezone.utc)
        entries: list[dict] = []
        errors: list[str] = []

        try:
            if use_httpx:
                async with httpx.AsyncClient(timeout=15) as client:
                    resp = await client.get(url_or_content, headers=headers or {})

                    if resp.status_code == 304:
                        return [], []

                    resp.raise_for_status()
                    content = resp.text
            else:
                content = url_or_content

            parsed = await asyncio.to_thread(feedparser.parse, content)

            for entry in parsed.entries:
                published = entry.get("published_parsed")
                published_at = (
                    datetime(*published[:6], tzinfo=timezone.utc)
                    if published
                    else fetched_at
                )
                entries.append({
                    "title": entry.get("title", ""),
                    "url": entry.get("link", ""),
                    "summary": entry.get("summary"),
                    "author": entry.get("author"),
                    "published_at": published_at,
                })
        except Exception as e:
            errors.append(str(e))
            logger.warning("parse_feed failed for %s: %s", url_or_content, e)

        return entries, errors

    async def get_existing_urls(
        self, table: str, url_column: str, urls: list[str]
    ) -> set[str]:
        """테이블에서 이미 존재하는 URL 집합을 조회합니다."""
        if not urls:
            return set()
        rows = await self.db.fetch(
            f"SELECT {url_column} FROM {table} WHERE {url_column} = ANY($1)",
            urls,
        )
        return {r[url_column] for r in rows}

    async def upsert_entry(
        self,
        table: str,
        columns: list[str],
        values: list,
        conflict_column: str,
        fetched_at: datetime,
    ) -> bool:
        """단일 엔트리를 UPSERT 합니다. 신규 삽입이면 ``True`` 를 반환합니다.

        ``ON CONFLICT (conflict_column) DO UPDATE SET fetched_at = EXCLUDED.fetched_at``
        패턴을 사용합니다.

        Parameters
        ----------
        table:
            대상 테이블명.
        columns:
            컬럼명 리스트 (``fetched_at`` 포함 권장).
        values:
            ``columns`` 와 동일 순서의 값 리스트.
        conflict_column:
            ``ON CONFLICT`` 기준 컬럼.
        fetched_at:
            UPSERT 시 갱신할 ``fetched_at`` 값.
        """
        col_str = ", ".join(columns)
        placeholders = ", ".join(f"${i}" for i in range(1, len(values) + 1))
        query = (
            f"INSERT INTO {table} ({col_str}) "
            f"VALUES ({placeholders}) "
            f"ON CONFLICT ({conflict_column}) DO UPDATE SET "
            f"fetched_at = EXCLUDED.fetched_at"
        )
        await self.db.execute(query, *values)
        return True
