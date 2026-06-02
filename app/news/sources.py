# news/sources.py
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RssSource:
    name: str
    url: str
    active: bool = True
