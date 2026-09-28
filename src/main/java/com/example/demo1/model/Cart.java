package com.example.demo1.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer's shopping cart.
 *
 * <p>The project has no authentication yet, so a cart is identified by a
 * client-supplied {@code customerId} string (see {@code /api/carts/{customerId}}).
 *
 * <p><strong>A cart never reserves stock.</strong> Adding, changing or removing items
 * only writes to {@code cart_items}; {@code products.stock} is not read for writes and
 * is not modified. Stock is deducted exactly once, when the cashier confirms payment
 * ({@code POST /api/orders/{id}/pay}). This is what removes both the oversell race and
 * the "restored stock" double-counting of the previous design.
 */
@Entity
@Table(name = "carts", uniqueConstraints = @UniqueConstraint(name = "uk_cart_customer", columnNames = "customer_id"))
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    // Empty constructor (required by JPA)
    public Cart() {
    }

    public Cart(String customerId, LocalDateTime updatedAt) {
        this.customerId = customerId;
        this.updatedAt = updatedAt;
    }

    /**
     * Adds a line and maintains the owning side of the association.
     */
    public void addItem(CartItem item) {
        items.add(item);
        item.setCart(this);
    }

    /**
     * Removes a line. {@code orphanRemoval} turns this into a {@code DELETE} on flush,
     * so the caller must not also null out the back-reference (that would try to write a
     * {@code NULL} into the non-nullable {@code cart_id} column first).
     */
    public void removeItem(CartItem item) {
        items.remove(item);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public void setItems(List<CartItem> items) {
        this.items = items;
    }
}
