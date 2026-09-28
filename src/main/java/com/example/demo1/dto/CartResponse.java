package com.example.demo1.dto;

import com.example.demo1.model.Cart;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Schema(description = "A customer's cart with live prices and a running total")
public record CartResponse(
        @Schema(description = "Cart owner", example = "budi") String customerId,

        @Schema(description = "Lines in the cart, ordered by product ID") List<CartItemResponse> items,

        @Schema(description = "Sum of all line quantities", example = "3") int totalItems,

        @Schema(description = "Sum of all line subtotals", example = "30000000.00") BigDecimal totalPrice,

        @Schema(description = "When the cart was last modified; null for a cart that has never been written", example = "2026-09-23T10:15:30") LocalDateTime updatedAt) {

    public static CartResponse from(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .map(CartItemResponse::from)
                .toList();

        int totalItems = items.stream()
                .mapToInt(item -> item.quantity())
                .sum();

        BigDecimal totalPrice = items.stream()
                .map(item -> item.subtotal())
                .reduce(BigDecimal.ZERO, (running, line) -> running.add(line));

        return new CartResponse(cart.getCustomerId(), items, totalItems, totalPrice, cart.getUpdatedAt());
    }

    public static CartResponse empty(String customerId) {
        return new CartResponse(customerId, List.of(), 0, BigDecimal.ZERO, null);
    }
}
