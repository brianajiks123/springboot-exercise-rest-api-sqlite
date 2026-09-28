package com.example.demo1.repository;

import com.example.demo1.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @Modifying(flushAutomatically = true)
    @Query(value = "update products set stock = stock - :quantity, version = version + 1 "
            + "where id = :id and stock >= :quantity", nativeQuery = true)
    int decrementStockIfAvailable(@Param("id") Long id, @Param("quantity") int quantity);
}
