# Spring Boot Concepts for Beginners

Beginner-friendly notes on the core concepts used throughout this project. Each section explains **what** something is, **why** it exists, and **where** it appears in this codebase.

---

## MVC (Model-View-Controller)

MVC is a **design pattern** that splits an application into three parts, each with one job:

- **Model** , the data and business rules (what the problem is about). In this project: `Product` entity and `ProductService`.
- **View** , what the user sees (presentation). In this project: the JSON the API returns , `ProductResponse`.
- **Controller** , the traffic cop. It receives requests from the outside, asks the Model to do work, and hands the result to the View.

In a REST API there is no HTML page, so JSON plays the role of the View.

**Why it exists:** each layer has one responsibility, so code is easier to maintain, test, and swap out (e.g., changing the DB doesn't touch the controller).

### In this project

```
Client (HTTP request)
    │
    ▼
ProductController   ← Controller: takes the URL/method, calls the service
    │
    ▼
ProductService      ← Model: holds the business logic
    │
    ▼
ProductRepository   ← talks to the database (via JPA/Hibernate)
    │
    ▼
SQLite database
```

**Key idea:** the Controller never touches the database. It delegates to the Service, which delegates to the Repository.

**Related file:** `controller/ProductController.java`, `service/ProductService.java`.

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

**Key idea:**

```
@Repository / JpaRepository  ──►  Spring Data JPA  ──►  Hibernate  ──►  Database
```

**Related dependencies:** `spring-boot-starter-data-jpa` (brings both Spring Data JPA and Hibernate).

---

## JDBC (Java Database Connectivity)

JDBC is the **lowest-level Java API for talking to any database**. It is database-vendor-neutral: `Connection`, `Statement`, `ResultSet`, executing raw SQL.

**In this project:** JDBC is what actually ships bytes to and from the SQLite file. JPA/Hibernate sit *on top of* JDBC , they translate your entity operations into JDBC calls. The `sqlite-jdbc` dependency provides the JDBC driver for SQLite.

```
ProductService → JPA/Hibernate → JDBC → sqlite-jdbc driver → demo1.db
```

**Key idea:** everyone above JDBC (Hibernate, Spring Data) is sugar. At the lowest level, all Java database access is JDBC.

**Related config:**

```properties
spring.datasource.url=jdbc:sqlite:demo1.db   # JDBC URL of our database
spring.datasource.driver-class-name=org.sqlite.JDBC  # which driver to load
```

---

## SQLite Dialect

A **dialect** tells Hibernate which SQL variant to generate. Hibernate writes different SQL for different databases , data types, auto-increment syntax, LIMIT handling , and all differ between MySQL, PostgreSQL, Oracle, and SQLite.

SQLite is a lightweight embedded database, and Hibernate does not support it natively. The **community dialect** fills that gap: it teaches Hibernate the SQLite specifics (e.g., how auto-increment works in SQLite, integer vs. real type mapping).

```properties
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect
```

**Key idea:** without a dialect, Hibernate would generate MySQL/PostgreSQL-style SQL that SQLite would reject.

**Related dependency:** `hibernate-community-dialects`.

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

**Related file:** `dto/ProductRequest.java`.

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

```
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
JDBC                  sends the SQL via the SQLite driver
        │
        ▼
demo1.db              returns the row
        │
        ▼
Jackson               serializes ProductResponse to JSON, sent back to client
```

- **JPA** , the spec (annotations + rules).
- **Hibernate** , the implementation of that spec.
- **Spring Data JPA** , the convenience layer that auto-generates repositories.
- **JDBC** , the low-level transport to the database.
- **SQLite Dialect** , lets Hibernate speak SQLite's dialect of SQL.
- **Bean Validation** , automatic request validation.
- **JAR** , how everything ships as one runnable file.
- **MVC** , the pattern that keeps Controller, Model (Service/Entity), and View (JSON) separated.

## Further Reading

- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/) , repositories and query methods.
- [Hibernate ORM Documentation](https://hibernate.org/orm/documentation/) , mapping and semantics.
- [Jakarta Bean Validation](https://beanvalidation.org/) , constraint annotations.