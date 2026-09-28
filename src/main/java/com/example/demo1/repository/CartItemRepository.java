package com.example.demo1.repository;

import com.example.demo1.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    long countByProductId(Long productId);
}
