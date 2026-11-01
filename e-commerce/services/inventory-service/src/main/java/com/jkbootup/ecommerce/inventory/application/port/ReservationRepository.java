package com.jkbootup.ecommerce.inventory.application.port;

import com.jkbootup.ecommerce.inventory.domain.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository {

    /** Reservations of one order, locked for update, in SKU order. */
    List<Reservation> lockByOrder(UUID orderId);

    void insertAll(List<Reservation> reservations);

    void updateStatus(Reservation reservation);

    /** Live reservations whose expiry has passed, locked with SKIP LOCKED so parallel runners never collide. */
    List<Reservation> lockDue(Instant now, int limit);
}
