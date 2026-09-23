package com.example.demo1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "Payload for creating an order")
public record OrderRequest(

                @Schema(description = "Line items of the order", requiredMode = Schema.RequiredMode.REQUIRED) @NotEmpty(message = "items must not be empty") List<@Valid OrderItemRequest> items) {
}