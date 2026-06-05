# jira/summarizer.py
from __future__ import annotations

import json
import logging
import re
from datetime import date

from app.config import settings
from app.core.database import Database
from app.core.llm import LLMClient
from app.jira.models import (
    JiraIssue,
    JiraIssueComment,
    JiraTopic,
    JiraTopicMapping,
    JiraWeeklySummary,
)
from app.jira.repository import (
    TopicMappingRepo,
    TopicRepo,
    WeeklySummaryRepo,
)

logger = logging.getLogger(__name__)

SUMMARY_SYSTEM_PROMPT = """\
당신은 Jira 작업 로그 기반 주간 보고서 생성 도우미입니다.
지난 주 동안 사용자가 작업한 Jira 이슈와 코멘트가 입력으로 주어집니다.

다음을 생성하세요:
1. weekly_summary: 주간 작업 요약 (3~5문장, 한국어)
   - 완료한 작업, 진행 중인 작업, 주요 성과를 포함
   - 각 이슈의 키(PROJ-123)를 괄호로 참조
2. highlights: 주요 성과나 중요 진행 사항 (최대 5개, 각각 1문장)
3. blocked_or_delayed: 블로커나 지연 사항이 있으면 나열 (없으면 빈 배열)

반드시 아래 JSON 형식만 출력하세요. 설명·코드블록 없이 JSON만:
{
  "weekly_summary": "...",
  "highlights": ["...", "..."],
  "blocked_or_delayed": ["...", "..."]
}"""

TOPIC_SYSTEM_PROMPT = """\
당신은 Jira 이슈를 주제별로 분류하는 도우미입니다.
각 이슈의 제목, 상태, 코멘트 내용을 보고 적절한 주제를 매핑하세요.

기존 주제 목록이 제공됩니다. 이슈가 기존 주제에 해당하면 해당 주제의 id를 선택하고,
새로운 주제가 필요하면 "new_topics" 배열에 주제명을 제안하세요.

각 이슈마다 다음을 생성하세요:
- idx: 이슈 번호 (1부터)
- topic_ids: 매핑할 기존 주제 ID 목록 (1~3개, 빈 배열 가능)
- new_topics: 새로 생성해야 할 주제명 목록 (빈 배열 가능)
- confidence: 분류 신뢰도 (0.0~1.0)

반드시 아래 JSON 배열 형식만 출력하세요:
[{"idx": 1, "topic_ids": [1, 3], "new_topics": [], "confidence": 0.9}, ...]"""


class JiraSummarizer:
    def __init__(self, db: Database) -> None:
        self._db = db
        self._topic_repo = TopicRepo(db)
        self._mapping_repo = TopicMappingRepo(db)
        self._summary_repo = WeeklySummaryRepo(db)

    async def generate_weekly_summary(
        self,
        issues: list[JiraIssue],
        comments: list[JiraIssueComment],
        week_start: date,
        week_end: date,
    ) -> JiraWeeklySummary:
        # pending 레코드 먼저 생성
        summary = await self._summary_repo.create(JiraWeeklySummary(
            week_start=week_start,
            week_end=week_end,
            summary_text="",
            summary_status="pending",
            model_used=settings.LLM_MODEL,
            issue_count=len(issues),
            comment_count=len(comments),
        ))

        if not issues:
            return await self._summary_repo.update_status(
                summary.id, "success", summary_text="수집된 이슈가 없습니다.",
            ) or summary

        # LLM 호출
        user_prompt = self._build_summary_prompt(issues, comments, week_start, week_end)
        try:
            async with LLMClient() as llm:
                raw = await llm.chat(SUMMARY_SYSTEM_PROMPT, user_prompt)

            parsed = self._parse_json(raw)
            summary_text = parsed.get("weekly_summary", raw)

            return await self._summary_repo.update_status(
                summary.id, "success",
                summary_text=summary_text,
                raw_response=raw,
            ) or summary

        except Exception as e:
            logger.error("LLM summary failed for week %s: %s", week_start, e)
            await self._summary_repo.update_status(summary.id, "failed")
            return summary

    async def classify_topics(
        self,
        issues: list[JiraIssue],
        comments: list[JiraIssueComment],
    ) -> list[JiraTopicMapping]:
        if not issues:
            return []

        topics = await self._topic_repo.find_all(active_only=True)
        user_prompt = self._build_topic_prompt(issues, comments, topics)

        try:
            async with LLMClient() as llm:
                raw = await llm.chat(TOPIC_SYSTEM_PROMPT, user_prompt)

            parsed = self._parse_json_array(raw)
            return await self._apply_classifications(parsed, issues, topics)

        except Exception as e:
            logger.error("Topic classification failed: %s", e)
            return []

    # ── prompt builders ────────────────────────────────

    @staticmethod
    def _build_summary_prompt(
        issues: list[JiraIssue],
        comments: list[JiraIssueComment],
        week_start: date,
        week_end: date,
    ) -> str:
        lines = [f"기간: {week_start} ~ {week_end}", f"이슈 수: {len(issues)}", ""]

        for i, issue in enumerate(issues, 1):
            lines.append(f"[{i}] {issue.jira_key} — {issue.summary}")
            lines.append(f"    상태: {issue.status}, 타입: {issue.issue_type}")
            if issue.priority:
                lines.append(f"    우선순위: {issue.priority}")

            issue_comments = [c for c in comments if c.jira_key == issue.jira_key]
            if issue_comments:
                lines.append(f"    코멘트 ({len(issue_comments)}개):")
                for c in issue_comments[:5]:
                    body = (c.body or "")[:200]
                    lines.append(f"      - {body}")
            lines.append("")

        return "\n".join(lines)

    @staticmethod
    def _build_topic_prompt(
        issues: list[JiraIssue],
        comments: list[JiraIssueComment],
        topics: list[JiraTopic],
    ) -> str:
        lines = ["기존 주제 목록:"]
        for t in topics:
            lines.append(f"  id={t.id}: {t.name}" + (f" — {t.description}" if t.description else ""))
        lines.append("")
        lines.append(f"분류할 이슈 ({len(issues)}개):")

        for i, issue in enumerate(issues, 1):
            lines.append(f"[{i}] {issue.jira_key} — {issue.summary} ({issue.status})")
            issue_comments = [c for c in comments if c.jira_key == issue.jira_key]
            if issue_comments:
                for c in issue_comments[:3]:
                    body = (c.body or "")[:150]
                    lines.append(f"      코멘트: {body}")

        return "\n".join(lines)

    # ── classification apply ───────────────────────────

    async def _apply_classifications(
        self,
        parsed: list[dict],
        issues: list[JiraIssue],
        existing_topics: list[JiraTopic],
    ) -> list[JiraTopicMapping]:
        topic_by_id = {t.id: t for t in existing_topics}
        mappings: list[JiraTopicMapping] = []

        for item in parsed:
            idx = item.get("idx", 0) - 1
            if idx < 0 or idx >= len(issues):
                continue
            issue = issues[idx]

            # 기존 토픽 매핑
            for tid in item.get("topic_ids", []):
                if tid in topic_by_id:
                    mappings.append(JiraTopicMapping(
                        jira_key=issue.jira_key,
                        topic_id=tid,
                        confidence=item.get("confidence", 0.5),
                    ))

            # 새 토픽 생성
            for name in item.get("new_topics", []):
                new_topic = await self._topic_repo.create(JiraTopic(name=name))
                if new_topic and new_topic.id:
                    topic_by_id[new_topic.id] = new_topic
                    mappings.append(JiraTopicMapping(
                        jira_key=issue.jira_key,
                        topic_id=new_topic.id,
                        confidence=item.get("confidence", 0.5),
                    ))

        await self._mapping_repo.save_batch(mappings)
        return mappings

    # ── JSON parsers ───────────────────────────────────

    @staticmethod
    def _parse_json(text: str) -> dict:
        text = text.strip()
        if text.startswith("```"):
            text = re.sub(r"^```[a-zA-Z]*\n?|\n?```$", "", text).strip()
        lo, hi = text.find("{"), text.rfind("}")
        if lo == -1 or hi == -1:
            raise ValueError("no JSON object in LLM response")
        return json.loads(text[lo : hi + 1])

    @staticmethod
    def _parse_json_array(text: str) -> list[dict]:
        text = text.strip()
        if text.startswith("```"):
            text = re.sub(r"^```[a-zA-Z]*\n?|\n?```$", "", text).strip()
        lo, hi = text.find("["), text.rfind("]")
        if lo == -1 or hi == -1:
            raise ValueError("no JSON array in LLM response")
        data = json.loads(text[lo : hi + 1])
        return [item for item in data if isinstance(item, dict)]
