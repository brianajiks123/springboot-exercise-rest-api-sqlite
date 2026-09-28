package com.example.demo1.service;

import com.example.demo1.dto.ProductRequest;
import com.example.demo1.dto.ProductResponse;
import com.example.demo1.exception.ConflictException;
import com.example.demo1.exception.NotFoundException;
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
                .orElseThrow(() -> new NotFoundException("Product " + id + " does not exist"));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product " + id + " does not exist"));
        applyRequest(product, request);
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public void deleteById(Long id) {
        if (!productRepository.existsById(id)) {
            throw new NotFoundException("Product " + id + " does not exist");
        }

        if (orderItemRepository.countByProductId(id) > 0) {
            throw new ConflictException(
                    "Product " + id + " cannot be deleted because it is referenced by existing orders");
        }
        if (cartItemRepository.countByProductId(id) > 0) {
            throw new ConflictException(
                    "Product " + id + " cannot be deleted because it is in a customer's cart");
        }

        productRepository.deleteById(id);
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
    }
}
