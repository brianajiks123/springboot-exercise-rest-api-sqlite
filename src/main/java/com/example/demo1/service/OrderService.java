package com.example.demo1.service;

import com.example.demo1.dto.OrderResponse;
import com.example.demo1.exception.ConflictException;
import com.example.demo1.model.Order;
import com.example.demo1.model.OrderItem;
import com.example.demo1.repository.OrderRepository;
import com.example.demo1.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    public OrderResponse pay(Long id) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return null;
        }
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            throw new ConflictException(
                    "Order " + id + " is " + order.getStatus() + " and can no longer be paid");
        }

        List<OrderItem> items = new ArrayList<>(order.getItems());
        items.sort(Comparator.comparing(item -> item.getProduct().getId()));

        for (OrderItem item : items) {
            int settled = productRepository.decrementStockIfAvailable(
                    item.getProduct().getId(), item.getQuantity());
            if (settled == 0) {
                throw new ConflictException(
                        "Insufficient stock for product: " + item.getProduct().getName());
            }
        }

        order.setStatus(Order.Status.PAID);
        order.setPaidAt(LocalDateTime.now());

        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return null;
        }
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            throw new ConflictException(
                    "Order " + id + " is " + order.getStatus() + " and can no longer be cancelled");
        }

        order.setStatus(Order.Status.CANCELLED);
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional
    public boolean deleteById(Long id) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return false;
        }
        if (order.getStatus() == Order.Status.PAID) {
            throw new ConflictException(
                    "Order " + id + " has been paid and cannot be deleted");
        }

        orderRepository.delete(order);
        return true;
    }
}
