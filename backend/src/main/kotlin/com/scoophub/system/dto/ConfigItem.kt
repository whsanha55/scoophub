package com.scoophub.system.dto

import com.scoophub.global.schedule.entity.CrawlConfigEntity
import tools.jackson.databind.JsonNode
import java.time.Instant

/** legacy `_row_to_dict` — crawl_config row */
data class ConfigItem(val crawler: String, val params: JsonNode, val updatedAt: Instant) {
    companion object {
        fun from(entity: CrawlConfigEntity) = ConfigItem(
            crawler = entity.crawler,
            params = entity.params,
            updatedAt = entity.updatedAt,
        )
    }
}
