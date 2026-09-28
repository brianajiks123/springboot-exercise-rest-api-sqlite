package com.example.demo1.service;

import com.example.demo1.dto.CartItemRequest;
import com.example.demo1.dto.CartResponse;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.model.Cart;
import com.example.demo1.model.CartItem;
import com.example.demo1.model.Order;
import com.example.demo1.model.OrderItem;
import com.example.demo1.model.Product;
import com.example.demo1.repository.CartRepository;
import com.example.demo1.repository.OrderRepository;
import com.example.demo1.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shopping cart for a customer.
 *
 * <p><strong>The cart never touches {@code products.stock}.</strong> Every method here only
 * writes {@code cart_items}. Stock is settled once, by {@link OrderService#pay}, when the
 * cashier takes the money. That separation is what removes the oversell race: nothing is
 * reserved, so nothing has to be given back, so the same units cannot be counted twice.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public CartService(CartRepository cartRepository, ProductRepository productRepository,
            OrderRepository orderRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Reads a cart. Returns an empty cart when the customer has none.
     *
     * <p>Deliberately does not create a row: reading is a pure read, otherwise a typo in a
     * customer id would leave an empty cart behind. The row appears on the first write.
     */
    @Transactional(readOnly = true)
    public CartResponse getCart(String customerId) {
        return cartRepository.findByCustomerId(customerId)
                .map(CartResponse::from)
                .orElseGet(() -> CartResponse.empty(customerId));
    }

    /**
     * Adds units of a product, creating the cart on first use.
     *
     * <p>Adding a product that is already in the cart <em>accumulates</em> into the existing
     * line. That is both the usual e-commerce behaviour and a necessity: {@code cart_items}
     * is unique on {@code (cart_id, product_id)}, so a second row for the same product would
     * otherwise fail at flush time with an opaque {@code 409}.
     */
    @Transactional
    public CartResponse addItem(String customerId, CartItemRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product with id " + request.productId() + " does not exist"));

        Cart cart = cartRepository.findByCustomerId(customerId)
                .orElseGet(() -> new Cart(customerId, LocalDateTime.now()));

        Optional<CartItem> existingLine = findLine(cart, product.getId());
        if (existingLine.isPresent()) {
            CartItem line = existingLine.get();
            line.setQuantity(line.getQuantity() + request.quantity());
        } else {
            cart.addItem(new CartItem(product, request.quantity()));
        }

        cart.setUpdatedAt(LocalDateTime.now());
        return CartResponse.from(cartRepository.save(cart));
    }

    /**
     * Sets the absolute quantity of a line. Returns {@code null} when the cart or the line
     * does not exist, which the controller turns into a {@code 404}.
     */
    @Transactional
    public CartResponse updateItemQuantity(String customerId, Long productId, int quantity) {
        Cart cart = cartRepository.findByCustomerId(customerId).orElse(null);
        if (cart == null) {
            return null;
        }
        CartItem line = findLine(cart, productId).orElse(null);
        if (line == null) {
            return null;
        }

        line.setQuantity(quantity);
        cart.setUpdatedAt(LocalDateTime.now());
        return CartResponse.from(cartRepository.save(cart));
    }

    /** Removes one line. Returns {@code false} when there was nothing to remove. */
    @Transactional
    public boolean removeItem(String customerId, Long productId) {
        Cart cart = cartRepository.findByCustomerId(customerId).orElse(null);
        if (cart == null) {
            return false;
        }
        CartItem line = findLine(cart, productId).orElse(null);
        if (line == null) {
            return false;
        }

        cart.removeItem(line);
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);
        return true;
    }

    /**
     * Empties the cart but keeps the row. Returns {@code false} when the cart is missing or
     * already empty, so the caller can answer {@code 404} instead of pretending it cleared
     * something.
     */
    @Transactional
    public boolean clear(String customerId) {
        Cart cart = cartRepository.findByCustomerId(customerId).orElse(null);
        if (cart == null || cart.getItems().isEmpty()) {
            return false;
        }

        cart.getItems().clear();
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);
        return true;
    }

    /**
     * Turns the cart into an order awaiting payment, and empties the cart.
     *
     * <p>Returns {@code null} when the customer has no cart ({@code 404}); throws
     * {@link IllegalArgumentException} when the cart is empty or a product is short
     * ({@code 400}).
     *
     * <p><strong>Stock is not deducted here.</strong> The availability check below is a
     * fast-fail courtesy so the buyer is told immediately instead of at the cashier; it is a
     * read and can go stale the moment it runs. The authoritative check is the atomic
     * conditional update in {@link OrderService#pay}.
     */
    @Transactional
    public OrderResponse checkout(String customerId) {
        Cart cart = cartRepository.findByCustomerId(customerId).orElse(null);
        if (cart == null) {
            return null;
        }
        if (cart.getItems().isEmpty()) {
            throw new IllegalArgumentException("Cart of customer " + customerId + " is empty");
        }

        // Pass 1 , validate the whole cart before creating anything, so a rejected checkout
        // never leaves a half-built order behind.
        List<CartItem> lines = new ArrayList<>(cart.getItems());
        for (CartItem line : lines) {
            Product product = line.getProduct();
            if (product.availableStock() < line.getQuantity()) {
                throw new IllegalArgumentException("Insufficient stock for product: " + product.getName());
            }
        }

        // Pass 2 , build the order and freeze the current price of every line.
        Order order = new Order(LocalDateTime.now(), Order.Status.PENDING_PAYMENT);
        for (CartItem line : lines) {
            Product product = line.getProduct();
            order.addItem(new OrderItem(product, line.getQuantity(), product.getPrice()));
        }
        Order saved = orderRepository.save(order);

        // The cart has become an order. Leaving the lines behind would let the same items be
        // checked out twice, which is the e-commerce equivalent of a double booking.
        cart.getItems().clear();
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);

        return OrderResponse.from(saved);
    }

    private static Optional<CartItem> findLine(Cart cart, Long productId) {
        return cart.getItems().stream()
                .filter(line -> line.getProduct().getId().equals(productId))
                .findFirst();
    }
}
