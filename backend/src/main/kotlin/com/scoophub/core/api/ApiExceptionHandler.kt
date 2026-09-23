package com.scoophub.core.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * FastAPI `HTTPException` 호환 에러 바디 `{"detail": "..."}`.
 * 핸들러에서는 `ResponseStatusException(status, detail)` 을 던진다.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val detail = (body as? ProblemDetail)?.detail
            ?: HttpStatus.resolve(statusCode.value())?.reasonPhrase
            ?: statusCode.toString()
        return ResponseEntity.status(statusCode).headers(headers).body(ErrorBody(detail))
    }
}

data class ErrorBody(val detail: String)
