-- V46: 핫딜 제외 키워드와 LLM 관련성·제외 판정 (#271, HOTDEAL_PLAN 8.4)

-- 제외 키워드는 관심도 프로필의 한 종류로 둔다. seed_weight 는 쓰지 않는다(0).
ALTER TABLE hotdeal_interest_profile DROP CONSTRAINT hotdeal_interest_profile_kind_check;
ALTER TABLE hotdeal_interest_profile ADD CONSTRAINT hotdeal_interest_profile_kind_check
    CHECK (kind IN ('KEYWORD', 'CATEGORY', 'SOURCE', 'EXCLUDE'));

-- judgment: {relevant, excluded, reason, status(llm|rule|fallback), model, input_hash, judged_at}
-- judged_out: 관련 없음 또는 제외 판정. 기본 피드에서 숨기고 숨긴 항목에서 사유와 함께 보여준다.
ALTER TABLE hotdeal_feed_item ADD COLUMN judgment JSONB;
ALTER TABLE hotdeal_feed_item ADD COLUMN judged_out BOOLEAN NOT NULL DEFAULT FALSE;
