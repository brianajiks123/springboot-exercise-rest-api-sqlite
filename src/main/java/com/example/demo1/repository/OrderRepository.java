package com.example.demo1.repository;

import com.example.demo1.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Override
    @EntityGraph(attributePaths = { "items", "items.product" })
    List<Order> findAll();

    @Override
    @EntityGraph(attributePaths = { "items", "items.product" })
    Optional<Order> findById(Long id);
}
