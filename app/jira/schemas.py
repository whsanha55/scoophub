# jira/schemas.py
from __future__ import annotations

from pydantic import BaseModel, Field


# ── Issue ──────────────────────────────────────────────

class JiraIssueOut(BaseModel):
    id: int = Field(..., description="내부 ID")
    jira_key: str = Field(..., description="Jira 키 (예: PROJ-123)")
    summary: str = Field(..., description="이슈 제목")
    status: str = Field(..., description="이슈 상태")
    issue_type: str = Field("Task", description="이슈 타입")
    priority: str | None = Field(None, description="우선순위")
    project_key: str | None = Field(None, description="프로젝트 키")
    labels: list[str] = Field(default_factory=list, description="라벨 목록")
    jira_created_at: str | None = Field(None, description="Jira 생성일 (ISO 8601)")
    jira_updated_at: str | None = Field(None, description="Jira 수정일 (ISO 8601)")
    resolution_date: str | None = Field(None, description="해결일 (ISO 8601)")
    topic_names: list[str] = Field(default_factory=list, description="분류된 토픽 이름 목록")


# ── Summary ────────────────────────────────────────────

class JiraWeeklySummaryOut(BaseModel):
    id: int = Field(..., description="내부 ID")
    week_start: str = Field(..., description="주차 시작일 (ISO 8601)")
    week_end: str = Field(..., description="주차 종료일 (ISO 8601)")
    summary_text: str = Field(..., description="LLM 생성 주간 요약")
    summary_status: str = Field(..., description="상태 (pending | success | failed)")
    model_used: str | None = Field(None, description="사용된 LLM 모델")
    issue_count: int = Field(0, description="포함된 이슈 수")
    comment_count: int = Field(0, description="포함된 코멘트 수")
    created_at: str | None = Field(None, description="생성일시 (ISO 8601)")


# ── Topic ──────────────────────────────────────────────

class JiraTopicOut(BaseModel):
    id: int = Field(..., description="토픽 ID")
    name: str = Field(..., description="토픽 이름")
    description: str | None = Field(None, description="토픽 설명")
    is_active: bool = Field(True, description="활성 여부")
    issue_count: int = Field(0, description="해당 토픽에 속한 이슈 수")
    created_at: str | None = Field(None, description="생성일시 (ISO 8601)")


# ── Crawling ───────────────────────────────────────────

class JiraCrawlResult(BaseModel):
    issues_fetched: int = Field(0, description="수집된 이슈 수")
    comments_fetched: int = Field(0, description="수집된 코멘트 수")
    summary_id: int | None = Field(None, description="생성된 요약 ID")
    topics_classified: int = Field(0, description="분류된 토픽 매핑 수")
