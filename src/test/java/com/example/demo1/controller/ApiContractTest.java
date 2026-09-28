package com.example.demo1.controller;

import com.example.demo1.model.Product;
import com.example.demo1.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks down the HTTP contract of the REST API: status codes, response shapes and error
 * messages that clients depend on. Complements the service-level tests.
 *
 * <p>The flow under test is: fill a cart, check out to get an order awaiting payment, then let
 * the cashier settle it with {@code POST /api/orders/{id}/pay}.
 *
 * <p>Note: {@code AutoConfigureMockMvc} lives in
 * {@code org.springframework.boot.webmvc.test.autoconfigure} in Spring Boot 4 (it moved from
 * {@code org.springframework.boot.test.autoconfigure.web.servlet}).
 *
 * <p>Each test uses a fresh random {@code customerId} because both {@code @SpringBootTest}
 * classes share one cached in-memory database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    /**
     * The only {@code "id"} in a checkout response is the order id, because
     * {@code OrderItemResponse} exposes {@code productId} and not {@code id}. Extracting it
     * with a pattern keeps this test free of a JSON mapper bean: Spring Boot 4 does not
     * auto-configure a {@code com.fasterxml.jackson.databind.ObjectMapper} bean here.
     */
    private static final Pattern ORDER_ID = Pattern.compile("\"id\":(\\d+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    private Long givenProduct(String name, String price, int stock) {
        return productRepository.save(new Product(name, null, new BigDecimal(price), stock)).getId();
    }

    private static String newCustomer() {
        return "cust-" + UUID.randomUUID();
    }

    private static String cartItemJson(Long productId, int quantity) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + "}";
    }

    private void addToCart(String customerId, Long productId, int quantity) throws Exception {
        mockMvc.perform(post("/api/carts/{customerId}/items", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cartItemJson(productId, quantity)))
                .andExpect(status().isOk());
    }

    /** Fills a cart, checks it out, and returns the new order id. */
    private Long givenOrderAwaitingPayment(Long productId, int quantity) throws Exception {
        String customerId = newCustomer();
        addToCart(customerId, productId, quantity);

        String body = mockMvc.perform(post("/api/carts/{customerId}/checkout", customerId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Matcher matcher = ORDER_ID.matcher(body);
        assertTrue(matcher.find(), "no order id in checkout response: " + body);
        return Long.valueOf(matcher.group(1));
    }

    // ------------------------------------------------------------------ products

    @Test
    void createProduct_returns201WithDecimalPrice() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop\",\"description\":\"14 inch\",\"price\":15000000.00,\"stock\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Laptop"))
                .andExpect(jsonPath("$.price").value(15000000.00))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void createProduct_withoutStock_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NoStock\",\"price\":10.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProduct_withThreeDecimals_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ThreeDecimals\",\"price\":1.234,\"stock\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProduct_withNegativePrice_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Negative\",\"price\":-1.00,\"stock\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProduct_withNegativeStock_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NegStock\",\"price\":5.00,\"stock\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getProduct_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/products/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateProduct_setsStockAuthoritatively() throws Exception {
        Long productId = givenProduct("Restockable", "10.00", 7);

        mockMvc.perform(put("/api/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Restockable\",\"price\":10.00,\"stock\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(25));
    }

    @Test
    void deleteProduct_notReferenced_returns204() throws Exception {
        Long productId = givenProduct("Disposable", "9.99", 1);

        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteProduct_unknownId_returns404() throws Exception {
        mockMvc.perform(delete("/api/products/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteProduct_referencedByOrder_returns409WithActionableMessage() throws Exception {
        Long productId = givenProduct("Referenced", "5.00", 10);
        givenOrderAwaitingPayment(productId, 1);

        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Product " + productId + " cannot be deleted because it is referenced by existing orders"));
    }

    @Test
    void deleteProduct_sittingInACart_returns409WithActionableMessage() throws Exception {
        Long productId = givenProduct("InACart", "5.00", 10);
        addToCart(newCustomer(), productId, 2);

        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Product " + productId + " cannot be deleted because it is in a customer's cart"));
    }

    // ---------------------------------------------------------------------- cart

    @Test
    void getCart_unknownCustomer_returnsEmptyCart() throws Exception {
        String customerId = newCustomer();

        mockMvc.perform(get("/api/carts/{customerId}", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPrice").value(0));
    }

    @Test
    void addItem_returnsCartWithLiveTotalsAndDoesNotTouchStock() throws Exception {
        Long productId = givenProduct("Mouse", "19.99", 10);
        String customerId = newCustomer();

        mockMvc.perform(post("/api/carts/{customerId}/items", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cartItemJson(productId, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].productName").value("Mouse"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(19.99))
                .andExpect(jsonPath("$.items[0].subtotal").value(59.97))
                .andExpect(jsonPath("$.totalPrice").value(59.97));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void addItem_sameProductTwice_mergesIntoOneLine() throws Exception {
        Long productId = givenProduct("Keyboard", "30.00", 10);
        String customerId = newCustomer();

        addToCart(customerId, productId, 2);
        addToCart(customerId, productId, 3);

        mockMvc.perform(get("/api/carts/{customerId}", customerId))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.totalItems").value(5));
    }

    @Test
    void addItem_unknownProduct_returns400WithMessage() throws Exception {
        mockMvc.perform(post("/api/carts/{customerId}/items", newCustomer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cartItemJson(999_999L, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Product with id 999999 does not exist"));
    }

    @Test
    void addItem_withoutQuantity_returns400() throws Exception {
        Long productId = givenProduct("NoQuantity", "5.00", 5);

        mockMvc.perform(post("/api/carts/{customerId}/items", newCustomer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateItemQuantity_setsAbsoluteValue() throws Exception {
        Long productId = givenProduct("Monitor", "100.00", 10);
        String customerId = newCustomer();
        addToCart(customerId, productId, 2);

        mockMvc.perform(put("/api/carts/{customerId}/items/{productId}", customerId, productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(7));
    }

    @Test
    void updateItemQuantity_unknownLine_returns404() throws Exception {
        Long productId = givenProduct("Absent", "100.00", 10);

        mockMvc.perform(put("/api/carts/{customerId}/items/{productId}", newCustomer(), productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeItem_returns204AndThen404() throws Exception {
        Long productId = givenProduct("Removable", "5.00", 5);
        String customerId = newCustomer();
        addToCart(customerId, productId, 1);

        mockMvc.perform(delete("/api/carts/{customerId}/items/{productId}", customerId, productId))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/carts/{customerId}/items/{productId}", customerId, productId))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearCart_returns204() throws Exception {
        Long productId = givenProduct("Clearable", "5.00", 5);
        String customerId = newCustomer();
        addToCart(customerId, productId, 1);

        mockMvc.perform(delete("/api/carts/{customerId}", customerId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/carts/{customerId}", customerId))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void checkout_returns201PendingPaymentAndLeavesStockAlone() throws Exception {
        Long productId = givenProduct("Book", "50.00", 10);
        String customerId = newCustomer();
        addToCart(customerId, productId, 3);

        mockMvc.perform(post("/api/carts/{customerId}/checkout", customerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paidAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].unitPrice").value(50.00))
                .andExpect(jsonPath("$.totalPrice").value(150.00));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(10));

        mockMvc.perform(get("/api/carts/{customerId}", customerId))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void checkout_emptyCart_returns400WithMessage() throws Exception {
        String customerId = newCustomer();
        Long productId = givenProduct("Emptied", "5.00", 5);
        addToCart(customerId, productId, 1);
        mockMvc.perform(delete("/api/carts/{customerId}", customerId)).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/carts/{customerId}/checkout", customerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cart of customer " + customerId + " is empty"));
    }

    @Test
    void checkout_unknownCustomer_returns404() throws Exception {
        mockMvc.perform(post("/api/carts/{customerId}/checkout", newCustomer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkout_insufficientStock_returns400WithMessage() throws Exception {
        Long productId = givenProduct("Scarce", "10.00", 1);
        String customerId = newCustomer();
        addToCart(customerId, productId, 5);

        mockMvc.perform(post("/api/carts/{customerId}/checkout", customerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient stock for product: Scarce"));
    }

    // -------------------------------------------------------------------- orders

    @Test
    void creatingAnOrderDirectly_isNoLongerPossible() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void pay_returns200AndDeductsStock() throws Exception {
        Long productId = givenProduct("Payable", "10.00", 10);
        Long orderId = givenOrderAwaitingPayment(productId, 4);

        mockMvc.perform(post("/api/orders/{id}/pay", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").exists());

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(6));
    }

    @Test
    void pay_twice_returns409WithMessage() throws Exception {
        Long productId = givenProduct("AlreadyPaid", "10.00", 10);
        Long orderId = givenOrderAwaitingPayment(productId, 1);
        mockMvc.perform(post("/api/orders/{id}/pay", orderId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/pay", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Order " + orderId + " is PAID and can no longer be paid"));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(9));
    }

    @Test
    void pay_unknownOrder_returns404() throws Exception {
        mockMvc.perform(post("/api/orders/{id}/pay", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void pay_whenStockRanOut_returns409AndLeavesOrderUnpaid() throws Exception {
        Long productId = givenProduct("RanOut", "10.00", 3);
        Long orderId = givenOrderAwaitingPayment(productId, 3);

        mockMvc.perform(put("/api/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RanOut\",\"price\":10.00,\"stock\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/pay", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Insufficient stock for product: RanOut"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void cancel_unpaidOrder_returns200AndLeavesStockAlone() throws Exception {
        Long productId = givenProduct("Cancellable", "10.00", 10);
        Long orderId = givenOrderAwaitingPayment(productId, 4);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void cancel_paidOrder_returns409WithMessage() throws Exception {
        Long productId = givenProduct("PaidAlready", "10.00", 10);
        Long orderId = givenOrderAwaitingPayment(productId, 1);
        mockMvc.perform(post("/api/orders/{id}/pay", orderId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Order " + orderId + " is PAID and can no longer be cancelled"));
    }

    @Test
    void deleteOrder_unpaid_returns204() throws Exception {
        Long productId = givenProduct("Deletable", "10.00", 10);
        Long orderId = givenOrderAwaitingPayment(productId, 2);

        mockMvc.perform(delete("/api/orders/{id}", orderId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void deleteOrder_paid_returns409WithMessage() throws Exception {
        Long productId = givenProduct("KeepHistory", "10.00", 10);
        Long orderId = givenOrderAwaitingPayment(productId, 2);
        mockMvc.perform(post("/api/orders/{id}/pay", orderId)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/orders/{id}", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Order " + orderId + " has been paid and cannot be deleted"));
    }

    @Test
    void deleteOrder_unknownId_returns404() throws Exception {
        mockMvc.perform(delete("/api/orders/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOrder_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- error envelope

    @Test
    void invalidBody_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"no name\",\"price\":10.00,\"stock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.path").value("/api/products"))
                .andExpect(jsonPath("$.fieldErrors.name").value("Name must not be blank"));
    }

    @Test
    void descriptionLongerThanTheColumn_returns400InsteadOfFailingAtFlush() throws Exception {
        String tooLong = "d".repeat(256);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TooLong\",\"description\":\"" + tooLong
                                + "\",\"price\":10.00,\"stock\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.description")
                        .value("Description must be at most 255 characters"));
    }

    @Test
    void customerIdWithAnUnsupportedCharacter_returns400() throws Exception {
        mockMvc.perform(get("/api/carts/{customerId}", "not a valid id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.customerId")
                        .value("customerId may only contain letters, digits, dots, underscores and dashes"));
    }

    @Test
    void unmappedPath_returns404InTheSameEnvelope() throws Exception {
        mockMvc.perform(get("/api/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/nothing-here"));
    }

    @Test
    void unmappedMethod_returns405InTheSameEnvelope() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    /**
     * Every "resource does not exist" path must carry the same body.
     *
     * <p>This is a regression test for a real gap: these endpoints used to answer {@code 404} with
     * an <em>empty</em> body, because the controllers built the response with
     * {@code ResponseEntity.ofNullable(...)} / {@code notFound().build()} instead of letting an
     * exception reach {@code GlobalExceptionHandler}. A client could not tell a missing resource
     * from a broken server without inspecting the status line.
     */
    @Test
    void everyNotFoundPath_usesTheSameEnvelope() throws Exception {
        long missing = 999_999L;
        String absentBody = "{\"name\":\"Absent\",\"price\":1.00,\"stock\":1}";

        List<ResultActions> responses = List.of(
                mockMvc.perform(get("/api/products/{id}", missing)),
                mockMvc.perform(put("/api/products/{id}", missing)
                        .contentType(MediaType.APPLICATION_JSON).content(absentBody)),
                mockMvc.perform(delete("/api/products/{id}", missing)),
                mockMvc.perform(get("/api/orders/{id}", missing)),
                mockMvc.perform(post("/api/orders/{id}/pay", missing)),
                mockMvc.perform(post("/api/orders/{id}/cancel", missing)),
                mockMvc.perform(delete("/api/orders/{id}", missing)),
                mockMvc.perform(put("/api/carts/{customerId}/items/{productId}", newCustomer(), missing)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")),
                mockMvc.perform(delete("/api/carts/{customerId}/items/{productId}", newCustomer(), missing)),
                mockMvc.perform(delete("/api/carts/{customerId}", newCustomer())),
                mockMvc.perform(post("/api/carts/{customerId}/checkout", newCustomer())));

        for (ResultActions response : responses) {
            response.andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.path").isNotEmpty())
                    .andExpect(jsonPath("$.fieldErrors").isEmpty());
        }
    }
}
