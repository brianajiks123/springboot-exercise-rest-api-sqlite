# Database Schema

The application uses an embedded **SQLite** database (`demo1.db`).
Hibernate auto-generates (updates) this schema on application startup.

## Tables Overview

### 1. `products`

Stores product catalog information.

| Column | Type | Constraints | Description |
| --- | --- | --- | --- |
| `id` | INTEGER | PRIMARY KEY, AUTOINCREMENT | Unique product identifier |
| `name` | VARCHAR(255) | NOT NULL | Product name |
| `description` | VARCHAR(255) | | Optional description |
| `price` | REAL / DOUBLE | NOT NULL | Current price of the product |
| `stock` | INTEGER | NOT NULL | Current stock availability |

### 2. `orders`

Stores order header information.

| Column | Type | Constraints | Description |
| --- | --- | --- | --- |
| `id` | INTEGER | PRIMARY KEY, AUTOINCREMENT | Unique order identifier |
| `order_date` | TIMESTAMP | NOT NULL | Date and time when the order was created |
| `status` | VARCHAR(255) | NOT NULL | Order status (`PENDING`, `COMPLETED`, `CANCELLED`) |

### 3. `order_items`

Stores line items for an order, mapping a specific product and quantity to an order.

| Column | Type | Constraints | Description |
| --- | --- | --- | --- |
| `id` | INTEGER | PRIMARY KEY, AUTOINCREMENT | Unique line item identifier |
| `order_id` | INTEGER | NOT NULL, FOREIGN KEY | References `orders.id` |
| `product_id` | INTEGER | NOT NULL, FOREIGN KEY | References `products.id` |
| `quantity` | INTEGER | NOT NULL | Quantity ordered (>= 1) |
| `unit_price` | REAL / DOUBLE | NOT NULL | Price snapshot taken at the time of order creation |

*Constraint:* `uk_order_product (order_id, product_id)` ensures a product appears only once per order.

---

## Relationships

- **Product 1 : N OrderItem** - A product can be referenced by many order items.
- **Order 1 : N OrderItem** - An order contains one or more order items. When an order is deleted, its `order_items` are cascaded and removed automatically.
