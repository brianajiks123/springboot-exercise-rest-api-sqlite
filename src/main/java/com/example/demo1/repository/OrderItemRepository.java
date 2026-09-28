package com.example.demo1.repository;

import com.example.demo1.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    long countByProductId(Long productId);
}
