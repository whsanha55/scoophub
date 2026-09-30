package com.scoophub.system

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.system.dto.ConfigItem
import com.scoophub.system.service.SystemConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.node.ObjectNode
import java.time.Clock

/** legacy `system/config_router.py` — crawl_config 관리 API */
@RestController
class SystemConfigController(private val configService: SystemConfigService, private val clock: Clock) {
    data class ConfigPatchRequest(val params: ObjectNode)

    @Tag(name = "Crawler Config")
    @Operation(summary = "전체 crawler config 조회")
    @GetMapping("/api/config")
    fun listConfigs(): ApiResponse<List<ConfigItem>> {
        val items = configService.findAll().map { ConfigItem.from(it) }
        return ApiResponse.ok(items, ResponseMeta(clock.instant()))
    }

    @Tag(name = "Crawler Config")
    @Operation(summary = "단일 crawler config 조회")
    @GetMapping("/api/config/{crawler}")
    fun getConfig(@PathVariable crawler: String): ApiResponse<ConfigItem> =
        ApiResponse.ok(ConfigItem.from(configService.find(crawler)), ResponseMeta(clock.instant()))

    @Tag(name = "Crawler Config")
    @Operation(summary = "crawler params 갱신 (런타임 반영)")
    @SuperOnly
    @PatchMapping("/api/config/{crawler}")
    fun updateConfig(@PathVariable crawler: String, @RequestBody body: ConfigPatchRequest): ApiResponse<ConfigItem> =
        ApiResponse.ok(ConfigItem.from(configService.updateParams(crawler, body.params)), ResponseMeta(clock.instant()))
}
