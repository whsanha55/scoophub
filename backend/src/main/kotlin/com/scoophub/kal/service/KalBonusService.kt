package com.scoophub.kal.service

import com.scoophub.kal.KalBonusResponseParser
import com.scoophub.kal.dto.KalBonusCondition
import com.scoophub.kal.dto.KalBonusResponse
import com.scoophub.kal.repository.KalBonusQueryRepository
import org.springframework.stereotype.Service

@Service
class KalBonusService(private val repository: KalBonusQueryRepository) {

    fun findMonths(): List<String> = repository.findMonths()

    fun find(condition: KalBonusCondition): List<KalBonusResponse> = repository.findAll(condition)
        .map { KalBonusResponse.from(it, KalBonusResponseParser.parse(it.response)) }
}
