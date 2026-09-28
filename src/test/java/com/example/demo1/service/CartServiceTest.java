package com.example.demo1.service;

import com.example.demo1.dto.CartItemRequest;
import com.example.demo1.dto.CartResponse;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.model.Order;
import com.example.demo1.model.Product;
import com.example.demo1.repository.CartRepository;
import com.example.demo1.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cart: accumulation, absolute quantity updates, removal, clearing and checkout.
 *
 * <p>Deliberately NOT annotated with {@code @Transactional}: each service call must run in its
 * own transaction, so these tests verify real commit behaviour. A test-level transaction would
 * join the service transaction and make "stock was left untouched" assertions meaningless.
 *
 * <p>The single most important assertion in this class is
 * {@link #addItem_doesNotTouchStock()} / {@link #checkout_doesNotDeductStock()}: the cart must
 * never reserve anything, because that is what makes the oversell race impossible.
 *
 * <p>Every test uses a fresh random {@code customerId}. Both {@code @SpringBootTest} classes
 * share one in-memory database (Spring caches the context), so a fixed id would leak cart state
 * between tests.
 */
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
        // E-commerce carts let the buyer add more than is on the shelf; the shortfall is
        // reported at checkout. Reserving here is exactly what we removed.
        Product product = givenProduct("Rare", 500.0, 1);
        String customer = newCustomer();

        CartResponse cart = cartService.addItem(customer, new CartItemRequest(product.getId(), 5));

        assertEquals(5, cart.totalItems());
        assertEquals(1, stockOf(product.getId()));
    }

    @Test
    void addItem_unknownProduct_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
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
    void updateItemQuantity_unknownCart_returnsNull() {
        Product product = givenProduct("Cable", 5.0, 10);

        assertNull(cartService.updateItemQuantity(newCustomer(), product.getId(), 1));
    }

    @Test
    void updateItemQuantity_unknownLine_returnsNull() {
        Product product = givenProduct("Adapter", 15.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));

        assertNull(cartService.updateItemQuantity(customer, 999_999L, 3));
    }

    // ------------------------------------------------------------- removing

    @Test
    void removeItem_removesOnlyThatProduct() {
        Product first = givenProduct("Alpha", 10.0, 10);
        Product second = givenProduct("Beta", 20.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(first.getId(), 1));
        cartService.addItem(customer, new CartItemRequest(second.getId(), 1));

        assertTrue(cartService.removeItem(customer, first.getId()));

        CartResponse cart = cartService.getCart(customer);
        assertEquals(1, cart.items().size());
        assertEquals(second.getId(), cart.items().get(0).productId());
    }

    @Test
    void removeItem_unknownLine_returnsFalse() {
        Product product = givenProduct("Gamma", 10.0, 10);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));

        assertEquals(false, cartService.removeItem(customer, 999_999L));
        assertEquals(false, cartService.removeItem(newCustomer(), product.getId()));
    }

    @Test
    void clear_emptiesTheCartButKeepsTheRow() {
        Product product = givenProduct("TV", 500.0, 5);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 2));

        assertTrue(cartService.clear(customer));

        CartResponse cart = cartService.getCart(customer);
        assertTrue(cart.items().isEmpty());
        assertEquals(0, cart.totalItems());
        assertTrue(cartRepository.findByCustomerId(customer).isPresent(), "the cart row itself should survive");
    }

    @Test
    void clear_onAnEmptyOrUnknownCart_returnsFalse() {
        Product product = givenProduct("Delta", 10.0, 5);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 1));
        cartService.clear(customer);

        assertEquals(false, cartService.clear(customer));
        assertEquals(false, cartService.clear(newCustomer()));
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

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cartService.checkout(customer));

        assertTrue(ex.getMessage().contains("is empty"), ex.getMessage());
    }

    @Test
    void checkout_unknownCustomer_returnsNull() {
        assertNull(cartService.checkout(newCustomer()));
    }

    @Test
    void checkout_insufficientStock_throwsAndLeavesStockUntouched() {
        Product product = givenProduct("Scarce", 10.0, 2);
        String customer = newCustomer();
        cartService.addItem(customer, new CartItemRequest(product.getId(), 5));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
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
        assertThrows(IllegalArgumentException.class, () -> cartService.checkout(customer));

        cartService.addItem(customer, new CartItemRequest(product.getId(), 2));
        OrderResponse second = cartService.checkout(customer);

        assertEquals(2, second.items().get(0).quantity());
    }
}
