package com.example.demo1.controller;

import com.example.demo1.dto.OrderResponse;
import com.example.demo1.openapi.ApiBadRequest;
import com.example.demo1.openapi.ApiConflict;
import com.example.demo1.openapi.ApiNotFound;
import com.example.demo1.openapi.ApiServerError;
import com.example.demo1.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

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
    @ApiResponse(responseCode = "200", description = "Every order", content = @Content(array = @ArraySchema(schema = @Schema(implementation = OrderResponse.class))))
    @ApiServerError
    public List<OrderResponse> getAllOrders() {
        return orderService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order by ID")
    @ApiResponse(responseCode = "200", description = "The order", content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiBadRequest
    @ApiNotFound
    @ApiServerError
    public OrderResponse getOrderById(@PathVariable Long id) {
        return orderService.findById(id);
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Pay an order at the cashier", description = "Marks the order PAID and deducts the ordered quantities from product stock. This is the only operation in the API that changes stock because of a sale. Returns 409 when the order is not awaiting payment or a product ran out of stock.")
    @ApiResponse(responseCode = "200", description = "The order, now PAID", content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiBadRequest
    @ApiNotFound
    @ApiConflict
    @ApiServerError
    public OrderResponse payOrder(@PathVariable Long id) {
        return orderService.pay(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an unpaid order", description = "Marks the order CANCELLED. Product stock is not affected, because an unpaid order never deducted any.")
    @ApiResponse(responseCode = "200", description = "The order, now CANCELLED", content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiBadRequest
    @ApiNotFound
    @ApiConflict
    @ApiServerError
    public OrderResponse cancelOrder(@PathVariable Long id) {
        return orderService.cancel(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an unpaid order", description = "Only PENDING_PAYMENT and CANCELLED orders can be deleted. A PAID order is part of the sales record and returns 409.")
    @ApiResponse(responseCode = "204", description = "The order was deleted")
    @ApiBadRequest
    @ApiNotFound
    @ApiConflict
    @ApiServerError
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
