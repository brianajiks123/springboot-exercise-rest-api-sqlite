# Dependency Map

This document describes every dependency declared in `pom.xml` , what it does and where it is used in the source code.

> All listed dependencies are currently **in use** , there are no unused dependencies. The previously unused `spring-boot-starter-actuator` has been removed.

| Dependency | Purpose | Used in |
|---|---|---|
| `spring-boot-starter-webmvc` | Web / REST MVC layer: `@RestController`, `@RequestMapping`, HTTP mapping, JSON serialization via Jackson, embedded Tomcat. | `controller/ProductController.java` , all `/api/products` endpoints. |
| `spring-boot-starter-data-jpa` | Data / ORM layer (Spring Data JPA + Hibernate): `JpaRepository`, `@Entity`, table-to-object mapping, SQL generation. | `repository/ProductRepository.java` (extends `JpaRepository<Product, Long>`), `model/Product.java` (`@Entity`, `@Table`), `service/ProductService.java`. |
| `spring-boot-starter-validation` | Bean validation (Jakarta Bean Validation). Enables `@Valid` and constraint annotations on DTOs. | `dto/ProductRequest.java` (`@NotBlank`, `@Size`, `@NotNull`, `@DecimalMin`, `@Min`), `controller/ProductController.java` (`@Valid @RequestBody`). |
| `springdoc-openapi-starter-webmvc-ui` | Automatic API documentation: generates OpenAPI 3 spec and serves Swagger UI. | `controller/ProductController.java` (`@Tag`, `@Operation`), `dto/*` (`@Schema`). Endpoints: `/swagger-ui.html`, `/v3/api-docs`. |
| `sqlite-jdbc` (org.xerial) | SQLite JDBC driver. Connects the application to the SQLite database file. | `application.properties` , `spring.datasource.url=jdbc:sqlite:demo1.db`, `driver-class-name=org.sqlite.JDBC`. |
| `hibernate-community-dialects` | SQLite dialect for Hibernate, so Hibernate can generate correct SQL for SQLite. | `application.properties` , `spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect`. |
| `spring-boot-starter-webmvc-test` | Test-only starter (`scope=test`, not packaged into the production jar). Provides JUnit 5, MockMvc, `@SpringBootTest`. | `src/test/java/com/example/demo1/Demo1ApplicationTests.java` , `@SpringBootTest` context smoke test. |

## Detailed notes

### spring-boot-starter-webmvc
Spring Boot web MVC starter for servlet-based applications. Provides:

- REST controller annotations (`@RestController`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@RequestMapping`).
- Jackson message conversion (JSON serialization/deserialization).
- Embedded Tomcat and default HTTP configuration.

Without this dependency the REST endpoints would not run.

### spring-boot-starter-data-jpa
Spring Data JPA starter combining Spring Data repositories and Hibernate. Provides:

- Repository interfaces (`JpaRepository`) with built-in CRUD methods: `findAll()`, `findById()`, `save()`, `deleteById()`, `existsById()`.
- Entity lifecycle: `@Entity`, `@Id`, `@Column`, `@Table`.
- Transaction management (`@Transactional`).

### spring-boot-starter-validation
Enables Jakarta Bean Validation 3.0. Used with `@Valid` on the request body to automatically validate DTOs before they reach the service layer. Constraints used in `ProductRequest`:

- `@NotBlank` , `name` is required and must not be blank.
- `@Size` , length limits for `name` and `description`.
- `@NotNull` , `price` is required.
- `@DecimalMin` / `@Min` , prevent negative values.

### springdoc-openapi-starter-webmvc-ui
springdoc's starter for Spring Boot 4.x (version 3.1.0). Behavior:

- Scans `@RestController` classes at startup and builds an OpenAPI 3 document.
- Serves Swagger UI at `/swagger-ui.html` and the JSON spec at `/v3/api-docs`.
- Descriptions enriched via `@Tag`, `@Operation`, and `@Schema`.

### sqlite-jdbc
Pure-JDBC driver for SQLite (org.xerial). Connects directly to the `demo1.db` file without a separate database server, as configured in `application.properties`:

```properties
spring.datasource.url=jdbc:sqlite:demo1.db
spring.datasource.driver-class-name=org.sqlite.JDBC
```

### hibernate-community-dialects
SQLite is not natively supported by Hibernate; its dialect ships in the community package. It is selected via `spring.jpa.database-platform` so Hibernate knows how to translate JPA to SQLite (data types, auto-increment, syntax).

### spring-boot-starter-webmvc-test
Test scope only , not included in the final jar. Provides integration-test infrastructure (`@SpringBootTest`, JUnit 5, MockMvc support). Used to verify the Spring context loads (`contextLoads()`).

## Removed dependencies

| Dependency | Reason |
|---|---|
| `spring-boot-starter-actuator` | No references anywhere in the source or configuration (no actuator endpoints, health checks, or metrics enabled). Dead dependency , removed to shrink the artifact and speed up build/startup. |