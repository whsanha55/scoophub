# news/dedup.py
from __future__ import annotations

from difflib import SequenceMatcher
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

# Query params that carry tracking/session noise, not article identity.
_TRACKING_PREFIXES = ("utm_",)
_TRACKING_KEYS = {
    "gclid", "fbclid", "ocid", "oc", "cmpid", "ref", "ref_src",
    "spm", "igshid", "mc_cid", "mc_eid",
}


def normalize_url(url: str) -> str:
    """Canonicalize a URL for exact-match dedup: lowercase host, drop tracking
    params, sort remaining query, strip fragment and trailing slash."""
    url = (url or "").strip()
    try:
        parts = urlsplit(url)
    except ValueError:
        return url

    scheme = (parts.scheme or "https").lower()
    netloc = parts.netloc.lower()
    path = parts.path.rstrip("/") or "/"

    kept = [
        (k, v)
        for k, v in parse_qsl(parts.query, keep_blank_values=True)
        if not k.lower().startswith(_TRACKING_PREFIXES) and k.lower() not in _TRACKING_KEYS
    ]
    kept.sort()
    query = urlencode(kept)

    return urlunsplit((scheme, netloc, path, query, ""))


def _norm_title(title: str) -> str:
    return " ".join((title or "").lower().split())


def is_duplicate_title(title: str, recent_titles, threshold: float) -> bool:
    """True if `title` is similar (>= threshold) to any recent title."""
    t = _norm_title(title)
    if not t:
        return False
    for other in recent_titles:
        if SequenceMatcher(None, t, _norm_title(other)).ratio() >= threshold:
            return True
    return False
