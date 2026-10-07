-- 코드의 JSONB 조건(response -> 'x' @>, ->> 비교)은 이 GIN 인덱스를 쓰지 않는다 (#216).
DROP INDEX IF EXISTS ix_crawl_data_resp;
