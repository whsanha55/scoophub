# tests/test_summarizer.py
import json
import re
from datetime import datetime, timedelta, timezone

import pytest

from app.news.filter_rules import passes_cutoff
from app.news.summarizer import NewsSummarizer


class FakeLLM:
    """Returns one valid result per [idx N] mentioned in the prompt."""

    async def chat(self, system_prompt: str, user_prompt: str) -> str:
        n = len(re.findall(r"\[idx ", user_prompt))
        items = [
            {"idx": i, "summary_ko": "요약 문장입니다.", "importance": 3, "category": "economy"}
            for i in range(1, n + 1)
        ]
        return json.dumps(items)


async def _insert(db, *, title, status, age_days=0, summary="본문"):
    return await db.fetchval(
        "INSERT INTO news_articles (source, title, url, normalized_url, summary, summary_status, created_at) "
        "VALUES ('s', $1, $2, $2, $3, $4, NOW() - ($5 || ' days')::interval) RETURNING id",
        title, f"https://x.test/{title}", summary, status, str(age_days),
    )


@pytest.mark.asyncio
async def test_summarize_incomplete_scope(db):
    # success(new) → 제외, failed(new) → 포함, error(2일전) → 제외
    await _insert(db, title="succ", status="success")
    failed_id = await _insert(db, title="fail", status="failed")
    await _insert(db, title="olderr", status="error", age_days=2)

    res = await NewsSummarizer(db, FakeLLM()).summarize_incomplete()

    assert res == {"success": 1, "failed": 0, "error": 0, "total": 1}
    row = await db.fetchrow("SELECT summary_status, category FROM news_articles WHERE id = $1", failed_id)
    assert row["summary_status"] == "success"
    assert row["category"] == "economy"


def test_passes_cutoff():
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=24)
    assert passes_cutoff(now, cutoff) is True
    assert passes_cutoff(now - timedelta(hours=48), cutoff) is False
    assert passes_cutoff(None, cutoff) is True  # 발행일 없으면 저장
