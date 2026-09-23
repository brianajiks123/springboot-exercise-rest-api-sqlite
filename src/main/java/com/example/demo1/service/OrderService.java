package com.example.demo1.service;

import com.example.demo1.dto.OrderItemRequest;
import com.example.demo1.dto.OrderRequest;
import com.example.demo1.dto.OrderResponse;
import com.example.demo1.model.Order;
import com.example.demo1.model.OrderItem;
import com.example.demo1.model.Product;
import com.example.demo1.repository.OrderRepository;
import com.example.demo1.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElse(null);
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        // Pass 1 , validate the whole request before mutating anything, so an invalid
        // request can never leave stock partially decremented.
        List<OrderItem> pendingItems = new ArrayList<>();
        Set<Long> seenProductIds = new HashSet<>();

        for (OrderItemRequest itemRequest : request.items()) {
            // `order_items` has a unique constraint on (order_id, product_id), so a
            // repeated product would otherwise fail at flush time with an opaque 409.
            if (!seenProductIds.add(itemRequest.productId())) {
                throw new IllegalArgumentException(
                        "Duplicate productId " + itemRequest.productId()
                                + ": each product may appear only once per order");
            }

            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Product with id " + itemRequest.productId() + " does not exist"));

            if (currentStock(product) < itemRequest.quantity()) {
                throw new IllegalArgumentException("Insufficient stock for product: " + product.getName());
            }

            // Snapshot the price now; it must not change if the product is edited later.
            pendingItems.add(new OrderItem(product, itemRequest.quantity(), product.getPrice()));
        }

        // Pass 2 , reserve stock and attach the validated items to the order.
        Order order = new Order(LocalDateTime.now(), Order.Status.PENDING);
        for (OrderItem item : pendingItems) {
            Product product = item.getProduct();
            product.setStock(currentStock(product) - item.getQuantity());
            order.addItem(item);
        }

        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional
    public boolean deleteById(Long id) {
        return orderRepository.findById(id)
                .map(order -> {
                    // Give the reserved quantities back, otherwise deleting an order
                    // would permanently leak stock.
                    for (OrderItem item : order.getItems()) {
                        Product product = item.getProduct();
                        product.setStock(currentStock(product) + item.getQuantity());
                    }
                    orderRepository.delete(order);
                    return true;
                })
                .orElse(false);
    }

    /**
     * A missing stock value is treated as {@code 0} so legacy rows created before
     * {@code stock} became mandatory cannot cause an NPE (HTTP 500).
     */
    private static int currentStock(Product product) {
        return product.getStock() == null ? 0 : product.getStock();
    }
}
