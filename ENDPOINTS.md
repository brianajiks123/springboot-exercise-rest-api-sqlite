# API Endpoints

This document provides a detailed list of all available REST API endpoints for this application.
Base URL: `http://localhost:8080` (or whatever host/port the app is running on).

---

## 1. Products API (`/api/products`)

### `GET /api/products`
Retrieves a list of all products.
- **Success Response:** `200 OK`
- **Body:** JSON array of Product objects.

### `GET /api/products/{id}`
Retrieves a single product by its ID.
- **Path Variable:** `id` (Long)
- **Success Response:** `200 OK`
- **Error Response:** `404 Not Found` (if ID does not exist)

### `POST /api/products`
Creates a new product.
- **Request Body (JSON):**
  - `name`: string (required, max 255 chars)
  - `description`: string (optional)
  - `price`: number (required, non-negative)
  - `stock`: integer (required, non-negative)
- **Success Response:** `201 Created`
- **Error Response:** `400 Bad Request` (if validation fails)

### `PUT /api/products/{id}`
Updates an existing product.
- **Path Variable:** `id` (Long)
- **Request Body (JSON):** Same fields as POST.
- **Success Response:** `200 OK`
- **Error Response:** `400 Bad Request` (validation), `404 Not Found` (if ID does not exist)

### `DELETE /api/products/{id}`
Deletes a product by its ID.
- **Path Variable:** `id` (Long)
- **Success Response:** `204 No Content`
- **Error Response:** `404 Not Found` (if ID does not exist)

---

## 2. Orders API (`/api/orders`)

### `GET /api/orders`
Retrieves a list of all orders along with their line items and computed total price.
- **Success Response:** `200 OK`
- **Body:** JSON array of Order objects, each containing an array of items.

### `GET /api/orders/{id}`
Retrieves a single order by its ID.
- **Path Variable:** `id` (Long)
- **Success Response:** `200 OK`
- **Error Response:** `404 Not Found` (if ID does not exist)

### `POST /api/orders`
Creates a new order. The API automatically snapshots the current `price` of each product into `unitPrice` and computes the order total.
- **Request Body (JSON):**
  - `items`: array of objects (required, min 1 item).
    - `productId`: Long (required, must exist in DB)
    - `quantity`: integer (required, minimum 1)
- **Success Response:** `201 Created`
- **Error Responses:** 
  - `400 Bad Request` (if validation fails, e.g., missing fields, quantity < 1, or product ID does not exist)

### `DELETE /api/orders/{id}`
Deletes an order by its ID. This will also cascade and delete all associated order items.
- **Path Variable:** `id` (Long)
- **Success Response:** `204 No Content`
- **Error Response:** `404 Not Found` (if ID does not exist)

---

## 3. OpenAPI / Swagger Documentation

- `GET /swagger-ui.html` - Interactive Swagger UI.
- `GET /v3/api-docs` - Raw OpenAPI 3 JSON specification.
