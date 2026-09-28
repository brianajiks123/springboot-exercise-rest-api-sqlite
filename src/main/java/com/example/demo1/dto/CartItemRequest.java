package com.example.demo1.dto;

import com.example.demo1.model.CartItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Payload for adding a product to a cart")
public record CartItemRequest(
        @Schema(description = "ID of the product to add", example = "1", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "productId is required") Long productId,

        @Schema(description = "Quantity to add. Adding a product that is already in the cart adds to the existing quantity, and the line as a whole never exceeds 999 units.", example = "2", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "quantity is required") @Min(value = 1, message = "quantity must be at least 1") @Max(value = CartItem.MAX_QUANTITY, message = "quantity must be at most " + CartItem.MAX_QUANTITY) Integer quantity) {
}
