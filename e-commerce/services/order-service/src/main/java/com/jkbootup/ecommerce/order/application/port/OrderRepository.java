package com.jkbootup.ecommerce.order.application.port;

import com.jkbootup.ecommerce.order.domain.Order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    Optional<Order> findById(UUID orderId);

    Optional<Order> findByIdempotencyKey(UUID customerId, String idempotencyKey);

    /** Inserts the order, its lines, its idempotency key and its pending events. @return false if the key was taken concurrently. */
    boolean insert(Order order, String idempotencyKey);

    /** Writes status, saga step and pending events; fails with CONCURRENT_UPDATE if the version moved. */
    void update(Order order);

    /** In-flight orders (PENDING or CANCELLING) not touched for {@code olderThan}. */
    List<Order> findStuck(Instant now, Duration olderThan, int limit);

    /** Newest first, keyset-paginated: pass the last (createdAt, id) of the previous page. */
    List<Order> findByCustomer(UUID customerId, Instant beforeCreatedAt, UUID beforeId, int limit);
}
