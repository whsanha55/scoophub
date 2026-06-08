# news/filter_rules.py
from __future__ import annotations

import logging
from datetime import datetime, timezone

logger = logging.getLogger(__name__)


def passes_cutoff(published_at: datetime | None, cutoff: datetime) -> bool:
    """Keep articles published at or after the cutoff. Undated articles are kept."""
    logger.info("passes_cutoff 시작 - published_at=%s, cutoff=%s", published_at, cutoff)
    if published_at is None:
        return True
    # 타임존 정보가 없으면 UTC로 간주
    if published_at.tzinfo is None:
        published_at = published_at.replace(tzinfo=timezone.utc)
    return published_at >= cutoff
