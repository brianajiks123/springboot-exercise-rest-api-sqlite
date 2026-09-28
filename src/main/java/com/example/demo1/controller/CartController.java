package com.example.demo1.controller;

import com.example.demo1.dto.CartItemQuantityRequest;
import com.example.demo1.dto.CartItemRequest;
import com.example.demo1.dto.CartResponse;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carts/{customerId}")
@Validated
@Tag(name = "Carts", description = "Shopping cart of one customer, identified by customerId")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "Get a customer's cart", description = "Returns an empty cart when the customer has none yet. Reading never creates a cart.")
    public CartResponse getCart(@PathVariable @Size(max = 64, message = "customerId must be at most 64 characters") @Pattern(regexp = "[A-Za-z0-9._-]+", message = "customerId may only contain letters, digits, dots, underscores and dashes") String customerId) {
        return cartService.getCart(customerId);
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product to the cart", description = "Adding a product that is already in the cart adds to its existing quantity. Stock is not reserved.")
    public CartResponse addItem(
            @PathVariable @Size(max = 64, message = "customerId must be at most 64 characters") @Pattern(regexp = "[A-Za-z0-9._-]+", message = "customerId may only contain letters, digits, dots, underscores and dashes") String customerId,
            @Valid @RequestBody CartItemRequest request) {
        return cartService.addItem(customerId, request);
    }

    @PutMapping("/items/{productId}")
    @Operation(summary = "Set the absolute quantity of a cart line", description = "Sending 5 makes the line 5 units regardless of what it held before. To remove a line, use DELETE.")
    public CartResponse updateItemQuantity(
            @PathVariable @Size(max = 64, message = "customerId must be at most 64 characters") @Pattern(regexp = "[A-Za-z0-9._-]+", message = "customerId may only contain letters, digits, dots, underscores and dashes") String customerId,
            @PathVariable Long productId,
            @Valid @RequestBody CartItemQuantityRequest request) {
        return cartService.updateItemQuantity(customerId, productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove one product from the cart")
    public ResponseEntity<Void> removeItem(
            @PathVariable @Size(max = 64, message = "customerId must be at most 64 characters") @Pattern(regexp = "[A-Za-z0-9._-]+", message = "customerId may only contain letters, digits, dots, underscores and dashes") String customerId,
            @PathVariable Long productId) {
        cartService.removeItem(customerId, productId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart", description = "Keeps the cart itself, removes every line.")
    public ResponseEntity<Void> clearCart(
            @PathVariable @Size(max = 64, message = "customerId must be at most 64 characters") @Pattern(regexp = "[A-Za-z0-9._-]+", message = "customerId may only contain letters, digits, dots, underscores and dashes") String customerId) {
        cartService.clear(customerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checkout")
    @Operation(summary = "Turn the cart into an order awaiting payment", description = "Creates an order with status PENDING_PAYMENT, snapshots the current prices, and empties the cart. Stock is NOT deducted here , it is deducted when the cashier confirms payment via POST /api/orders/{id}/pay.")
    public ResponseEntity<OrderResponse> checkout(
            @PathVariable @Size(max = 64, message = "customerId must be at most 64 characters") @Pattern(regexp = "[A-Za-z0-9._-]+", message = "customerId may only contain letters, digits, dots, underscores and dashes") String customerId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cartService.checkout(customerId));
    }
}
