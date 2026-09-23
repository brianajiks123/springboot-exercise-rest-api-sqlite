package com.example.demo1.service;

import com.example.demo1.dto.OrderItemRequest;
import com.example.demo1.dto.OrderRequest;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.model.Product;
import com.example.demo1.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the stock validation and decrement/restore logic in {@link OrderService}.
 *
 * <p>Deliberately NOT annotated with {@code @Transactional}: each service call must run in
 * its own transaction, so these tests verify real commit/rollback behaviour instead of
 * silently sharing a single rolled-back test transaction (which would hide whether a
 * rejected order actually left stock untouched).
 */
@SpringBootTest
class OrderServiceStockTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    private Product givenProduct(String name, double price, Integer stock) {
        return productRepository.save(new Product(name, null, BigDecimal.valueOf(price), stock));
    }

    private static OrderRequest order(Long productId, int quantity) {
        return new OrderRequest(List.of(new OrderItemRequest(productId, quantity)));
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getStock();
    }

    @Test
    void createOrder_decrementsStockAndComputesTotal() {
        Product product = givenProduct("Laptop", 1000.0, 10);

        OrderResponse response = orderService.create(order(product.getId(), 3));

        assertEquals(7, stockOf(product.getId()));
        assertEquals(0, BigDecimal.valueOf(3000.0).compareTo(response.totalPrice()));
    }

    @Test
    void createOrder_consumingEntireStockLeavesZero() {
        Product product = givenProduct("Laptop", 1000.0, 2);

        orderService.create(order(product.getId(), 2));

        assertEquals(0, stockOf(product.getId()));
    }

    @Test
    void createOrder_withMultipleProducts_decrementsEachAndSumsTotal() {
        Product first = givenProduct("Mouse", 100.0, 5);
        Product second = givenProduct("Keyboard", 200.0, 5);

        OrderResponse response = orderService.create(new OrderRequest(List.of(
                new OrderItemRequest(first.getId(), 2),
                new OrderItemRequest(second.getId(), 3))));

        assertEquals(3, stockOf(first.getId()));
        assertEquals(2, stockOf(second.getId()));
        assertEquals(0, BigDecimal.valueOf(800.0).compareTo(response.totalPrice()));
    }

    @Test
    void createOrder_rejectsInsufficientStockAndLeavesStockUntouched() {
        Product product = givenProduct("Laptop", 1000.0, 2);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.create(order(product.getId(), 5)));

        assertTrue(ex.getMessage().contains("Insufficient stock"), ex.getMessage());
        assertEquals(2, stockOf(product.getId()));
    }

    @Test
    void createOrder_rejectsUnknownProduct() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.create(order(999_999L, 1)));

        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test
    void createOrder_rejectsDuplicateProductIdAndLeavesStockUntouched() {
        Product product = givenProduct("Laptop", 1000.0, 10);

        OrderRequest request = new OrderRequest(List.of(
                new OrderItemRequest(product.getId(), 1),
                new OrderItemRequest(product.getId(), 1)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.create(request));

        assertTrue(ex.getMessage().contains("Duplicate productId"), ex.getMessage());
        assertEquals(10, stockOf(product.getId()));
    }

    @Test
    void createOrder_treatsNullStockAsZeroInsteadOfFailingWithNpe() {
        Product legacy = givenProduct("Legacy product", 500.0, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.create(order(legacy.getId(), 1)));

        assertTrue(ex.getMessage().contains("Insufficient stock"), ex.getMessage());
    }

    @Test
    void deleteOrder_restoresStock() {
        Product product = givenProduct("Laptop", 1000.0, 10);
        OrderResponse created = orderService.create(order(product.getId(), 4));
        assertEquals(6, stockOf(product.getId()));

        assertTrue(orderService.deleteById(created.id()));

        assertEquals(10, stockOf(product.getId()));
    }

    @Test
    void deleteOrder_returnsFalseForUnknownId() {
        assertFalse(orderService.deleteById(999_999L));
    }

    @Test
    void productPrice_roundTripsExactlyAsDecimal() {
        Product product = givenProduct("Fractional price", 19.99, 5);

        BigDecimal stored = productRepository.findById(product.getId()).orElseThrow().getPrice();

        assertEquals(0, new BigDecimal("19.99").compareTo(stored));
        assertTrue(stored.scale() <= 2, "price should keep at most 2 decimals, was " + stored);
    }
}
