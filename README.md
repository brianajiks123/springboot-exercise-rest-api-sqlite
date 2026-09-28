# Demo1 , Product & Order REST API (Spring Boot)

A simple REST API for **Product** CRUD and **Order** management, built with Spring Boot + Spring Data JPA + Hibernate on a file-based **H2** database.

The ordering model is an **e-commerce cart with payment at the cashier** (no payment gateway):
a buyer fills a cart, checks out to get an order awaiting payment, and the cashier settles it.
Product stock is deducted **only** at the moment of payment, which makes the stock count exact
and removes the possibility of overselling under concurrency.

## Features

- Full CRUD for products (`GET`, `GET by id`, `POST`, `PUT`, `DELETE`).
- **Shopping cart** per customer: add, change quantity, remove a line, empty the cart. Adding the
  same product twice accumulates into one line, like any e-commerce cart.
- **Checkout turns a cart into an order** with status `PENDING_PAYMENT`, snapshots the price of
  every line, and empties the cart.
- **Pay at the cashier:** `POST /api/orders/{id}/pay` marks the order `PAID` and deducts stock.
  This is the only operation in the API that reduces stock because of a sale.
- **No overselling, by construction.** The availability check and the subtraction are a single
  conditional `UPDATE ... WHERE stock >= :quantity`, so the database arbitrates concurrent
  payments instead of the application.
- **Nothing ever adds stock back.** A cart reserves nothing, cancelling an unpaid order changes no
  stock, and a paid order is terminal , so the same units can never be counted twice.
- **Two-phase checkout:** the whole cart is validated before anything is created, so a rejected
  checkout never leaves a half-built order behind.
- **Line items are sorted by product ID before stock is decremented**, so concurrent payments
  sharing products cannot deadlock.
- **N+1-free listing:** `OrderRepository` and `CartRepository` use `@EntityGraph` to fetch items
  and their products in a single query.
- Layered architecture: `Controller → Service → Repository`, with **DTOs** separated from the entity
  (the API does not expose the table structure).
- Request-body validation via Jakarta Bean Validation.
- Central exception-to-HTTP mapping in `@RestControllerAdvice` (`GlobalExceptionHandler`).
- Decimal money handling: `BigDecimal` / `NUMERIC(19,2)`, never `double`.
- Automatic API documentation via **Swagger UI / OpenAPI 3**.
- File-based H2 database (no separate DB server) , `demo1db.mv.db`.
- 81 automated tests: a context smoke test, cart and payment service suites (including a
  concurrency test), an HTTP contract suite, and an end-to-end suite that drives a real running
  instance over HTTP against a file-backed database.

## Tech Stack

| Technology | Version |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring Data JPA (Hibernate) | via `spring-boot-starter-data-jpa` |
| Database | H2 (file-based, version managed by the Spring Boot parent) |
| API docs | springdoc-openapi 3.1.0 |
| Testing | JUnit 5 + MockMvc + `RestTestClient` (`spring-boot-starter-webmvc-test`) |
| Build | Maven (wrapper `mvnw` / `mvnw.cmd`) |

## Prerequisites

- **JDK 21**
- The Maven wrapper is included, so no global Maven install is needed. This repository uses
  `distributionType=only-script` (see `.mvn/wrapper/maven-wrapper.properties`): the script
  downloads Maven 3.9.16 on first run and therefore needs a working `JAVA_HOME` (or `java` on
  `PATH`).

## How to Run

```bash
# Windows (PowerShell / cmd)
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

Or build and run the jar:

```bash
# Windows
.\mvnw.cmd clean package -DskipTests
java -jar target\demo1-0.0.1-SNAPSHOT.jar

# macOS / Linux
./mvnw clean package -DskipTests
java -jar target/demo1-0.0.1-SNAPSHOT.jar
```

The app runs at `http://localhost:8080`.

> **Troubleshooting (Git Bash on Windows):** the POSIX `./mvnw` script can fail with
> `Could not find or load main class org.codehaus.plexus.classworlds.launcher.Launcher`
> when `JAVA_HOME` is not exported to the shell. Use `.\mvnw.cmd` from PowerShell/cmd, or
> export `JAVA_HOME` before running `./mvnw`.

### Configuration & secrets

Default non-sensitive settings live in `application.properties` (committed):
port, H2 database location, JPA/Hibernate flags.

Sensitive values (DB passwords, API keys) are meant to live in a separate
`application-secret.properties` file, which is **git-ignored**, and override the
defaults at runtime (Spring Boot loads `application-secret*` with higher
priority than `application.properties`).

Set it up:

```bash
# Windows
copy application-secret.properties.example application-secret.properties

# macOS / Linux
cp application-secret.properties.example application-secret.properties
```

Then edit the copied file and fill in the real values.

> **Never commit credentials into `application.properties`** , it is tracked by
> git. Use `application-secret.properties` for anything sensitive.

## The Order Flow

```text
POST /api/carts/{customerId}/items        add products            stock untouched
POST /api/carts/{customerId}/items/{pid}  change quantity         stock untouched
DELETE /api/carts/{customerId}/items/{pid} remove a line          stock untouched
            |
POST /api/carts/{customerId}/checkout     -> order PENDING_PAYMENT, cart emptied
                                          stock STILL untouched
            |
POST /api/orders/{id}/pay                 cashier takes the money -> PAID
                                          stock deducted HERE, once, atomically
```

The alternatives to paying change no stock at all:

```text
POST /api/orders/{id}/cancel              PENDING_PAYMENT -> CANCELLED   (no stock change)
DELETE /api/orders/{id}                   only for PENDING_PAYMENT / CANCELLED
```

There is deliberately no `POST /api/orders`: an order is always born from a cart checkout, so the
line items can never disagree with what the buyer actually chose.

### Full walkthrough

```bash
# 1. stock the shop
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Laptop","description":"14 inch","price":15000000,"stock":10}'

# 2. the buyer fills a cart (stock is still 10)
curl -X POST http://localhost:8080/api/carts/budi/items \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}'

# 3. checkout -> order 1, status PENDING_PAYMENT, cart now empty (stock is still 10)
curl -X POST http://localhost:8080/api/carts/budi/checkout

# 4. the cashier takes the money -> stock becomes 8
curl -X POST http://localhost:8080/api/orders/1/pay
```

## API Endpoints

### Products

All endpoints are prefixed with `/api/products`

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/products` | Get all products |
| `GET` | `/api/products/{id}` | Get product by ID (404 if not found) |
| `POST` | `/api/products` | Create a new product (201) |
| `PUT` | `/api/products/{id}` | Update product by ID, including an authoritative restock (404 if not found) |
| `DELETE` | `/api/products/{id}` | Delete product by ID (204 / 404 / 409) |

### Carts

All endpoints are prefixed with `/api/carts/{customerId}`. There is no authentication yet, so the
cart is identified by a client-supplied `customerId` (for example `budi`).

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/carts/{customerId}` | Get the cart (empty cart if the customer has none; reading never creates one) |
| `POST` | `/api/carts/{customerId}/items` | Add a product, accumulating into an existing line (200) |
| `PUT` | `/api/carts/{customerId}/items/{productId}` | Set the absolute quantity of a line (200 / 404) |
| `DELETE` | `/api/carts/{customerId}/items/{productId}` | Remove one line (204 / 404) |
| `DELETE` | `/api/carts/{customerId}` | Empty the cart (204 / 404) |
| `POST` | `/api/carts/{customerId}/checkout` | Create an order awaiting payment and empty the cart (201 / 400 / 404) |

### Orders

All endpoints are prefixed with `/api/orders`

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/orders` | Get all orders with their line items and totals |
| `GET` | `/api/orders/{id}` | Get order by ID (404 if not found) |
| `POST` | `/api/orders/{id}/pay` | **Cashier:** mark `PAID` and deduct stock (200 / 404 / 409) |
| `POST` | `/api/orders/{id}/cancel` | Cancel an unpaid order (200 / 404 / 409) |
| `DELETE` | `/api/orders/{id}` | Delete an unpaid order (204 / 404 / 409) |

### Example requests

#### Create a product

```bash
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Laptop","description":"14 inch, 16GB RAM","price":15000000,"stock":10}'
```

Response `201 Created`:

```json
{
  "id": 1,
  "name": "Laptop",
  "description": "14 inch, 16GB RAM",
  "price": 15000000.00,
  "stock": 10
}
```

> **Validation:** `name` is required (max 255 chars), `price` is required, must not be negative and accepts at most 2 decimals, `stock` is required and must not be negative. `description` is optional and the DTO allows up to 2000 chars , but see [Known limitations](#known-limitations) for the column-size mismatch. On validation failure the API returns `400 Bad Request`. Concurrent updates to the same product return `409 Conflict`.

#### Add to a cart

```bash
curl -X POST http://localhost:8080/api/carts/budi/items \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}'
```

Response `200 OK`:

```json
{
  "customerId": "budi",
  "items": [
    { "productId": 1, "productName": "Laptop", "unitPrice": 15000000.00, "quantity": 2, "subtotal": 30000000.00 }
  ],
  "totalItems": 2,
  "totalPrice": 30000000.00,
  "updatedAt": "2026-09-23T10:15:30"
}
```

> A cart shows the **live** catalogue price, not a snapshot. Sending the same `productId` again
> accumulates into the existing line rather than creating a second one.

#### Check out

```bash
curl -X POST http://localhost:8080/api/carts/budi/checkout
```

Response `201 Created`:

```json
{
  "id": 1,
  "orderDate": "2026-09-23T10:16:02",
  "status": "PENDING_PAYMENT",
  "paidAt": null,
  "items": [
    { "productId": 1, "productName": "Laptop", "quantity": 2, "unitPrice": 15000000.00 }
  ],
  "totalPrice": 30000000.00
}
```

> **Validation:** an unknown customer returns `404`; an empty cart or insufficient stock returns
> `400 Bad Request`. Prices are snapshotted here and never recalculated, so a later price change
> does not alter the order. **Stock is not deducted at this point.**

#### Pay at the cashier

```bash
curl -X POST http://localhost:8080/api/orders/1/pay
```

Response `200 OK`: the same order with `"status": "PAID"` and a non-null `paidAt`. Stock for the
ordered products is reduced as part of the same request.

> **Concurrency:** paying is a single conditional `UPDATE` per product. If two cashiers pay for the
> last unit at the same time, one succeeds and the other gets `409 Conflict`. An order can never be
> half-settled: if one product runs out, every decrement in that request is rolled back and the
> order stays `PENDING_PAYMENT`.

## Error Handling

All service-level exceptions are translated by `GlobalExceptionHandler`
(`@RestControllerAdvice`). The bodies below are **plain text**, not JSON:

| Exception | Status | Response body |
| --- | --- | --- |
| `IllegalArgumentException` | `400 Bad Request` | The exception message (e.g. `Insufficient stock for product: Laptop`) |
| `ConflictException` | `409 Conflict` | The exception message (e.g. `Order 3 is PAID and can no longer be paid`) |
| `OptimisticLockingFailureException` | `409 Conflict` | `The product was modified by another request. Please retry.` |
| `DataIntegrityViolationException` | `409 Conflict` | `The request conflicts with the current state of the data.` |

> **Two different 400 shapes.** Bean Validation failures are **not** handled by
> `GlobalExceptionHandler`: `MethodArgumentNotValidException` is left to Spring
> Boot's default error handling, so a validation error returns the standard
> Boot error JSON (`timestamp`, `status`, `error`, `path`), while business-rule
> errors return a plain-text message. Clients should not assume one single
> error envelope.

## API Documentation

With the app running:

| URL | Description |
| --- | --- |
| `http://localhost:8080/swagger-ui.html` | Swagger UI (interactive) |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3 spec (JSON) |

## Project Structure

```text
demo1/
├── pom.xml                             # Dependencies & build config (see DEPENDENCIES.md)
├── mvnw / mvnw.cmd                     # Maven wrapper (script-only, no wrapper jar)
├── .mvn/wrapper/maven-wrapper.properties
├── .gitattributes                      # eol normalization; H2 db files marked -text
├── .gitignore                          # target/, *.db, application-secret.properties, .workbuddy-ai/
├── .markdownlint.json                  # MD013 (line length) & MD060 disabled
├── application-secret.properties.example  # Template for the git-ignored secrets file
├── demo1db.mv.db                       # H2 database file (auto-generated, git-ignored)
└── src/
    ├── main/
    │   ├── java/com/example/demo1/
    │   │   ├── Demo1Application.java        # Application entry point
    │   │   ├── controller/
    │   │   │   ├── ProductController.java       # Product endpoints & OpenAPI docs
    │   │   │   ├── CartController.java          # Cart endpoints (no stock effects)
    │   │   │   └── OrderController.java         # Cashier endpoints: pay / cancel / delete
    │   │   ├── service/
    │   │   │   ├── ProductService.java          # Product CRUD, entity ↔ DTO mapping, delete guards
    │   │   │   ├── CartService.java             # Cart logic + checkout (never touches stock)
    │   │   │   └── OrderService.java            # pay() = the only sale-driven stock write
    │   │   ├── repository/
    │   │   │   ├── ProductRepository.java       # + decrementStockIfAvailable (atomic)
    │   │   │   ├── CartRepository.java          # findByCustomerId with @EntityGraph
    │   │   │   ├── CartItemRepository.java      # countByProductId , guards product deletion
    │   │   │   ├── OrderRepository.java         # findAll/findById with @EntityGraph (avoids N+1)
    │   │   │   └── OrderItemRepository.java     # countByProductId , guards product deletion
    │   │   ├── model/
    │   │   │   ├── Product.java                 # JPA entity (table `products`, @Version optimistic lock)
    │   │   │   ├── Cart.java                    # JPA entity (table `carts`, unique customer_id)
    │   │   │   ├── CartItem.java                # JPA entity (table `cart_items`, uk_cart_product)
    │   │   │   ├── Order.java                   # JPA entity (table `orders`, PENDING_PAYMENT/PAID/CANCELLED)
    │   │   │   └── OrderItem.java               # JPA entity (table `order_items`, uk_order_product)
    │   │   ├── exception/
    │   │   │   ├── ConflictException.java       # Runtime exception → 409 Conflict
    │   │   │   └── GlobalExceptionHandler.java  # @RestControllerAdvice: service exceptions → HTTP
    │   │   └── dto/
    │   │       ├── ProductRequest.java          # Input payload + validation
    │   │       ├── ProductResponse.java         # Response payload
    │   │       ├── CartItemRequest.java         # Add-to-cart payload
    │   │       ├── CartItemQuantityRequest.java # Absolute-quantity payload
    │   │       ├── CartResponse.java            # Cart with live prices and running total
    │   │       ├── CartItemResponse.java        # Cart line response
    │   │       ├── OrderResponse.java           # Order response (items + computed totalPrice)
    │   │       └── OrderItemResponse.java       # Order line response
    │   └── resources/
    │       └── application.properties            # Server & DB configuration
    └── test/
        ├── java/com/example/demo1/
        │   ├── Demo1ApplicationTests.java        # Context smoke test
        │   ├── controller/
        │   │   └── ApiContractTest.java          # 35 MockMvc tests locking status codes & messages
        │   ├── service/
        │   │   ├── CartServiceTest.java          # 21 tests: cart behaviour & checkout
        │   │   └── OrderPaymentFlowTest.java     # 15 tests: payment, rollback, concurrency
        │   └── e2e/
        │       └── CashierFlowEndToEndTest.java  # 9 tests over real HTTP on a file-backed H2
        └── resources/
            ├── application.properties            # In-memory H2 so tests never touch demo1db.mv.db
            └── application-e2e.properties        # File-backed H2 with the production URL shape
```

## Key Configuration (`application.properties`)

| Property | Value | Purpose |
| --- | --- | --- |
| `spring.application.name` | `demo1` | Application name |
| `server.port` | `8080` | HTTP port |
| `spring.datasource.url` | `jdbc:h2:file:./demo1db;AUTO_SERVER=TRUE` | H2 database file location |
| `spring.datasource.driver-class-name` | `org.h2.Driver` | H2 JDBC driver |
| `spring.datasource.username` / `password` | `sa` / *(empty)* | Local dev credentials |
| `spring.jpa.database-platform` | `org.hibernate.dialect.H2Dialect` | H2 dialect for Hibernate |
| `spring.jpa.hibernate.ddl-auto` | `update` | Auto-create/update table schema (keeps data between restarts) |
| `spring.jpa.show-sql` | `true` | Logs every generated statement |
| `spring.jpa.properties.hibernate.format_sql` | `true` | Pretty-prints the logged SQL |
| `spring.jpa.open-in-view` | `false` | Lazy associations must load inside the service transaction |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | Stable `LocalDateTime` values across environments |

> **Do not add `DB_CLOSE_ON_EXIT=FALSE` to the H2 URL.** H2 rejects it together
> with `AUTO_SERVER=TRUE` (`Feature not supported`, error 50100, SQLState `HYC00`)
> and the application will not start. `CashierFlowEndToEndTest` asserts the URL shape of its own
> profile, so reintroducing that combination fails the build instead of failing at runtime.
>
> **`spring.jpa.open-in-view=false` matters for this codebase.** Response DTOs
> read lazy associations (`cart.getItems()`, `order.getItems()`, `item.getProduct().getName()`),
> so all mapping must happen inside the `@Transactional` service methods , which is
> exactly what `CartService`/`OrderService`/`ProductService` do. Turning this flag back on
> would hide lazy-loading mistakes instead of surfacing them.

## Running Tests

```bash
# Windows
.\mvnw.cmd test

# macOS / Linux
./mvnw test
```

The suite has **81 tests** across five classes:

| Class | Tests | Scope |
| --- | --- | --- |
| `Demo1ApplicationTests` | 1 | Spring context loads |
| `CartServiceTest` | 21 | Cart accumulation, quantity updates, removal, checkout, and that the cart never touches stock |
| `OrderPaymentFlowTest` | 15 | Payment, rollback, `cancel`/`delete` guards, the `10-7-10-13` regression, and concurrent payments |
| `ApiContractTest` | 35 | HTTP contract: status codes and error messages via MockMvc |
| `CashierFlowEndToEndTest` | 9 | The whole flow over real HTTP against a file-backed database (see below) |

Four of the five classes run against an isolated in-memory H2 database
(`src/test/resources/application.properties`, `jdbc:h2:mem:demo1test` with
`ddl-auto=create-drop`), so they never read or modify the on-disk `demo1db.mv.db`.
`CashierFlowEndToEndTest` deliberately uses its own file-backed database instead , see
[End-to-end HTTP test](#end-to-end-http-test).

Three deliberate design choices in the test suite:

- **The test classes are not annotated with `@Transactional`.** Each service call
  must run in its own transaction so that real commit/rollback behaviour is
  verified; a test-level transaction would join the service transaction and make
  "stock was left untouched" assertions meaningless.
- **The three in-memory `@SpringBootTest` classes share one database** (`demo1test`,
  kept alive by `DB_CLOSE_DELAY=-1`) because Spring reuses the cached application
  context. Rows created in one class are still visible in the others, so tests must
  not rely on global row counts or on `id` values starting at 1, and every cart test
  uses a fresh random `customerId`. `CashierFlowEndToEndTest` runs in its own context
  against its own database, so that sharing does not affect it.
- **`LOCK_TIMEOUT` is raised to 10 s in the test datasource** so that
  `concurrentPayments_cannotOversellTheLastUnit` measures correctness rather than
  H2's patience: the losing threads are *supposed* to block on the winner's row lock.

> The concurrency test catches only `ConflictException` as a legitimate loss, so an
> unexpected failure (for example a lock timeout) fails the test loudly instead of being
> silently counted as a correct rejection.

### End-to-end HTTP test

Most of the suite runs against an in-memory database through MockMvc, so it never touches the
datasource the application actually boots with. That leaves a blind spot: a wrong H2 URL, an
unsupported URL flag, or a schema that cannot be created on disk passes every test and still
stops the application from starting. `CashierFlowEndToEndTest` closes it by starting the real
application on a random port and driving it with real HTTP requests.

It runs automatically as part of `mvn test` , there is nothing extra to launch. Unlike the rest
of the suite it uses its own profile, `src/test/resources/application-e2e.properties`, whose
datasource deliberately copies the production URL shape (`jdbc:h2:file:` with `AUTO_SERVER=TRUE`)
while keeping the database file under `target/`, so `mvn clean` removes it. One of its tests
asserts that shape directly, including that `DB_CLOSE_ON_EXIT=FALSE` is absent , the combination
H2 refuses.

What it covers, and why each case needs a real instance:

- The full cashier flow: fill a cart, check out to `PENDING_PAYMENT`, pay, and observe that
  stock is deducted exactly once, at payment.
- The `10 → 7 → 10 → 13` regression sequence, driven over HTTP.
- `cancel` and `delete` on an unpaid order leaving stock untouched, and a paid order being
  terminal.
- A payment refused because the stock ran out, and a multi-product payment rolled back in full.
- Five simultaneous cashiers competing for the last unit, sent as five concurrent HTTP requests:
  exactly one must win and the others must receive `409`.

> It deliberately does not repeat the exhaustive status-code and message assertions that
> `ApiContractTest` already owns. Keeping one copy of that contract avoids having to update two
> places whenever a response changes; what lives here is only what a real socket and a
> file-backed database can show.
>
> **Why not `TestRestTemplate`?** It lives in `spring-boot-resttestclient` and needs
> `RestTemplateBuilder` from the `spring-boot-restclient` module, which a webmvc-only application
> does not put on the classpath. `RestTestClient` needs nothing beyond `spring-test` and
> `spring-web`, so the test suite stays free of extra dependencies.

## Stock Semantics

`stock` is the **physical count on hand** and the single source of truth for availability.
It is written in exactly two places:

- `POST` / `PUT /api/products` , sets it verbatim. This is a restock or a stock correction, and
  the value you send is authoritative.
- `POST /api/orders/{id}/pay` , subtracts the sold quantities. This is the only sale-driven write.

**Nothing ever adds stock back.** That is what makes the count trustworthy:

- A cart reserves nothing, so it never needs releasing.
- Cancelling or deleting an unpaid order changes no stock, because nothing was deducted.
- A paid order is terminal, so it can never be cancelled or deleted.
- `OrderService.cancel` and `OrderService.deleteById` therefore contain no stock arithmetic at all.

The sequence below used to end at **13**. It now ends at **7**, and the difference is the whole
point of the redesign:

```text
stock 10  →  add 3 to a cart            →  stock 10
          →  checkout                   →  stock 10   (order PENDING_PAYMENT)
          →  PUT /api/products stock=10 →  stock 10   (restock; nothing to invalidate)
          →  pay at the cashier         →  stock 7    (the one and only deduction)
```

The old design kept the order's reservation *inside* `stock`, and then deleting the order added
that reservation back on top of the value the `PUT` had already overwritten , inventing three
units. There is no "restore" step left, so that can no longer happen.

> **Considered and deliberately not adopted:** a separate "reserved quantity" table with
> expiring reservations. It is the right model for a shop that holds stock for a limited time
> while a buyer completes an online payment. Here payment happens at a physical cashier within
> minutes, so reserving would add a whole lifecycle (timeouts, cleanup, reconciliation) to solve
> a problem this flow does not have.

## Known Limitations

1. **`description` length mismatch (known, left in code on purpose).**
   `ProductRequest.description` allows up to 2000 chars (`@Size(max = 2000)`), but
   `Product.description` has no explicit `@Column(length = ...)`, so Hibernate maps
   it to `VARCHAR(255)`. A description longer than 255 chars passes bean validation
   and then fails at flush time (surfacing as a `409` from the
   `DataIntegrityViolationException` safety net, or a `500`). The fix , either
   adding `@Column(length = 2000)` to the entity or lowering the DTO limit to 255 ,
   was consciously deferred, so **keep descriptions under 255 characters** for now.
2. **No authentication.** A cart is addressed by a client-supplied `customerId` in the URL, so
   anyone who knows the id can read and modify that cart, and `GET /api/orders` exposes every
   order. This is an exercise-scale shortcut, not a security boundary.
3. **No refund or return flow.** A `PAID` order is terminal and can never be cancelled or
   deleted, and stock is never given back. A return would need its own explicit operation, with
   a deliberate decision about whether the unit becomes sellable again.
4. **The checkout stock check is advisory.** It is a read, so between checkout and payment
   another buyer can take the stock. That is intentional , the authoritative check happens
   atomically at payment , but it means a `PENDING_PAYMENT` order is not a guarantee.
5. **Carts are never expired.** There is no TTL and no cleanup job, so abandoned carts
   accumulate. The same is true of `PENDING_PAYMENT` orders.
6. **No pagination or sorting** on `GET /api/products` and `GET /api/orders`.
7. **Error bodies are inconsistent** (plain text for service exceptions, Spring
   Boot's default JSON for validation errors) , see [Error Handling](#error-handling).
8. **`Order`, `OrderItem`, `Cart` and `CartItem` have no `@Version`**, so concurrent
   modifications to those rows are not protected by optimistic locking (only `Product` is).
   In practice the order lifecycle is guarded by explicit status checks instead.
9. **No unique constraint on `products.name`**, so duplicate product names are allowed.
10. **No explicit index on `order_items.product_id` or `cart_items.product_id`.** The unique
    constraints are `(order_id, product_id)` and `(cart_id, product_id)`, which cannot serve
    product-only lookups efficiently (used by `countByProductId`).

### Upgrading an existing database

`carts` and `cart_items` are new tables and are created automatically by `ddl-auto=update`.
`orders` is not: its `status` column is a native H2 `ENUM`, and the values changed from
`('CANCELLED','COMPLETED','PENDING')` to `('CANCELLED','PAID','PENDING_PAYMENT')`. `update`
adds the new `paid_at` column but will **not** widen the enum type, so an existing database
will reject the new status values at runtime. Delete `demo1db.mv.db`, or drop `orders` and
`order_items` by hand, before running this version. See
[Migrations](DB_SCHEMA.md#migrations).

## References

- [ENDPOINTS.md](ENDPOINTS.md) , endpoint-by-endpoint reference with request/response shapes.
- [DB_SCHEMA.md](DB_SCHEMA.md) , tables, columns, relationships, stock & money handling.
- [DEPENDENCIES.md](DEPENDENCIES.md) , detailed explanation of each dependency in `pom.xml`.
- [CONCEPTS.md](CONCEPTS.md) , beginner-friendly notes on MVC, JPA/Hibernate, JDBC, validation, transactions.
- [springdoc-openapi](https://springdoc.org/) , OpenAPI documentation for Spring Boot.
- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)
