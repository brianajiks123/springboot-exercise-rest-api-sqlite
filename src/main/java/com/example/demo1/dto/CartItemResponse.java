package com.example.demo1.dto;

import com.example.demo1.model.CartItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Cart line returned to the client")
public record CartItemResponse(
        @Schema(description = "ID of the product", example = "1") Long productId,

        @Schema(description = "Product name", example = "Laptop") String productName,

        @Schema(description = "Current unit price of the product", example = "15000000.00") BigDecimal unitPrice,

        @Schema(description = "Quantity in the cart", example = "2") Integer quantity,

        @Schema(description = "unitPrice multiplied by quantity", example = "30000000.00") BigDecimal subtotal) {

    public static CartItemResponse from(CartItem item) {
        BigDecimal unitPrice = item.getProduct().getPrice();
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(
                item.getProduct().getId(),
                item.getProduct().getName(),
                unitPrice,
                item.getQuantity(),
                subtotal);
    }
}
