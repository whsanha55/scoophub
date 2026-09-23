# news/sources.py
# RSS 피드 소스 정의 - 크롤러가 수집할 뉴스 피드의 메타데이터를 표현합니다.
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RssSource:
    """단일 RSS 피드 소스를 나타내는 불변 데이터 클래스."""
    name: str
    url: str
    active: bool = True
