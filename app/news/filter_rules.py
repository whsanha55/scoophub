# news/filter_rules.py
from __future__ import annotations

from datetime import datetime, timezone, timedelta


def is_within_cutoff(
    published_at: datetime | None,
    cutoff_minutes: int = 30,
) -> bool:
    """Reject articles published before the cutoff window."""
    if published_at is None:
        return True
    now = datetime.now(timezone.utc)
    if published_at.tzinfo is None:
        published_at = published_at.replace(tzinfo=timezone.utc)
    cutoff = now - timedelta(minutes=cutoff_minutes)
    return published_at >= cutoff
