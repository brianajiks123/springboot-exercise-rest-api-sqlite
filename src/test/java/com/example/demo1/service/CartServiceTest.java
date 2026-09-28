package com.example.demo1.service;

import com.example.demo1.dto.CartItemRequest;
import com.example.demo1.dto.CartResponse;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.exception.BadRequestException;
import com.example.demo1.exception.NotFoundException;
import com.example.demo1.model.CartItem;
import com.example.demo1.model.Order;
import com.example.demo1.model.Product;
import com.example.demo1.repository.CartRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class CartServiceTest {
    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CartRepository cartRepository;

    private Product givenProduct(String name, double price, Integer stock) {
        return productRepository.save(new Product(name, null, BigDecimal.valueOf(price), stock));
    }

    private static String newCustomer() {
        return "cust-" + UUID.randomUUID();
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getStock();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------ adding

    @Test
    void addItem_createsTheCartAndComputesTotals() {
        Product product = givenProduct("Laptop", 1000.0, 10);
        String customer = newCustomer();

        CartResponse cart = cartService.addItem(customer, new CartItemRequest(product.getId(), 2));

        assertEquals(customer, cart.customerId());
        assertEquals(1, cart.items().size());
        assertEquals(product.getId(), cart.items().get(0).productId());
        assertEquals("Laptop", cart.items().get(0).productName());
        assertEquals(2, cart.totalItems());
        assertMoney("2000.00", cart.totalPrice());
        assertMoney("2000.00", cart.items().get(0).subtotal());
    }

    @Test
    void addItem_sameProductTwice_accumulatesIntoASingleLine() {
        Product product = givenProduct("Mouse", 100.0, 10);
        String customer = newCustomer();

        cartService.addItem(customer, new CartItemRequest(product.getId(), 2));
        CartResponse cart = cartService.addItem(customer, new CartItemRequest(product.getId(), 3));

        assertEquals(1, cart.items().size(), "a repeated product must merge, not create a second line");
        assertEquals(5, cart.items().get(0).quantity());
        assertEquals(5, cart.totalItems());
        assertMoney("500.00", cart.totalPrice());
    }

    @Test
    void addItem_doesNotTouchStock() {
        Product product = givenProduct("Keyboard", 200.0, 10);
        String customer = newCustomer();

        cartService.addItem(customer, new CartItemRequest(product.getId(), 4));

        assertEquals(10, stockOf(product.getId()), "a cart must never reserve stock");
    }

    @Test
    void addItem_withQuantityBeyondStock_isStillAllowed() {
        Product product = givenProduct("Rare", 500.0, 1);
        String customer = newCustomer();

        CartResponse cart = cartService.addItem(customer, new CartItemRequest(product.getId(), 5));

        assertEquals(5, cart.totalItems());
        assertEquals(1, stockOf(product.getId()));
    }

    @Test
    void addItem_unknownProduct_throws() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> cartService.addItem(newCustomer(), new CartItemRequest(999_999L, 1)));

        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    // ------------------------------------------------------------- updating

    @Test
    void updateItemQuantity_setsAnAbsoluteValue() {
        Product product = givenProduct("Monitor", 300.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 2));

        CartResponse cart = cartService.updateItemQuantity(customer, product.getId(), 7);

        assertEquals(7, cart.items().get(0).quantity());
        assertMoney("2100.00", cart.totalPrice());
    }

    @Test
    void updateItemQuantity_unknownCart_returnsNotFound() {
        Product product = givenProduct("Cable", 5.0, 10);

        assertThrows(NotFoundException.class,
                () -> cartService.updateItemQuantity(newCustomer(), product.getId(), 1));
    }

    @Test
    void updateItemQuantity_unknownLine_returnsNotFound() {
        Product product = givenProduct("Adapter", 15.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));

        assertThrows(NotFoundException.class, () -> cartService.updateItemQuantity(customer, 999_999L, 3));
    }

    // ------------------------------------------------------------- removing

    @Test
    void removeItem_removesOnlyThatProduct() {
        Product first = givenProduct("Alpha", 10.0, 10);
        Product second = givenProduct("Beta", 20.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(first.getId(), 1));
        cartService.addItem(customer, new CartItemRequest(second.getId(), 1));

        cartService.removeItem(customer, first.getId());

        CartResponse cart = cartService.getCart(customer);
        assertEquals(1, cart.items().size());
        assertEquals(second.getId(), cart.items().get(0).productId());
    }

    @Test
    void removeItem_unknownLine_returnsNotFound() {
        Product product = givenProduct("Gamma", 10.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));

        assertThrows(NotFoundException.class, () -> cartService.removeItem(customer, 999_999L));
        assertThrows(NotFoundException.class, () -> cartService.removeItem(newCustomer(), product.getId()));
    }

    @Test
    void clear_emptiesTheCartButKeepsTheRow() {
        Product product = givenProduct("TV", 500.0, 5);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 2));

        cartService.clear(customer);

        CartResponse cart = cartService.getCart(customer);
        assertTrue(cart.items().isEmpty());
        assertEquals(0, cart.totalItems());
        assertTrue(cartRepository.findByCustomerId(customer).isPresent(), "the cart row itself should survive");
    }

    @Test
    void clear_onAnEmptyOrUnknownCart_returnsNotFound() {
        Product product = givenProduct("Delta", 10.0, 5);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));
        cartService.clear(customer);

        assertThrows(NotFoundException.class, () -> cartService.clear(customer));
        assertThrows(NotFoundException.class, () -> cartService.clear(newCustomer()));
    }

    // -------------------------------------------------------------- reading

    @Test
    void getCart_unknownCustomer_returnsAnEmptyCartAndCreatesNothing() {
        String customer = newCustomer();

        CartResponse cart = cartService.getCart(customer);

        assertEquals(customer, cart.customerId());
        assertTrue(cart.items().isEmpty());
        assertNull(cart.updatedAt());
        assertTrue(cartRepository.findByCustomerId(customer).isEmpty(),
                "reading a cart must not create a row");
    }

    // ------------------------------------------------------------- checkout

    @Test
    void checkout_createsAPendingPaymentOrderAndEmptiesTheCart() {
        Product product = givenProduct("Book", 50.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 3));

        OrderResponse order = cartService.checkout(customer);

        assertEquals(Order.Status.PENDING_PAYMENT, order.status());
        assertNull(order.paidAt());
        assertEquals(1, order.items().size());
        assertEquals(3, order.items().get(0).quantity());
        assertMoney("150.00", order.totalPrice());
        assertTrue(cartService.getCart(customer).items().isEmpty(), "checkout must empty the cart");
    }

    @Test
    void checkout_doesNotDeductStock() {
        Product product = givenProduct("Pen", 5.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 3));

        cartService.checkout(customer);

        assertEquals(10, stockOf(product.getId()),
                "stock is deducted only when the cashier confirms payment");
    }

    @Test
    void checkout_snapshotsThePriceSoLaterRepricingDoesNotChangeTheOrder() {
        Product product = givenProduct("Headset", 100.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 2));
        OrderResponse order = cartService.checkout(customer);

        product.setPrice(BigDecimal.valueOf(999.0));
        productRepository.save(product);

        OrderResponse reloaded = orderService.findById(order.id());
        assertMoney("100.00", reloaded.items().get(0).unitPrice());
        assertMoney("200.00", reloaded.totalPrice());
    }

    @Test
    void checkout_sumsSeveralProducts() {
        Product first = givenProduct("One", 100.0, 10);
        Product second = givenProduct("Two", 200.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(first.getId(), 2));
        cartService.addItem(customer, new CartItemRequest(second.getId(), 3));

        OrderResponse order = cartService.checkout(customer);

        assertEquals(2, order.items().size());
        assertMoney("800.00", order.totalPrice());
    }

    @Test
    void checkout_emptyCart_throws() {
        Product product = givenProduct("Epsilon", 10.0, 5);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));
        cartService.clear(customer);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> cartService.checkout(customer));

        assertTrue(ex.getMessage().contains("is empty"), ex.getMessage());
    }

    @Test
    void checkout_unknownCustomer_returnsNotFound() {
        assertThrows(NotFoundException.class, () -> cartService.checkout(newCustomer()));
    }

    @Test
    void checkout_insufficientStock_throwsAndLeavesStockUntouched() {
        Product product = givenProduct("Scarce", 10.0, 2);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 5));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> cartService.checkout(customer));

        assertTrue(ex.getMessage().contains("Insufficient stock"), ex.getMessage());
        assertEquals(2, stockOf(product.getId()));
    }

    @Test
    void checkout_canBeUsedAgainAfterwards() {
        Product product = givenProduct("Zeta", 10.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));
        cartService.checkout(customer);

        // The cart was emptied, so a second checkout must fail rather than re-sell the item.
        assertThrows(BadRequestException.class, () -> cartService.checkout(customer));

        cartService.addItem(customer, new CartItemRequest(product.getId(), 2));
        OrderResponse second = cartService.checkout(customer);

        assertEquals(2, second.items().get(0).quantity());
    }

    // ---------------------------------------------------------- concurrency

    @Test
    void concurrentAddItemOfTheSameProduct_neverLosesAnIncrement() throws Exception {
        Product product = givenProduct("Cart race", 10.0, 1000);
        int writers = 8;

        for (int round = 0; round < 5; round++) {
            String customer = newCustomer();
            cartService.addItem(customer, new CartItemRequest(product.getId(), 1));

            CountDownLatch startGate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < writers; i++) {
                    results.add(pool.submit(() -> {
                        startGate.await();
                        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));
                        return Boolean.TRUE;
                    }));
                }

                startGate.countDown();

                for (Future<Boolean> result : results) {
                    assertTrue(result.get(30, TimeUnit.SECONDS), "round " + round + ": an addItem failed");
                }
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "cart threads did not stop");
            }

            assertEquals(1 + writers, cartService.getCart(customer).totalItems(),
                    "round " + round + ": every accepted addItem must survive in the cart");
        }
    }

    @Test
    void concurrentCheckoutOfTheSameCart_createsOneOrderAndTellsTheLoserTheCartIsEmpty() throws Exception {
        Product product = givenProduct("Checkout race", 10.0, 100);

        for (int round = 0; round < 5; round++) {
            String customer = newCustomer();
            cartService.addItem(customer, new CartItemRequest(product.getId(), 1));

            // Any exception other than BadRequestException propagates out of race(...) and fails the
            // test with its real cause. That is deliberate: the misleading 409 this replaced came
            // from an ObjectOptimisticLockingFailureException escaping here.
            Callable<String> checkout = () -> {
                try {
                    cartService.checkout(customer);
                    return "ORDERED";
                } catch (BadRequestException ex) {
                    return ex.getMessage().contains("is empty") ? "EMPTY" : "BAD:" + ex.getMessage();
                }
            };

            List<String> outcomes = race(checkout, checkout);

            assertEquals(List.of("EMPTY", "ORDERED"), outcomes.stream().sorted().toList(),
                    "round " + round + ": one checkout must win and the other must be told the cart is empty");
            assertEquals(100, stockOf(product.getId()),
                    "round " + round + ": checkout never deducts stock, so nothing may be deducted here");
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

    // --------------------------------------------------------- quantity limit

    @Test
    void addItem_pastTheLineLimit_isRejectedAndLeavesTheLineAlone() {
        Product product = givenProduct("Bulk", 1.0, 5000);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), CartItem.MAX_QUANTITY));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> cartService.addItem(customer, new CartItemRequest(product.getId(), 1)));

        assertTrue(ex.getMessage().contains("holds at most " + CartItem.MAX_QUANTITY), ex.getMessage());
        assertEquals(CartItem.MAX_QUANTITY, cartService.getCart(customer).totalItems(),
                "a rejected add must leave the line exactly as it was");
    }

    @Test
    void addItem_aNewLineAtTheLimit_isAllowed() {
        Product product = givenProduct("Bulk new line", 1.0, 5000);
        String customer = newCustomer();

        CartResponse cart = cartService.addItem(customer,
                new CartItemRequest(product.getId(), CartItem.MAX_QUANTITY));

        assertEquals(CartItem.MAX_QUANTITY, cart.totalItems(),
                "the limit is on the line, so a fresh line may be filled right up to it");
    }
}
