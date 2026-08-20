# Demo1 , Product REST API (Spring Boot)

A simple REST API for **Product** CRUD, built with Spring Boot + Spring Data JPA + Hibernate on an **SQLite** database.

## Features

- Full CRUD for products (`GET`, `GET by id`, `POST`, `PUT`, `DELETE`).
- Layered architecture: `Controller → Service → Repository`, with **DTOs** separated from the entity (the API does not expose the table structure).
- Request-body validation via Jakarta Bean Validation.
- Automatic API documentation via **Swagger UI / OpenAPI 3**.
- File-based SQLite database (no separate DB server) , `demo1.db`.

## Tech Stack

| Technology | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.0 |
| Spring Data JPA (Hibernate) | via starter |
| Database | SQLite (file) |
| API docs | springdoc-openapi 3.1.0 |
| Build | Maven (wrapper `mvnw`) |

## Prerequisites

- **JDK 21**
- The Maven wrapper (`mvnw` / `mvnw.cmd`) is included; no global Maven install needed.

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

### Configuration & secrets

Default non-sensitive settings live in `application.properties` (committed):
port, SQLite database location, JPA/Hibernate flags.

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

> **Never commit credentials into `application.properties`** — it is tracked by
> git. Use `application-secret.properties` for anything sensitive.

## API Endpoints

All endpoints are prefixed with `/api/products`

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/products` | Get all products |
| `GET` | `/api/products/{id}` | Get product by ID (404 if not found) |
| `POST` | `/api/products` | Create a new product (201) |
| `PUT` | `/api/products/{id}` | Update product by ID (404 if not found) |
| `DELETE` | `/api/products/{id}` | Delete product by ID (204 / 404) |

### Example requests

**Create a product**

```bash
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Laptop","description":"14 inch, 16GB RAM","price":15000000,"stock":10}'
```

**Update a product**

```bash
curl -X PUT http://localhost:8080/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Laptop Pro","description":"Updated","price":17000000,"stock":8}'
```

**Delete a product**

```bash
curl -X DELETE http://localhost:8080/api/products/1
```

> **Validation:** `name` is required (max 255 chars), `price` is required and must not be negative, `stock` must not be negative. On validation failure the API returns `400 Bad Request`.

## API Documentation

With the app running:

| URL | Description |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI (interactive) |
| `http://localhost:8080/v3/api-docs` | OpenAPI 3 spec (JSON) |

## Project Structure

```
demo1/
├── pom.xml                          # Dependencies & build config (see DEPENDENCIES.md)
├── mvnw / mvnw.cmd                  # Maven wrapper
├── demo1.db                         # SQLite database (auto-generated)
└── src/
    ├── main/
    │   ├── java/com/example/demo1/
    │   │   ├── Demo1Application.java    # Application entry point
    │   │   ├── controller/
    │   │   │   └── ProductController.java   # REST endpoints & OpenAPI docs
    │   │   ├── service/
    │   │   │   └── ProductService.java      # Business logic, entity ↔ DTO mapping
    │   │   ├── repository/
    │   │   │   └── ProductRepository.java   # Spring Data JPA repository
    │   │   ├── model/
    │   │   │   └── Product.java             # JPA entity (table `products`)
    │   │   └── dto/
    │   │       ├── ProductRequest.java      # Input payload + validation
    │   │       └── ProductResponse.java     # Response payload
    │   └── resources/
    │       └── application.properties        # Server & DB configuration
    └── test/java/com/example/demo1/
        └── Demo1ApplicationTests.java        # Context smoke test
```

## Key Configuration (`application.properties`)

| Property | Value | Purpose |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.datasource.url` | `jdbc:sqlite:demo1.db` | SQLite database file location |
| `spring.jpa.database-platform` | `org.hibernate.community.dialect.SQLiteDialect` | SQLite dialect for Hibernate |
| `spring.jpa.hibernate.ddl-auto` | `update` | Auto-create/update table schema |

## Running Tests

```bash
# Windows
.\mvnw.cmd test

# macOS / Linux
./mvnw test
```

## References

- [DEPENDENCIES.md](DEPENDENCIES.md) , detailed explanation of each dependency in `pom.xml`.
- [springdoc-openapi](https://springdoc.org/) , OpenAPI documentation for Spring Boot.
- [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html)