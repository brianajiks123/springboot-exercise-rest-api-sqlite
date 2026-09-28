package com.example.demo1.repository;

import com.example.demo1.model.Cart;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    @EntityGraph(attributePaths = { "items", "items.product" })
    Optional<Cart> findByCustomerId(String customerId);
}
