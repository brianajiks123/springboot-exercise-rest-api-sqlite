package com.example.demo1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;

@Schema(description = "The single error body this API returns, whatever the status code")
public record ApiErrorResponse(
        @Schema(description = "When the error was produced", example = "2026-09-23T08:49:45") LocalDateTime timestamp,

        @Schema(description = "HTTP status code", example = "409") int status,

        @Schema(description = "HTTP reason phrase", example = "Conflict") String error,

        @Schema(description = "Human-readable explanation", example = "Order 7 is PAID and can no longer be paid") String message,

        @Schema(description = "Path that was requested", example = "/api/orders/7/pay") String path,

        @Schema(description = "Field name to validation message; null unless a request failed validation", example = "{\"name\":\"Name must not be blank\"}") Map<String, String> fieldErrors) {
}
