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
ProductController / OrderController   ← Controller: takes the URL/method, calls the service
    │
    ▼
ProductService / OrderService         ← Model: holds the business logic
    │
    ▼
ProductRepository / OrderRepository   ← talks to the database (via JPA/Hibernate)
    │
    ▼
H2 database
```

**Key idea:** the Controller never touches the database. It delegates to the Service, which delegates to the Repository.

**Related file:** `controller/ProductController.java`, `controller/OrderController.java`, `service/ProductService.java`, `service/OrderService.java`.

### Layered architecture in practice

| Layer | Responsibility | Must NOT do |
| --- | --- | --- |
| `controller/` | HTTP mapping, `@Valid`, status codes | business rules, database access |
| `service/` | business rules, transaction boundaries, entity ↔ DTO mapping | know about `HttpServletRequest` |
| `repository/` | data access, query derivation | business rules |
| `model/` | persistence mapping | contain HTTP or validation logic |
| `dto/` | API input/output shape + validation | contain persistence annotations |

The controller returns `ResponseEntity` and picks the status code; the service returns DTOs and throws
domain exceptions (`IllegalArgumentException`, `ConflictException`). The translation from exception to
HTTP status happens in one central place , see [Exception handling](#exception-handling-restcontrolleradvice).

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
to have the change persisted , there is no explicit `save()` call needed. `OrderService.create()` relies
on this when it does `product.setStock(...)` and then saves only the `Order`.

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

```properties
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
```

**Key idea:** without a dialect, Hibernate would generate MySQL/PostgreSQL-style SQL that H2 would reject.

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

If validation fails, Spring returns `400 Bad Request` automatically , you write no error-handling code.

**Key idea:** bean validation guards the *shape* of the input (required fields, ranges, lengths). It cannot
express rules that depend on other data , "is there enough stock?", "does this product exist?". Those are
business rules and live in the service layer, which is why both mechanisms exist in this project.

**Related file:** `dto/ProductRequest.java`, `dto/OrderRequest.java`, `dto/OrderItemRequest.java`.

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
public OrderResponse create(OrderRequest request) { ... }

@Transactional(readOnly = true)
public List<OrderResponse> findAll() { ... }
```

What the annotation does:

- Opens a transaction when the method is entered and commits it when it returns normally.
- Rolls back automatically if a **runtime** exception escapes the method.
- `readOnly = true` tells Hibernate nothing will be written, so it can skip dirty-check bookkeeping.
- If a transactional method calls another one, the inner call joins the existing transaction by default
  (`Propagation.REQUIRED`) , there is one physical transaction, not two.

**Why it matters here:** `OrderService.create()` throws `IllegalArgumentException` when stock is
insufficient. Because the method is transactional, that exception rolls the whole unit back, so no
partially decremented stock can survive. Combined with the two-phase structure (validate everything,
then mutate), a rejected order leaves the database exactly as it was.

> **Important for tests:** a test annotated with `@Transactional` would make the service join the *test's*
> transaction, which Spring rolls back at the end. Assertions like "stock was left untouched" would then
> pass for the wrong reason. `OrderServiceStockTest` deliberately omits `@Transactional` so each service
> call really commits.

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
`Order`/`OrderItem` do not, so concurrent edits to an order are not protected.

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
why `OrderResponse.from(order)` (which reads `order.getItems()` and `item.getProduct().getName()`) is
always called from inside a `@Transactional` service method, and why `OrderRepository` declares
`@EntityGraph` on `findById` too , the delete path needs the items as well.

With the flag off, a missing fetch plan fails loudly (`LazyInitializationException`) instead of
quietly degrading performance.

---

## Exception handling (`@RestControllerAdvice`)

Without a central handler, every controller would need `try`/`catch` blocks and would have to decide
which HTTP status each exception maps to. Spring lets you declare that once:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
```

The service layer stays free of HTTP concerns: it throws plain domain exceptions, and the advice
translates them.

| Exception | HTTP status |
| --- | --- |
| `IllegalArgumentException` | `400 Bad Request` |
| `ConflictException` | `409 Conflict` |
| `OptimisticLockingFailureException` | `409 Conflict` |
| `DataIntegrityViolationException` | `409 Conflict` (safety net) |

`ConflictException` is a tiny custom `RuntimeException`: the request is well-formed, but it clashes with
the current state of the data (deleting a product that orders still reference).

> **Two gaps worth knowing.** (1) `DataIntegrityViolationException` is a *safety net* , if it fires, the
> message is generic because the real cause is a database constraint. (2) Bean Validation failures are
> **not** routed through this advice (`MethodArgumentNotValidException` is left to Spring Boot's default
> handling), so validation errors come back as Spring's error JSON while business errors come back as
> plain text.

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
HTTP POST /api/orders
        │
        ▼
OrderController        @Valid OrderRequest , bean validation runs first
        │
        ▼
OrderService.create()  @Transactional
        ├── pass 1: duplicate productId? product exists? enough stock?
        │           → on failure: throw IllegalArgumentException
        ├── pass 2: decrement stock, attach items, save the order
        │
        ▼
Hibernate              INSERT/UPDATE + optimistic-locking version check
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
- **`@RestControllerAdvice`** , one place that maps exceptions to HTTP.
- **JAR** , how everything ships as one runnable file.
- **MVC** , the pattern that keeps Controller, Model (Service/Entity), and View (JSON) separated.

## Further Reading

- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/) , repositories and query methods.
- [Hibernate ORM Documentation](https://hibernate.org/orm/documentation/) , mapping and semantics.
- [Jakarta Bean Validation](https://beanvalidation.org/) , constraint annotations.
- [Spring Framework , Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [Hibernate User Guide , Locking](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#locking)
