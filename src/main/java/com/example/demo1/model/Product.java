package com.example.demo1.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    /**
     * Money is stored as {@link BigDecimal}, never as {@code double}: binary floating
     * point cannot represent decimal amounts exactly and would slowly accumulate
     * rounding errors across repeated arithmetic.
     */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    /**
     * Physical units on hand, and the single source of truth for availability.
     *
     * <p>Exactly two things write this column: {@code ProductService} (create/update, i.e.
     * an explicit restock , the value sent is authoritative) and {@code OrderService.pay()}
     * (the atomic conditional decrement that settles a sale).
     *
     * <p>No order operation ever adds stock back. An unpaid or cancelled order never
     * touched it, and a paid order is terminal, so there is nothing to restore and no way
     * for the same units to be counted twice. This is what removes the old
     * {@code 10 -> 7 -> 10 -> 13} behaviour.
     *
     * <p>Nullable for legacy rows; read it through {@link #availableStock()}.
     */
    private Integer stock;

    /**
     * Optimistic-locking version. Hibernate bumps it on every update, so two
     * concurrent orders cannot both consume the last item in stock.
     */
    @Version
    private Long version;

    // Empty Constructor (important for JPA)
    public Product() {
    }

    public Product(String name, String description, BigDecimal price, Integer stock) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
    }

    // Getter & Setter
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    /**
     * Null-safe read of {@link #stock}: a missing value is treated as {@code 0} so legacy
     * rows created before {@code stock} became mandatory cannot cause an NPE (HTTP 500).
     *
     * <p>This is only a convenience for <em>checks</em>. The authoritative availability
     * test for settling a sale is the conditional update
     * {@code ProductRepository.decrementStockIfAvailable}, which evaluates the comparison
     * inside the database and is therefore safe under concurrency.
     */
    public int availableStock() {
        return stock == null ? 0 : stock;
    }

    public Long getVersion() {
        return version;
    }
}
