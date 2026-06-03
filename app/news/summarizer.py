# news/summarizer.py
from __future__ import annotations

import logging
import re
from typing import TYPE_CHECKING

from app.core.llm import LLMClient

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """당신은 한국어 뉴스 요약 도우미입니다.
주어진 뉴스 기사들을 각각 3-5문장으로 요약하세요.

형식:
[기사 1]
요약 내용

[기사 2]
요약 내용
...

각 기사는 반드시 [기사 N] 헤더로 시작하고, 요약은 한국어로 작성하세요."""


class NewsSummarizer:
    def __init__(self, db: Database, llm: LLMClient):
        self.db = db
        self.llm = llm

    async def summarize_pending(self) -> int:
        """Summarize all articles where summary IS NULL. Returns count of summarized articles."""
        # 1. Fetch articles without summary (LIMIT to avoid context overflow)
        rows = await self.db.fetch(
            "SELECT id, title, description FROM news_articles WHERE summary IS NULL ORDER BY id LIMIT 20"
        )
        if not rows:
            logger.info("No pending articles to summarize")
            return 0

        # 2. Build single prompt with all articles
        articles_text = ""
        article_ids: list[int] = []
        for i, row in enumerate(rows, 1):
            title = row["title"]
            desc = row["description"] or ""
            articles_text += f"[기사 {i}]\n제목: {title}\n본문: {desc}\n\n"
            article_ids.append(row["id"])

        # 3. Single LLM call
        try:
            user_prompt = f"다음 {len(rows)}개의 뉴스 기사를 요약해주세요:\n\n{articles_text}"
            response = await self.llm.chat(SYSTEM_PROMPT, user_prompt)
        except Exception as e:
            logger.error("LLM summarization failed: %s", e)
            return 0

        # 4. Parse response and update each article
        summaries = self._parse_summaries(response, len(rows))
        updated = 0
        for article_id, summary in zip(article_ids, summaries):
            if summary:
                await self.db.execute(
                    "UPDATE news_articles SET summary = $1 WHERE id = $2",
                    summary, article_id,
                )
                updated += 1

        logger.info("Summarized %d/%d articles", updated, len(rows))
        return updated

    def _parse_summaries(self, response: str, expected_count: int) -> list[str]:
        """Parse LLM response into individual summaries, keyed by article number."""
        # Extract numbered summaries: [기사 N] ... content ...
        matched = re.findall(r"\[기사\s*(\d+)\]\s*(.*?)(?=\[기사\s*\d+\]|$)", response, re.DOTALL)
        numbered: dict[int, str] = {}
        for num_str, content in matched:
            idx = int(num_str) - 1
            text = content.strip()
            if text:
                numbered[idx] = text

        if len(numbered) == expected_count:
            return [numbered[i] for i in range(expected_count)]

        # Fallback: pad missing entries with empty strings
        logger.warning(
            "Summary parse mismatch: expected %d, got %d",
            expected_count, len(numbered),
        )
        return [numbered.get(i, "") for i in range(expected_count)]
