# news/filter_rules.py
from __future__ import annotations

from datetime import datetime, timezone


def passes_cutoff(published_at: datetime | None, cutoff: datetime) -> bool:
    """Keep articles published at or after the cutoff. Undated articles are kept."""
    if published_at is None:
        return True
    if published_at.tzinfo is None:
        published_at = published_at.replace(tzinfo=timezone.utc)
    return published_at >= cutoff
