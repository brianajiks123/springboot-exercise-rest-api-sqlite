package com.example.demo1.dto;

import com.example.demo1.model.CartItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "New absolute quantity for an existing cart line")
public record CartItemQuantityRequest(
        @Schema(description = "New quantity for the line", example = "5", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "quantity is required") @Min(value = 1, message = "quantity must be at least 1") @Max(value = CartItem.MAX_QUANTITY, message = "quantity must be at most " + CartItem.MAX_QUANTITY) Integer quantity) {
}
