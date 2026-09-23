package com.example.demo1.dto;

import com.example.demo1.model.Order;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Order representation returned to the client")
public record OrderResponse(

                @Schema(description = "Order ID", example = "1") Long id,

                @Schema(description = "Order creation date", example = "2026-08-20T12:00:00") LocalDateTime orderDate,

                @Schema(description = "Order status", example = "PENDING") Order.Status status,

                @Schema(description = "Line items of the order") List<OrderItemResponse> items,

                @Schema(description = "Total price of the order", example = "30000000") BigDecimal totalPrice) {

        public static OrderResponse from(Order order) {
                List<OrderItemResponse> itemResponses = order.getItems().stream()
                                .map(OrderItemResponse::from)
                                .toList();
                BigDecimal total = itemResponses.stream()
                                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));
                return new OrderResponse(
                                order.getId(),
                                order.getOrderDate(),
                                order.getStatus(),
                                itemResponses,
                                total);
        }
}