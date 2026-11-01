package com.jkbootup.ecommerce.order.application;

import com.jkbootup.ecommerce.order.application.port.OrderRepository;
import com.jkbootup.ecommerce.order.domain.Order;

import java.time.Clock;
import java.time.Duration;

/** Finds sagas that stopped half-way (crash, timeout, dependency outage) and drives them to a final state. */
public class SagaRecovery {

    private final OrderRepository orders;
    private final CheckoutSaga saga;
    private final Clock clock;
    private final Duration staleAfter;

    public SagaRecovery(OrderRepository orders, CheckoutSaga saga, Clock clock, Duration staleAfter) {
        this.orders = orders;
        this.saga = saga;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    /** @return how many orders were looked at. */
    public int recoverBatch(int limit) {
        var stuck = orders.findStuck(clock.instant(), staleAfter, limit);
        for (Order order : stuck) {
            try {
                saga.resume(order);
            } catch (RuntimeException e) {
                // leave it for the next pass; one bad order must not block the others
            }
        }
        return stuck.size();
    }
}
