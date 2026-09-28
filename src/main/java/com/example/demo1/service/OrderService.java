package com.example.demo1.service;

import com.example.demo1.dto.OrderResponse;
import com.example.demo1.exception.ConflictException;
import com.example.demo1.model.Order;
import com.example.demo1.model.OrderItem;
import com.example.demo1.repository.OrderRepository;
import com.example.demo1.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orders in the "pay at the cashier" flow.
 *
 * <p>Orders are no longer created here: they are born from a cart checkout
 * ({@link CartService#checkout}) in status {@code PENDING_PAYMENT}. This service owns the
 * two transitions out of that state:
 *
 * <ul>
 *   <li>{@link #pay} , the cashier takes the money. This is the <strong>only</strong> place
 *       in the whole application that deducts stock.</li>
 *   <li>{@link #cancel} , the buyer walks away. Stock is untouched, because it was never
 *       deducted.</li>
 * </ul>
 *
 * <p>Because stock is only ever deducted , never given back , the old
 * {@code 10 -> 7 -> 10 -> 13} double-counting is structurally impossible: there is no
 * "restore" step left that could add back units which an intervening
 * {@code PUT /api/products} had already written off.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElse(null);
    }

    /**
     * Cashier action: confirm that the order has been paid, and settle the stock.
     *
     * <p>Returns {@code null} when the order does not exist ({@code 404}). Throws
     * {@link ConflictException} ({@code 409}) when the order is not awaiting payment, or
     * when a product no longer has enough stock.
     */
    @Transactional
    public OrderResponse pay(Long id) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return null;
        }
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            throw new ConflictException(
                    "Order " + id + " is " + order.getStatus() + " and can no longer be paid");
        }

        // Sorted by product ID so that two concurrent payments touching the same products
        // always request the row locks in the same order. Without a fixed order, order A
        // holding product 1 and waiting for product 2 while order B holds product 2 and
        // waits for product 1 would deadlock.
        List<OrderItem> items = new ArrayList<>(order.getItems());
        items.sort(Comparator.comparing(item -> item.getProduct().getId()));

        for (OrderItem item : items) {
            int settled = productRepository.decrementStockIfAvailable(
                    item.getProduct().getId(), item.getQuantity());
            if (settled == 0) {
                // Returning 0 means the database refused the subtraction because
                // `stock >= quantity` did not hold at that instant , i.e. somebody else
                // bought the remaining units first.
                //
                // Throwing here rolls the whole transaction back, including the decrements
                // that already succeeded for earlier items, so an order can never end up
                // half-settled.
                throw new ConflictException(
                        "Insufficient stock for product: " + item.getProduct().getName());
            }
        }

        order.setStatus(Order.Status.PAID);
        order.setPaidAt(LocalDateTime.now());

        // The Product entities loaded through `items` still hold their pre-decrement values
        // in the persistence context. That is intentional and harmless: they were not
        // modified in memory, so dirty checking emits no UPDATE for them and cannot write a
        // stale stock value back. Only the Order is updated.
        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * Abandons an order that has not been paid yet.
     *
     * <p>Stock is deliberately not touched: the order never deducted any, so there is
     * nothing to give back. Restoring stock here would be exactly the bug this design
     * removes.
     */
    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return null;
        }
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            throw new ConflictException(
                    "Order " + id + " is " + order.getStatus() + " and can no longer be cancelled");
        }

        order.setStatus(Order.Status.CANCELLED);
        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * Removes an order that is not part of the sales record yet.
     *
     * <p>Only unpaid orders ({@code PENDING_PAYMENT}, {@code CANCELLED}) can be deleted.
     * A {@code PAID} order is history and is refused with {@code 409}; and because stock is
     * never restored, allowing the delete would silently make the stock count disagree with
     * the sales record.
     */
    @Transactional
    public boolean deleteById(Long id) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return false;
        }
        if (order.getStatus() == Order.Status.PAID) {
            throw new ConflictException(
                    "Order " + id + " has been paid and cannot be deleted");
        }

        orderRepository.delete(order);
        return true;
    }
}
