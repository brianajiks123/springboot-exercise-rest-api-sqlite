package com.example.demo1.controller;

import com.example.demo1.model.Product;
import com.example.demo1.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks down the HTTP contract of the REST API: status codes and error messages
 * that clients depend on. Complements the service-level tests.
 *
 * <p>Note: {@code AutoConfigureMockMvc} lives in
 * {@code org.springframework.boot.webmvc.test.autoconfigure} in Spring Boot 4
 * (it moved from {@code org.springframework.boot.test.autoconfigure.web.servlet}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    private Long givenProduct(String name, String price, int stock) {
        return productRepository.save(new Product(name, null, new BigDecimal(price), stock)).getId();
    }

    private String orderJson(Long productId, int quantity) {
        return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":" + quantity + "}]}";
    }

    // ------------------------------------------------------------------ products

    @Test
    void createProduct_returns201WithDecimalPrice() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop\",\"description\":\"14 inch\",\"price\":15000000.00,\"stock\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Laptop"))
                .andExpect(jsonPath("$.price").value(15000000.00))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void createProduct_withoutStock_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NoStock\",\"price\":10.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProduct_withThreeDecimals_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ThreeDecimals\",\"price\":1.234,\"stock\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProduct_withNegativePrice_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Negative\",\"price\":-1.00,\"stock\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProduct_withNegativeStock_returns400() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NegStock\",\"price\":5.00,\"stock\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getProduct_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/products/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteProduct_notReferenced_returns204() throws Exception {
        Long productId = givenProduct("Disposable", "9.99", 1);

        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteProduct_unknownId_returns404() throws Exception {
        mockMvc.perform(delete("/api/products/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteProduct_referencedByOrder_returns409WithActionableMessage() throws Exception {
        Long productId = givenProduct("Referenced", "5.00", 10);
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(productId, 1)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "Product " + productId + " cannot be deleted because it is referenced by existing orders"));
    }

    // -------------------------------------------------------------------- orders

    @Test
    void createOrder_decrementsStockAndReturnsTotal() throws Exception {
        Long productId = givenProduct("Mouse", "19.99", 10);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(productId, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(19.99))
                .andExpect(jsonPath("$.totalPrice").value(59.97));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(7));
    }

    @Test
    void createOrder_insufficientStock_returns400WithMessage() throws Exception {
        Long productId = givenProduct("Scarce", "10.00", 1);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(productId, 5)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Insufficient stock for product: Scarce"));
    }

    @Test
    void createOrder_duplicateProductId_returns400WithMessage() throws Exception {
        Long productId = givenProduct("Dup", "10.00", 5);
        String body = "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1},"
                + "{\"productId\":" + productId + ",\"quantity\":1}]}";

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "Duplicate productId " + productId + ": each product may appear only once per order"));
    }

    @Test
    void createOrder_unknownProduct_returns400WithMessage() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(999_999L, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Product with id 999999 does not exist"));
    }

    @Test
    void createOrder_emptyItems_returns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteOrder_returns204AndRestoresStock() throws Exception {
        Long productId = givenProduct("Restock", "10.00", 10);
        String created = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(productId, 4)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long orderId = Long.valueOf(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(6));

        mockMvc.perform(delete("/api/orders/{id}", orderId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.stock").value(10));
    }

    @Test
    void deleteOrder_unknownId_returns404() throws Exception {
        mockMvc.perform(delete("/api/orders/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }
}
