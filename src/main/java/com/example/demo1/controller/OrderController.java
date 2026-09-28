package com.example.demo1.controller;

import com.example.demo1.dto.OrderResponse;
import com.example.demo1.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Orders awaiting payment at the cashier.
 *
 * <p>There is intentionally no {@code POST /api/orders}: an order is always born from a cart
 * checkout ({@code POST /api/carts/{customerId}/checkout}), which is what keeps prices and
 * line items consistent with what the buyer actually put in the cart. What is left here are
 * the cashier's actions on an existing order.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Orders awaiting payment at the cashier")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(summary = "Get all orders", description = "Returns the full list of orders with their line items.")
    public List<OrderResponse> getAllOrders() {
        return orderService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order by ID")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ofNullable(orderService.findById(id));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Pay an order at the cashier", description = "Marks the order PAID and deducts the ordered quantities from product stock. This is the only operation in the API that changes stock because of a sale. Returns 409 when the order is not awaiting payment or a product ran out of stock.")
    public ResponseEntity<OrderResponse> payOrder(@PathVariable Long id) {
        return ResponseEntity.ofNullable(orderService.pay(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an unpaid order", description = "Marks the order CANCELLED. Product stock is not affected, because an unpaid order never deducted any.")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable Long id) {
        return ResponseEntity.ofNullable(orderService.cancel(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an unpaid order", description = "Only PENDING_PAYMENT and CANCELLED orders can be deleted. A PAID order is part of the sales record and returns 409.")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        return orderService.deleteById(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
