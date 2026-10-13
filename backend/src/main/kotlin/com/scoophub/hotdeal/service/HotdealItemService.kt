package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.repository.HotdealItemQueryRepository
import com.scoophub.hotdeal.vo.HotdealItemUpsert
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

@Service
class HotdealItemService(
    private val repository: HotdealItemQueryRepository,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {

    /** 수집 결과를 저장하고 신규 행 수를 돌려준다 */
    @Transactional
    fun saveAll(items: List<HotdealItemUpsert>): Int {
        val seenAt = clock.instant()
        return items.count { repository.upsert(it, jsonMapper.writeValueAsString(it.extra), seenAt) }
    }
}
