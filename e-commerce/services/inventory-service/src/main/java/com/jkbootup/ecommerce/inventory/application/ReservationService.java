package com.jkbootup.ecommerce.inventory.application;

import com.jkbootup.ecommerce.inventory.application.port.ReservationRepository;
import com.jkbootup.ecommerce.inventory.application.port.StockEvents;
import com.jkbootup.ecommerce.inventory.application.port.StockRepository;
import com.jkbootup.ecommerce.inventory.application.port.Transactions;
import com.jkbootup.ecommerce.inventory.domain.DomainException;
import com.jkbootup.ecommerce.inventory.domain.OrderLine;
import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.Reservation;
import com.jkbootup.ecommerce.inventory.domain.ReservationStatus;
import com.jkbootup.ecommerce.inventory.domain.ReserveOutcome;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import com.jkbootup.ecommerce.inventory.domain.StockLevel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Use cases of the inventory context. Each public method is one transaction. All commands are idempotent per order id,
 * because order-service retries them and its saga recovery may repeat them.
 */
public class ReservationService {

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final StockEvents events;
    private final Transactions tx;
    private final Clock clock;
    private final Duration holdFor;

    public ReservationService(StockRepository stock, ReservationRepository reservations, StockEvents events,
                              Transactions tx, Clock clock, Duration holdFor) {
        this.stock = stock;
        this.reservations = reservations;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.holdFor = holdFor;
    }

    /** Reserves every line or none. Lines for the same SKU are merged and SKUs are locked in order to avoid deadlocks. */
    public ReserveOutcome reserve(UUID orderId, List<OrderLine> lines) {
        if (lines.isEmpty()) throw new DomainException("EMPTY_ORDER", "Nothing to reserve");
        Map<Sku, Integer> merged = new LinkedHashMap<>();
        lines.stream().sorted(Comparator.comparing(OrderLine::sku))
                .forEach(l -> merged.merge(l.sku(), l.quantity().value(), Integer::sum));
        try {
            return tx.inTransaction(() -> {
                List<Reservation> existing = reservations.lockByOrder(orderId);
                if (!existing.isEmpty()) return replay(existing);
                Instant expiresAt = clock.instant().plus(holdFor);
                List<Reservation> held = new ArrayList<>();
                for (var e : merged.entrySet()) {
                    Quantity q = Quantity.of(e.getValue());
                    if (!stock.tryReserve(e.getKey(), q)) throw new NotEnoughStock(e.getKey());
                    held.add(Reservation.hold(orderId, e.getKey(), q, expiresAt));
                }
                reservations.insertAll(held);
                publish(merged.keySet());
                return new ReserveOutcome.Reserved(expiresAt);
            });
        } catch (NotEnoughStock e) {
            return new ReserveOutcome.OutOfStock(e.sku);
        }
    }

    /** Reserved stock becomes sold. Fails with RESERVATION_EXPIRED when the hold already ran out. */
    public void commit(UUID orderId) {
        transition(orderId, ReservationStatus.COMMITTED, (r) -> stock.commitReserved(r.sku(), r.quantity()));
    }

    /** Gives reserved stock back. Safe to repeat; ignores reservations that already ended. */
    public void release(UUID orderId) {
        tx.inTransaction(() -> {
            List<Sku> changed = new ArrayList<>();
            for (Reservation r : reservations.lockByOrder(orderId)) {
                if (r.status() != ReservationStatus.RESERVED) continue;
                r.moveTo(ReservationStatus.RELEASED);
                stock.releaseReserved(r.sku(), r.quantity());
                reservations.updateStatus(r);
                changed.add(r.sku());
            }
            publish(changed);
            return null;
        });
    }

    /** Sold stock comes back after a cancellation of a confirmed order. */
    public void returnStock(UUID orderId) {
        transition(orderId, ReservationStatus.RETURNED, (r) -> stock.returnSold(r.sku(), r.quantity()));
    }

    /** Releases reservations whose hold has run out. @return how many expired. */
    public int expireDue(int limit) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            List<Reservation> due = reservations.lockDue(now, limit);
            List<Sku> changed = new ArrayList<>();
            for (Reservation r : due) {
                if (!r.isDue(now)) continue;
                r.moveTo(ReservationStatus.EXPIRED);
                stock.releaseReserved(r.sku(), r.quantity());
                reservations.updateStatus(r);
                changed.add(r.sku());
            }
            publish(changed);
            return changed.size();
        });
    }

    public void setAvailable(Sku sku, int available) {
        if (available < 0) throw new DomainException("INVALID_QUANTITY", "Available stock cannot be negative");
        tx.inTransaction(() -> {
            stock.setAvailable(sku, available);
            publish(List.of(sku));
            return null;
        });
    }

    public Optional<StockLevel> level(Sku sku) {
        return stock.find(sku);
    }

    private void transition(UUID orderId, ReservationStatus target, java.util.function.Consumer<Reservation> moveStock) {
        tx.inTransaction(() -> {
            List<Reservation> all = reservations.lockByOrder(orderId);
            if (all.isEmpty()) throw new DomainException("NOT_FOUND", "No reservation for order " + orderId);
            List<Sku> changed = new ArrayList<>();
            for (Reservation r : all) {
                if (r.status() == target) continue;
                if (target == ReservationStatus.COMMITTED && r.status() == ReservationStatus.EXPIRED)
                    throw new DomainException("RESERVATION_EXPIRED", "Reservation for order " + orderId + " expired");
                r.moveTo(target);
                moveStock.accept(r);
                reservations.updateStatus(r);
                changed.add(r.sku());
            }
            if (target == ReservationStatus.RETURNED) publish(changed);
            return null;
        });
    }

    private ReserveOutcome replay(List<Reservation> existing) {
        Reservation first = existing.getFirst();
        if (existing.stream().allMatch(r -> r.status() == ReservationStatus.RESERVED || r.status() == ReservationStatus.COMMITTED))
            return new ReserveOutcome.Reserved(first.expiresAt());
        throw new DomainException("ILLEGAL_STATE", "Order " + first.orderId() + " already has ended reservations");
    }

    private void publish(Iterable<Sku> skus) {
        for (Sku sku : new TreeSet<Sku>(toList(skus))) stock.find(sku).ifPresent(events::stockChanged);
    }

    private static List<Sku> toList(Iterable<Sku> skus) {
        List<Sku> out = new ArrayList<>();
        skus.forEach(out::add);
        return out;
    }

    /** Thrown inside the transaction to roll back every reservation made so far. */
    private static final class NotEnoughStock extends RuntimeException {
        private final Sku sku;

        NotEnoughStock(Sku sku) {
            super(null, null, false, false);
            this.sku = sku;
        }
    }
}
