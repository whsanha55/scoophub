# jira/crawler.py
from __future__ import annotations

import base64
import logging
from datetime import date, datetime, timedelta, timezone

import httpx

from app.config import settings
from app.core.database import Database
from app.jira.models import JiraIssue, JiraIssueComment, JiraPendingRetry
from app.jira.repository import (
    CommentRepo,
    IssueRepo,
    PendingRetryRepo,
)
from app.jira.schemas import JiraCrawlResult
from app.jira.summarizer import JiraSummarizer

logger = logging.getLogger(__name__)


class JiraWeeklyCrawler:
    def __init__(self, db: Database) -> None:
        self._db = db
        self._issue_repo = IssueRepo(db)
        self._comment_repo = CommentRepo(db)
        self._retry_repo = PendingRetryRepo(db)
        self._summarizer = JiraSummarizer(db)

        self._base_url = settings.JIRA_BASE_URL.strip().rstrip("/")
        credentials = base64.b64encode(
            f"{settings.JIRA_EMAIL}:{settings.JIRA_API_TOKEN}".encode()
        ).decode()
        self._headers = {
            "Authorization": f"Basic {credentials}",
            "Content-Type": "application/json",
        }
        self._account_id = settings.JIRA_ACCOUNT_ID

    async def run(self, max_results: int = 100) -> JiraCrawlResult:
        result = JiraCrawlResult()

        # 1. 날짜 범위: 지난주 월~일
        today = date.today()
        week_start = today - timedelta(days=today.weekday() + 7)  # 지난주 월요일
        week_end = week_start + timedelta(days=6)  # 지난주 일요일

        dt_start = datetime.combine(week_start, datetime.min.time(), tzinfo=timezone.utc)
        dt_end = datetime.combine(week_end, datetime.max.time(), tzinfo=timezone.utc)

        logger.info("Jira weekly crawl: %s ~ %s", week_start, week_end)

        # 2. Jira 이슈 조회
        try:
            raw_issues = await self._fetch_issues(dt_start, dt_end, max_results)
            issues = [self._parse_issue(r) for r in raw_issues]
            await self._issue_repo.upsert_batch(issues)
            result.issues_fetched = len(issues)
            logger.info("Fetched %d issues", len(issues))
        except Exception as e:
            logger.error("Issue fetch failed: %s", e)
            await self._retry_repo.create(JiraPendingRetry(
                operation_type="fetch_issues",
                payload={"week_start": str(week_start), "week_end": str(week_end)},
                error_message=str(e),
            ))
            return result

        if not issues:
            logger.info("No issues found for %s ~ %s", week_start, week_end)
            return result

        # 3. 코멘트 조회 (account_id 필터)
        all_comments: list[JiraIssueComment] = []
        for issue in issues:
            try:
                raw_comments = await self._fetch_comments(issue.jira_key)
                filtered = [
                    self._parse_comment(issue.jira_key, c)
                    for c in raw_comments
                    if self._is_my_comment(c)
                ]
                all_comments.extend(filtered)
            except Exception as e:
                logger.warning("Comment fetch failed for %s: %s", issue.jira_key, e)

        await self._comment_repo.upsert_batch(all_comments)
        result.comments_fetched = len(all_comments)
        logger.info("Fetched %d comments (filtered)", len(all_comments))

        # 4. 주간 요약 생성
        try:
            summary = await self._summarizer.generate_weekly_summary(
                issues, all_comments, week_start, week_end,
            )
            result.summary_id = summary.id
        except Exception as e:
            logger.error("Summary generation failed: %s", e)

        # 5. 토픽 분류
        try:
            mappings = await self._summarizer.classify_topics(issues, all_comments)
            result.topics_classified = len(mappings)
        except Exception as e:
            logger.error("Topic classification failed: %s", e)

        return result

    # ── Jira API helpers ───────────────────────────────

    async def _fetch_issues(
        self, start: datetime, end: datetime, max_results: int,
    ) -> list[dict]:
        jql = (
            f'assignee = "{self._account_id}" '
            f'AND updated >= "{start.strftime("%Y-%m-%d %H:%M")}" '
            f'ORDER BY updated DESC'
        )
        url = f"{self._base_url}/rest/api/3/search/jql"
        body = {
            "jql": jql,
            "maxResults": max_results,
            "fields": ["summary", "status", "issuetype", "priority", "project", "labels", "created", "updated", "resolutiondate"],
        }

        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(url, headers=self._headers, json=body)
            resp.raise_for_status()
            data = resp.json()
            return data.get("issues", [])

    async def _fetch_comments(self, issue_key: str) -> list[dict]:
        url = f"{self._base_url}/rest/api/3/issue/{issue_key}/comment"
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(url, headers=self._headers, params={"maxResults": 100})
            resp.raise_for_status()
            data = resp.json()
            return data.get("comments", [])

    def _is_my_comment(self, raw: dict) -> bool:
        author = raw.get("author", {})
        return author.get("accountId") == self._account_id

    # ── parsers ────────────────────────────────────────

    @staticmethod
    def _parse_issue(raw: dict) -> JiraIssue:
        fields = raw.get("fields", {})
        status = fields.get("status", {}).get("name", "")
        issue_type = fields.get("issuetype", {}).get("name", "Task")
        priority_obj = fields.get("priority")
        priority = priority_obj.get("name") if priority_obj else None
        project_obj = fields.get("project", {})
        project_key = project_obj.get("key")

        return JiraIssue(
            jira_key=raw.get("key", ""),
            summary=fields.get("summary", ""),
            status=status,
            issue_type=issue_type,
            priority=priority,
            project_key=project_key,
            labels=fields.get("labels", []),
            jira_created_at=_parse_jira_dt(fields.get("created")),
            jira_updated_at=_parse_jira_dt(fields.get("updated")),
            resolution_date=_parse_jira_dt(fields.get("resolutiondate")),
        )

    @staticmethod
    def _parse_comment(jira_key: str, raw: dict) -> JiraIssueComment:
        author = raw.get("author", {})
        return JiraIssueComment(
            jira_comment_id=raw.get("id", ""),
            jira_key=jira_key,
            author_display_name=author.get("displayName"),
            body=_adf_to_text(raw.get("body")).strip() or None,
            created_at_jira=_parse_jira_dt(raw.get("created")),
            updated_at_jira=_parse_jira_dt(raw.get("updated")),
        )


_ADF_BLOCK_TYPES = {"paragraph", "heading", "blockquote", "listItem", "codeBlock"}


def _adf_to_text(node: object) -> str:
    """Jira v3 코멘트 body는 ADF(dict). 평문으로 추출. 이미 str이면 그대로."""
    if node is None:
        return ""
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return "".join(_adf_to_text(n) for n in node)
    if isinstance(node, dict):
        if node.get("type") == "text":
            return node.get("text", "")
        if node.get("type") == "hardBreak":
            return "\n"
        text = "".join(_adf_to_text(c) for c in node.get("content", []))
        if node.get("type") in _ADF_BLOCK_TYPES:
            text += "\n"
        return text
    return ""


def _parse_jira_dt(val: str | None) -> datetime | None:
    if not val:
        return None
    try:
        return datetime.fromisoformat(val.replace("+0000", "+00:00"))
    except (ValueError, TypeError):
        return None
