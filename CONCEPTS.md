# Spring Boot Concepts for Beginners

Beginner-friendly notes on the core concepts used throughout this project. Each section explains **what** something is, **why** it exists, and **where** it appears in this codebase.

---

## MVC (Model-View-Controller)

MVC is a **design pattern** that splits an application into three parts, each with one job:

- **Model** , the data and business rules (what the problem is about). In this project: the `Product`/`Order` entities and the services that operate on them.
- **View** , what the user sees (presentation). In this project: the JSON the API returns , `ProductResponse`, `OrderResponse`.
- **Controller** , the traffic cop. It receives requests from the outside, asks the Model to do work, and hands the result to the View.

In a REST API there is no HTML page, so JSON plays the role of the View.

**Why it exists:** each layer has one responsibility, so code is easier to maintain, test, and swap out (e.g., changing the DB doesn't touch the controller).

### In this project

```text
Client (HTTP request)
    │
    ▼
ProductController / CartController / OrderController   ← Controller: takes the URL/method, calls the service
    │
    ▼
ProductService / CartService / OrderService            ← Model: holds the business logic
    │
    ▼
ProductRepository / CartRepository / OrderRepository   ← talks to the database (via JPA/Hibernate)
    │
    ▼
H2 database
```

**Key idea:** the Controller never touches the database. It delegates to the Service, which delegates to the Repository.

**Related file:** `controller/ProductController.java`, `controller/CartController.java`, `controller/OrderController.java`, `service/ProductService.java`, `service/CartService.java`, `service/OrderService.java`.

### Layered architecture in practice

| Layer | Responsibility | Must NOT do |
| --- | --- | --- |
| `controller/` | HTTP mapping, `@Valid`, status codes | business rules, database access |
| `service/` | business rules, transaction boundaries, entity ↔ DTO mapping | know about `HttpServletRequest` |
| `repository/` | data access, query derivation | business rules |
| `model/` | persistence mapping | contain HTTP or validation logic |
| `dto/` | API input/output shape + validation | contain persistence annotations |

The controller returns `ResponseEntity` and picks the status code; the service returns DTOs and throws
domain exceptions (`BadRequestException`, `ConflictException`, `NotFoundException`). The translation
from exception to HTTP status happens in one central place , see
[Exception handling](#exception-handling-restcontrolleradvice).

---

## JPA (Jakarta Persistence API)

JPA is a **specification** (a set of interfaces and rules) for mapping Java objects to database tables and back , also called *object-relational mapping* (ORM).

> **A note on the name:** it was originally **Java Persistence API** (package `javax.persistence`, Java EE era). After Oracle donated Java EE to the Eclipse Foundation in 2019 it was renamed **Jakarta Persistence API** and the package moved to `jakarta.persistence`. The two are different APIs and cannot be mixed. This project, on Spring Boot 4.1 / Jakarta EE, uses the **Jakarta** version (see `import jakarta.persistence.*` in `model/Product.java`).

**Plain Java before JPA:** you write all the SQL yourself.

```java
// manual JDBC, error-prone and repetitive
ResultSet rs = stmt.executeQuery("SELECT * FROM products WHERE id = 1");
String name = rs.getString("name");
```

**With JPA:** you just annotate a class and call methods.

```java
@Entity                                  // this class maps to a table
public class Product {
    @Id                                 // this field is the primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // auto-increment
    private Long id;
    @Column(nullable = false)
    private String name;
}
```

Hibernate then reads those annotations and does the SQL for you.

**Key idea:** JPA is only the *interface/contract*. Someone else actually implements it , and that someone is Hibernate.

**Related file:** `model/Product.java` (the `@Entity`), `repository/ProductRepository.java`.

### Relationships in this project

```text
Order  1 ──── N  OrderItem  N ──── 1  Product
```

- `Order.items` is `@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)`.
  Deleting an order therefore deletes its line items automatically.
- `OrderItem.order` / `OrderItem.product` are `@ManyToOne(fetch = FetchType.LAZY)`.
  **Lazy** means the referenced row is not loaded until you call the getter , which is why the
  response DTOs must be built inside a transaction (see [`open-in-view`](#open-in-viewfalse)).
- `@UniqueConstraint(name = "uk_order_product", columnNames = {"order_id", "product_id"})` makes a
  product appear at most once per order at the database level.

---

## Hibernate

Hibernate is the **default JPA implementation** in Spring Boot , the "engine" that makes JPA work. When you `save()` a `Product`, Hibernate:

1. Reads the entity annotations.
2. Builds the correct SQL (`INSERT INTO products ...`).
3. Runs it against the database.
4. Returns the result (with generated id) back to you.

Spring Data JPA sits on top: you define a repository interface, and it generates the CRUD implementation at runtime.

```java
public interface ProductRepository extends JpaRepository<Product, Long> { }
// Spring dynamically provides an implementation of findAll(), save(), etc.
```

Hibernate also implements **dirty checking**: inside a transaction, mutating a managed entity is enough
to have the change persisted , no explicit `save()` call is required. This project still calls `save()`
explicitly (`OrderService.pay()`, `ProductService.update()`), which is harmless on an entity that is
already managed: it just makes the intent obvious.

The stock decrement in `OrderService.pay()` is the deliberate exception. It is a **bulk `UPDATE`**
(`ProductRepository.decrementStockIfAvailable`) that bypasses dirty checking and the persistence context
entirely, precisely so that the availability check and the subtraction happen inside one atomic
statement , see [Optimistic locking](#optimistic-locking-version) below.

**Key idea:**

```text
@Repository / JpaRepository  ──►  Spring Data JPA  ──►  Hibernate  ──►  Database
```

**Related dependencies:** `spring-boot-starter-data-jpa` (brings both Spring Data JPA and Hibernate).

---

## Spring Data JPA (repositories, derived queries, fetch plans)

You write an interface; Spring writes the implementation.

**Derived queries** are generated from the method name. `OrderItemRepository` needs only:

```java
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    long countByProductId(Long productId);   // WHERE product_id = ?
}
```

`ProductService.deleteById()` calls it to reject a delete with a readable `409` instead of letting the
foreign key fail at flush time.

**Overriding a CRUD method to change its fetch plan** is done in `OrderRepository`:

```java
@Override
@EntityGraph(attributePaths = { "items", "items.product" })
List<Order> findAll();
```

That is the fix for the N+1 problem described [below](#the-n1-problem-and-entitygraph).

---

## JDBC (Java Database Connectivity)

JDBC is the **lowest-level Java API for talking to any database**. It is database-vendor-neutral: `Connection`, `Statement`, `ResultSet`, executing raw SQL.

**In this project:** JDBC is what actually ships bytes to and from the H2 database file. JPA/Hibernate sit *on top of* JDBC , they translate your entity operations into JDBC calls. The `h2` dependency provides the JDBC driver for H2.

```text
ProductService → JPA/Hibernate → JDBC → H2 driver → demo1db.mv.db
```

**Key idea:** everyone above JDBC (Hibernate, Spring Data) is sugar. At the lowest level, all Java database access is JDBC.

**Related config:**

```properties
spring.datasource.url=jdbc:h2:file:./demo1db;AUTO_SERVER=TRUE   # JDBC URL of our database
spring.datasource.driver-class-name=org.h2.Driver  # which driver to load
```

---

## H2 Dialect

A **dialect** tells Hibernate which SQL variant to generate. Hibernate writes different SQL for different databases , data types, auto-increment syntax, LIMIT handling , and all differ between MySQL, PostgreSQL, Oracle, and H2.

H2 is supported by Hibernate **natively**: `org.hibernate.dialect.H2Dialect` ships inside `hibernate-core`, so no extra dependency is needed (unlike the SQLite dialect, which had to be pulled in from `hibernate-community-dialects`).

**This project does not declare the dialect at all.** Hibernate inspects the JDBC connection and
selects `H2Dialect` on its own, so `spring.jpa.database-platform` is absent from
`application.properties`. Setting it by hand would only add a line that can drift out of date , and
it makes Hibernate log `HHH90000025` ("dialect was explicitly set, so automatic detection is
skipped"), which is a fair warning: you have overridden something that would have been correct.

```properties
# not present in this project , the dialect is detected from the JDBC connection
# spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
```

**Key idea:** without a dialect, Hibernate would generate MySQL/PostgreSQL-style SQL that H2 would reject. Letting it detect the dialect is what makes the same code portable.

**Related dependency:** `spring-boot-starter-data-jpa` (bundles `hibernate-core`, which contains the H2 dialect).

---

## Jakarta Bean Validation

A **specification for validating data** with annotations, instead of writing `if` checks by hand.

Instead of:

```java
if (product.getName() == null || product.getName().isEmpty()) {
    throw new IllegalArgumentException("Name is required");
}
```

you write:

```java
record ProductRequest(@NotBlank String name) { }
```

Spring runs the validation automatically when the controller receives a request, *before* the method body executes:

```java
@PostMapping
public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
    // request is already valid here; Spring rejected it above otherwise
}
```

If validation fails, Spring rejects the request with `400 Bad Request` automatically , you write no
error-handling code. `GlobalExceptionHandler` then renders that failure in the same envelope as every
other error, with the offending fields listed under `fieldErrors`.

**Key idea:** bean validation guards the *shape* of the input (required fields, ranges, lengths). It cannot
express rules that depend on other data , "is there enough stock?", "does this product exist?". Those are
business rules and live in the service layer, which is why both mechanisms exist in this project.

**Related file:** `dto/ProductRequest.java`, `dto/CartItemRequest.java`.

### Records as DTOs

All request/response DTOs are Java `record`s, which gives them for free:

- `private final` fields, a canonical constructor, `equals`/`hashCode`/`toString`.
- No setters , a DTO cannot be mutated by accident after construction.
- Jackson can deserialize them (via the canonical constructor) and serialize them.

Request records carry the validation annotations; response records carry a static `from(entity)` factory
that maps the entity to the API shape. Keeping them separate means the API never leaks table structure
and `@Version`/lazy proxies are never serialized.

---

## `@Transactional` (transactions)

A **transaction** groups database operations into an all-or-nothing unit: either every statement is
committed, or none is.

In Spring you do not manage `commit`/`rollback` yourself , you declare a boundary:

```java
@Transactional
public OrderResponse pay(Long id) { ... }

@Transactional(readOnly = true)
public List<OrderResponse> findAll() { ... }
```

What the annotation does:

- Opens a transaction when the method is entered and commits it when it returns normally.
- Rolls back automatically if a **runtime** exception escapes the method.
- `readOnly = true` tells Hibernate nothing will be written, so it can skip dirty-check bookkeeping.
- If a transactional method calls another one, the inner call joins the existing transaction by default
  (`Propagation.REQUIRED`) , there is one physical transaction, not two.

**Why it matters here:** `OrderService.pay()` throws `ConflictException` when a product runs out of
stock mid-payment. Because the method is transactional, that exception rolls the whole unit back ,
including the decrements that already succeeded for earlier line items , so an order can never be left
half-settled. Combined with the two-phase structure (validate everything, then mutate), a rejected
request leaves the database exactly as it was.

> **Important for tests:** a test annotated with `@Transactional` would make the service join the *test's*
> transaction, which Spring rolls back at the end. Assertions like "stock was left untouched" would then
> pass for the wrong reason. `CartServiceTest`, `OrderPaymentFlowTest` and `CashierFlowEndToEndTest`
> deliberately omit `@Transactional` so each call really commits. That is also what makes the concurrency
> tests meaningful , `concurrentPayments_cannotOversellTheLastUnit` at the service level,
> `concurrentAddItemOfTheSameProduct_neverLosesAnIncrement` for the cart's row lock, and
> `simultaneousCashiers_overRealHttp_onlyOneWinsTheLastUnit` with five genuinely parallel HTTP requests:
> the competing threads have to see each other's committed writes, which they would not inside one shared
> test transaction.

---

## Optimistic locking (`@Version`)

**The problem:** two requests read `stock = 1` at the same time, both decide there is enough, both
write `stock = 0`. One item was sold twice , a classic *lost update*.

**Optimistic locking** detects this instead of preventing it up front (no locks are held, hence
"optimistic"):

```java
@Version
private Long version;
```

Hibernate then:

1. Reads the row together with its `version`.
2. On update, adds the version to the `WHERE` clause: `UPDATE products SET stock = ?, version = ? WHERE id = ? AND version = ?`.
3. If no row matched, someone else got there first , Hibernate throws
   `OptimisticLockingFailureException`.

The application maps that to `409 Conflict` with the message
`The product was modified by another request. Please retry.`

**Key idea:** the client is expected to *retry*, not to be blocked. `Product` has `@Version`;
`Order`, `OrderItem`, `Cart` and `CartItem` do not, so concurrent edits to those rows are not protected
by optimistic locking. Where a transition must not be lost, the code does not lean on optimistic
locking at all: it makes the transition itself conditional. Payment claims the order with
`UPDATE ... WHERE id = ? AND status = 'PENDING_PAYMENT'` rather than checking the status in Java first.
Cancelling uses the same conditional `UPDATE`, and deleting takes the order's row lock instead, because
it has to read the order before cascading to its items. The cart takes the third route , a row lock held
across the whole read-modify-write , see [Pessimistic locking](#pessimistic-locking-lock) below.

> **Here, `@Version` is not the main defence against overselling.** The payment path uses a single
> conditional `UPDATE` (`... WHERE id = ? AND stock >= ?`), which *prevents* the lost update rather than
> detecting it afterwards , see [Stock semantics](ENDPOINTS.md#stock-semantics). The same trick guards the
> status: claiming an order for payment is one conditional
> `UPDATE ... WHERE id = ? AND status = 'PENDING_PAYMENT'`, so two cashiers cannot both settle the same
> order. Optimistic locking still protects the plain read-modify-write in `PUT /api/products/{id}`, and
> the payment's decrement bumps `version` by hand, so a concurrent `PUT` fails its version check instead
> of silently overwriting a settled sale.

---

## Pessimistic locking (`@Lock`)

**The same lost-update problem, solved by blocking rather than by detecting.** Optimistic locking lets
both writers proceed and rejects the loser afterwards. Pessimistic locking stops the second writer from
reading at all until the first has committed:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Cart c where c.customerId = :customerId")
Optional<Cart> findByCustomerIdForUpdate(@Param("customerId") String customerId);
```

Hibernate renders that as `SELECT ... FOR UPDATE`: the database takes a write lock on the matching row,
and any other transaction that asks for the same row **waits** (up to the datasource's `LOCK_TIMEOUT`)
instead of being handed a stale copy.

**Why the cart needs it.** `addItem` is a read-modify-write on `cart_items.quantity`: read the line, add
the requested quantity, write it back. Two requests that read `2` at the same time both write `3`, and
one increment is gone , with *both* calls reporting success. A conditional `UPDATE` cannot help here
either, because the value being written depends on the value being read. Taking the cart's row lock
first makes the pair serialise, so the second request reads what the first committed.

**Which primitive is used where, and why:**

| Situation | Primitive | Why not the other one |
| --- | --- | --- |
| Payment deducting stock | conditional `UPDATE ... WHERE stock >= ?` | the new value is a pure function of the row (`stock - qty`), so the database can decide without a lock |
| Payment / cancel claiming a status | conditional `UPDATE ... WHERE status = 'PENDING_PAYMENT'` | same , a fixed target value, nothing to read first |
| `DELETE /api/orders/{id}` | the order's row lock | it must *read* the order before cascading to `order_items` |
| Every cart mutation | the cart's row lock | the quantity written depends on the quantity read |

> **A row lock is not free.** It serialises writers on that one cart, so a hot cart becomes a
> bottleneck, and holding the lock across slow work makes other requests wait. Here the locked region is
> a handful of statements, which is why the lock was preferred over retrying on conflict: `addItem`
> *accumulates*, so an optimistic retry would have to re-read and re-apply, and a rejected add is
> indistinguishable from a successful one to the caller.
>
> **The cold-start insert is the one race left.** Locking a row only helps once the row exists. Two
> *first* `addItem` calls for the same brand-new `customerId` both find nothing and both insert; the
> unique constraint on `carts.customer_id` then rejects the second with `409 Conflict` rather than
> silently losing it. That is a loud, retryable failure , unlike the lost update it replaced.

**Related files:** `repository/CartRepository.java` (`findByCustomerIdForUpdate`),
`service/CartService.java` (every mutating method locks the cart first), `repository/OrderRepository.java`
(`findByIdForUpdate`).

---

## BigDecimal for money

**Never use `double` for money.** Binary floating point cannot represent decimal fractions exactly:
`0.1 + 0.2 != 0.3`. On a price that is a rounding error; multiplied over many orders it becomes wrong
totals and failed reconciliations.

This project stores money as `BigDecimal` mapped to a fixed-precision column:

```java
@Column(nullable = false, precision = 19, scale = 2)
private BigDecimal price;
```

- `scale = 2` , two decimal places.
- `precision = 19` , up to 19 significant digits in total.
- `ProductRequest.price` adds `@Digits(integer = 17, fraction = 2)` so a value the column cannot hold is
  rejected with `400` instead of being silently rounded.
- Comparisons use `compareTo(...) == 0`, not `equals(...)`, because `equals` also compares the scale
  (`19.9` and `19.90` are `equals`-unequal but numerically equal).

**Related file:** `model/Product.java`, `model/OrderItem.java`, `dto/ProductRequest.java`.

---

## The N+1 problem and `@EntityGraph`

A **N+1** happens when you load a list and then, for each element, run one more query.

Building an order list requires, per order, its items, and per item the product name. Naively that is:

```text
1 query  → SELECT * FROM orders
+ N queries → one per order, to load its items
+ M queries → one per item, to load its product
```

Measured in this project with 3 orders / 6 items: **7 queries**. With `@EntityGraph` telling Hibernate to
fetch the associations up front, it becomes **1 query**:

```java
@Override
@EntityGraph(attributePaths = { "items", "items.product" })
List<Order> findAll();
```

**Key idea:** the symptom is not a wrong result but a slow one that gets slower as the table grows. It is
also easy to miss while `spring.jpa.open-in-view` is enabled, because lazy loads then happen silently
during JSON serialization.

---

## `open-in-view=false`

By default Spring Boot keeps the Hibernate `Session` open for the whole HTTP request, so a lazy
association can still be loaded while Jackson serializes the response. That is convenient and hides
design problems: a missing fetch plan silently turns into extra queries in the view layer.

This project disables it:

```properties
spring.jpa.open-in-view=false
```

**Consequence:** lazy associations must be loaded **inside** the transactional service method. That is
why `OrderResponse.from(order)` (which reads `order.getItems()` and `item.getProduct().getName()`) and
`CartResponse.from(cart)` (which reads `cart.getItems()` and `item.getProduct().getPrice()`) are always
called from inside a `@Transactional` service method, and why `OrderRepository` and `CartRepository`
declare `@EntityGraph` , the delete and checkout paths need the items as well.

With the flag off, a missing fetch plan fails loudly (`LazyInitializationException`) instead of
quietly degrading performance.

---

## Exception handling (`@RestControllerAdvice`)

Without a central handler, every controller would need `try`/`catch` blocks and would have to decide
which HTTP status each exception maps to. Spring lets you declare that once:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(BadRequestException ex,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }
}
```

The service layer stays free of HTTP concerns: it throws plain domain exceptions, and the advice
translates them.

| Exception | HTTP status |
| --- | --- |
| `BadRequestException` | `400 Bad Request` |
| `NotFoundException` | `404 Not Found` |
| `ConflictException` | `409 Conflict` |
| `OptimisticLockingFailureException` | `409 Conflict` |
| `DataIntegrityViolationException` | `409 Conflict` (safety net) |
| `MethodArgumentTypeMismatchException` | `400 Bad Request` |

The three custom exceptions are tiny `RuntimeException`s that differ only in meaning: the request
itself is wrong (`BadRequestException`), the thing being addressed does not exist
(`NotFoundException`), or the request clashes with the current state of the data
(`ConflictException` , e.g. deleting a product that orders still reference). Splitting them is what
lets one advice method map each to the right status without ever inspecting a message.

### One envelope, and why `null` is not a control-flow value

Every handler returns the same `ApiErrorResponse` body, so a client parses one shape no matter what
failed. Two consequences are worth calling out:

- **"Not found" is thrown, not returned.** It is tempting to write
  `ResponseEntity.ofNullable(service.findById(id))`, which produces a `404` with an *empty* body.
  Instead the service throws `NotFoundException` and the controller simply returns the DTO, so a
  missing resource carries a message like `Order 999 does not exist`. Returning `null` also forces
  every caller to remember a null check; an exception cannot be forgotten.
- **Framework errors are folded in.** `NoResourceFoundException`, `NoHandlerFoundException` and
  `HttpRequestMethodNotSupportedException` all implement `ErrorResponse`, but that is an interface
  describing a *response*, not a throwable, so it cannot be an `@ExceptionHandler` parameter. The
  catch-all `Exception` handler checks `instanceof ErrorResponse` and reuses Spring's status and
  detail, which is why a typo in a URL looks like every other error instead of Boot's default page.
- **Not every framework error is an `ErrorResponse`.** `MethodArgumentTypeMismatchException`, raised
  when a path variable cannot be parsed as its declared type (`GET /api/products/abc`), extends
  `TypeMismatchException` and implements no such interface, so the catch-all above could not rescue it
  , it fell through to the generic `500` and logged a stack trace. It needs , and now has , its own
  handler, which answers `400` naming the parameter and the offending value.

> **Two things worth knowing.** (1) `DataIntegrityViolationException` is a *safety net* , if it fires,
> the message is generic because the real cause is a database constraint. (2) Bean Validation failures
> now reach the advice , as `MethodArgumentNotValidException` for a request body and as
> `HandlerMethodValidationException` for path variables , and come back with `fieldErrors` filled in
> instead of as Spring Boot's default error document.

---

## JAR (Java Archive)

A JAR is a **single compressed file** that bundles compiled `.class` files, resources, and metadata , a way to package a Java application for distribution.

Spring Boot's Maven plugin creates a special *fat/executable JAR*: your code **plus all dependencies bundled inside**. That's why one command runs the whole app:

```bash
java -jar target/demo1-0.0.1-SNAPSHOT.jar
```

**Why it matters:** no installed Tomcat, no Maven, no classpath juggling , everything needed is inside the file. Deploying is just copying and running one file.

---

## How it all connects

A single request through the whole stack:

```text
HTTP GET /api/products/1
        │
        ▼
MVC Controller        ProductController , routes the request
        │
        ▼
Service               ProductService , finds the product (business logic)
        │
        ▼
Spring Data JPA       ProductRepository.findById(1L) , generated implementation
        │
        ▼
Hibernate             builds SELECT ... FROM products WHERE id = 1
        │
        ▼
JDBC                  sends the SQL via the H2 driver
        │
        ▼
demo1db.mv.db         returns the row
        │
        ▼
Jackson               serializes ProductResponse to JSON, sent back to client
```

And the write path, including the error branch:

```text
HTTP POST /api/carts/{customerId}/checkout
        │
        ▼
CartController         path variable only, no request body
        │
        ▼
CartService.checkout() @Transactional
        ├── no cart for this customer? → NotFoundException (404)
        ├── pass 1: cart empty? every product still in stock?
        │           → on failure: throw BadRequestException (400)
        ├── pass 2: snapshot each price, build the order, save it,
        │           then empty the cart
        │           → stock is NOT touched here
        │
        ▼
Hibernate              INSERT orders + INSERT order_items
```

And the one path that does write stock:

```text
HTTP POST /api/orders/{id}/pay
        │
        ▼
OrderController        path variable only, no request body
        │
        ▼
OrderService.pay()     @Transactional
        ├── claim: UPDATE orders SET status = PAID, paid_at = now
        │          WHERE id = ? AND status = PENDING_PAYMENT
        │          → 0 rows: not awaiting payment, or another cashier claimed it
        │            first → ConflictException (409), and no stock is touched
        ├── reload the order (now PAID) for the response
        ├── sort the items by productId (fixed lock order, no deadlock)
        ├── per item: UPDATE products SET stock = stock - qty, version = version + 1
        │             WHERE id = ? AND stock >= qty
        │             → 0 rows updated: ConflictException (409) and the whole
        │               transaction rolls back, so nothing is half-settled
        └── the claim rolls back with it: the order stays PENDING_PAYMENT
        │
        ▼
Hibernate              a conditional UPDATE on orders, then one per product
        │
        ▼
GlobalExceptionHandler maps the exception to 400 / 409 and writes the body
```

- **JPA** , the spec (annotations + rules).
- **Hibernate** , the implementation of that spec.
- **Spring Data JPA** , the convenience layer that auto-generates repositories and derived queries.
- **JDBC** , the low-level transport to the database.
- **H2 Dialect** , lets Hibernate speak H2's dialect of SQL.
- **Bean Validation** , automatic request validation.
- **DTO + records** , the API shape, decoupled from the entities.
- **`@Transactional`** , all-or-nothing units of work.
- **`@Version`** , optimistic locking against lost updates.
- **`BigDecimal`** , exact money arithmetic.
- **`@EntityGraph`** , one query instead of N+1.
- **`@RestControllerAdvice`** , one place that maps every exception to the same error body.
- **JAR** , how everything ships as one runnable file.
- **MVC** , the pattern that keeps Controller, Model (Service/Entity), and View (JSON) separated.

## Further Reading

- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/) , repositories and query methods.
- [Hibernate ORM Documentation](https://hibernate.org/orm/documentation/) , mapping and semantics.
- [Jakarta Bean Validation](https://beanvalidation.org/) , constraint annotations.
- [Spring Framework , Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [Hibernate User Guide , Locking](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking)
