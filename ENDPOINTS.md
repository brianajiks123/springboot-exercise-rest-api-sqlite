# API Endpoints

This document provides a detailed list of all available REST API endpoints for this application.
Base URL: `http://localhost:8080` (or whatever host/port the app is running on).

## Conventions

- All request and response bodies are `application/json`.
- Timestamps are serialized as ISO-8601 `LocalDateTime` (e.g. `2026-09-23T08:49:45`), stored in UTC
  (`spring.jpa.properties.hibernate.jdbc.time_zone=UTC`).
- Monetary values are decimals (`BigDecimal`), never binary floats. At most 2 decimal places.
- **Success bodies are JSON, and so are error bodies.** Every failure , service exception,
  Bean Validation, malformed JSON, unmapped path or wrong method , is rendered by
  `GlobalExceptionHandler` as the same `ApiErrorResponse` object, so a client only ever has to
  parse one shape. See [Error responses](#6-error-responses).

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
  - `description`: string (optional, max 255 chars , same limit as the column)
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

> **`description` is capped at 255 chars in both places.** The DTO's `@Size(max = 255)` matches
> the `varchar(255)` column exactly, so an over-long description is rejected with `400` and a
> `fieldErrors` entry instead of passing validation and then failing at flush time.

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

> **`customerId` is validated on every cart endpoint.** It must be 1–64 characters drawn from
> `[A-Za-z0-9._-]`. A value that breaks either rule is rejected with `400 Bad Request` before the
> service is reached, so it can never reach `carts.customer_id`. A URL-encoded space, slash or
> accented character therefore fails fast instead of creating a junk row.

**No endpoint in this section changes `products.stock`.**

> **Concurrent writes to the same cart serialise.** Every mutating endpoint here takes the cart's row
> lock (`SELECT ... FOR UPDATE`) before reading it, so two simultaneous `POST .../items` calls cannot
> both read the same quantity and write it back , the second waits and then sees the first. Every
> accepted add therefore survives in the cart. The one exception is the very first request for a
> brand-new `customerId`: locking a row that does not exist yet cannot help, so if two such requests
> race, the unique constraint on `carts.customer_id` rejects the loser with `409 Conflict`.

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

| Condition | Status | `message` |
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

| Condition | Status | `message` |
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

> **Cancelling is a conditional `UPDATE ... WHERE id = ? AND status = 'PENDING_PAYMENT'`**, not a
> status check followed by a write. A cancel racing against a payment therefore cannot overwrite a
> settled order: whoever arrives second matches no row and gets `409`.

### `DELETE /api/orders/{id}`

Deletes an order by its ID. This also cascades to its order items.

- **Path Variable:** `id` (Long)
- **Success Response:** `204 No Content`
- **Error Responses:**
  - `404 Not Found` (if ID does not exist)
  - `409 Conflict` , `Order <id> has been paid and cannot be deleted` (a `PAID` order is part of the sales record, and because stock is never restored, deleting it would make the stock count disagree with the record)
- **Side effects:** none on stock.
- **Allowed statuses:** `PENDING_PAYMENT` and `CANCELLED`.

> **The order's row is locked before its status is read.** A delete has to read the order and then
> cascade to its items, so it takes a row lock first. A payment arriving at the same instant either
> commits before the lock is granted , in which case the delete sees `PAID` and returns `409` , or it
> waits and then finds no row at all. Either way a settled order is never erased.

---

## 4. OpenAPI / Swagger Documentation

- `GET /swagger-ui.html` , Interactive Swagger UI.
- `GET /v3/api-docs` , Raw OpenAPI 3 JSON specification.

---

## 5. Health endpoint

`GET /actuator/health` answers `200 OK` with `{"status":"UP"}` while the application and its
datasource are usable, and `503` when a health indicator reports `DOWN`. It is the only actuator
endpoint exposed (`management.endpoints.web.exposure.include=health`); every other actuator path
returns `404`.

---

## 6. Error responses

### One envelope for everything

`GlobalExceptionHandler` (`@RestControllerAdvice`) renders every failure as `ApiErrorResponse`,
whatever raised it , a service exception, Bean Validation, an unreadable body, an unmapped path,
or an unexpected bug:

```json
{
  "timestamp": "2026-09-23T10:20:41.512",
  "status": 409,
  "error": "Conflict",
  "message": "Order 3 is PAID and can no longer be paid",
  "path": "/api/orders/3/pay",
  "fieldErrors": null
}
```

| Field | Meaning |
| --- | --- |
| `timestamp` | When the error was produced (ISO-8601 `LocalDateTime`) |
| `status` | HTTP status code, repeated in the body |
| `error` | HTTP reason phrase, e.g. `Conflict` |
| `message` | Human-readable explanation. Safe to show to a user |
| `path` | The requested path, without query string |
| `fieldErrors` | Field name → validation message. **`null`** unless a request failed validation |

### What produces which status

| Cause | Status | `message` |
| --- | --- | --- |
| `BadRequestException` , a business rule on the request (unknown product, empty cart, not enough stock at checkout) | `400` | The exception message |
| `ConflictException` , the request clashes with the current state | `409` | The exception message |
| `NotFoundException` , the addressed resource does not exist | `404` | The exception message, e.g. `Order 999 does not exist` |
| `MethodArgumentNotValidException` , invalid request body | `400` | `Request validation failed` + `fieldErrors` |
| `HandlerMethodValidationException` / `ConstraintViolationException` , invalid path variable | `400` | `Request validation failed` + `fieldErrors` |
| `MethodArgumentTypeMismatchException` , path variable that cannot be parsed as its type, e.g. `GET /api/products/abc` | `400` | `Parameter 'id' must be a number, but was 'abc'` |
| `HttpMessageNotReadableException` , missing or malformed JSON | `400` | `Request body is missing or malformed` |
| `OptimisticLockingFailureException` | `409` | `The product was modified by another request. Please retry.` |
| `DataIntegrityViolationException` (safety net) | `409` | `The request conflicts with the current state of the data.` |
| Spring's own `ErrorResponse` , unmapped path, unsupported method | `404` / `405` | Spring's detail, or its title |
| Anything else | `500` | `Unexpected server error` |

A validation failure, with `fieldErrors` populated:

```json
{
  "timestamp": "2026-09-23T10:19:02.144",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/products",
  "fieldErrors": { "name": "Name must not be blank", "price": "Price is required" }
}
```

An unmapped path uses the same envelope , `POST /api/orders` is the built-in example, because
that endpoint deliberately does not exist:

```json
{
  "timestamp": "2026-09-23T10:21:55.006",
  "status": 405,
  "error": "Method Not Allowed",
  "message": "Method 'POST' is not supported.",
  "path": "/api/orders",
  "fieldErrors": null
}
```

> **`500` never leaks internals.** The catch-all handler logs the real exception with its stack
> trace and returns a fixed message, so a database or null-pointer error cannot expose a stack
> trace or a SQL fragment to the client.
>
> **Every `404` in this document carries that body.** "Not found" is signalled by the service
> throwing `NotFoundException`, not by a controller returning an empty `404`, so all eleven
> not-found paths include a `message` naming what was missing. A `404` with no body would mean the
> envelope has been bypassed somewhere.

### Status code summary

| Status | Meaning in this API |
| --- | --- |
| `200` | Read, cart update, payment or cancellation succeeded |
| `201` | Resource created (`POST /api/products`, `POST /api/carts/{id}/checkout`) |
| `204` | Deleted, no body (`DELETE`) |
| `400` | Validation failure, a path variable that cannot be parsed (e.g. `/api/products/abc`), or a business-rule violation on the request itself (unknown product, empty cart, not enough stock at checkout) |
| `404` | Resource does not exist (product, order, cart, or cart line) |
| `405` | `POST /api/orders` , orders are created by a cart checkout |
| `409` | State conflict: insufficient stock at payment, wrong order status, product still referenced, concurrent modification, constraint violation |
| `500` | An unexpected failure; the body says only `Unexpected server error` |

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
