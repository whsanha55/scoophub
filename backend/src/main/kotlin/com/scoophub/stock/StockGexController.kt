package com.scoophub.stock

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.stock.dto.GexOut
import com.scoophub.stock.service.StockGexService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/** 행사가별 GEX 분포 조회 (#264). 공개 GET, 없으면 data null */
@RestController
@RequestMapping("/api")
class StockGexController(private val gexService: StockGexService, private val clock: Clock) {

    @Tag(name = "Stock")
    @Operation(summary = "행사가별 GEX 분포")
    @GetMapping("/stock/gex/{ticker}")
    fun getGex(@PathVariable ticker: String): ApiResponse<GexOut?> =
        ApiResponse.ok(gexService.findLatest(ticker.uppercase()), ResponseMeta(clock.instant()))
}
