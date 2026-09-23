package com.example.demo1.dto;

import com.example.demo1.model.OrderItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Order line item returned to the client")
public record OrderItemResponse(

        @Schema(description = "ID of the product", example = "1") Long productId,

        @Schema(description = "Product name", example = "Laptop") String productName,

        @Schema(description = "Quantity ordered", example = "2") Integer quantity,

        @Schema(description = "Unit price at order time", example = "15000000") BigDecimal unitPrice) {

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getQuantity(),
                item.getUnitPrice());
    }
}