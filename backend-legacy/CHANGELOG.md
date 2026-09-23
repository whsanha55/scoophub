# Changelog

## [Unreleased] — 도메인 구조 개편

### Added
- 금융 데이터 실시간 스크래퍼 모듈 (`app/finance_scraper/`) — TradingView, Barchart (#71)
- Flyway 마이그레이션 V1~V6 재작성 (#75)

### Changed
- 디렉토리 그룹핑: `community/`, `feed/` (#73, #74)
- DB 테이블명 그룹핑: `community_*`, `feed_*` (#75)
- import 경로 및 wiring 모듈 경로 업데이트 (#74)

### Removed
- `exchange_crypto` 도메인 제거 (#72)
- `rss_universal` 도메인 제거 (#72)
- 기존 Flyway 마이그레이션 V1~V29 삭제 (#75)
- `settings.yaml`에서 `exchange_crypto`, `rss_universal` 설정 키 삭제 (#76)
