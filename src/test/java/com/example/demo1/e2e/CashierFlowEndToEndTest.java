package com.example.demo1.e2e;

import com.example.demo1.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the cart / pay-at-the-cashier flow against a <strong>real, running application</strong>:
 * a real servlet container on a random port, real HTTP requests, and a real file-backed H2
 * database opened with the production URL shape.
 *
 * <p>This class exists for the one dimension the rest of the suite cannot reach.
 * {@link com.example.demo1.controller.ApiContractTest} and the service tests run against an
 * in-memory database through MockMvc, so they never exercise a socket or the database
 * configuration the application actually boots with. A mistake there passes every other test
 * and still stops the application from starting, which is exactly what happened once.
 *
 * <p>It deliberately does <em>not</em> repeat the exhaustive status-code and message assertions
 * that {@code ApiContractTest} already owns: the same contract would then have to be maintained
 * in two places. What is covered here is what only a real instance can show.
 *
 * <p>Every test uses a fresh random {@code customerId}, so the tests do not depend on each
 * other or on the order they run in.
 *
 * <p>Note: the HTTP client is {@code RestTestClient}, not {@code TestRestTemplate}. The latter
 * lives in {@code spring-boot-resttestclient} and needs {@code RestTemplateBuilder} from the
 * {@code spring-boot-restclient} module, which a webmvc-only application does not have on the
 * classpath. {@code RestTestClient} needs nothing beyond {@code spring-test} and {@code spring-web}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
class CashierFlowEndToEndTest {

    /** Shapes of the JSON the API returns, used only to read values out of the responses. */
    record ProductView(Long id, String name, String description, BigDecimal price, Integer stock) {
    }

    record CartItemView(Long productId, String productName, BigDecimal unitPrice, Integer quantity,
                        BigDecimal subtotal) {
    }

    record CartView(String customerId, List<CartItemView> items, int totalItems, BigDecimal totalPrice,
                    String updatedAt) {
    }

    record OrderItemView(Long productId, String productName, Integer quantity, BigDecimal unitPrice) {
    }

    record OrderView(Long id, String status, String paidAt, List<OrderItemView> items, BigDecimal totalPrice) {
    }

    @Value("${local.server.port}")
    private int port;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    private RestTestClient client;

    @BeforeEach
    void pointTheClientAtTheRunningServer() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    // ------------------------------------------------------------------ infrastructure

    /**
     * Pins the reason this class exists: it must run against a real, file-backed H2 database
     * opened with the production URL shape, not the in-memory database the rest of the suite uses.
     *
     * <p>{@code DB_CLOSE_ON_EXIT=FALSE} must in particular stay out. H2 rejects it together with
     * {@code AUTO_SERVER=TRUE} (error 50100) and the application then fails to start. Adding it
     * back breaks this assertion instead of breaking the next deployment.
     */
    @Test
    void runsAgainstAFileDatabaseWithTheProductionUrlShape() {
        assertTrue(datasourceUrl.startsWith("jdbc:h2:file:"),
                () -> "the end-to-end profile must use a file-backed H2 database, was: " + datasourceUrl);
        assertTrue(datasourceUrl.contains("AUTO_SERVER=TRUE"),
                () -> "AUTO_SERVER=TRUE is part of the production URL shape, was: " + datasourceUrl);
        assertFalse(datasourceUrl.contains("DB_CLOSE_ON_EXIT=FALSE"),
                () -> "H2 rejects DB_CLOSE_ON_EXIT=FALSE together with AUTO_SERVER=TRUE, was: " + datasourceUrl);
        assertTrue(Files.exists(Path.of("target", "e2e-db.mv.db")),
                "the schema must really have been written to disk, not kept in memory");
    }

    /** The readiness probe a deployment needs, instead of polling a business endpoint. */
    @Test
    void exposesAHealthEndpoint() {
        EntityExchangeResult<String> health = get("/actuator/health", String.class);

        assertEquals(HttpStatus.OK, health.getStatus());
        assertTrue(health.getResponseBody().contains("\"status\":\"UP\""), health.getResponseBody());
    }

    // ------------------------------------------------------------------------- helpers

    private static String newCustomer() {
        return "e2e-" + UUID.randomUUID();
    }

    private static Map<String, Object> productBody(String name, Object price, Object stock) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("price", price);
        if (stock != null) {
            body.put("stock", stock);
        }
        return body;
    }

    /** Compares money by value: {@code BigDecimal.equals} would also demand an identical scale. */
    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private <T> EntityExchangeResult<T> get(String path, Class<T> type, Object... vars) {
        return client.get().uri(path, vars).exchange().returnResult(type);
    }

    private <T> EntityExchangeResult<T> post(String path, Object body, Class<T> type, Object... vars) {
        if (body == null) {
            return client.post().uri(path, vars).exchange().returnResult(type);
        }
        return client.post().uri(path, vars).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange().returnResult(type);
    }

    private <T> EntityExchangeResult<T> put(String path, Object body, Class<T> type, Object... vars) {
        return client.put().uri(path, vars).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange().returnResult(type);
    }

    private <T> EntityExchangeResult<T> delete(String path, Class<T> type, Object... vars) {
        return client.delete().uri(path, vars).exchange().returnResult(type);
    }

    private Long givenProduct(String name, String price, int stock) {
        EntityExchangeResult<ProductView> response = post("/api/products",
                productBody(name, new BigDecimal(price), stock), ProductView.class);
        assertEquals(HttpStatus.CREATED, response.getStatus(), () -> "could not create " + name);
        return response.getResponseBody().id();
    }

    private int stockOf(Long productId) {
        EntityExchangeResult<ProductView> response = get("/api/products/{id}", ProductView.class, productId);
        assertEquals(HttpStatus.OK, response.getStatus());
        return response.getResponseBody().stock();
    }

    private EntityExchangeResult<CartView> addToCart(String customerId, Long productId, int quantity) {
        return post("/api/carts/{customerId}/items",
                Map.<String, Object>of("productId", productId, "quantity", quantity),
                CartView.class, customerId);
    }

    private EntityExchangeResult<CartView> readCart(String customerId) {
        return get("/api/carts/{customerId}", CartView.class, customerId);
    }

    private EntityExchangeResult<OrderView> checkout(String customerId) {
        return post("/api/carts/{customerId}/checkout", null, OrderView.class, customerId);
    }

    private EntityExchangeResult<String> checkoutText(String customerId) {
        return post("/api/carts/{customerId}/checkout", null, String.class, customerId);
    }

    private EntityExchangeResult<OrderView> pay(Long orderId) {
        return post("/api/orders/{id}/pay", null, OrderView.class, orderId);
    }

    private EntityExchangeResult<String> payText(Long orderId) {
        return post("/api/orders/{id}/pay", null, String.class, orderId);
    }

    private EntityExchangeResult<String> cancel(Long orderId) {
        return post("/api/orders/{id}/cancel", null, String.class, orderId);
    }

    private EntityExchangeResult<String> deleteOrder(Long orderId) {
        return delete("/api/orders/{id}", String.class, orderId);
    }

    private EntityExchangeResult<String> putProduct(Long productId, String name, String price, int stock) {
        return put("/api/products/{id}", productBody(name, new BigDecimal(price), stock), String.class, productId);
    }

    private String statusOf(Long orderId) {
        return get("/api/orders/{id}", OrderView.class, orderId).getResponseBody().status();
    }

    /** Walks the real path, cart then checkout, so the order is exactly what a buyer would get. */
    private Long givenOrderAwaitingPayment(Long productId, int quantity) {
        String customerId = newCustomer();
        assertEquals(HttpStatus.OK, addToCart(customerId, productId, quantity).getStatus());
        EntityExchangeResult<OrderView> response = checkout(customerId);
        assertEquals(HttpStatus.CREATED, response.getStatus());
        return response.getResponseBody().id();
    }

    // ---------------------------------------------------------------------------- flow

    @Test
    void fullCashierFlow_overRealHttp_deductsStockExactlyOnceAtPayment() {
        Long product = givenProduct("E2E Laptop", "1000.00", 10);
        String customer = newCustomer();

        EntityExchangeResult<CartView> added = addToCart(customer, product, 3);
        assertEquals(HttpStatus.OK, added.getStatus());
        assertEquals(3, added.getResponseBody().totalItems());
        assertMoney("3000.00", added.getResponseBody().items().get(0).subtotal());
        assertEquals(10, stockOf(product), "filling a cart must never touch stock");

        EntityExchangeResult<OrderView> checkedOut = checkout(customer);
        assertEquals(HttpStatus.CREATED, checkedOut.getStatus());
        assertEquals(Order.Status.PENDING_PAYMENT.name(), checkedOut.getResponseBody().status());
        assertNull(checkedOut.getResponseBody().paidAt(), "an unpaid order has no payment timestamp");
        assertMoney("1000.00", checkedOut.getResponseBody().items().get(0).unitPrice());
        assertEquals(10, stockOf(product), "checkout must not deduct stock");
        assertTrue(readCart(customer).getResponseBody().items().isEmpty(), "checkout empties the cart");

        Long orderId = checkedOut.getResponseBody().id();
        EntityExchangeResult<OrderView> paid = pay(orderId);
        assertEquals(HttpStatus.OK, paid.getStatus());
        assertEquals(Order.Status.PAID.name(), paid.getResponseBody().status());
        assertNotNull(paid.getResponseBody().paidAt());
        assertEquals(7, stockOf(product), "payment is the one and only place stock is deducted");
    }

    /**
     * The sequence from the original bug report, driven over HTTP: {@code 10 -> 7 -> 10 -> 13}.
     *
     * <p>Under the replaced design the PUT discarded the order's reservation and cancelling the
     * order then added it back on top, inventing three units. Stock is now written in exactly two
     * places, restocking and payment, and nothing ever adds units back, so the fourth step can no
     * longer reach 13.
     */
    @Test
    void regression_restockWhileAnOrderIsUnpaid_noLongerInflatesStockToThirteen() {
        Long product = givenProduct("E2E Book", "50.00", 10);
        Long orderId = givenOrderAwaitingPayment(product, 3);
        assertEquals(10, stockOf(product), "1. checkout leaves the shelf untouched");

        assertEquals(HttpStatus.OK, putProduct(product, "E2E Book", "50.00", 10).getStatus());
        assertEquals(10, stockOf(product), "2. the restatement is authoritative while the order is unpaid");

        assertEquals(HttpStatus.OK, pay(orderId).getStatus());
        assertEquals(7, stockOf(product), "3. the sale is deducted once: 10 - 3 = 7, not 13");

        assertEquals(HttpStatus.CONFLICT, cancel(orderId).getStatus());
        assertEquals(HttpStatus.CONFLICT, deleteOrder(orderId).getStatus());
        assertEquals(7, stockOf(product), "4. a paid order is terminal and restores nothing");
    }

    @Test
    void cartReadsMergeLinesAndNeverTouchStock() {
        Long product = givenProduct("E2E Mouse", "19.99", 10);
        String unknown = newCustomer();

        EntityExchangeResult<CartView> empty = readCart(unknown);
        assertEquals(HttpStatus.OK, empty.getStatus());
        assertEquals(unknown, empty.getResponseBody().customerId());
        assertTrue(empty.getResponseBody().items().isEmpty());
        assertEquals(0, empty.getResponseBody().totalItems());
        assertNull(empty.getResponseBody().updatedAt(), "a cart that was never written has no updatedAt");

        addToCart(unknown, product, 3);
        EntityExchangeResult<CartView> merged = addToCart(unknown, product, 2);
        assertEquals(1, merged.getResponseBody().items().size(), "one product is always one line");
        assertEquals(5, merged.getResponseBody().items().get(0).quantity(), "adding accumulates the quantity");
        assertMoney("99.95", merged.getResponseBody().totalPrice());
        assertEquals(10, stockOf(product), "no cart operation may touch stock");
    }

    @Test
    void cancellingAnUnpaidOrder_leavesStockUntouchedAndLetsTheOrderBeDeleted() {
        Long product = givenProduct("E2E Chair", "200.00", 10);
        Long orderId = givenOrderAwaitingPayment(product, 4);
        assertEquals(10, stockOf(product));

        EntityExchangeResult<String> cancelled = cancel(orderId);
        assertEquals(HttpStatus.OK, cancelled.getStatus());
        assertTrue(cancelled.getResponseBody().contains(Order.Status.CANCELLED.name()),
                cancelled.getResponseBody());
        assertEquals(10, stockOf(product), "an unpaid order never deducted, so cancelling adds nothing back");

        assertEquals(HttpStatus.NO_CONTENT, deleteOrder(orderId).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/orders/{id}", String.class, orderId).getStatus());
        assertEquals(10, stockOf(product));
    }

    @Test
    void paymentWhenStockRanOut_isRefusedAndLeavesTheOrderUnpaid() {
        Long product = givenProduct("E2E Rare", "10.00", 1);
        Long orderId = givenOrderAwaitingPayment(product, 1);
        assertEquals(HttpStatus.OK, putProduct(product, "E2E Rare", "10.00", 0).getStatus());

        EntityExchangeResult<String> refused = payText(orderId);

        assertEquals(HttpStatus.CONFLICT, refused.getStatus());
        assertTrue(refused.getResponseBody().contains("Insufficient stock for product: E2E Rare"),
                refused.getResponseBody());
        assertEquals(Order.Status.PENDING_PAYMENT.name(), statusOf(orderId),
                "a refused payment must leave the order waiting at the cashier");
    }

    @Test
    void paymentOfAMultiProductOrder_rollsBackEveryDecrementWhenOneProductIsGone() {
        Long available = givenProduct("E2E First", "10.00", 5);
        Long soldOut = givenProduct("E2E Second", "10.00", 5);

        String customer = newCustomer();
        addToCart(customer, available, 2);
        addToCart(customer, soldOut, 2);
        Long orderId = checkout(customer).getResponseBody().id();

        assertEquals(HttpStatus.OK, putProduct(soldOut, "E2E Second", "10.00", 0).getStatus());

        assertEquals(HttpStatus.CONFLICT, payText(orderId).getStatus());
        assertEquals(5, stockOf(available), "the first product's decrement must be rolled back too");
        assertEquals(Order.Status.PENDING_PAYMENT.name(), statusOf(orderId));
    }

    @Test
    void checkoutBeyondStock_isRefusedBeforeAnythingIsWritten() {
        Long product = givenProduct("E2E Short", "10.00", 1);
        String customer = newCustomer();
        addToCart(customer, product, 5);

        EntityExchangeResult<String> refused = checkoutText(customer);

        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatus());
        assertTrue(refused.getResponseBody().contains("Insufficient stock for product: E2E Short"),
                refused.getResponseBody());
        assertEquals(1, stockOf(product), "a refused checkout writes no stock at all");
        assertEquals(5, readCart(customer).getResponseBody().totalItems(), "and leaves the cart intact");
    }

    /**
     * The race the redesign exists for, seen from outside the process: several cashiers reach the
     * till at the same instant for the last unit in stock.
     *
     * <p>Every order was created by a real checkout while stock was still 1, so all five are
     * legitimately valid at that moment. Only one payment may win; the others must be refused with
     * {@code 409} and stock must land on 0, never below. The database arbitrates through a single
     * conditional UPDATE ({@code ... WHERE id = ? AND stock >= ?}), so a read-then-write
     * implementation would fail this test.
     */
    @Test
    void simultaneousCashiers_overRealHttp_onlyOneWinsTheLastUnit() throws Exception {
        Long product = givenProduct("E2E LastUnit", "100.00", 1);

        int cashiers = 5;
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < cashiers; i++) {
            orderIds.add(givenOrderAwaitingPayment(product, 1));
        }
        assertEquals(1, stockOf(product), "every checkout is valid while the unit is still on the shelf");

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(cashiers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Long orderId : orderIds) {
                results.add(pool.submit(() -> {
                    startGate.await();
                    return payText(orderId).getStatus().value();
                }));
            }

            startGate.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> result : results) {
                codes.add(result.get(60, TimeUnit.SECONDS));
            }

            assertEquals(1, codes.stream().filter(code -> code == HttpStatus.OK.value()).count(),
                    () -> "exactly one cashier may win the last unit, got " + codes);
            assertEquals(cashiers - 1, codes.stream().filter(code -> code == HttpStatus.CONFLICT.value()).count(),
                    () -> "every other cashier must be refused with 409, got " + codes);
            assertEquals(0, stockOf(product), "stock must land on 0 and never below");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "cashier threads did not stop");
        }
    }
}
