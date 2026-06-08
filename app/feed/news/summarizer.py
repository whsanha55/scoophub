# news/summarizer.py
from __future__ import annotations

import json
import logging
import re
from typing import TYPE_CHECKING

from app.core.llm import LLMClient

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)

CHUNK_SIZE = 20
# 재시도 대상 시간 윈도우 (오래된 실패 무한 재시도/비용 폭주 방지)
RETRY_WINDOW_HOURS = 24
_HANGUL = re.compile(r"[가-힣]")

# 허용 category 집합 (LLM은 이 중 하나로 분류, 불명확할 때만 other)
CATEGORIES = (
    "politics", "economy", "markets", "tech", "society",
    "world", "disaster", "science", "culture", "other",
)

SYSTEM_PROMPT = """당신은 한국어 뉴스 요약 도우미입니다.
입력으로 여러 개의 뉴스 기사가 주어집니다. 각 기사는 idx, 제목, 본문을 가집니다.

각 기사마다 다음을 생성하세요:
- summary_ko: 한국어로 3-5문장 요약
- importance: 중요도 정수 1~5 (5=속보·매우중요, 1=단순·일상)
- category: 다음 중 하나만 선택 (애매할 때만 other):
    politics : 정치·선거·정부·외교부
    economy  : 거시경제·정책·금리·고용·무역·환율
    markets  : 증시·기업실적·M&A·코인·원자재
    tech     : IT·AI·반도체·플랫폼
    society  : 사건·사고·범죄·노동·교육·복지 (재난 제외)
    world    : 국제·분쟁·해외정세
    disaster : 지진·태풍·홍수·화재·테러 등 재난
    science  : 과학·보건의료·환경기후
    culture  : 문화·예술·스포츠·연예·라이프
    other    : 위 어디에도 안 맞을 때만
- title_ko: 제목이 한국어가 아닌 경우에만 한국어 번역 제목. 이미 한국어면 생략

반드시 아래 형식의 JSON 배열만 출력하세요. 설명·코드블록 없이 JSON만:
[{"idx": 1, "summary_ko": "...", "importance": 3, "category": "markets", "title_ko": "..."}, ...]"""


class NewsSummarizer:
    def __init__(self, db: Database, llm: LLMClient):
        self.db = db
        self.llm = llm

    async def summarize_incomplete(self) -> dict:
        """Summarize not-yet-succeeded articles (pending/failed/error) from the last
        RETRY_WINDOW_HOURS, in chunks. Returns counts per outcome.

        The recency bound is a safety guard against retrying stale failures forever."""
        rows = await self.db.fetch(
            "SELECT id, title, summary, importance, category FROM news_articles "
            f"WHERE summary_status <> 'success' AND duplicated = false "
            f"AND created_at >= NOW() - interval '{RETRY_WINDOW_HOURS} hours' "
            "ORDER BY id"
        )
        if not rows:
            logger.info("No incomplete articles to summarize")
            return {"success": 0, "failed": 0, "error": 0, "total": 0}

        counts = {"success": 0, "failed": 0, "error": 0}
        for start in range(0, len(rows), CHUNK_SIZE):
            chunk = rows[start : start + CHUNK_SIZE]
            c = await self._process_chunk(chunk)
            for k in counts:
                counts[k] += c[k]

        total = sum(counts.values())
        logger.info(
            "Summarized %d articles: %d success, %d failed, %d error",
            total, counts["success"], counts["failed"], counts["error"],
        )
        return {**counts, "total": total}

    async def _process_chunk(self, chunk: list) -> dict:
        # Build prompt; flag which articles need title translation (non-Korean title).
        needs_title: dict[int, bool] = {}
        articles_text = ""
        for idx, row in enumerate(chunk, 1):
            title = row["title"]
            body = row["summary"] or ""
            needs_title[idx] = _HANGUL.search(title) is None
            tail = "  (제목 한국어 번역 필요)" if needs_title[idx] else ""
            articles_text += f"[idx {idx}]{tail}\n제목: {title}\n본문: {body}\n\n"

        user_prompt = f"다음 {len(chunk)}개 기사를 처리하세요:\n\n{articles_text}"

        try:
            response = await self.llm.chat(SYSTEM_PROMPT, user_prompt)
            parsed = self._parse(response)
        except Exception as e:
            logger.error("LLM summarization failed for chunk (%d articles): %s", len(chunk), e)
            await self._mark_error([row["id"] for row in chunk])
            return {"success": 0, "failed": 0, "error": len(chunk)}

        # Build final values for every article in the chunk.
        ids: list[int] = []
        titles: list[str] = []
        summaries: list[str | None] = []
        importances: list[int] = []
        categories: list[str | None] = []
        statuses: list[str] = []
        success = failed = 0

        for idx, row in enumerate(chunk, 1):
            item = parsed.get(idx)
            summary_ko = (item or {}).get("summary_ko") if item else None
            if item and summary_ko:
                title = row["title"]
                if needs_title[idx] and item.get("title_ko"):
                    title = str(item["title_ko"]).strip() or title
                ids.append(row["id"])
                titles.append(title)
                summaries.append(str(summary_ko).strip())
                importances.append(_clamp_importance(item.get("importance"), row["importance"]))
                categories.append(_clean_category(item.get("category")))
                statuses.append("success")
                success += 1
            else:
                # Missing/incomplete entry: keep existing fields, mark failed.
                ids.append(row["id"])
                titles.append(row["title"])
                summaries.append(row["summary"])
                importances.append(row["importance"])
                categories.append(row["category"])
                statuses.append("failed")
                failed += 1

        await self.db.execute(
            "UPDATE news_articles AS n SET "
            "  title = v.title, summary = v.summary, importance = v.importance, "
            "  category = v.category, summary_status = v.status, updated_at = NOW() "
            "FROM (SELECT * FROM unnest($1::int[], $2::text[], $3::text[], $4::smallint[], $5::text[], $6::text[]) "
            "      AS t(id, title, summary, importance, category, status)) AS v "
            "WHERE n.id = v.id",
            ids, titles, summaries, importances, categories, statuses,
        )
        return {"success": success, "failed": failed, "error": 0}

    async def _mark_error(self, ids: list[int]) -> None:
        await self.db.execute(
            "UPDATE news_articles SET summary_status = 'error', updated_at = NOW() "
            "WHERE id = ANY($1::int[])",
            ids,
        )

    @staticmethod
    def _parse(response: str) -> dict[int, dict]:
        """Parse LLM JSON array response into {idx: item}."""
        text = response.strip()
        # Strip code fences if present.
        if text.startswith("```"):
            text = re.sub(r"^```[a-zA-Z]*\n?|\n?```$", "", text).strip()
        # Extract the JSON array span.
        lo, hi = text.find("["), text.rfind("]")
        if lo == -1 or hi == -1 or hi < lo:
            raise ValueError("no JSON array in LLM response")
        data = json.loads(text[lo : hi + 1])
        result: dict[int, dict] = {}
        for item in data:
            if isinstance(item, dict) and "idx" in item:
                try:
                    result[int(item["idx"])] = item
                except (TypeError, ValueError):
                    continue
        return result


def _clamp_importance(value, fallback: int) -> int:
    try:
        n = int(value)
    except (TypeError, ValueError):
        return fallback
    return max(1, min(5, n))


def _clean_category(value) -> str:
    c = str(value or "").strip().lower()
    return c if c in CATEGORIES else "other"
