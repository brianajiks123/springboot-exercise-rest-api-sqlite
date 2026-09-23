package com.example.demo1.repository;

import com.example.demo1.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Loads the orders together with their line items and the referenced products in a
     * single query.
     *
     * <p>Without the entity graph, rendering the order list caused an N+1: one query for
     * the orders, then one query per order to load {@code items}, then one query per item
     * to load its {@code product} (the response DTO needs the product name). Measured
     * with 3 orders / 6 items that was 7 queries; with the graph it is 1.
     */
    @Override
    @EntityGraph(attributePaths = { "items", "items.product" })
    List<Order> findAll();

    /**
     * Same fetch plan for the single-order lookups. This also covers the delete path,
     * which has to read {@code items} to give the reserved stock back.
     */
    @Override
    @EntityGraph(attributePaths = { "items", "items.product" })
    Optional<Order> findById(Long id);
}
