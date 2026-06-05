# jira/router.py
from __future__ import annotations

import logging
from typing import AsyncGenerator

from fastapi import APIRouter, Depends, Query

from app.core.database import Database
from app.core.models import ApiResponse
from app.jira.schemas import (
    JiraCrawlResult,
    JiraIssueOut,
    JiraTopicOut,
    JiraWeeklySummaryOut,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


# ── Issues ─────────────────────────────────────────────

@router.get(
    "/jira/issues",
    tags=["Jira"],
    summary="주간 이슈 목록 조회",
)
async def list_issues(
    week: str | None = Query(None, description="ISO 주차 (예: 2026-W23). 없으면 최근 7일"),
    project_key: str | None = Query(None, description="프로젝트 키 필터"),
    limit: int = Query(50, ge=1, le=200),
    db: Database = Depends(_get_db),
) -> ApiResponse:
    from app.jira.repository import IssueRepo, TopicMappingRepo

    issue_repo = IssueRepo(db)

    if week:
        week_start, week_end = _iso_week_range(week)
    else:
        from datetime import date, timedelta
        today = date.today()
        week_start = today - timedelta(days=today.weekday() + 7)
        week_end = week_start + timedelta(days=6)

    issues = await issue_repo.find_by_week(week_start, week_end)

    if project_key:
        issues = [i for i in issues if i.project_key == project_key]
    issues = issues[:limit]

    # 토픽 이름 조회
    mapping_repo = TopicMappingRepo(db)
    topic_map = await mapping_repo.find_topic_names_by_keys([i.jira_key for i in issues])

    data = [
        JiraIssueOut(
            id=i.id, jira_key=i.jira_key, summary=i.summary,
            status=i.status, issue_type=i.issue_type,
            priority=i.priority, project_key=i.project_key,
            labels=i.labels,
            jira_created_at=_fmt(i.jira_created_at),
            jira_updated_at=_fmt(i.jira_updated_at),
            resolution_date=_fmt(i.resolution_date),
            topic_names=topic_map.get(i.jira_key, []),
        ).model_dump()
        for i in issues
    ]
    return ApiResponse(success=True, data=data)


# ── Summaries ──────────────────────────────────────────

@router.get(
    "/jira/summaries/latest",
    tags=["Jira"],
    summary="최근 주간 요약",
)
async def latest_summary(
    db: Database = Depends(_get_db),
) -> ApiResponse:
    from app.jira.repository import WeeklySummaryRepo

    repo = WeeklySummaryRepo(db)
    summaries = await repo.find_recent(limit=1)
    if not summaries:
        return ApiResponse(success=True, data=None)
    s = summaries[0]
    return ApiResponse(success=True, data=_summary_out(s).model_dump())


@router.get(
    "/jira/summaries",
    tags=["Jira"],
    summary="주간 요약 목록",
)
async def list_summaries(
    limit: int = Query(10, ge=1, le=50),
    db: Database = Depends(_get_db),
) -> ApiResponse:
    from app.jira.repository import WeeklySummaryRepo

    repo = WeeklySummaryRepo(db)
    summaries = await repo.find_recent(limit=limit)
    data = [_summary_out(s).model_dump() for s in summaries]
    return ApiResponse(success=True, data=data)


@router.get(
    "/jira/summaries/{summary_id}",
    tags=["Jira"],
    summary="주간 요약 단건 조회",
)
async def get_summary(
    summary_id: int,
    db: Database = Depends(_get_db),
) -> ApiResponse:
    from app.jira.repository import WeeklySummaryRepo

    repo = WeeklySummaryRepo(db)
    s = await repo.find_by_id(summary_id)
    if not s:
        return ApiResponse(success=False, error={"code": "NOT_FOUND", "message": f"Summary {summary_id} not found"})
    return ApiResponse(success=True, data=_summary_out(s).model_dump())


# ── Topics ─────────────────────────────────────────────

@router.get(
    "/jira/topics",
    tags=["Jira"],
    summary="토픽 목록 조회",
)
async def list_topics(
    active_only: bool = Query(True, description="활성 토픽만 조회"),
    db: Database = Depends(_get_db),
) -> ApiResponse:
    from app.jira.repository import TopicRepo, TopicMappingRepo

    topic_repo = TopicRepo(db)
    mapping_repo = TopicMappingRepo(db)
    topics = await topic_repo.find_all(active_only=active_only)

    # 각 토픽별 이슈 수
    data = []
    for t in topics:
        mappings = await mapping_repo.find_by_topic(t.id)
        data.append(JiraTopicOut(
            id=t.id, name=t.name, description=t.description,
            is_active=t.is_active, issue_count=len(mappings),
            created_at=_fmt(t.created_at),
        ).model_dump())
    return ApiResponse(success=True, data=data)


@router.get(
    "/jira/topics/{topic_id}/issues",
    tags=["Jira"],
    summary="토픽별 이슈 목록",
)
async def topic_issues(
    topic_id: int,
    limit: int = Query(50, ge=1, le=200),
    db: Database = Depends(_get_db),
) -> ApiResponse:
    from app.jira.repository import IssueRepo, TopicRepo, TopicMappingRepo

    topic_repo = TopicRepo(db)
    topic = await topic_repo.find_by_id(topic_id)
    if not topic:
        return ApiResponse(success=False, error={"code": "NOT_FOUND", "message": f"Topic {topic_id} not found"})

    issue_repo = IssueRepo(db)
    issues = await issue_repo.find_by_topic(topic_id)

    mapping_repo = TopicMappingRepo(db)
    topic_map = await mapping_repo.find_topic_names_by_keys([i.jira_key for i in issues])

    data = [
        JiraIssueOut(
            id=i.id, jira_key=i.jira_key, summary=i.summary,
            status=i.status, issue_type=i.issue_type,
            priority=i.priority, project_key=i.project_key,
            labels=i.labels,
            jira_created_at=_fmt(i.jira_created_at),
            jira_updated_at=_fmt(i.jira_updated_at),
            resolution_date=_fmt(i.resolution_date),
            topic_names=topic_map.get(i.jira_key, []),
        ).model_dump()
        for i in issues[:limit]
    ]
    return ApiResponse(success=True, data=data)


# ── 수동 실행 ──────────────────────────────────────────

@router.post(
    "/crawling/jira/weekly",
    tags=["Jira Crawling"],
    summary="Jira 주간 로그 수동 실행",
)
async def trigger_weekly_crawl(
    max_results: int = Query(100, ge=1, le=500),
    db: Database = Depends(_get_db),
) -> ApiResponse:
    from app.jira.crawler import JiraWeeklyCrawler

    crawler = JiraWeeklyCrawler(db)
    result = await crawler.run(max_results=max_results)
    return ApiResponse(success=True, data=result.model_dump())


# ── helpers ────────────────────────────────────────────

def _fmt(val) -> str | None:
    return val.isoformat() if val else None


def _summary_out(s) -> JiraWeeklySummaryOut:
    return JiraWeeklySummaryOut(
        id=s.id,
        week_start=str(s.week_start) if s.week_start else "",
        week_end=str(s.week_end) if s.week_end else "",
        summary_text=s.summary_text,
        summary_status=s.summary_status,
        model_used=s.model_used,
        issue_count=s.issue_count,
        comment_count=s.comment_count,
        created_at=_fmt(s.created_at),
    )


def _iso_week_range(week: str) -> tuple:
    """'2026-W23' → (week_start: date, week_end: date)."""
    from datetime import date, timedelta
    year, week_num = week.split("-W")
    d = date.fromisocalendar(int(year), int(week_num), 1)  # 월요일
    return d, d + timedelta(days=6)
