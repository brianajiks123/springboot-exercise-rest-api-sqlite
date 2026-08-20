package com.example.demo1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.example.demo1.model.Product;

@Schema(description = "Product representation returned to the client")
public record ProductResponse(

        @Schema(description = "Product ID", example = "1") Long id,

        @Schema(description = "Product name", example = "Laptop") String name,

        @Schema(description = "Product description", example = "Laptop 14 inch, 16GB RAM") String description,

        @Schema(description = "Product price", example = "15000000") Double price,

        @Schema(description = "Product stock", example = "10") Integer stock) {

    /** Maps an entity to a response DTO. */
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock());
    }
}
