package com.scoophub.kal

import com.scoophub.global.jackson.elements
import com.scoophub.global.jackson.scalar
import tools.jackson.databind.JsonNode

data class KalFlight(
    val flight: String?,
    val depTime: String?,
    val frontBookingClass: String?,
    val cabinLabel: String,
    val available: Boolean,
)

data class KalDay(val date: String?, val flights: List<KalFlight>)

data class KalParsed(val departure: String?, val arrival: String?, val days: List<KalDay>)

/** legacy `kal_bonus_scraper.parse_bonus_response` — API 원문 → 구조화 (cabin_label 부여) */
object KalBonusResponseParser {

    /** availableSeat → 잔석 존재. 문자열("0")/정수/null 혼합 → bool() 오탐 방지 */
    fun hasSeat(value: JsonNode?): Boolean = value != null && !value.isNull && value.asInt(0) > 0

    fun parse(raw: JsonNode): KalParsed {
        val days = raw["flightList"].elements().map { flightDay ->
            KalDay(
                date = flightDay.scalar("departureDate"),
                flights = flightDay["flightDetailList"].elements().map { d ->
                    val fbc = d.scalar("frontBookingClass")
                    KalFlight(
                        flight = d.scalar("flightNumber"),
                        depTime = d.scalar("departureTime"),
                        frontBookingClass = fbc,
                        cabinLabel = KalCabin.map(fbc),
                        available = hasSeat(d["availableSeat"]),
                    )
                },
            )
        }
        return KalParsed(
            departure = raw.scalar("departureAirport"),
            arrival = raw.scalar("arrivalAirport"),
            days = days,
        )
    }
}
