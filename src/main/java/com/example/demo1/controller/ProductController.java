package com.example.demo1.controller;

import com.example.demo1.dto.ProductRequest;
import com.example.demo1.dto.ProductResponse;
import com.example.demo1.openapi.ApiBadRequest;
import com.example.demo1.openapi.ApiConflict;
import com.example.demo1.openapi.ApiNotFound;
import com.example.demo1.openapi.ApiServerError;
import com.example.demo1.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "CRUD operations for products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @Operation(summary = "Get all products", description = "Returns the full list of products.")
    @ApiResponse(responseCode = "200", description = "Every product", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProductResponse.class))))
    @ApiServerError
    public List<ProductResponse> getAllProducts() {
        return productService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a product by ID")
    @ApiResponse(responseCode = "200", description = "The product", content = @Content(schema = @Schema(implementation = ProductResponse.class)))
    @ApiBadRequest
    @ApiNotFound
    @ApiServerError
    public ProductResponse getProductById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a new product")
    @ApiResponse(responseCode = "201", description = "The product was created", content = @Content(schema = @Schema(implementation = ProductResponse.class)))
    @ApiBadRequest
    @ApiServerError
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a product by ID")
    @ApiResponse(responseCode = "200", description = "The product, as updated", content = @Content(schema = @Schema(implementation = ProductResponse.class)))
    @ApiBadRequest
    @ApiNotFound
    @ApiConflict
    @ApiServerError
    public ProductResponse updateProduct(@PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a product by ID")
    @ApiResponse(responseCode = "204", description = "The product was deleted")
    @ApiBadRequest
    @ApiNotFound
    @ApiConflict
    @ApiServerError
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
