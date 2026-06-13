# app/crawl_data/repo.py
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from app.core.database import Database

# 동일 (category, purpose, key) 재크롤 시 최신 응답으로 덮어쓴다.
_UPSERT_SQL = """
INSERT INTO crawl_data (category, purpose, key, date_at, response)
VALUES ($1, $2, $3, $4, $5::jsonb)
ON CONFLICT (category, purpose, key)
DO UPDATE SET response   = EXCLUDED.response,
              date_at    = EXCLUDED.date_at,
              updated_at = now()
RETURNING id, updated_at
"""


class CrawlDataRepo:
    """generic crawl_data 테이블 repo.

    모든 "외부 크롤 후 최신 응답 저장" 도메인이 공유. DDL 추가 없이
    category/purpose/key 조합으로 upsert/조회.
    """

    def __init__(self, db: Database):
        self.db = db

    async def upsert(
        self,
        *,
        category: str,
        purpose: str,
        key: str,
        response: Any,
        date_at: datetime | None = None,
    ) -> int:
        """크롤 응답을 upsert 후 row id 반환.

        동일 key가 이미 존재하면 response/date_at/updated_at 갱신(최신 응답으로 덮어씀).
        date_at 미지정 시 현재 시각(UTC) 사용.
        """
        when = date_at if date_at is not None else datetime.now(timezone.utc)
        row = await self.db.fetchrow(
            _UPSERT_SQL,
            category,
            purpose,
            key,
            when,
            json.dumps(response),
        )
        return row["id"]

    async def latest(self, *, category: str, purpose: str) -> dict[str, Any] | None:
        """특정 category/purpose의 최신 1건 반환 (없으면 None)."""
        row = await self.db.fetchrow(
            "SELECT id, key, date_at, response, updated_at "
            "FROM crawl_data WHERE category=$1 AND purpose=$2 "
            "ORDER BY date_at DESC LIMIT 1",
            category,
            purpose,
        )
        return _row_to_dict(row)

    async def get(
        self, *, category: str, purpose: str, key: str
    ) -> dict[str, Any] | None:
        """자연키 직접 조회 (없으면 None)."""
        row = await self.db.fetchrow(
            "SELECT id, key, date_at, response, updated_at "
            "FROM crawl_data WHERE category=$1 AND purpose=$2 AND key=$3",
            category,
            purpose,
            key,
        )
        return _row_to_dict(row)

    async def query_path(
        self,
        *,
        category: str,
        purpose: str,
        path: str,
        value: Any,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        """response JSONB 경로 값으로 필터.

        path 예: 'flightList' / 'meta.score'. asyncpg Record의 JSONB는 이미
        파싱된 dict/list로 반환되므로 app 단에서 비교하지 않고 DB측
        `(response->>'<leaf>') = $value` 로 좁힌다.
        """
        leaf = path.split(".")[-1]
        rows = await self.db.fetch(
            "SELECT id, key, date_at, response, updated_at "
            "FROM crawl_data "
            "WHERE category=$1 AND purpose=$2 "
            f"  AND (response #>> '{_json_path(path)}') = $3 "
            "ORDER BY date_at DESC LIMIT $4",
            category,
            purpose,
            str(value),
            limit,
        )
        return [_row_to_dict(r) for r in rows]


def _row_to_dict(row) -> dict[str, Any] | None:
    if row is None:
        return None
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "id": row["id"],
        "key": row["key"],
        "date_at": row["date_at"],
        "response": resp,
        "updated_at": row["updated_at"],
    }


def _json_path(path: str) -> str:
    """`a.b.c` → `{a,b,c}` (Postgres jsonb #>> path 텍스트 형식)."""
    parts = ",".join(path.split("."))
    return "{" + parts + "}"


async def upsert_crawl_data(
    db: Database,
    *,
    category: str,
    purpose: str,
    key: str,
    response: Any,
    date_at: datetime | None = None,
) -> int:
    """함수형 upsert 헬퍼 (CrawlDataRepo 인스턴스 없이 단발 사용)."""
    return await CrawlDataRepo(db).upsert(
        category=category, purpose=purpose, key=key, response=response, date_at=date_at
    )
