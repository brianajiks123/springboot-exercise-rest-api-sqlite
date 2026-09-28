package com.example.demo1.controller;

import com.example.demo1.dto.CartItemQuantityRequest;
import com.example.demo1.dto.CartItemRequest;
import com.example.demo1.dto.CartResponse;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * The cart a buyer fills before going to the cashier.
 *
 * <p>There is no authentication yet, so the cart is addressed by a client-supplied
 * {@code customerId} in the path (for example {@code /api/carts/budi}). Anyone who knows the
 * id can read and change that cart; that is an accepted limitation of this exercise, not a
 * security boundary.
 */
@RestController
@RequestMapping("/api/carts/{customerId}")
@Tag(name = "Carts", description = "Shopping cart of one customer, identified by customerId")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "Get a customer's cart", description = "Returns an empty cart when the customer has none yet. Reading never creates a cart.")
    public CartResponse getCart(@PathVariable String customerId) {
        return cartService.getCart(customerId);
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product to the cart", description = "Adding a product that is already in the cart adds to its existing quantity. Stock is not reserved.")
    public CartResponse addItem(@PathVariable String customerId,
            @Valid @RequestBody CartItemRequest request) {
        return cartService.addItem(customerId, request);
    }

    @PutMapping("/items/{productId}")
    @Operation(summary = "Set the absolute quantity of a cart line", description = "Sending 5 makes the line 5 units regardless of what it held before. To remove a line, use DELETE.")
    public ResponseEntity<CartResponse> updateItemQuantity(@PathVariable String customerId,
            @PathVariable Long productId,
            @Valid @RequestBody CartItemQuantityRequest request) {
        return ResponseEntity.ofNullable(
                cartService.updateItemQuantity(customerId, productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove one product from the cart")
    public ResponseEntity<Void> removeItem(@PathVariable String customerId, @PathVariable Long productId) {
        return cartService.removeItem(customerId, productId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart", description = "Keeps the cart itself, removes every line.")
    public ResponseEntity<Void> clearCart(@PathVariable String customerId) {
        return cartService.clear(customerId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/checkout")
    @Operation(summary = "Turn the cart into an order awaiting payment", description = "Creates an order with status PENDING_PAYMENT, snapshots the current prices, and empties the cart. Stock is NOT deducted here , it is deducted when the cashier confirms payment via POST /api/orders/{id}/pay.")
    public ResponseEntity<OrderResponse> checkout(@PathVariable String customerId) {
        OrderResponse order = cartService.checkout(customerId);
        return order == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.status(HttpStatus.CREATED).body(order);
    }
}
