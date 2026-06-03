# news/classifier.py
from __future__ import annotations

from pathlib import Path

import yaml


class NewsClassifier:
    def __init__(self, keywords_path: str | Path = "config/keywords.yaml"):
        with open(keywords_path) as f:
            data = yaml.safe_load(f)
        self._categories: dict[str, list[str]] = {
            name: cfg["keywords"] for name, cfg in data["categories"].items()
        }
        self._exclude: list[str] = data["exclude"]["keywords"]

    def classify_category(self, text: str) -> str | None:
        text_lower = text.lower()
        best: str | None = None
        best_count = 0
        for cat, keywords in self._categories.items():
            count = sum(1 for kw in keywords if kw.lower() in text_lower)
            if count > best_count:
                best_count = count
                best = cat
        return best

    def should_exclude(self, text: str) -> bool:
        text_lower = text.lower()
        return any(kw.lower() in text_lower for kw in self._exclude)

    def classify(self, text: str) -> str | None:
        """Return category name, or None if excluded / uncategorized."""
        if self.should_exclude(text):
            return None
        return self.classify_category(text)
