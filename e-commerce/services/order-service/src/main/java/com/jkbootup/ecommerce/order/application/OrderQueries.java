package com.jkbootup.ecommerce.order.application;

import com.jkbootup.ecommerce.order.application.port.OrderRepository;
import com.jkbootup.ecommerce.order.domain.DomainException;
import com.jkbootup.ecommerce.order.domain.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read side: an order (owner or staff only) and a customer's order history, newest first. */
public class OrderQueries {

    private final OrderRepository orders;

    public OrderQueries(OrderRepository orders) {
        this.orders = orders;
    }

    public Order get(UUID orderId, UUID requestedBy, boolean staff) {
        Order order = orders.findById(orderId).orElseThrow(() -> new DomainException("NOT_FOUND", "Order " + orderId + " not found"));
        if (!staff && !order.isOwnedBy(requestedBy)) throw new DomainException("NOT_FOUND", "Order " + orderId + " not found");
        return order;
    }

    public List<Order> history(UUID customerId, Instant beforeCreatedAt, UUID beforeId, int limit) {
        return orders.findByCustomer(customerId, beforeCreatedAt, beforeId, Math.min(Math.max(limit, 1), 100));
    }
}
