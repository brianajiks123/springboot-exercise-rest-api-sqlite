package com.example.demo1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Payload for adding a product to a cart")
public record CartItemRequest(

        @Schema(description = "ID of the product to add", example = "1", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "productId is required") Long productId,

        @Schema(description = "Quantity to add. Adding a product that is already in the cart adds to the existing quantity.", example = "2", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "quantity is required") @Min(value = 1, message = "quantity must be at least 1") @Max(value = 999, message = "quantity must be at most 999") Integer quantity) {
}
