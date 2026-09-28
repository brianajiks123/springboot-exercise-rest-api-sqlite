package com.example.demo1.repository;

import com.example.demo1.model.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Override
    @EntityGraph(attributePaths = { "items", "items.product" })
    List<Order> findAll();

    @Override
    @EntityGraph(attributePaths = { "items", "items.product" })
    Optional<Order> findById(Long id);

    @Modifying
    @Query("update Order o set o.status = :paid, o.paidAt = :paidAt "
            + "where o.id = :id and o.status = :pending")
    int claimForPayment(@Param("id") Long id, @Param("paidAt") LocalDateTime paidAt,
            @Param("paid") Order.Status paid, @Param("pending") Order.Status pending);
}
