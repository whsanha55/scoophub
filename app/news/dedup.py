# news/dedup.py
from __future__ import annotations

import logging
from difflib import SequenceMatcher
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

logger = logging.getLogger(__name__)

# Query params that carry tracking/session noise, not article identity.
_TRACKING_PREFIXES = ("utm_",)
_TRACKING_KEYS = {
    "gclid", "fbclid", "ocid", "oc", "cmpid", "ref", "ref_src",
    "spm", "igshid", "mc_cid", "mc_eid",
}


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


def _norm_title(title: str) -> str:
    return " ".join((title or "").lower().split())


def is_duplicate_title(title: str, recent_titles, threshold: float) -> bool:
    """True if `title` is similar (>= threshold) to any recent title."""
    logger.info("is_duplicate_title 시작 - title=%s, 비교 대상=%d건, 임계값=%.2f", title, len(recent_titles), threshold)
    # 공백 정규화 및 소문자 변환으로 비교 기준 통일
    t = _norm_title(title)
    if not t:
        return False
    # SequenceMatcher를 이용한 문자열 유사도 비교로 제목 중복 판별
    for other in recent_titles:
        if SequenceMatcher(None, t, _norm_title(other)).ratio() >= threshold:
            logger.info("is_duplicate_title 완료 - 중복 감지 (유사도 %.2f 이상)", threshold)
            return True
    return False
