package com.example.demo1.repository;

import com.example.demo1.model.Cart;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Loads a cart together with its lines and the referenced products in a single query.
     *
     * <p>{@code spring.jpa.open-in-view=false}, and the response DTO reads
     * {@code item.getProduct().getName()}, so the association has to be fetched inside the
     * service transaction. Without the entity graph that would be an N+1 (one query for the
     * cart, one per line for its product).
     */
    @EntityGraph(attributePaths = { "items", "items.product" })
    Optional<Cart> findByCustomerId(String customerId);
}
