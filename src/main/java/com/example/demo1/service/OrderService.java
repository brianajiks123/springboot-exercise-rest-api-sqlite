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
import java.util.List;

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
        Order order = new Order(LocalDateTime.now(), Order.Status.PENDING);
        for (OrderItemRequest itemRequest : request.items()) {
            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Product with id " + itemRequest.productId() + " does not exist"));
            OrderItem item = new OrderItem(product, itemRequest.quantity(), product.getPrice());
            order.addItem(item);
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional
    public boolean deleteById(Long id) {
        if (orderRepository.existsById(id)) {
            orderRepository.deleteById(id);
            return true;
        }
        return false;
    }
}