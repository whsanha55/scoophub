# jira/repository.py
from __future__ import annotations

import json
import logging
from datetime import date, datetime, timezone

from app.core.database import Database
from app.jira.models import (
    JiraIssue,
    JiraIssueComment,
    JiraPendingRetry,
    JiraTopic,
    JiraTopicMapping,
    JiraWeeklySummary,
)

logger = logging.getLogger(__name__)


# ── helpers ────────────────────────────────────────────

def _ts(val: datetime | None) -> str | None:
    return val.isoformat() if val else None


def _dt(row: dict, key: str) -> datetime | None:
    v = row.get(key)
    return v if isinstance(v, datetime) else None


def _d(row: dict, key: str) -> date | None:
    v = row.get(key)
    if isinstance(v, date) and not isinstance(v, datetime):
        return v
    if isinstance(v, datetime):
        return v.date()
    return None


# ── TopicRepo ──────────────────────────────────────────

class TopicRepo:
    def __init__(self, db: Database) -> None:
        self._db = db

    async def find_all(self, active_only: bool = False) -> list[JiraTopic]:
        q = "SELECT * FROM jira_topic"
        if active_only:
            q += " WHERE is_active = TRUE"
        q += " ORDER BY name"
        rows = await self._db.fetch(q)
        return [_row_to_topic(r) for r in rows]

    async def find_by_id(self, topic_id: int) -> JiraTopic | None:
        row = await self._db.fetchrow("SELECT * FROM jira_topic WHERE id = $1", topic_id)
        return _row_to_topic(row) if row else None

    async def find_by_name(self, name: str) -> JiraTopic | None:
        row = await self._db.fetchrow("SELECT * FROM jira_topic WHERE name = $1", name)
        return _row_to_topic(row) if row else None

    async def create(self, topic: JiraTopic) -> JiraTopic:
        row = await self._db.fetchrow(
            "INSERT INTO jira_topic (name, description, is_active) "
            "VALUES ($1, $2, TRUE) ON CONFLICT (name) DO NOTHING RETURNING *",
            topic.name, topic.description,
        )
        if row is None:
            # 이미 존재 → 기존 것 반환
            existing = await self.find_by_name(topic.name)
            return existing or topic
        return _row_to_topic(row)


# ── IssueRepo ──────────────────────────────────────────

class IssueRepo:
    def __init__(self, db: Database) -> None:
        self._db = db

    async def find_by_jira_key(self, jira_key: str) -> JiraIssue | None:
        row = await self._db.fetchrow("SELECT * FROM jira_issue WHERE jira_key = $1", jira_key)
        return _row_to_issue(row) if row else None

    async def find_by_week(self, week_start: date, week_end: date) -> list[JiraIssue]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_issue "
            "WHERE jira_updated_at >= $1 AND jira_updated_at <= $2 "
            "ORDER BY jira_updated_at DESC",
            week_start, week_end,
        )
        return [_row_to_issue(r) for r in rows]

    async def find_recent(self, limit: int = 50) -> list[JiraIssue]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_issue ORDER BY jira_updated_at DESC LIMIT $1", limit,
        )
        return [_row_to_issue(r) for r in rows]

    async def upsert(self, issue: JiraIssue) -> JiraIssue:
        row = await self._db.fetchrow(
            "INSERT INTO jira_issue "
            "  (jira_key, summary, status, issue_type, priority, project_key, labels, "
            "   jira_created_at, jira_updated_at, resolution_date) "
            "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) "
            "ON CONFLICT (jira_key) DO UPDATE SET "
            "  summary=EXCLUDED.summary, status=EXCLUDED.status, "
            "  issue_type=EXCLUDED.issue_type, priority=EXCLUDED.priority, "
            "  project_key=EXCLUDED.project_key, labels=EXCLUDED.labels, "
            "  jira_created_at=EXCLUDED.jira_created_at, "
            "  jira_updated_at=EXCLUDED.jira_updated_at, "
            "  resolution_date=EXCLUDED.resolution_date, "
            "  updated_at=NOW() "
            "RETURNING *",
            issue.jira_key, issue.summary, issue.status, issue.issue_type,
            issue.priority, issue.project_key, issue.labels,
            issue.jira_created_at, issue.jira_updated_at, issue.resolution_date,
        )
        return _row_to_issue(row)

    async def upsert_batch(self, issues: list[JiraIssue]) -> int:
        count = 0
        for issue in issues:
            await self.upsert(issue)
            count += 1
        return count

    async def find_by_topic(self, topic_id: int) -> list[JiraIssue]:
        rows = await self._db.fetch(
            "SELECT i.* FROM jira_issue i "
            "JOIN jira_topic_mapping m ON m.jira_key = i.jira_key "
            "WHERE m.topic_id = $1 "
            "ORDER BY i.jira_updated_at DESC",
            topic_id,
        )
        return [_row_to_issue(r) for r in rows]


# ── CommentRepo ────────────────────────────────────────

class CommentRepo:
    def __init__(self, db: Database) -> None:
        self._db = db

    async def find_by_jira_key(self, jira_key: str) -> list[JiraIssueComment]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_issue_comment WHERE jira_key = $1 "
            "ORDER BY created_at_jira", jira_key,
        )
        return [_row_to_comment(r) for r in rows]

    async def find_by_week(self, week_start: date, week_end: date) -> list[JiraIssueComment]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_issue_comment "
            "WHERE created_at_jira >= $1 AND created_at_jira <= $2 "
            "ORDER BY created_at_jira",
            week_start, week_end,
        )
        return [_row_to_comment(r) for r in rows]

    async def upsert(self, comment: JiraIssueComment) -> JiraIssueComment:
        row = await self._db.fetchrow(
            "INSERT INTO jira_issue_comment "
            "  (jira_comment_id, jira_key, author_display_name, body, "
            "   created_at_jira, updated_at_jira) "
            "VALUES ($1,$2,$3,$4,$5,$6) "
            "ON CONFLICT (jira_comment_id) DO UPDATE SET "
            "  body=EXCLUDED.body, updated_at_jira=EXCLUDED.updated_at_jira "
            "RETURNING *",
            comment.jira_comment_id, comment.jira_key,
            comment.author_display_name, comment.body,
            comment.created_at_jira, comment.updated_at_jira,
        )
        return _row_to_comment(row)

    async def upsert_batch(self, comments: list[JiraIssueComment]) -> int:
        count = 0
        for c in comments:
            await self.upsert(c)
            count += 1
        return count

    async def count_by_week(self, week_start: date, week_end: date) -> int:
        row = await self._db.fetchrow(
            "SELECT COUNT(*) AS cnt FROM jira_issue_comment "
            "WHERE created_at_jira >= $1 AND created_at_jira <= $2",
            week_start, week_end,
        )
        return int(row["cnt"]) if row else 0


# ── WeeklySummaryRepo ──────────────────────────────────

class WeeklySummaryRepo:
    def __init__(self, db: Database) -> None:
        self._db = db

    async def find_by_week(self, week_start: date) -> JiraWeeklySummary | None:
        row = await self._db.fetchrow(
            "SELECT * FROM jira_weekly_summary WHERE week_start = $1", week_start,
        )
        return _row_to_summary(row) if row else None

    async def find_recent(self, limit: int = 10) -> list[JiraWeeklySummary]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_weekly_summary ORDER BY week_start DESC LIMIT $1", limit,
        )
        return [_row_to_summary(r) for r in rows]

    async def find_by_id(self, summary_id: int) -> JiraWeeklySummary | None:
        row = await self._db.fetchrow(
            "SELECT * FROM jira_weekly_summary WHERE id = $1", summary_id,
        )
        return _row_to_summary(row) if row else None

    async def create(self, summary: JiraWeeklySummary) -> JiraWeeklySummary:
        row = await self._db.fetchrow(
            "INSERT INTO jira_weekly_summary "
            "  (week_start, week_end, summary_text, summary_status, model_used, "
            "   issue_count, comment_count, raw_llm_response) "
            "VALUES ($1,$2,$3,$4,$5,$6,$7,$8) "
            "RETURNING *",
            summary.week_start, summary.week_end,
            summary.summary_text, summary.summary_status,
            summary.model_used, summary.issue_count,
            summary.comment_count, summary.raw_llm_response,
        )
        return _row_to_summary(row)

    async def update_status(
        self,
        summary_id: int,
        status: str,
        summary_text: str | None = None,
        raw_response: str | None = None,
    ) -> JiraWeeklySummary | None:
        sets = ["summary_status = $2", "updated_at = NOW()"]
        params: list = [summary_id, status]
        idx = 3

        if summary_text is not None:
            sets.append(f"summary_text = ${idx}")
            params.append(summary_text)
            idx += 1
        if raw_response is not None:
            sets.append(f"raw_llm_response = ${idx}")
            params.append(raw_response)
            idx += 1

        row = await self._db.fetchrow(
            f"UPDATE jira_weekly_summary SET {', '.join(sets)} "
            f"WHERE id = $1 RETURNING *",
            *params,
        )
        return _row_to_summary(row) if row else None


# ── TopicMappingRepo ───────────────────────────────────

class TopicMappingRepo:
    def __init__(self, db: Database) -> None:
        self._db = db

    async def find_by_jira_key(self, jira_key: str) -> list[JiraTopicMapping]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_topic_mapping WHERE jira_key = $1", jira_key,
        )
        return [_row_to_mapping(r) for r in rows]

    async def find_by_topic(self, topic_id: int) -> list[JiraTopicMapping]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_topic_mapping WHERE topic_id = $1", topic_id,
        )
        return [_row_to_mapping(r) for r in rows]

    async def find_topic_names_by_keys(self, jira_keys: list[str]) -> dict[str, list[str]]:
        """jira_key → [topic_name, ...] 매핑 반환."""
        if not jira_keys:
            return {}
        rows = await self._db.fetch(
            "SELECT m.jira_key, t.name FROM jira_topic_mapping m "
            "JOIN jira_topic t ON t.id = m.topic_id "
            "WHERE m.jira_key = ANY($1)",
            jira_keys,
        )
        result: dict[str, list[str]] = {}
        for r in rows:
            result.setdefault(r["jira_key"], []).append(r["name"])
        return result

    async def save_batch(self, mappings: list[JiraTopicMapping]) -> int:
        count = 0
        for m in mappings:
            await self._db.execute(
                "INSERT INTO jira_topic_mapping (jira_key, topic_id, confidence, classified_by) "
                "VALUES ($1,$2,$3,$4) "
                "ON CONFLICT (jira_key, topic_id) DO NOTHING",
                m.jira_key, m.topic_id, m.confidence, m.classified_by,
            )
            count += 1
        return count

    async def delete_by_jira_key(self, jira_key: str) -> None:
        await self._db.execute(
            "DELETE FROM jira_topic_mapping WHERE jira_key = $1", jira_key,
        )


# ── PendingRetryRepo ───────────────────────────────────

class PendingRetryRepo:
    def __init__(self, db: Database) -> None:
        self._db = db

    async def find_pending(self) -> list[JiraPendingRetry]:
        rows = await self._db.fetch(
            "SELECT * FROM jira_pending_retry "
            "WHERE status = 'pending' AND next_retry_at <= NOW() "
            "ORDER BY created_at",
        )
        return [_row_to_retry(r) for r in rows]

    async def create(self, entry: JiraPendingRetry) -> JiraPendingRetry:
        row = await self._db.fetchrow(
            "INSERT INTO jira_pending_retry "
            "  (operation_type, payload, error_message, max_retries) "
            "VALUES ($1,$2,$3,$4) RETURNING *",
            entry.operation_type,
            json.dumps(entry.payload, default=str),
            entry.error_message,
            entry.max_retries,
        )
        return _row_to_retry(row)

    async def mark_running(self, entry_id: int) -> None:
        await self._db.execute(
            "UPDATE jira_pending_retry SET status='running', updated_at=NOW() WHERE id=$1",
            entry_id,
        )

    async def mark_done(self, entry_id: int) -> None:
        await self._db.execute(
            "UPDATE jira_pending_retry SET status='done', updated_at=NOW() WHERE id=$1",
            entry_id,
        )

    async def mark_failed(self, entry_id: int, error_message: str) -> None:
        await self._db.execute(
            "UPDATE jira_pending_retry "
            "SET status='failed', error_message=$2, updated_at=NOW() WHERE id=$1",
            entry_id, error_message,
        )

    async def increment_retry(self, entry_id: int, error_message: str) -> None:
        await self._db.execute(
            "UPDATE jira_pending_retry "
            "SET retry_count = retry_count + 1, "
            "    error_message = $2, "
            "    status = CASE WHEN retry_count + 1 >= max_retries THEN 'failed' ELSE 'pending' END, "
            "    next_retry_at = NOW() + interval '5 minutes', "
            "    updated_at = NOW() "
            "WHERE id = $1",
            entry_id, error_message,
        )


# ── row → dataclass converters ────────────────────────

def _row_to_topic(r: dict) -> JiraTopic:
    return JiraTopic(
        id=r["id"], name=r["name"], description=r.get("description"),
        is_active=r.get("is_active", True),
        created_at=_dt(r, "created_at"), updated_at=_dt(r, "updated_at"),
    )


def _row_to_issue(r: dict) -> JiraIssue:
    return JiraIssue(
        id=r["id"], jira_key=r["jira_key"], summary=r["summary"],
        status=r["status"], issue_type=r.get("issue_type", "Task"),
        priority=r.get("priority"), project_key=r.get("project_key"),
        labels=r.get("labels") or [],
        jira_created_at=_dt(r, "jira_created_at"),
        jira_updated_at=_dt(r, "jira_updated_at"),
        resolution_date=_dt(r, "resolution_date"),
        created_at=_dt(r, "created_at"), updated_at=_dt(r, "updated_at"),
    )


def _row_to_comment(r: dict) -> JiraIssueComment:
    return JiraIssueComment(
        id=r["id"], jira_comment_id=r["jira_comment_id"],
        jira_key=r["jira_key"],
        author_display_name=r.get("author_display_name"),
        body=r.get("body"),
        created_at_jira=_dt(r, "created_at_jira"),
        updated_at_jira=_dt(r, "updated_at_jira"),
        created_at=_dt(r, "created_at"),
    )


def _row_to_summary(r: dict) -> JiraWeeklySummary:
    return JiraWeeklySummary(
        id=r["id"],
        week_start=_d(r, "week_start"), week_end=_d(r, "week_end"),
        summary_text=r.get("summary_text", ""),
        summary_status=r.get("summary_status", "pending"),
        model_used=r.get("model_used"),
        issue_count=r.get("issue_count", 0),
        comment_count=r.get("comment_count", 0),
        raw_llm_response=r.get("raw_llm_response"),
        created_at=_dt(r, "created_at"), updated_at=_dt(r, "updated_at"),
    )


def _row_to_mapping(r: dict) -> JiraTopicMapping:
    return JiraTopicMapping(
        id=r["id"], jira_key=r["jira_key"],
        topic_id=r["topic_id"],
        confidence=float(r.get("confidence", 0)),
        classified_by=r.get("classified_by", "llm"),
        created_at=_dt(r, "created_at"),
    )


def _row_to_retry(r: dict) -> JiraPendingRetry:
    payload = r.get("payload", {})
    if isinstance(payload, str):
        payload = json.loads(payload)
    return JiraPendingRetry(
        id=r["id"], operation_type=r["operation_type"],
        payload=payload, error_message=r.get("error_message"),
        retry_count=r.get("retry_count", 0),
        max_retries=r.get("max_retries", 3),
        status=r.get("status", "pending"),
        next_retry_at=_dt(r, "next_retry_at"),
        created_at=_dt(r, "created_at"), updated_at=_dt(r, "updated_at"),
    )
