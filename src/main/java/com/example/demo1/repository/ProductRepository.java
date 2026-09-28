package com.example.demo1.repository;

import com.example.demo1.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Atomically settles {@code quantity} units of a product, but only when enough are
     * available. Returns {@code 1} when the stock was settled, {@code 0} when it was not.
     *
     * <p><strong>This method is the race-condition fix.</strong> The availability check and
     * the subtraction happen inside a single SQL statement, so the database , not the
     * application , decides whether there is enough stock:
     *
     * <pre>
     * UPDATE products SET stock = stock - :quantity, version = version + 1
     *  WHERE id = :id AND stock >= :quantity
     * </pre>
     *
     * <p>If two cashiers pay for the last unit at the same time, the second statement blocks
     * on the row lock, re-evaluates {@code stock >= :quantity} against the newly committed
     * value, matches nothing and returns {@code 0}; the caller must reject that payment. A
     * read-then-write version ({@code if (stock >= qty) { stock -= qty; }}) gives no such
     * guarantee, because the check and the write are two separate round trips.
     *
     * <p>A {@code NULL} stock never matches, which matches the "treat missing stock as 0"
     * rule in {@link Product#availableStock()}.
     *
     * <p>{@code version} is bumped by hand so it stays true that the version changes whenever
     * the row changes. Without it a concurrent {@code PUT /api/products/{id}} , which is a
     * read-modify-write protected by {@code @Version} , could silently overwrite a settled
     * sale. Native SQL is used because this is deliberately a set-based operation that
     * bypasses the persistence context.
     *
     * <p>{@code clearAutomatically} is intentionally left off: the caller
     * ({@code OrderService.pay}) still holds a managed {@code Order} that it must update in
     * the same transaction, and clearing the context would detach it.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update products set stock = stock - :quantity, version = version + 1 "
            + "where id = :id and stock >= :quantity", nativeQuery = true)
    int decrementStockIfAvailable(@Param("id") Long id, @Param("quantity") int quantity);
}
