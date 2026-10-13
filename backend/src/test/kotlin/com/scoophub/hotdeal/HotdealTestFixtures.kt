package com.scoophub.hotdeal

import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.enums.HotdealSourceEnum
import com.scoophub.hotdeal.enums.ProfileKindEnum
import com.scoophub.hotdeal.repository.HotdealInterestProfileRepository
import com.scoophub.hotdeal.service.HotdealItemService
import com.scoophub.hotdeal.vo.HotdealItemUpsert
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant

/** 핫딜 통합 테스트 공용 데이터 준비 */
class HotdealTestFixtures(
    private val jdbcClient: JdbcClient,
    private val itemService: HotdealItemService,
    private val profileRepository: HotdealInterestProfileRepository,
) {
    fun clean() {
        jdbcClient.sql("DELETE FROM hotdeal_feed_item").update()
        jdbcClient.sql("DELETE FROM hotdeal_interest_profile").update()
        jdbcClient.sql("DELETE FROM hotdeal_item").update()
    }

    fun saveProfile(kind: ProfileKindEnum, value: String, weight: Double) {
        profileRepository.save(HotdealInterestProfileEntity(kind = kind, value = value, seedWeight = weight))
    }

    /** 콜드스타트 인기 노출을 끄기 위해 관심 키워드 5개를 채운다 */
    fun saveEnoughInterests(vararg excludeValues: String) {
        listOf("에어프라이어", "캠핑", "커피머신", "안마의자", "프로젝터")
            .filter { it !in excludeValues }
            .forEach { saveProfile(ProfileKindEnum.KEYWORD, it, 0.5) }
    }

    fun saveItem(sourceKey: String, title: String, votes: Long, itemAt: Instant) {
        itemService.saveAll(
            listOf(
                HotdealItemUpsert(
                    source = HotdealSourceEnum.ZOD,
                    provider = "zod",
                    sourceKey = sourceKey,
                    title = title,
                    url = "https://zod.kr/deal/$sourceKey",
                    itemAt = itemAt,
                    extra = mapOf("mall" to "지마켓", "price" to 10000L, "shipping_fee" to "무료", "votes" to votes),
                ),
            ),
        )
    }
}
