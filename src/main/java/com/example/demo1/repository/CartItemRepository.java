package com.example.demo1.repository;

import com.example.demo1.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /**
     * Counts how many cart lines reference the given product. {@code ProductId} resolves to
     * the path {@code product.id}.
     *
     * <p>{@code cart_items.product_id} is a foreign key, so a product sitting in somebody's
     * cart cannot be deleted. {@code ProductService.deleteById} uses this to return an
     * actionable {@code 409} instead of an opaque constraint-violation message.
     *
     * <p>Like {@code order_items}, this table has no index that starts with
     * {@code product_id} (the unique constraint is {@code (cart_id, product_id)}), so this
     * count is a scan. Acceptable at this scale; see the known limitations.
     */
    long countByProductId(Long productId);
}
