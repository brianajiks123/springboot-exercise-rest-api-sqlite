package com.example.demo1.service;

import com.example.demo1.dto.CartItemRequest;
import com.example.demo1.dto.CartResponse;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.exception.BadRequestException;
import com.example.demo1.exception.NotFoundException;
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

    @Transactional(readOnly = true)
    public CartResponse getCart(String customerId) {
        return cartRepository.findByCustomerId(customerId)
                .map(CartResponse::from)
                .orElseGet(() -> CartResponse.empty(customerId));
    }

    @Transactional
    public CartResponse addItem(String customerId, CartItemRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new BadRequestException(
                        "Product with id " + request.productId() + " does not exist"));

        Cart cart = cartRepository.findByCustomerIdForUpdate(customerId)
                .orElseGet(() -> cartRepository.save(new Cart(customerId, LocalDateTime.now())));

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

    @Transactional
    public CartResponse updateItemQuantity(String customerId, Long productId, int quantity) {
        Cart cart = lockCart(customerId);
        CartItem line = findLine(cart, productId)
                .orElseThrow(() -> new NotFoundException(
                        "Product " + productId + " is not in the cart of customer " + customerId));

        line.setQuantity(quantity);
        cart.setUpdatedAt(LocalDateTime.now());
        return CartResponse.from(cartRepository.save(cart));
    }

    @Transactional
    public void removeItem(String customerId, Long productId) {
        Cart cart = lockCart(customerId);
        CartItem line = findLine(cart, productId)
                .orElseThrow(() -> new NotFoundException(
                        "Product " + productId + " is not in the cart of customer " + customerId));

        cart.removeItem(line);
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);
    }

    @Transactional
    public void clear(String customerId) {
        Cart cart = lockCart(customerId);
        if (cart.getItems().isEmpty()) {
            throw new NotFoundException("Cart of customer " + customerId + " is already empty");
        }

        cart.getItems().clear();
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);
    }

    @Transactional
    public OrderResponse checkout(String customerId) {
        Cart cart = lockCart(customerId);
        if (cart.getItems().isEmpty()) {
            throw new BadRequestException("Cart of customer " + customerId + " is empty");
        }

        List<CartItem> lines = new ArrayList<>(cart.getItems());
        for (CartItem line : lines) {
            Product product = line.getProduct();
            if (product.getStock() < line.getQuantity()) {
                throw new BadRequestException("Insufficient stock for product: " + product.getName());
            }
        }

        Order order = new Order(LocalDateTime.now(), Order.Status.PENDING_PAYMENT);
        for (CartItem line : lines) {
            Product product = line.getProduct();
            order.addItem(new OrderItem(product, line.getQuantity(), product.getPrice()));
        }
        Order saved = orderRepository.save(order);

        cart.getItems().clear();
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);

        return OrderResponse.from(saved);
    }

    private Cart lockCart(String customerId) {
        return cartRepository.findByCustomerIdForUpdate(customerId)
                .orElseThrow(() -> new NotFoundException("Cart of customer " + customerId + " does not exist"));
    }

    private static Optional<CartItem> findLine(Cart cart, Long productId) {
        return cart.getItems().stream()
                .filter(line -> line.getProduct().getId().equals(productId))
                .findFirst();
    }
}
