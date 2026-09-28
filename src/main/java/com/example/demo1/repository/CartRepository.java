package com.example.demo1.repository;

import com.example.demo1.model.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    @EntityGraph(attributePaths = { "items", "items.product" })
    Optional<Cart> findByCustomerId(String customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.customerId = :customerId")
    Optional<Cart> findByCustomerIdForUpdate(@Param("customerId") String customerId);
}
