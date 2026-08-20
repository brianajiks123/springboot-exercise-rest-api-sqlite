package com.example.demo1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload for creating or updating a product")
public record ProductRequest(

                @Schema(description = "Product name", example = "Laptop", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "Name must not be blank") @Size(max = 255, message = "Name must be at most 255 characters") String name,

                @Schema(description = "Product description", example = "Laptop 14 inch, 16GB RAM") @Size(max = 2000, message = "Description must be at most 2000 characters") String description,

                @Schema(description = "Product price", example = "15000000", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "Price is required") @DecimalMin(value = "0.0", message = "Price must not be negative") Double price,

                @Schema(description = "Product stock", example = "10") @Min(value = 0, message = "Stock must not be negative") Integer stock) {
}
