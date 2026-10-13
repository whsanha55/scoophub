package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.dto.HotdealFeedItemResponse
import com.scoophub.hotdeal.enums.HotdealFeedSortEnum
import com.scoophub.hotdeal.repository.HotdealFeedItemRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class HotdealFeedService(private val feedItemRepository: HotdealFeedItemRepository) {

    data class FeedPage(val items: List<HotdealFeedItemResponse>, val total: Long)

    /** page 는 1부터 */
    fun findFeed(sort: String, limit: Int, page: Int): FeedPage {
        val feedSort = HotdealFeedSortEnum.fromParam(sort)
        if (feedSort == null || limit !in 1..100 || page < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid hotdeal feed filters")
        }
        val result = feedItemRepository.findAllWithItem(PageRequest.of(page - 1, limit, feedSort.sort))
        return FeedPage(result.content.map { HotdealFeedItemResponse.from(it) }, result.totalElements)
    }
}
