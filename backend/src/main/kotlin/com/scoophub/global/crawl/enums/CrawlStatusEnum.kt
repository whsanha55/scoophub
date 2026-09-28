package com.scoophub.global.crawl.enums

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/** crawl_logs.status */
enum class CrawlStatusEnum {
    SUCCESS,
    PARTIAL,
    ERROR,
}

/** DB 값은 legacy 소문자(success/partial/error)와 1:1 유지 */
@Converter(autoApply = true)
class CrawlStatusEnumConverter : AttributeConverter<CrawlStatusEnum, String> {

    override fun convertToDatabaseColumn(attribute: CrawlStatusEnum): String = attribute.name.lowercase()

    override fun convertToEntityAttribute(dbData: String): CrawlStatusEnum =
        CrawlStatusEnum.entries.first { it.name.lowercase() == dbData }
}
