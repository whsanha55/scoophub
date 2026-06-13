# app/crawl_data/__init__.py
"""Generic crawl_data 캐시 테이블 접근 레이어.

"크롤 → 최신 응답 저장 → 최신 조회" 패턴의 도메인이 공통으로 사용하는
upsert / 조회 헬퍼. 개별 도메인(ex. KAL 보너스 좌석)은 category/purpose/key
조합만 지정해 재사용한다.
"""
from app.crawl_data.repo import CrawlDataRepo, upsert_crawl_data

__all__ = ["CrawlDataRepo", "upsert_crawl_data"]
