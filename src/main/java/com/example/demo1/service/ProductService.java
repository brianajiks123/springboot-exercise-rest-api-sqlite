package com.example.demo1.service;

import com.example.demo1.dto.ProductRequest;
import com.example.demo1.dto.ProductResponse;
import com.example.demo1.exception.ConflictException;
import com.example.demo1.model.Product;
import com.example.demo1.repository.CartItemRepository;
import com.example.demo1.repository.OrderItemRepository;
import com.example.demo1.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartItemRepository cartItemRepository;

    public ProductService(ProductRepository productRepository, OrderItemRepository orderItemRepository,
            CartItemRepository cartItemRepository) {
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
        this.cartItemRepository = cartItemRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElse(null);
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        return productRepository.findById(id)
                .map(product -> {
                    applyRequest(product, request);
                    return ProductResponse.from(productRepository.save(product));
                })
                .orElse(null);
    }

    @Transactional
    public boolean deleteById(Long id) {
        if (!productRepository.existsById(id)) {
            return false;
        }

        // `order_items.product_id` and `cart_items.product_id` are foreign keys, so deleting
        // a referenced product would fail at flush time. Each case is detected here to return
        // an actionable 409 instead of an opaque constraint-violation message.
        if (orderItemRepository.countByProductId(id) > 0) {
            throw new ConflictException(
                    "Product " + id + " cannot be deleted because it is referenced by existing orders");
        }
        if (cartItemRepository.countByProductId(id) > 0) {
            throw new ConflictException(
                    "Product " + id + " cannot be deleted because it is in a customer's cart");
        }

        productRepository.deleteById(id);
        return true;
    }

    /**
     * Copies the payload onto the entity. {@code stock} is written <strong>verbatim</strong>:
     * the value sent is the new physical stock on hand (a restock or a stock correction).
     *
     * <p>This used to be the source of the {@code 10 -> 7 -> 10 -> 13} behaviour, because
     * orders held reservations inside {@code stock} and a subsequent cancel/delete added them
     * back on top of the overwritten value. In the current model no order reserves anything
     * , stock is deducted only when a sale is settled at the cashier , so there is no
     * reservation left for this write to invalidate.
     *
     * <p>Note that a concurrent cashier payment is still protected: the decrement bumps
     * {@code version}, so this read-modify-write fails with {@code 409} instead of silently
     * overwriting a sale.
     */
    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
    }
}
