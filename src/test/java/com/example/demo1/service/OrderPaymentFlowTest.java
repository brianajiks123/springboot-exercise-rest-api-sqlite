package com.example.demo1.service;

import com.example.demo1.dto.CartItemRequest;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.exception.ConflictException;
import com.example.demo1.exception.NotFoundException;
import com.example.demo1.model.Order;
import com.example.demo1.model.Product;
import com.example.demo1.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class OrderPaymentFlowTest {
    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private ProductRepository productRepository;

    private Product givenProduct(String name, double price, Integer stock) {
        return productRepository.save(new Product(name, null, BigDecimal.valueOf(price), stock));
    }

    private static String newCustomer() {
        return "cust-" + UUID.randomUUID();
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getStock();
    }

    private OrderResponse orderAwaitingPayment(Product product, int quantity) {
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), quantity));
        return cartService.checkout(customer);
    }

    // -------------------------------------------------------------------- pay

    @Test
    void pay_deductsStockAndMarksTheOrderPaid() {
        Product product = givenProduct("Laptop", 1000.0, 10);
        OrderResponse order = orderAwaitingPayment(product, 3);

        assertEquals(10, stockOf(product.getId()), "checkout must not deduct stock");

        OrderResponse paid = orderService.pay(order.id());

        assertEquals(Order.Status.PAID, paid.status());
        assertNotNull(paid.paidAt());
        assertEquals(7, stockOf(product.getId()));
    }

    @Test
    void pay_twice_returnsConflictAndDoesNotDeductTwice() {
        Product product = givenProduct("Mouse", 100.0, 10);
        OrderResponse order = orderAwaitingPayment(product, 3);
        orderService.pay(order.id());

        ConflictException ex = assertThrows(ConflictException.class, () -> orderService.pay(order.id()));

        assertTrue(ex.getMessage().contains("can no longer be paid"), ex.getMessage());
        assertEquals(7, stockOf(product.getId()), "the second payment must not deduct again");
    }

    @Test
    void pay_unknownOrder_returnsNotFound() {
        assertThrows(NotFoundException.class, () -> orderService.pay(999_999L));
    }

    @Test
    void pay_insufficientStock_returnsConflictAndLeavesTheOrderUnpaid() {
        Product product = givenProduct("Scarce", 10.0, 2);
        OrderResponse order = orderAwaitingPayment(product, 2);

        product.setStock(0);
        productRepository.save(product);

        ConflictException ex = assertThrows(ConflictException.class, () -> orderService.pay(order.id()));

        assertTrue(ex.getMessage().contains("Insufficient stock"), ex.getMessage());
        assertEquals(Order.Status.PENDING_PAYMENT, orderService.findById(order.id()).status());
        assertEquals(0, stockOf(product.getId()));
    }

    @Test
    void pay_multiProductOrder_rollsBackEveryDecrementWhenOneProductRunsOut() {
        Product available = givenProduct("Available", 10.0, 10);
        Product soldOut = givenProduct("SoldOut", 10.0, 5);

        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(available.getId(), 4));
        cartService.addItem(customer, new CartItemRequest(soldOut.getId(), 5));
        OrderResponse order = cartService.checkout(customer);

        soldOut.setStock(0);
        productRepository.save(soldOut);

        assertThrows(ConflictException.class, () -> orderService.pay(order.id()));

        assertEquals(10, stockOf(available.getId()),
                "the decrement of the first product must be rolled back too");
        assertEquals(Order.Status.PENDING_PAYMENT, orderService.findById(order.id()).status());
    }

    @Test
    void pay_bumpsTheProductVersionSoAConcurrentPutCannotSilentlyOverwriteTheSale() {
        Product product = givenProduct("Versioned", 100.0, 10);
        long before = productRepository.findById(product.getId()).orElseThrow().getVersion();

        OrderResponse order = orderAwaitingPayment(product, 3);
        orderService.pay(order.id());

        long after = productRepository.findById(product.getId()).orElseThrow().getVersion();
        assertTrue(after > before, "settling a sale must advance the version, was " + before + " now " + after);
    }

    // ----------------------------------------------------------------- cancel

    @Test
    void cancel_unpaidOrder_doesNotTouchStock() {
        Product product = givenProduct("Chair", 200.0, 10);
        OrderResponse order = orderAwaitingPayment(product, 4);

        OrderResponse cancelled = orderService.cancel(order.id());

        assertEquals(Order.Status.CANCELLED, cancelled.status());
        assertEquals(10, stockOf(product.getId()),
                "an unpaid order never deducted stock, so cancelling must not add any back");
    }

    @Test
    void cancel_paidOrder_returnsConflict() {
        Product product = givenProduct("Table", 300.0, 10);
        OrderResponse order = orderAwaitingPayment(product, 2);
        orderService.pay(order.id());

        ConflictException ex = assertThrows(ConflictException.class, () -> orderService.cancel(order.id()));

        assertTrue(ex.getMessage().contains("can no longer be cancelled"), ex.getMessage());
        assertEquals(8, stockOf(product.getId()));
    }

    @Test
    void cancel_unknownOrder_returnsNotFound() {
        assertThrows(NotFoundException.class, () -> orderService.cancel(999_999L));
    }

    // ----------------------------------------------------------------- delete

    @Test
    void delete_unpaidOrder_removesItWithoutTouchingStock() {
        Product product = givenProduct("Lamp", 30.0, 10);
        OrderResponse order = orderAwaitingPayment(product, 3);

        orderService.deleteById(order.id());

        assertThrows(NotFoundException.class, () -> orderService.findById(order.id()));
        assertEquals(10, stockOf(product.getId()));
    }

    @Test
    void delete_cancelledOrder_isAllowed() {
        Product product = givenProduct("Stool", 40.0, 10);
        OrderResponse order = orderAwaitingPayment(product, 1);
        orderService.cancel(order.id());

        orderService.deleteById(order.id());
        assertThrows(NotFoundException.class, () -> orderService.findById(order.id()));
    }

    @Test
    void delete_paidOrder_returnsConflict() {
        Product product = givenProduct("Desk", 500.0, 10);
        OrderResponse order = orderAwaitingPayment(product, 2);
        orderService.pay(order.id());

        ConflictException ex = assertThrows(ConflictException.class, () -> orderService.deleteById(order.id()));

        assertTrue(ex.getMessage().contains("cannot be deleted"), ex.getMessage());
        assertNotNull(orderService.findById(order.id()), "a paid order is part of the sales record");
        assertEquals(8, stockOf(product.getId()));
    }

    @Test
    void delete_unknownOrder_returnsNotFound() {
        assertThrows(NotFoundException.class, () -> orderService.deleteById(999_999L));
    }

    // ------------------------------------------------------------- regression

    @Test
    void putStockWhileAnOrderIsUnpaid_noLongerInflatesStock() {
        Product product = givenProduct("Laptop", 1000.0, 10);

        // 1. the buyer takes 3 to the cashier; nothing is deducted yet
        OrderResponse order = orderAwaitingPayment(product, 3);
        assertEquals(10, stockOf(product.getId()));

        // 2. an admin restates the physical stock while the order is still unpaid
        product.setStock(10);
        productRepository.save(product);
        assertEquals(10, stockOf(product.getId()));

        // 3. the cashier settles the sale: the only deduction in the whole flow
        orderService.pay(order.id());
        assertEquals(7, stockOf(product.getId()));

        // 4. the old design reached 13 here. Now the paid order is terminal, and even if it
        //    could be cancelled there is no restore step left that could add units back.
        assertThrows(ConflictException.class, () -> orderService.cancel(order.id()));
        assertThrows(ConflictException.class, () -> orderService.deleteById(order.id()));
        assertEquals(7, stockOf(product.getId()));
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentPayments_cannotOversellTheLastUnit() throws Exception {
        Product product = givenProduct("Last unit", 100.0, 1);

        int contenders = 6;
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String customer = newCustomer();
            cartService.addItem(customer, new CartItemRequest(product.getId(), 1));
            orderIds.add(cartService.checkout(customer).id());
        }
        assertEquals(1, stockOf(product.getId()), "checkout must leave the single unit in place");

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (Long orderId : orderIds) {
                results.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        orderService.pay(orderId);
                        return Boolean.TRUE;
                    } catch (ConflictException ex) {
                        return Boolean.FALSE;
                    }
                }));
            }

            startGate.countDown();

            int paid = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    paid++;
                }
            }

            assertEquals(1, paid, "exactly one payment may win the last unit");
            assertEquals(0, stockOf(product.getId()), "stock must never go below zero");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "payment threads did not stop");
        }
    }

    @Test
    void concurrentPaymentsOfTheSameOrder_deductStockExactlyOnce() throws Exception {
        Product product = givenProduct("One order", 100.0, 100);
        OrderResponse order = orderAwaitingPayment(product, 1);

        int cashiers = 6;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(cashiers);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < cashiers; i++) {
                results.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        orderService.pay(order.id());
                        return Boolean.TRUE;
                    } catch (ConflictException ex) {
                        return Boolean.FALSE;
                    }
                }));
            }

            startGate.countDown();

            int paid = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    paid++;
                }
            }

            assertEquals(1, paid, "only one cashier may settle a given order");
            assertEquals(99, stockOf(product.getId()),
                    "a single one-unit order must take exactly one unit off the shelf");
            assertEquals(Order.Status.PAID, orderService.findById(order.id()).status());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "payment threads did not stop");
        }
    }

    @Test
    void payingAndCancellingTheSameOrderAtOnce_neverCancelsAnOrderThatTookStock() throws Exception {
        for (int round = 0; round < 8; round++) {
            Product product = givenProduct("Cancel race " + round, 100.0, 50);
            OrderResponse order = orderAwaitingPayment(product, 1);

            race(
                    () -> {
                        try {
                            orderService.pay(order.id());
                            return "PAID";
                        } catch (ConflictException ex) {
                            return "REFUSED";
                        }
                    },
                    () -> {
                        try {
                            orderService.cancel(order.id());
                            return "CANCELLED";
                        } catch (ConflictException ex) {
                            return "REFUSED";
                        }
                    });

            Order.Status status = orderService.findById(order.id()).status();
            if (status == Order.Status.PAID) {
                assertEquals(49, stockOf(product.getId()),
                        "round " + round + ": a paid order must have taken exactly one unit");
            } else {
                assertEquals(Order.Status.CANCELLED, status, "round " + round);
                assertEquals(50, stockOf(product.getId()),
                        "round " + round + ": an abandoned order must not have taken anything");
            }
        }
    }

    @Test
    void payingAndDeletingTheSameOrderAtOnce_neverDeductsStockForAnOrderThatIsGone() throws Exception {
        for (int round = 0; round < 8; round++) {
            Product product = givenProduct("Delete race " + round, 100.0, 50);
            OrderResponse order = orderAwaitingPayment(product, 1);

            race(
                    () -> {
                        try {
                            orderService.pay(order.id());
                            return "PAID";
                        } catch (ConflictException ex) {
                            return "REFUSED";
                        } catch (NotFoundException ex) {
                            return "MISSING";
                        }
                    },
                    () -> {
                        try {
                            orderService.deleteById(order.id());
                            return "DELETED";
                        } catch (ConflictException ex) {
                            return "REFUSED";
                        } catch (NotFoundException ex) {
                            return "MISSING";
                        }
                    });

            OrderResponse remaining;
            try {
                remaining = orderService.findById(order.id());
            } catch (NotFoundException ex) {
                remaining = null;
            }
            int stock = stockOf(product.getId());
            if (remaining == null) {
                assertEquals(50, stock,
                        "round " + round + ": an order that no longer exists must not have taken stock");
            } else {
                assertEquals(Order.Status.PAID, remaining.status(), "round " + round);
                assertEquals(49, stock, "round " + round);
            }
        }
    }

    private static List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (Callable<String> action : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    startGate.await();
                    return action.call();
                }));
            }

            startGate.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "race threads did not stop");
        }
    }
}
