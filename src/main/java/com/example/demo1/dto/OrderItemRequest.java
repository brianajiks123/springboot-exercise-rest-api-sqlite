package com.example.demo1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A product line item within an order")
public record OrderItemRequest(

                @Schema(description = "ID of the product being ordered", example = "1", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "productId is required") Long productId,

                @Schema(description = "Quantity of the product", example = "2", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "quantity is required") @Min(value = 1, message = "quantity must be at least 1") Integer quantity) {
}