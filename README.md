# Demo1 , Product & Order REST API (Spring Boot)

A simple REST API for **Product** CRUD and **Order** management, built with Spring Boot + Spring Data JPA + Hibernate on a file-based **H2** database.

## Features

- Full CRUD for products (`GET`, `GET by id`, `POST`, `PUT`, `DELETE`).
- Create/read/delete orders with line items; unit prices are **snapshotted at order time** and a `totalPrice` is computed per order.
- Orders start with status `PENDING` (enum also defines `COMPLETED`, `CANCELLED` , see [Known limitations](#known-limitations)).
- **Stock control:** creating an order decrements `products.stock`, deleting it restores the reserved quantity. Insufficient stock, an unknown product, or a duplicate `productId` is rejected with `400` **before** any stock is touched.
- **Optimistic locking** (`@Version` on `Product`): two orders racing for the same stock cannot both succeed , the loser gets `409 Conflict`.
- **Two-phase order creation:** the whole request is validated first, then stock is mutated , an invalid request never leaves stock partially decremented.
- **N+1-free order listing:** `OrderRepository` overrides `findAll()`/`findById()` with `@EntityGraph` to fetch items and their products in one query.
- Layered architecture: `Controller → Service → Repository`, with **DTOs** separated from the entity (the API does not expose the table structure).
- Request-body validation via Jakarta Bean Validation.
- Central exception-to-HTTP mapping in `@RestControllerAdvice` (`GlobalExceptionHandler`).
- Decimal money handling: `BigDecimal` / `NUMERIC(19,2)`, never `double`.
- Automatic API documentation via **Swagger UI / OpenAPI 3**.
- File-based H2 database (no separate DB server) , `demo1db.mv.db`.
- 27 automated tests: context smoke test, service-level stock logic, and an HTTP contract suite.

## Tech Stack

| Technology | Version |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring Data JPA (Hibernate) | via `spring-boot-starter-data-jpa` |
| Database | H2 (file-based, version managed by the Spring Boot parent) |
| API docs | springdoc-openapi 3.1.0 |
| Testing | JUnit 5 + MockMvc (`spring-boot-starter-webmvc-test`) |
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

## API Endpoints

### Products

All endpoints are prefixed with `/api/products`

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/products` | Get all products |
| `GET` | `/api/products/{id}` | Get product by ID (404 if not found) |
| `POST` | `/api/products` | Create a new product (201) |
| `PUT` | `/api/products/{id}` | Update product by ID (404 if not found) |
| `DELETE` | `/api/products/{id}` | Delete product by ID (204 / 404 / 409) |

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

#### Update a product

```bash
curl -X PUT http://localhost:8080/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Laptop Pro","description":"Updated","price":17000000,"stock":8}'
```

#### Delete a product

```bash
curl -X DELETE http://localhost:8080/api/products/1
```

> **Deleting a referenced product** returns `409 Conflict` with the message
> `Product <id> cannot be deleted because it is referenced by existing orders`:
> a product that existing orders still reference cannot be removed, because
> `order_items.product_id` is a foreign key.
>
> **Validation:** `name` is required (max 255 chars), `price` is required, must not be negative and accepts at most 2 decimals, `stock` is required and must not be negative. `description` is optional and the DTO allows up to 2000 chars , but see [Known limitations](#known-limitations) for the column-size mismatch. On validation failure the API returns `400 Bad Request`. Concurrent updates to the same product return `409 Conflict`.

### Orders

All endpoints are prefixed with `/api/orders`

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/orders` | Get all orders with their line items and totals |
| `GET` | `/api/orders/{id}` | Get order by ID (404 if not found) |
| `POST` | `/api/orders` | Create a new order (201) |
| `DELETE` | `/api/orders/{id}` | Delete order by ID (204 / 404) |

#### Create an order

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}'
```

Response `201 Created`:

```json
{
  "id": 1,
  "orderDate": "2026-09-23T08:49:45",
  "status": "PENDING",
  "items": [
    { "productId": 1, "productName": "Laptop", "quantity": 2, "unitPrice": 15000000.00 }
  ],
  "totalPrice": 30000000.00
}
```

Each item snapshots the product's current price, and the response includes a
`totalPrice` computed from those snapshots.

> **Validation:** `items` must not be empty; each item requires a valid
> `productId` and a `quantity >= 1`. A missing product, a **duplicate
> `productId`**, or **insufficient stock** returns `400 Bad Request`. Product
> stock is automatically decremented when the order is created, and restored
> again when the order is deleted.
>
> **Concurrency:** products use optimistic locking (`@Version`). If two orders
> race for the same stock, the losing request returns `409 Conflict` and should
> be retried.

## Error Handling

All service-level exceptions are translated by `GlobalExceptionHandler`
(`@RestControllerAdvice`). The bodies below are **plain text**, not JSON:

| Exception | Status | Response body |
| --- | --- | --- |
| `IllegalArgumentException` | `400 Bad Request` | The exception message (e.g. `Insufficient stock for product: Laptop`) |
| `ConflictException` | `409 Conflict` | The exception message (e.g. `Product 3 cannot be deleted because it is referenced by existing orders`) |
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
    │   │   │   ├── ProductController.java       # REST endpoints & OpenAPI docs
    │   │   │   └── OrderController.java         # Order endpoints & OpenAPI docs
    │   │   ├── service/
    │   │   │   ├── ProductService.java          # Product business logic, entity ↔ DTO mapping
    │   │   │   └── OrderService.java            # Order logic: two-phase validation, price snapshot, stock
    │   │   ├── repository/
    │   │   │   ├── ProductRepository.java       # Spring Data JPA repository
    │   │   │   ├── OrderRepository.java         # findAll/findById with @EntityGraph (avoids N+1)
    │   │   │   └── OrderItemRepository.java     # countByProductId , guards product deletion
    │   │   ├── model/
    │   │   │   ├── Product.java                 # JPA entity (table `products`, @Version optimistic lock)
    │   │   │   ├── Order.java                   # JPA entity (table `orders`, 1-:N → order_items)
    │   │   │   └── OrderItem.java               # JPA entity (table `order_items`, N-:1 → Product, uk_order_product)
    │   │   ├── exception/
    │   │   │   ├── ConflictException.java       # Runtime exception → 409 Conflict
    │   │   │   └── GlobalExceptionHandler.java  # @RestControllerAdvice: service exceptions → HTTP
    │   │   └── dto/
    │   │       ├── ProductRequest.java          # Input payload + validation
    │   │       ├── ProductResponse.java         # Response payload
    │   │       ├── OrderRequest.java            # Order input payload + validation
    │   │       ├── OrderItemRequest.java        # Line-item input payload + validation
    │   │       ├── OrderResponse.java           # Order response (items + computed totalPrice)
    │   │       └── OrderItemResponse.java       # Line-item response
    │   └── resources/
    │       └── application.properties            # Server & DB configuration
    └── test/
        ├── java/com/example/demo1/
        │   ├── Demo1ApplicationTests.java        # Context smoke test
        │   ├── controller/
        │   │   └── ApiContractTest.java          # 16 MockMvc tests locking status codes & messages
        │   └── service/
        │       └── OrderServiceStockTest.java    # 10 tests: stock validation, decrement & restore
        └── resources/
            └── application.properties            # In-memory H2 so tests never touch demo1db.mv.db
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
> and the application will not start. This is not covered by the test suite,
> because tests use a different (in-memory) datasource.
>
> **`spring.jpa.open-in-view=false` matters for this codebase.** Response DTOs
> read lazy associations (`order.getItems()`, `item.getProduct().getName()`), so
> all mapping must happen inside the `@Transactional` service methods , which is
> exactly what `OrderService`/`ProductService` do. Turning this flag back on
> would hide lazy-loading mistakes instead of surfacing them.

## Running Tests

```bash
# Windows
.\mvnw.cmd test

# macOS / Linux
./mvnw test
```

The suite has **27 tests** across three classes:

| Class | Tests | Scope |
| --- | --- | --- |
| `Demo1ApplicationTests` | 1 | Spring context loads |
| `OrderServiceStockTest` | 10 | Stock validation, decrement/restore, `null` stock, decimal price round-trip |
| `ApiContractTest` | 16 | HTTP contract: status codes and error messages via MockMvc |

Tests run against an isolated in-memory H2 database
(`src/test/resources/application.properties`, `jdbc:h2:mem:demo1test` with
`ddl-auto=create-drop`), so they never read or modify the on-disk `demo1db.mv.db`.

Two deliberate design choices in the test suite:

- **The test classes are not annotated with `@Transactional`.** Each service call
  must run in its own transaction so that real commit/rollback behaviour is
  verified; a test-level transaction would join the service transaction and make
  "stock was left untouched" assertions meaningless.
- **Both `@SpringBootTest` classes share one in-memory database** (`demo1test`,
  kept alive by `DB_CLOSE_DELAY=-1`) because Spring reuses the cached application
  context. Rows created in one class are still visible in the other, so tests must
  not rely on global row counts or on `id` values starting at 1.

## Stock Semantics

`stock` means **available** stock, not total stock on hand. This is the decided
model for the project:

- Creating an order subtracts the ordered quantity from `products.stock`.
- Deleting an order adds the consumed quantity back.
- `PUT /api/products/{id}` sets `stock` **authoritatively** , the value you send
  becomes the new available stock, regardless of how many orders are open.

Because the last two rules can interact, stock may legitimately end up above the
original total. Accepted sequence:

```text
stock 10  →  POST /api/orders (quantity 3)  →  stock 7
          →  PUT /api/products (stock=10)   →  stock 10
          →  DELETE /api/orders/{id}        →  stock 13
```

This is **expected behaviour, not a bug**: the order's reservation was still
outstanding when the PUT overwrote the available stock, so restoring it adds back
a quantity that had already been written off. Clients that care about exact totals
must not overwrite `stock` while orders are open.

> **Considered and deliberately not adopted:** persisting a separate total stock
> and deriving available stock from open orders. That would remove the interaction
> above, but it changes the entity model, the schema and every service that reads
> `stock`, so the simpler "available stock" definition was chosen instead.

## Known Limitations

1. **`description` length mismatch (known, left in code on purpose).**
   `ProductRequest.description` allows up to 2000 chars (`@Size(max = 2000)`), but
   `Product.description` has no explicit `@Column(length = ...)`, so Hibernate maps
   it to `VARCHAR(255)`. A description longer than 255 chars passes bean validation
   and then fails at flush time (surfacing as a `409` from the
   `DataIntegrityViolationException` safety net, or a `500`). The fix , either
   adding `@Column(length = 2000)` to the entity or lowering the DTO limit to 255 ,
   was consciously deferred, so **keep descriptions under 255 characters** for now.
2. **Order status is never updated.** The enum defines `PENDING`, `COMPLETED`,
   `CANCELLED`, but no endpoint changes the status, so every order stays `PENDING`.
3. **No pagination or sorting** on `GET /api/products` and `GET /api/orders`.
4. **Error bodies are inconsistent** (plain text for service exceptions, Spring
   Boot's default JSON for validation errors) , see [Error Handling](#error-handling).
5. **`Order` and `OrderItem` have no `@Version`**, so concurrent updates to an
   order are not protected by optimistic locking (only `Product` is).
6. **No unique constraint on `products.name`**, so duplicate product names are allowed.
7. **No explicit index on `order_items.product_id`.** The `uk_order_product`
   index is `(order_id, product_id)`, which cannot serve product-only lookups
   efficiently (used by `countByProductId`).

## References

- [ENDPOINTS.md](ENDPOINTS.md) , endpoint-by-endpoint reference with request/response shapes.
- [DB_SCHEMA.md](DB_SCHEMA.md) , tables, columns, relationships, stock & money handling.
- [DEPENDENCIES.md](DEPENDENCIES.md) , detailed explanation of each dependency in `pom.xml`.
- [CONCEPTS.md](CONCEPTS.md) , beginner-friendly notes on MVC, JPA/Hibernate, JDBC, validation, transactions.
- [springdoc-openapi](https://springdoc.org/) , OpenAPI documentation for Spring Boot.
- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)
