package com.example.demo1.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks down the OpenAPI document the application publishes at {@code /v3/api-docs}, the file
 * Swagger UI renders and any generated client is built from.
 *
 * <p>This is a regression test for a real gap: springdoc cannot infer a status code from
 * {@code ResponseEntity}, so before the controllers declared their responses every one of the
 * sixteen operations advertised {@code 200} and nothing else. A client generated from that document
 * believed {@code POST /api/products} returned {@code 200} (it returns {@code 201}),
 * {@code DELETE} returned {@code 200} (it returns {@code 204}), and that no call could ever fail ,
 * {@code ApiErrorResponse} did not even appear in {@code components}, so the error body was absent
 * from the published contract even though every failure returns it.
 *
 * <p>The document is read back over HTTP rather than from the {@code OpenAPI} model, so this test
 * asserts what a client actually downloads.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> METHODS = List.of("get", "post", "put", "delete", "patch");

    /**
     * Every operation the API exposes, with the success code it really returns. Keeping the full
     * list here means a new endpoint cannot be added without deciding, and stating, its contract:
     * the test fails until the map is updated.
     */
    private static final Map<String, String> EXPECTED_SUCCESS = Map.ofEntries(
            Map.entry("GET /api/products", "200"),
            Map.entry("GET /api/products/{id}", "200"),
            Map.entry("POST /api/products", "201"),
            Map.entry("PUT /api/products/{id}", "200"),
            Map.entry("DELETE /api/products/{id}", "204"),
            Map.entry("GET /api/carts/{customerId}", "200"),
            Map.entry("POST /api/carts/{customerId}/items", "200"),
            Map.entry("PUT /api/carts/{customerId}/items/{productId}", "200"),
            Map.entry("DELETE /api/carts/{customerId}/items/{productId}", "204"),
            Map.entry("DELETE /api/carts/{customerId}", "204"),
            Map.entry("POST /api/carts/{customerId}/checkout", "201"),
            Map.entry("GET /api/orders", "200"),
            Map.entry("GET /api/orders/{id}", "200"),
            Map.entry("POST /api/orders/{id}/pay", "200"),
            Map.entry("POST /api/orders/{id}/cancel", "200"),
            Map.entry("DELETE /api/orders/{id}", "204"));

    @Autowired
    private MockMvc mockMvc;

    private JsonNode publishedSpec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    /** "GET /api/products" -> the operation object, for every operation in the document. */
    private static Map<String, JsonNode> operations(JsonNode spec) {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        JsonNode paths = spec.path("paths");
        for (String path : paths.propertyNames()) {
            for (String method : METHODS) {
                JsonNode operation = paths.path(path).get(method);
                if (operation != null) {
                    operations.put(method.toUpperCase(Locale.ROOT) + " " + path, operation);
                }
            }
        }
        return operations;
    }

    private static List<String> statusCodes(JsonNode operation) {
        List<String> codes = new ArrayList<>(operation.path("responses").propertyNames());
        Collections.sort(codes);
        return codes;
    }

    private static String firstSuccessCode(JsonNode operation) {
        for (String code : statusCodes(operation)) {
            if (code.startsWith("2")) {
                return code;
            }
        }
        return null;
    }

    private static String errorSchemaRef(JsonNode response) {
        JsonNode content = response.path("content");
        for (String mediaType : content.propertyNames()) {
            return content.path(mediaType).path("schema").path("$ref").asString("");
        }
        return null;
    }

    @Test
    void everyOperation_documentsTheSuccessCodeItActuallyReturns() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : operations(publishedSpec()).entrySet()) {
            actual.put(entry.getKey(), firstSuccessCode(entry.getValue()));
        }

        assertEquals(EXPECTED_SUCCESS, actual,
                "the published success codes must match what the API returns, and every operation must be listed");
    }

    @Test
    void everyOperation_documentsAtLeastOneErrorResponse() throws Exception {
        for (Map.Entry<String, JsonNode> entry : operations(publishedSpec()).entrySet()) {
            boolean hasError = statusCodes(entry.getValue()).stream().anyMatch(code -> !code.startsWith("2"));

            assertTrue(hasError, entry.getKey() + " is published without a single error response");
        }
    }

    @Test
    void everyErrorResponse_carriesTheSharedErrorBody() throws Exception {
        JsonNode spec = publishedSpec();

        assertNotNull(spec.path("components").path("schemas").get("ApiErrorResponse"),
                "the one error body this API returns must be published as a component schema");

        for (Map.Entry<String, JsonNode> entry : operations(spec).entrySet()) {
            for (String code : statusCodes(entry.getValue())) {
                if (code.startsWith("2")) {
                    continue;
                }

                assertEquals("#/components/schemas/ApiErrorResponse",
                        errorSchemaRef(entry.getValue().path("responses").path(code)),
                        entry.getKey() + " " + code + " must document the shared error body");
            }
        }
    }
}
