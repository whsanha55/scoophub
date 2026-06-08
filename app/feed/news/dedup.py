# news/dedup.py
from __future__ import annotations

import json
import logging
import re
from typing import TYPE_CHECKING
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

if TYPE_CHECKING:
    from app.core.database import Database
    from app.core.llm import LLMClient
logger = logging.getLogger(__name__)

# Query params that carry tracking/session noise, not article identity.
_TRACKING_PREFIXES = ("utm_",)
_TRACKING_KEYS = {
    "gclid", "fbclid", "ocid", "oc", "cmpid", "ref", "ref_src",
    "spm", "igshid", "mc_cid", "mc_eid",
}

DEDUP_SYSTEM_PROMPT = """당신은 한국어 뉴스 중복 판단 전문가입니다.

입력으로 기존 기사와 신규 기사 목록이 주어집니다. 중복 그룹을 찾아내세요.

## 중복 기준 (같은 기사)
- 통신사 기사를 여러 매체가 재배포한 경우
- 제목/본문이 약간만 다르고 본질적으로 동일한 내용인 경우
- 같은 기사의 업데이트판

## 중복 아님 기준 (다른 기사)
- 같은 사건을 다른 각도/취재로 보도한 경우
- 같은 토픽의 후속 보도
- 다른 매체의 독자 취재 기사

반드시 아래 형식의 JSON만 출력하세요. 설명·코드블록 없이 JSON만:
{"groups": [[idx1, idx2, ...], [idx3, idx4, ...]]}

- 각 그룹은 서로 중복인 기사의 idx 목록입니다.
- 중복 그룹이 없으면 빈 배열을 출력하세요: {"groups": []}
- 한 그룹에 기존 기사(E로 시작)와 신규 기사(N으로 시작)가 섞여 있을 수 있습니다."""


def normalize_url(url: str) -> str:
    """Canonicalize a URL for exact-match dedup: lowercase host, drop tracking
    params, sort remaining query, strip fragment and trailing slash."""
    logger.info("normalize_url 시작 - url=%s", url)
    url = (url or "").strip()
    try:
        parts = urlsplit(url)
    except ValueError:
        return url

    # 스킴과 호스트를 소문자로 통일하여 대소문자 차이로 인한 중복 방지
    scheme = (parts.scheme or "https").lower()
    netloc = parts.netloc.lower()
    # 경로 끝 슬래시 제거로 /path vs /path/ 동일 취급
    path = parts.path.rstrip("/") or "/"

    # 트래킹 파라미터(utm_*, gclid 등)를 제거한 뒤 정렬하여 쿼리 문자열 정규화
    kept = [
        (k, v)
        for k, v in parse_qsl(parts.query, keep_blank_values=True)
        if not k.lower().startswith(_TRACKING_PREFIXES) and k.lower() not in _TRACKING_KEYS
    ]
    kept.sort()
    query = urlencode(kept)

    normalized = urlunsplit((scheme, netloc, path, query, ""))
    logger.info("normalize_url 완료 - normalized=%s", normalized)
    return normalized


async def llm_dedup(
    db: Database,
    llm: LLMClient,
    new_article_ids: list[int],
    dedup_window_hours: int = 24,
) -> int:
    """LLM으로 중복 그룹 판단. 중복 처리된 기사 수 반환.

    실패 시 전체 duplicated=false 유지하고 에러 로깅.
    """
    if not new_article_ids:
        return 0

    # 신규 기사 조회
    new_rows = await db.fetch(
        "SELECT id, title, source, url, summary FROM feed_news "
        "WHERE id = ANY($1::int[])",
        new_article_ids,
    )
    if not new_rows:
        return 0

    # 24h 내 기존 기사 조회
    existing_rows = await db.fetch(
        "SELECT id, title, source, url, summary FROM feed_news "
        "WHERE duplicated = false "
        f"AND created_at >= NOW() - interval '{dedup_window_hours} hours' "
        "AND id != ALL($1::int[])",
        new_article_ids,
    )

    # 프롬프트 구성
    lines: list[str] = []

    if existing_rows:
        lines.append("[기존 기사]")
        for i, r in enumerate(existing_rows, 1):
            summary = (r["summary"] or "")[:100]
            lines.append(f"E{i}: 제목={r['title']} | 매체={r['source']} | URL={r['url']} | 요약={summary}")
        lines.append("")

    lines.append("[신규 기사]")
    for i, r in enumerate(new_rows, 1):
        summary = (r["summary"] or "")[:100]
        lines.append(f"N{i}: 제목={r['title']} | 매체={r['source']} | URL={r['url']} | 요약={summary}")

    user_prompt = "\n".join(lines)

    # idx → id 매핑
    existing_idx_to_id = {f"E{i}": r["id"] for i, r in enumerate(existing_rows, 1)}
    new_idx_to_id = {f"N{i}": r["id"] for i, r in enumerate(new_rows, 1)}
    idx_to_id = {**existing_idx_to_id, **new_idx_to_id}

    try:
        response = await llm.chat(DEDUP_SYSTEM_PROMPT, user_prompt)
        groups = _parse_groups(response)
    except Exception as e:
        logger.error("LLM dedup failed, keeping all as non-duplicate: %s", e)
        return 0

    if not groups:
        logger.info("LLM dedup: no duplicates found among %d new articles", len(new_rows))
        return 0

    # 중복 처리
    total_deduped = 0
    for group_indices in groups:
        ids_in_group = [idx_to_id[idx] for idx in group_indices if idx in idx_to_id]
        if len(ids_in_group) < 2:
            continue

        # 대표 기사: 가장 먼저 발견된 기사 (기존 기사 우선, 그 다음 신규)
        representative_id = ids_in_group[0]
        # 기존 기사가 있으면 그것을 대표로
        existing_ids_in_group = [idx_to_id[idx] for idx in group_indices
                                  if idx.startswith("E") and idx in idx_to_id]
        if existing_ids_in_group:
            representative_id = existing_ids_in_group[0]

        duplicate_ids = [aid for aid in ids_in_group if aid != representative_id]
        if duplicate_ids:
            await db.execute(
                "UPDATE feed_news SET duplicated = true, duplicated_news_id = $1, "
                "updated_at = NOW() WHERE id = ANY($2::int[])",
                representative_id,
                duplicate_ids,
            )
            total_deduped += len(duplicate_ids)

    logger.info("LLM dedup: %d duplicates marked among %d new articles",
                total_deduped, len(new_rows))
    return total_deduped


def _parse_groups(response: str) -> list[list[str]]:
    """LLM JSON 응답에서 groups 파싱."""
    text = response.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\n?|\n?```$", "", text).strip()
    lo, hi = text.find("{"), text.rfind("}")
    if lo == -1 or hi == -1 or hi < lo:
        raise ValueError("no JSON object in LLM dedup response")
    data = json.loads(text[lo:hi + 1])
    groups = data.get("groups", [])
    if not isinstance(groups, list):
        return []
    return [g for g in groups if isinstance(g, list) and len(g) >= 2]
