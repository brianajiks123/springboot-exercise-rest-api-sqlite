package com.example.demo1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code PUT /api/carts/{customerId}/items/{productId}}.
 *
 * <p>The quantity is absolute, not a delta: sending {@code 5} makes the line 5 units,
 * whatever it held before. This mirrors how e-commerce carts behave when the buyer types
 * a number into the quantity box. To remove a line use the {@code DELETE} endpoint rather
 * than sending {@code 0}, so that "remove" has exactly one meaning.
 */
@Schema(description = "New absolute quantity for an existing cart line")
public record CartItemQuantityRequest(

        @Schema(description = "New quantity for the line", example = "5", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "quantity is required") @Min(value = 1, message = "quantity must be at least 1") @Max(value = 999, message = "quantity must be at most 999") Integer quantity) {
}
