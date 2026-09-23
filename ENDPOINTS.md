# API Endpoints

This document provides a detailed list of all available REST API endpoints for this application.
Base URL: `http://localhost:8080` (or whatever host/port the app is running on).

## Conventions

- All request and response bodies are `application/json`.
- Timestamps are serialized as ISO-8601 `LocalDateTime` (e.g. `2026-09-23T08:49:45`), stored in UTC
  (`spring.jpa.properties.hibernate.jdbc.time_zone=UTC`).
- Monetary values are decimals (`BigDecimal`), never binary floats. At most 2 decimal places.
- **Success bodies are JSON. Error bodies are plain text** for exceptions raised by the service layer
  (see [Error responses](#4-error-responses)). Bean Validation failures instead return Spring Boot's
  default error JSON, so the two shapes differ.

---

## 1. Products API (`/api/products`)

### `GET /api/products`

Retrieves a list of all products.

- **Success Response:** `200 OK`
- **Body:** JSON array of Product objects. Empty array when no products exist.
- **Note:** no pagination or sorting; the full table is returned.

```json
[
  {
    "id": 1,
    "name": "Laptop",
    "description": "14 inch, 16GB RAM",
    "price": 15000000.00,
    "stock": 10
  }
]
```

### `GET /api/products/{id}`

Retrieves a single product by its ID.

- **Path Variable:** `id` (Long)
- **Success Response:** `200 OK`
- **Error Response:** `404 Not Found` (if ID does not exist)

### `POST /api/products`

Creates a new product.

- **Request Body (JSON):**
  - `name`: string (required, max 255 chars)
  - `description`: string (optional, max 2000 chars in the DTO , see the note below)
  - `price`: number (required, non-negative, at most 2 decimals)
  - `stock`: integer (required, non-negative)
- **Success Response:** `201 Created`
- **Error Response:** `400 Bad Request` (if validation fails)

```json
{
  "name": "Laptop",
  "description": "14 inch, 16GB RAM",
  "price": 15000000,
  "stock": 10
}
```

> **`description` caveat:** the DTO accepts up to 2000 chars but the `description`
> column is `VARCHAR(255)`. A longer value passes validation and then fails at
> flush time. This mismatch was consciously left in the code, so **keep
> descriptions under 255 chars**.

### `PUT /api/products/{id}`

Updates an existing product. The payload replaces **all** mutable fields , this is
a full replacement, not a partial patch, so omitted fields become `null` (and
`name`/`price`/`stock` are rejected as invalid).

- **Path Variable:** `id` (Long)
- **Request Body (JSON):** Same fields and constraints as `POST`.
- **Success Response:** `200 OK`
- **Error Response:** `400 Bad Request` (validation), `404 Not Found` (if ID does not exist), `409 Conflict` (if the product was modified concurrently)

> **`stock` is set authoritatively (decided behaviour).** `stock` represents
> *available* stock (decremented when an order is created, restored when an order
> is deleted), and this endpoint overwrites it with whatever value you send.
> Because an open order's reservation is not taken into account, stock can end up
> above the original total:
>
> ```text
> stock 10 → create order for 3 → 7 → PUT stock=10 → delete the order → 13
> ```
>
> That is expected, not a defect. Treat the value you send as authoritative, and
> avoid overwriting `stock` while orders are open if exact totals matter.

### `DELETE /api/products/{id}`

Deletes a product by its ID.

- **Path Variable:** `id` (Long)
- **Success Response:** `204 No Content`
- **Error Responses:**
  - `404 Not Found` (if ID does not exist)
  - `409 Conflict` (if existing orders still reference the product , `order_items.product_id` is a foreign key). Body: `Product <id> cannot be deleted because it is referenced by existing orders`

---

## 2. Orders API (`/api/orders`)

### `GET /api/orders`

Retrieves a list of all orders along with their line items and computed total price.

- **Success Response:** `200 OK`
- **Body:** JSON array of Order objects, each containing an array of items.
- **Note:** items and their products are fetched with `@EntityGraph`, so the whole list is
  produced with a single query (no N+1). No pagination or sorting.

### `GET /api/orders/{id}`

Retrieves a single order by its ID.

- **Path Variable:** `id` (Long)
- **Success Response:** `200 OK`
- **Error Response:** `404 Not Found` (if ID does not exist)

### `POST /api/orders`

Creates a new order. The API automatically snapshots the current `price` of each product into `unitPrice` and computes the order total.

- **Request Body (JSON):**
  - `items`: array of objects (required, min 1 item).
    - `productId`: Long (required, must exist in DB)
    - `quantity`: integer (required, minimum 1)
- **Success Response:** `201 Created`
- **Error Responses:**
  - `400 Bad Request` (if validation fails, e.g., missing fields, quantity < 1, product ID does not exist, duplicate `productId`, or insufficient stock)
  - `409 Conflict` (if a product row was modified concurrently by another request; retry the request)
- **Side effects:** the ordered quantity is subtracted from each product's `stock`. Rejected requests leave stock untouched.

```json
{
  "items": [
    { "productId": 1, "quantity": 2 },
    { "productId": 2, "quantity": 1 }
  ]
}
```

Response `201 Created`:

```json
{
  "id": 1,
  "orderDate": "2026-09-23T08:49:45",
  "status": "PENDING",
  "items": [
    { "productId": 1, "productName": "Laptop", "quantity": 2, "unitPrice": 15000000.00 },
    { "productId": 2, "productName": "Mouse", "quantity": 1, "unitPrice": 19.99 }
  ],
  "totalPrice": 30000019.99
}
```

Exact error messages returned for this endpoint:

| Condition | Status | Body |
| --- | --- | --- |
| Insufficient stock | `400` | `Insufficient stock for product: <name>` |
| Unknown product | `400` | `Product with id <id> does not exist` |
| Duplicate product in one order | `400` | `Duplicate productId <id>: each product may appear only once per order` |
| Concurrent modification | `409` | `The product was modified by another request. Please retry.` |

> **Two-phase validation.** `OrderService.create()` validates the *entire* request
> first (duplicate `productId`, product existence, sufficient stock) and only then
> mutates stock. An invalid request therefore never leaves stock partially
> decremented.
>
> **Price snapshot.** `unitPrice` is copied from the product at order time and is
> never recalculated, so later price changes do not alter existing orders.

### `DELETE /api/orders/{id}`

Deletes an order by its ID. This will also cascade and delete all associated order items.

- **Path Variable:** `id` (Long)
- **Success Response:** `204 No Content`
- **Error Response:** `404 Not Found` (if ID does not exist)
- **Side effects:** the quantities consumed by the order are added back to each product's `stock`.

---

## 3. OpenAPI / Swagger Documentation

- `GET /swagger-ui.html` , Interactive Swagger UI.
- `GET /v3/api-docs` , Raw OpenAPI 3 JSON specification.

---

## 4. Error responses

### Service-layer exceptions

Handled by `GlobalExceptionHandler` (`@RestControllerAdvice`). The body is the raw
message as **plain text** (`text/plain`), not JSON.

| Exception | Status | Body |
| --- | --- | --- |
| `IllegalArgumentException` | `400 Bad Request` | The exception message |
| `ConflictException` | `409 Conflict` | The exception message |
| `OptimisticLockingFailureException` | `409 Conflict` | `The product was modified by another request. Please retry.` |
| `DataIntegrityViolationException` | `409 Conflict` | `The request conflicts with the current state of the data.` |

### Bean Validation failures

`MethodArgumentNotValidException` is **not** handled by `GlobalExceptionHandler`, so
validation errors return Spring Boot's default error document (JSON), e.g.:

```json
{
  "timestamp": "2026-09-23T08:49:45.123Z",
  "status": 400,
  "error": "Bad Request",
  "path": "/api/products"
}
```

Clients must therefore tolerate two different error shapes: plain text for
business-rule violations, JSON for validation failures.

### Status code summary

| Status | Meaning in this API |
| --- | --- |
| `200` | Read or update succeeded |
| `201` | Resource created (`POST`) |
| `204` | Deleted, no body (`DELETE`) |
| `400` | Validation failure or business-rule violation (insufficient stock, unknown product, duplicate `productId`) |
| `404` | Resource does not exist |
| `409` | State conflict: product referenced by orders, concurrent modification, or constraint violation |
