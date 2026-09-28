# API Endpoints

This document provides a detailed list of all available REST API endpoints for this application.
Base URL: `http://localhost:8080` (or whatever host/port the app is running on).

## Conventions

- All request and response bodies are `application/json`.
- Timestamps are serialized as ISO-8601 `LocalDateTime` (e.g. `2026-09-23T08:49:45`), stored in UTC
  (`spring.jpa.properties.hibernate.jdbc.time_zone=UTC`).
- Monetary values are decimals (`BigDecimal`), never binary floats. At most 2 decimal places.
- **Success bodies are JSON. Error bodies are plain text** for exceptions raised by the service layer
  (see [Error responses](#5-error-responses)). Bean Validation failures instead return Spring Boot's
  default error JSON, so the two shapes differ.

## The flow in one picture

```text
POST /api/carts/{customerId}/items        add products      (stock untouched)
            |
POST /api/carts/{customerId}/checkout     order PENDING_PAYMENT, cart emptied
            |                             (stock still untouched)
POST /api/orders/{id}/pay                 cashier takes money -> PAID
                                          (stock deducted here, once, atomically)
```

The only alternative to paying is abandoning the order, which changes no stock:

```text
POST /api/orders/{id}/cancel              PENDING_PAYMENT -> CANCELLED
DELETE /api/orders/{id}                   only for PENDING_PAYMENT / CANCELLED
```

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
- **Error Responses:** `400 Bad Request` (validation), `404 Not Found` (if ID does not exist), `409 Conflict` (if the product was changed concurrently , retry)

> **`stock` is a restock.** The value you send becomes the new physical stock on
> hand, regardless of how many carts or unpaid orders exist. That is safe now because
> neither of those reserves anything: stock is only reduced when a sale is settled at
> the cashier, and nothing ever adds it back. The old
> `10 → order 3 → 7 → PUT 10 → delete order → 13` sequence can no longer happen ,
> see [Stock semantics](#stock-semantics) below.
>
> Sending a value lower than what has already been sold does not create a debt; it
> simply states the shelf is emptier than the sales record implies.

### `DELETE /api/products/{id}`

Deletes a product by its ID.

- **Path Variable:** `id` (Long)
- **Success Response:** `204 No Content`
- **Error Responses:**
  - `404 Not Found` (if ID does not exist)
  - `409 Conflict` (if the product is still referenced). Two distinct bodies:
    - `Product <id> cannot be deleted because it is referenced by existing orders` , `order_items.product_id` is a foreign key
    - `Product <id> cannot be deleted because it is in a customer's cart` , `cart_items.product_id` is a foreign key

---

## 2. Carts API (`/api/carts/{customerId}`)

There is no authentication yet, so a cart is addressed by a client-supplied
`customerId` string in the path, for example `/api/carts/budi`. Anyone who knows the id
can read and change that cart; that is an accepted limitation of this exercise, not a
security boundary.

**No endpoint in this section changes `products.stock`.**

### `GET /api/carts/{customerId}`

Returns the cart, or an empty cart when the customer has none.

- **Success Response:** `200 OK`
- **Body:** `CartResponse`
- **Side effects:** none. Reading never creates a cart row.

```json
{
  "customerId": "budi",
  "items": [],
  "totalItems": 0,
  "totalPrice": 0,
  "updatedAt": null
}
```

### `POST /api/carts/{customerId}/items`

Adds a product to the cart, creating the cart on first use.

- **Request Body (JSON):**
  - `productId`: Long (required, must exist)
  - `quantity`: integer (required, `1`–`999`)
- **Success Response:** `200 OK` , the whole updated cart
- **Error Responses:**
  - `400 Bad Request` , validation failure, or `Product with id <id> does not exist`

```json
{ "productId": 1, "quantity": 2 }
```

> **Adding the same product again accumulates.** The second call adds to the existing
> quantity instead of creating a second line, because `cart_items` is unique on
> `(cart_id, product_id)`. Adding 2 then 3 leaves one line with quantity 5.
>
> **Adding more than is in stock is allowed.** A cart does not reserve anything, and
> blocking here would make the "buyer fixes the quantity" loop needlessly awkward. The
> shortfall is reported at checkout.

### `PUT /api/carts/{customerId}/items/{productId}`

Sets the **absolute** quantity of a line, the way typing into a quantity box works.

- **Path Variables:** `customerId` (String), `productId` (Long)
- **Request Body (JSON):** `{ "quantity": 5 }` (required, `1`–`999`)
- **Success Response:** `200 OK` , the whole updated cart
- **Error Responses:** `400 Bad Request` (validation), `404 Not Found` (no such cart or no such line)

> Sending `0` is rejected. To remove a line, use `DELETE`, so that "remove" has exactly
> one meaning.

### `DELETE /api/carts/{customerId}/items/{productId}`

Removes one line from the cart.

- **Success Response:** `204 No Content`
- **Error Response:** `404 Not Found` (no such cart, or the product is not in it)

### `DELETE /api/carts/{customerId}`

Empties the cart, keeping the cart itself.

- **Success Response:** `204 No Content`
- **Error Response:** `404 Not Found` (no such cart, or it is already empty)

### `POST /api/carts/{customerId}/checkout`

Turns the cart into an order awaiting payment, and empties the cart.

- **Request Body:** none
- **Success Response:** `201 Created` , an `OrderResponse` with `status` = `PENDING_PAYMENT`
- **Error Responses:**
  - `404 Not Found` , no cart for this customer
  - `400 Bad Request` , empty cart, or a product no longer has enough stock

```json
{
  "id": 7,
  "orderDate": "2026-09-23T10:15:30",
  "status": "PENDING_PAYMENT",
  "paidAt": null,
  "items": [
    { "productId": 1, "productName": "Laptop", "quantity": 2, "unitPrice": 15000000.00 }
  ],
  "totalPrice": 30000000.00
}
```

| Condition | Status | Body |
| --- | --- | --- |
| Cart is empty | `400` | `Cart of customer <customerId> is empty` |
| Not enough stock | `400` | `Insufficient stock for product: <name>` |

> **Stock is not deducted here.** The availability check is a fast-fail courtesy so the
> buyer is told immediately; it is a read and can go stale. The authoritative check runs
> inside the database when the cashier is paid , see `POST /api/orders/{id}/pay`.
>
> **Prices are snapshotted here.** `unitPrice` is copied from the product at checkout and
> never recalculated, so repricing a product later does not alter existing orders. A cart
> shows live prices, so its total can legitimately differ from the order total if a
> repricing happens in between.
>
> **The cart is emptied.** Leaving the lines behind would let the same items be checked
> out twice.

---

## 3. Orders API (`/api/orders`)

There is intentionally **no `POST /api/orders`** (it returns `405 Method Not Allowed`): an
order is always born from a cart checkout, which is what keeps the line items consistent
with what the buyer actually put in the cart.

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

### `POST /api/orders/{id}/pay`

**The cashier action.** Confirms payment and settles the stock.

- **Path Variable:** `id` (Long)
- **Request Body:** none
- **Success Response:** `200 OK` , the order with `status` = `PAID` and a non-null `paidAt`
- **Error Responses:**
  - `404 Not Found` (if ID does not exist)
  - `409 Conflict` (if the order is not awaiting payment, or a product ran out of stock)

| Condition | Status | Body |
| --- | --- | --- |
| Order already `PAID` | `409` | `Order <id> is PAID and can no longer be paid` |
| Order `CANCELLED` | `409` | `Order <id> is CANCELLED and can no longer be paid` |
| Not enough stock | `409` | `Insufficient stock for product: <name>` |

**Side effects:** the ordered quantities are subtracted from each product's `stock`, in one
atomic conditional `UPDATE` per product. This is the only operation in the whole API that
reduces stock because of a sale.

> **Why the failure is `409`, not `400`.** The request was valid when it was sent; the
> conflict is with the *current state of the data*, and retrying the same request unchanged
> will not help. The buyer must be given a chance to reduce the quantity.
>
> **An order can never be half-settled.** If one product runs out, the whole transaction
> rolls back, including decrements that already succeeded, and the order stays
> `PENDING_PAYMENT`.
>
> **Two cashiers cannot oversell the last unit.** The availability check and the subtraction
> are a single SQL statement, so the database arbitrates; the loser gets `409`. A
> read-then-write implementation could not give this guarantee.
>
> **The same order cannot be paid twice.** Claiming the order is itself a single conditional
> `UPDATE ... WHERE id = ? AND status = 'PENDING_PAYMENT'`, so a second cashier working on the same
> order matches nothing, gets `409`, and never reaches the stock. The order is claimed *before* the
> stock is deducted, which is what lets the two guarantees hold together: a loser leaves the order,
> and the stock, exactly as they were.

### `POST /api/orders/{id}/cancel`

Abandons an order that has not been paid.

- **Path Variable:** `id` (Long)
- **Request Body:** none
- **Success Response:** `200 OK` , the order with `status` = `CANCELLED`
- **Error Responses:**
  - `404 Not Found` (if ID does not exist)
  - `409 Conflict` , `Order <id> is <STATUS> and can no longer be cancelled`
- **Side effects:** none. An unpaid order never deducted stock, so there is nothing to give back.

### `DELETE /api/orders/{id}`

Deletes an order by its ID. This also cascades to its order items.

- **Path Variable:** `id` (Long)
- **Success Response:** `204 No Content`
- **Error Responses:**
  - `404 Not Found` (if ID does not exist)
  - `409 Conflict` , `Order <id> has been paid and cannot be deleted` (a `PAID` order is part of the sales record, and because stock is never restored, deleting it would make the stock count disagree with the record)
- **Side effects:** none on stock.
- **Allowed statuses:** `PENDING_PAYMENT` and `CANCELLED`.

---

## 4. OpenAPI / Swagger Documentation

- `GET /swagger-ui.html` , Interactive Swagger UI.
- `GET /v3/api-docs` , Raw OpenAPI 3 JSON specification.

---

## 5. Error responses

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
| `200` | Read, cart update, payment or cancellation succeeded |
| `201` | Resource created (`POST /api/products`, `POST /api/carts/{id}/checkout`) |
| `204` | Deleted, no body (`DELETE`) |
| `400` | Validation failure, or a business-rule violation on the request itself (unknown product, empty cart, not enough stock at checkout) |
| `404` | Resource does not exist (product, order, cart, or cart line) |
| `405` | `POST /api/orders` , orders are created by a cart checkout |
| `409` | State conflict: insufficient stock at payment, wrong order status, product still referenced, concurrent modification, constraint violation |

---

## Stock semantics

`stock` is the **physical count on hand**, and it is the single source of truth for
availability. It is written in exactly two places:

- `POST` / `PUT /api/products` , sets it verbatim (a restock or a stock correction).
- `POST /api/orders/{id}/pay` , subtracts the sold quantities.

Nothing ever adds stock back, which is what makes the count trustworthy:

- A cart reserves nothing, so it never needs releasing.
- Cancelling or deleting an unpaid order changes no stock.
- A paid order is terminal, so it can never be cancelled or deleted.

```text
stock 10  →  add 3 to a cart           →  stock 10
          →  checkout                  →  stock 10   (order PENDING_PAYMENT)
          →  PUT /api/products stock=10 →  stock 10
          →  pay at the cashier         →  stock 7
```

The old design reached `13` in that sequence, because the order's reservation lived inside
`stock` and a `DELETE` added it back on top of the value the `PUT` had already overwritten.
That is now structurally impossible: there is no "restore" step left.
