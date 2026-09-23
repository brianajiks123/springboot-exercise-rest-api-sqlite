# Dependency Map

This document describes every dependency declared in `pom.xml` , what it does and where it is used in the source code.

> All listed dependencies are currently **in use** , there are no unused dependencies. The previously unused `spring-boot-starter-actuator` has been removed, and the SQLite stack was replaced by H2 (see [Removed dependencies](#removed-dependencies)).

| Dependency | Version | Scope | Purpose | Used in |
| --- | --- | --- | --- | --- |
| `spring-boot-starter-webmvc` | managed by parent | compile | Web / REST MVC layer: `@RestController`, `@RequestMapping`, HTTP mapping, JSON serialization via Jackson, embedded Tomcat. | `controller/ProductController.java`, `controller/OrderController.java`, all `/api/*` endpoints. |
| `spring-boot-starter-data-jpa` | managed by parent | compile | Data / ORM layer (Spring Data JPA + Hibernate): `JpaRepository`, `@Entity`, table-to-object mapping, SQL generation, transaction management. | `repository/*Repository.java`, `model/*.java`, `service/*Service.java`. |
| `spring-boot-starter-validation` | managed by parent | compile | Bean validation (Jakarta Bean Validation). Enables `@Valid` and constraint annotations on DTOs. | `dto/*Request.java` (`@NotBlank`, `@Size`, `@NotNull`, `@Min`, `@DecimalMin`, `@Digits`, `@NotEmpty`), `controller/*Controller.java` (`@Valid @RequestBody`). |
| `springdoc-openapi-starter-webmvc-ui` | `3.1.0` (explicit) | compile | Automatic API documentation: generates the OpenAPI 3 spec and serves Swagger UI. | `controller/*Controller.java` (`@Tag`, `@Operation`), `dto/*` (`@Schema`). Endpoints: `/swagger-ui.html`, `/v3/api-docs`. |
| `h2` (com.h2database) | `2.4.240` (managed by parent) | compile | H2 JDBC driver **and** the embedded database engine. Connects the application to the file-based H2 database. No explicit `<version>` in `pom.xml`. | `application.properties` , `spring.datasource.url=jdbc:h2:file:./demo1db;AUTO_SERVER=TRUE`, `driver-class-name=org.h2.Driver`. Also the in-memory test datasource. |
| `spring-boot-starter-webmvc-test` | managed by parent | test | Test-only starter (not packaged into the production jar). Provides JUnit 5, MockMvc, `@SpringBootTest` and `@AutoConfigureMockMvc`. | `Demo1ApplicationTests.java` (context smoke test), `service/OrderServiceStockTest.java` (stock validation, decrement & restore), `controller/ApiContractTest.java` (HTTP contract via MockMvc). |

## Detailed notes

### spring-boot-starter-webmvc

Spring Boot web MVC starter for servlet-based applications. Provides:

- REST controller annotations (`@RestController`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@RequestMapping`).
- Jackson message conversion (JSON serialization/deserialization).
- Embedded Tomcat and default HTTP configuration.

Without this dependency the REST endpoints would not run.

> **Spring Boot 4 rename:** what used to be `spring-boot-starter-web` is now
> `spring-boot-starter-webmvc`. The `-webmvc` suffix reflects that Boot 4 keeps
> the servlet MVC stack separate from the new `spring-boot-starter-webflux`.
> If you copy a tutorial for Boot 3.x, the artifact name will be wrong here.

### spring-boot-starter-data-jpa

Spring Data JPA starter combining Spring Data repositories and Hibernate. Provides:

- Repository interfaces (`JpaRepository`) with built-in CRUD methods: `findAll()`, `findById()`, `save()`, `deleteById()`, `delete()`, `existsById()`.
- Derived query methods , `OrderItemRepository.countByProductId(Long)` is generated from its name alone.
- Entity lifecycle: `@Entity`, `@Id`, `@Column`, `@Table`, `@ManyToOne`, `@OneToMany`, `@Version`, `@UniqueConstraint`.
- Transaction management (`@Transactional`, including `readOnly = true`).
- Query hints / fetch plans: `@EntityGraph` is used by `OrderRepository` to avoid the N+1 problem when serializing orders.

### spring-boot-starter-validation

Enables Jakarta Bean Validation 3.0. Used with `@Valid` on the request body to automatically validate DTOs before they reach the service layer. Constraints used in request DTOs:

- `@NotBlank` , field is required and must not be blank (`ProductRequest.name`).
- `@Size` , length limits (`name` max 255, `description` max 2000).
- `@NotNull` , field is required (`price`, `stock`, `productId`, `quantity`).
- `@Min` , numeric lower bound (`quantity >= 1`, `stock >= 0`).
- `@DecimalMin` , decimal lower bound (`price >= 0.0`).
- `@Digits(integer = 17, fraction = 2)` , precision guard on `price`, matching the `NUMERIC(19,2)` column.
- `@NotEmpty` , ensures lists (like order items) are not empty (`OrderRequest.items`).
- `@Valid` , cascades validation into each element of `List<@Valid OrderItemRequest>`.

### springdoc-openapi-starter-webmvc-ui

springdoc's starter for Spring Boot 4.x (version 3.1.0). Behavior:

- Scans `@RestController` classes at startup and builds an OpenAPI 3 document.
- Serves Swagger UI at `/swagger-ui.html` and the JSON spec at `/v3/api-docs`.
- Descriptions enriched via `@Tag`, `@Operation`, and `@Schema` (including `requiredMode`).

### h2

H2 is a pure-Java, embedded (file-based) relational database. The single `h2` artifact ships both the **JDBC driver** and the **database engine**, so no separate database server is required.

```properties
spring.datasource.url=jdbc:h2:file:./demo1db;AUTO_SERVER=TRUE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
```

> **Gotcha:** H2 rejects `AUTO_SERVER=TRUE` combined with `DB_CLOSE_ON_EXIT=FALSE`
> with `Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"`
> (error 50100, SQLState `HYC00`). With that combination the application fails to
> start. Keep only one of the two options.
>
> **Why the tests do not catch this:** `src/test/resources/application.properties`
> replaces the datasource URL with `jdbc:h2:mem:demo1test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`.
> The production URL is therefore never exercised by the test suite , the
> combination above was only discovered by actually booting the packaged jar.
> Note that `DB_CLOSE_ON_EXIT=FALSE` *is* legal there, because that URL has no
> `AUTO_SERVER=TRUE`.

Why H2 instead of SQLite:

- Hibernate supports H2 **natively** through `org.hibernate.dialect.H2Dialect`, which ships with `hibernate-core`. No community dialect package is needed.
- H2 is a first-class citizen in Spring Boot: the version is managed by the parent POM (currently `2.4.240`), and the default connection pool settings work out of the box.
- The `AUTO_SERVER=TRUE` flag allows a second process (e.g. a SQL client) to attach to the same database file while the application is running.

### spring-boot-starter-webmvc-test

Test scope only , not included in the final jar. Provides integration-test infrastructure (`@SpringBootTest`, JUnit 5, MockMvc support, `@AutoConfigureMockMvc`). Used by all three test classes: the context smoke test, the order/stock service tests, and the HTTP contract tests.

> **Spring Boot 4 rename + package move:** `spring-boot-starter-test` is now
> `spring-boot-starter-webmvc-test`, and `@AutoConfigureMockMvc` **moved package**
> from `org.springframework.boot.test.autoconfigure.web.servlet` to
> `org.springframework.boot.webmvc.test.autoconfigure`.
> `MockMvc` itself did not move: it stays in `org.springframework.test.web.servlet`
> (with `...request.MockMvcRequestBuilders` and `...result.MockMvcResultMatchers`).
> Importing the old `AutoConfigureMockMvc` package does not compile on Boot 4.

## Removed dependencies

| Dependency | Reason |
| --- | --- |
| `spring-boot-starter-actuator` | No references anywhere in the source or configuration (no actuator endpoints, health checks, or metrics enabled). Dead dependency , removed to shrink the artifact and speed up build/startup. |
| `sqlite-jdbc` (org.xerial, `3.46.1.0`) | SQLite JDBC driver. No longer needed after the database was migrated from SQLite to H2. |
| `hibernate-community-dialects` | Provided `org.hibernate.community.dialect.SQLiteDialect`. H2 has a dialect built into `hibernate-core`, so this package became unnecessary. |

## Dependency graph (runtime)

```text
spring-boot-starter-webmvc ──► embedded Tomcat, Spring MVC, Jackson
spring-boot-starter-data-jpa ──► Spring Data JPA ──► Hibernate ORM ──► HikariCP
spring-boot-starter-validation ──► Hibernate Validator (Jakarta Bean Validation)
springdoc-openapi-starter-webmvc-ui ──► OpenAPI 3 generator + Swagger UI
h2 ──► JDBC driver + embedded database engine
```

`spring-boot-starter-webmvc-test` sits beside them in `test` scope and is not part
of the produced jar.
