package com.example.demo1.repository;

import com.example.demo1.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * Counts how many order line items reference the given product.
     * {@code ProductId} resolves to the path {@code product.id}.
     */
    long countByProductId(Long productId);
}
