-- V10: DROP legacy 도메인 테이블 — crawl_data 이관 완료 (#123)
-- community/feed/weather 도메인의 9개 전용 테이블은 crawl_data(category, purpose, key)로
-- 이관되어 런타임 SQL 참조가 0건. 전용 테이블 제거.
--
-- Flyway checksum 보호: V2/V3/V5의 CREATE 문은 그대로 두고 본 마이그레이션에서 DROP만 수행.
-- 신규 환경에서는 create → drop 사이클이 발생하지만, 이미 마이그레이션된 운영 DB의
-- checksum 변경을 유발하지 않음.
--
-- 제외 (NO-GO): crawl_logs (별도 테이블 유지, #962635c), feed_news, stock 6테이블,
--               crawl_sources, crawler_metadata, users

DROP TABLE IF EXISTS community_hackernews;
DROP TABLE IF EXISTS community_reddit;
DROP TABLE IF EXISTS community_producthunt;
DROP TABLE IF EXISTS community_github;
DROP TABLE IF EXISTS feed_newsletter;
DROP TABLE IF EXISTS feed_devblog;
DROP TABLE IF EXISTS feed_arxiv;
DROP TABLE IF EXISTS feed_youtube;
DROP TABLE IF EXISTS weather_snapshots;
