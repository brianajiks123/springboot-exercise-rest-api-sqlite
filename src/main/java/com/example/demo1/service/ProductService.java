package com.example.demo1.service;

import com.example.demo1.dto.ProductRequest;
import com.example.demo1.dto.ProductResponse;
import com.example.demo1.exception.ConflictException;
import com.example.demo1.repository.OrderItemRepository;
import com.example.demo1.repository.ProductRepository;
import com.example.demo1.model.Product;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;

    public ProductService(ProductRepository productRepository, OrderItemRepository orderItemRepository) {
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
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

        // `order_items.product_id` is a foreign key, so deleting a referenced product
        // would fail at flush time. Detect it here to return an actionable 409 instead
        // of an opaque constraint-violation message.
        if (orderItemRepository.countByProductId(id) > 0) {
            throw new ConflictException(
                    "Product " + id + " cannot be deleted because it is referenced by existing orders");
        }

        productRepository.deleteById(id);
        return true;
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
    }
}
