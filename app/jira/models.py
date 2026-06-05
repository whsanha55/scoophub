# jira/models.py
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime


@dataclass
class JiraTopic:
    id: int | None = None
    name: str = ""
    description: str | None = None
    is_active: bool = True
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass
class JiraIssue:
    id: int | None = None
    jira_key: str = ""
    summary: str = ""
    status: str = ""
    issue_type: str = "Task"
    priority: str | None = None
    project_key: str | None = None
    labels: list[str] = field(default_factory=list)
    jira_created_at: datetime | None = None
    jira_updated_at: datetime | None = None
    resolution_date: datetime | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass
class JiraIssueComment:
    id: int | None = None
    jira_comment_id: str = ""
    jira_key: str = ""
    author_display_name: str | None = None
    body: str | None = None
    created_at_jira: datetime | None = None
    updated_at_jira: datetime | None = None
    created_at: datetime | None = None


@dataclass
class JiraWeeklySummary:
    id: int | None = None
    week_start: date | None = None
    week_end: date | None = None
    summary_text: str = ""
    summary_status: str = "pending"
    model_used: str | None = None
    issue_count: int = 0
    comment_count: int = 0
    raw_llm_response: str | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None


@dataclass
class JiraTopicMapping:
    id: int | None = None
    jira_key: str = ""
    topic_id: int | None = None
    confidence: float = 0.0
    classified_by: str = "llm"
    created_at: datetime | None = None


@dataclass
class JiraPendingRetry:
    id: int | None = None
    operation_type: str = ""
    payload: dict = field(default_factory=dict)
    error_message: str | None = None
    retry_count: int = 0
    max_retries: int = 3
    status: str = "pending"
    next_retry_at: datetime | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None
