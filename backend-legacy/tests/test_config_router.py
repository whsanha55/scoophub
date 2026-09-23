# tests/test_config_router.py
import pytest
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import HTTPException

from app.community.github_trending.scheduler import register_jobs as gh_register_jobs
from app.core.base_scheduler import BaseScheduler
from app.system.config_router import (
    ConfigPatch,
    get_config,
    list_configs,
    update_config,
)


# --- resolve_params 단위 (crawl_config seed 필요) ---


async def test_resolve_params_returns_seed(db):
    params = await BaseScheduler.resolve_params(db, "github_trending")
    assert params["max_repos"] == 25
    assert params["since"] == "daily"


async def test_resolve_params_missing_returns_empty(db):
    # #127 대상 아닌 crawler (weather 등)는 params 행이 없어도 {} 반환.
    assert await BaseScheduler.resolve_params(db, "nonexistent") == {}


# --- GET ---


async def test_list_configs_returns_seed_rows(db):
    resp = await list_configs(db=db)
    assert resp.success
    crawlers = {row["crawler"] for row in resp.data}
    # reddit은 migration 시드(V11/V12)에 남아있으나 도메인 코드는 제거됨 — 검증 대상에서 제외.
    expected = {
        "news", "github_trending", "hacker_news", "arxiv", "product_hunt",
        "youtube_trending", "devto_hashnode", "tech_newsletter",
    }
    assert expected <= crawlers  # 필수 crawler 시드 검증 (부분집합)


async def test_get_config_single(db):
    resp = await get_config("arxiv", db=db)
    assert resp.success
    assert resp.data["crawler"] == "arxiv"
    assert resp.data["params"]["categories"] == ["cs.AI", "cs.LG", "cs.CL", "stat.ML"]


async def test_get_config_unknown_crawler_404(db):
    with pytest.raises(Exception):
        await get_config("nope", db=db)


# --- PATCH ---


async def test_update_config_partial_merge(db):
    resp = await update_config(
        "github_trending", ConfigPatch(params={"max_repos": 50}), db=db, scheduler=AsyncIOScheduler()
    )
    assert resp.success
    assert resp.data["params"]["max_repos"] == 50
    assert resp.data["params"]["since"] == "daily"  # 기존 키 보존


async def test_update_config_unknown_key_rejected_422(db):
    with pytest.raises(HTTPException) as exc:
        await update_config(
            "github_trending", ConfigPatch(params={"bogus": 1}), db=db,
            scheduler=AsyncIOScheduler(),
        )
    assert exc.value.status_code == 422


async def test_update_config_wrong_type_rejected_422(db):
    with pytest.raises(HTTPException) as exc:
        await update_config(
            "github_trending", ConfigPatch(params={"max_repos": "not-int"}), db=db,
            scheduler=AsyncIOScheduler(),
        )
    assert exc.value.status_code == 422


async def test_update_config_unknown_crawler_404(db):
    with pytest.raises(Exception):
        await update_config(
            "nope", ConfigPatch(params={"x": 1}), db=db, scheduler=AsyncIOScheduler()
        )


async def test_update_config_empty_patch_rejected(db):
    with pytest.raises(Exception):
        await update_config(
            "github_trending", ConfigPatch(params={}), db=db,
            scheduler=AsyncIOScheduler(),
        )


# --- live reload (modify_job kwargs) ---


async def test_update_config_live_reload(db):
    scheduler = AsyncIOScheduler()
    scheduler.start()
    try:
        await gh_register_jobs(scheduler, db)
        assert scheduler.get_job("github_trending_crawler").kwargs["max_repos"] == 25

        await update_config(
            "github_trending", ConfigPatch(params={"max_repos": 99}),
            db=db, scheduler=scheduler,
        )
        assert scheduler.get_job("github_trending_crawler").kwargs["max_repos"] == 99
    finally:
        scheduler.shutdown(wait=False)
