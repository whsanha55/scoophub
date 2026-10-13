package com.scoophub.hotdeal.repository

import com.scoophub.hotdeal.entity.HotdealFeedItemEntity
import com.scoophub.hotdeal.entity.HotdealInterestProfileEntity
import com.scoophub.hotdeal.entity.HotdealItemEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface HotdealItemRepository : JpaRepository<HotdealItemEntity, Long> {
    fun findByItemAtAfter(itemAt: Instant): List<HotdealItemEntity>
}

interface HotdealInterestProfileRepository : JpaRepository<HotdealInterestProfileEntity, Long>

interface HotdealFeedItemRepository : JpaRepository<HotdealFeedItemEntity, Long> {

    @Query("SELECT f FROM HotdealFeedItemEntity f WHERE f.item.id IN :itemIds")
    fun findAllByItemIds(itemIds: Collection<Long>): List<HotdealFeedItemEntity>

    @Query(
        value = "SELECT f FROM HotdealFeedItemEntity f JOIN FETCH f.item",
        countQuery = "SELECT COUNT(f) FROM HotdealFeedItemEntity f",
    )
    fun findAllWithItem(pageable: Pageable): Page<HotdealFeedItemEntity>
}
