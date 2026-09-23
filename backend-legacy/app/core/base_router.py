# core/base_router.py
"""도메인 router 공통 로직을 담은 ABC 기반 클래스.

각 도메인 router(hacker_news, reddit, arxiv 등)의 중복 코드를 제거하기 위해
공통 패턴을 추상화합니다. 서브클래스는 클래스 속성만 설정하면 POST crawl
trigger 엔드포인트가 자동 등록됩니다.

사용 예:
    class HNRouter(BaseRouter):
        table_name = "hackernews"  # OpenAPI description 라벨용
        route_path = "/hacker-news"
        crawler_import = "app.community.hacker_news.crawler"
        crawler_class_name = "HackerNewsCrawler"
        api_tag = "Hacker News"
        order_by = "score DESC NULLS LAST"

    _base = HNRouter()
    router = _base.router
    _get_db = _base.get_db_fn
"""
from __future__ import annotations

import importlib
import logging
from abc import ABC
from datetime import datetime
from typing import TYPE_CHECKING, Any, Callable

from fastapi import APIRouter, Depends

from app.core.auth import get_super_user
from app.core.models import ApiResponse, ErrorDetail

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


def row_to_dict(row: Any) -> dict:
    """asyncpg Record → dict 변환. datetime은 ISO 8601 문자열로 직렬화.

    system/news 라우터 공통 헬퍼. config_router 처럼 JSONB 추가 디코딩이
    필요한 곳은 별도로 처리한다.
    """
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


class BaseRouter(ABC):
    """도메인 router의 공통 로직을 제공하는 추상 기반 클래스.

    서브클래스가 설정하는 클래스 속성:
        table_name: OpenAPI description 라벨용 문자열 (예: "hacker_news")
        route_path: URL 경로 (예: "/hacker-news")
        api_tag: OpenAPI 태그명 (예: "Hacker News")
        crawler_import: crawler 모듈 경로 (예: "app.community.hacker_news.crawler")
        crawler_class_name: crawler 클래스명 (예: "HackerNewsCrawler")
        order_by: 기본 정렬 (예: "score DESC NULLS LAST")
    """

    table_name: str
    route_path: str
    api_tag: str
    crawler_import: str
    crawler_class_name: str
    order_by: str = "created_at DESC NULLS LAST"

    def __init__(self) -> None:
        # router-level 인증 제거 — GET 조회 엔드포인트는 공개.
        # mutation(POST crawl trigger)은 _register_crawl_trigger에서 get_super_user 적용.
        self.router = APIRouter(prefix="/api")
        self._get_db_fn = self._make_get_db()
        self._register_crawl_trigger()

    # ── 모듈 레벨 _get_db 호환 함수 생성 ──────────────────────

    def _make_get_db(self) -> Callable[[], Database]:
        """wiring에서 dependency_overrides 가능한 plain 함수 객체 반환.

        FastAPI Depends()는 람다/바운드 메서드를 직접 사용할 수 없으므로,
        모듈 수준에서 override 가능한 함수 객체를 생성합니다.
        """
        raise NotImplementedError

    @property
    def get_db_fn(self) -> Callable[[], Database]:
        """wiring에서 ``from module import _get_db`` 대신 사용할 함수 객체."""
        return self._get_db_fn

    # ── POST crawl trigger 자동 등록 ─────────────────────────

    def _register_crawl_trigger(self) -> None:
        """POST ``/crawling/{route_path}`` 엔드포인트를 router에 등록."""
        route = f"/crawling{self.route_path}"
        tag = f"{self.api_tag} Crawling"
        summary = f"{self.api_tag} 크롤 수동 실행"
        table_label = self.table_name.replace("_", " ").title()
        description = (
            f"{table_label} 크롤러를 수동으로 실행합니다.\n\n"
            "스케줄과 무관하게 즉시 크롤을 트리거합니다."
        )

        # self를 캡처하는 클로저 대신, 인스턴스 참조를 지역 변수로 고정
        crawler_import = self.crawler_import
        crawler_class_name = self.crawler_class_name
        name = self.api_tag
        get_db = self._get_db_fn

        async def _trigger(db: Database = Depends(get_db)) -> ApiResponse:
            logger.info("manual %s crawl triggered", name)
            module = importlib.import_module(crawler_import)
            crawler_cls = getattr(module, crawler_class_name)
            instance = crawler_cls.from_config(db)
            result = await instance.run()
            if result is None:
                return ApiResponse(
                    success=False,
                    error=ErrorDetail(
                        code="crawl_failed",
                        message=f"{name} 크롤 실패",
                    ),
                )
            return ApiResponse(
                success=True,
                data={
                    "crawler": instance.name,
                    "crawler_detail": instance.detail,
                    "items_fetched": result.items_fetched,
                    "items_new": result.items_new,
                    "errors": result.errors or None,
                },
            )

        # FastAPI가 엔드포인트 함수명을 OpenAPI operation ID로 사용하므로
        # 고유한 이름 부여
        safe_name = self.route_path.strip("/").replace("-", "_")
        _trigger.__name__ = f"crawling_{safe_name}"
        _trigger.__qualname__ = f"crawling_{safe_name}"

        self.router.post(
            route,
            summary=summary,
            description=description,
            tags=[tag],
            dependencies=[Depends(get_super_user)],
        )(_trigger)

    # ── 헬퍼 메서드 ─────────────────────────────────────────

    @staticmethod
    def _row_to_dict(row: Any) -> dict:
        """asyncpg Record → dict 변환. 모듈 수준 row_to_dict 로 위임."""
        return row_to_dict(row)

    async def get_latest(self, db: Database) -> Any | None:
        """``table_name``에서 MAX(fetched_at) 조회. 없으면 None."""
        row = await db.fetchrow(
            f"SELECT MAX(fetched_at) AS latest FROM {self.table_name}"
        )
        if not row or not row["latest"]:
            return None
        return row["latest"]

    async def query_items(
        self,
        db: Database,
        conditions: list[str],
        params: list,
        limit: int,
    ) -> list[dict]:
        """WHERE 조건 + 기본 정렬 + LIMIT으로 조회 후 dict 리스트 반환."""
        where = " AND ".join(conditions)
        idx = len(params) + 1
        rows = await db.fetch(
            f"SELECT * FROM {self.table_name} WHERE {where} "
            f"ORDER BY {self.order_by} LIMIT ${idx}",
            *params,
            limit,
        )
        return [self._row_to_dict(r) for r in rows]

    @staticmethod
    def empty_response() -> ApiResponse:
        """데이터가 없을 때 반환할 공통 응답."""
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    @staticmethod
    def items_response(items: list[dict]) -> ApiResponse:
        """조회 결과를 표준 응답으로 래핑."""
        return ApiResponse(
            success=True,
            data=items,
            meta={"total": len(items), "returned": len(items)},
        )
