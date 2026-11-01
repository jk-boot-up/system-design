package com.jkbootup.ecommerce.inventory.application;

import com.jkbootup.ecommerce.inventory.application.port.ReservationRepository;
import com.jkbootup.ecommerce.inventory.application.port.StockEvents;
import com.jkbootup.ecommerce.inventory.application.port.StockRepository;
import com.jkbootup.ecommerce.inventory.application.port.Transactions;
import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.Reservation;
import com.jkbootup.ecommerce.inventory.domain.ReservationStatus;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import com.jkbootup.ecommerce.inventory.domain.StockLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Test doubles for the ports: in-memory, with snapshot/rollback to mimic a transaction. */
final class InMemoryStock implements StockRepository, ReservationRepository, StockEvents, Transactions {

    final Map<Sku, int[]> stock = new HashMap<>();          // [available, reserved]
    final Map<String, Reservation> reservations = new HashMap<>();
    final List<StockLevel> published = new ArrayList<>();

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Map<Sku, int[]> stockCopy = new HashMap<>();
        stock.forEach((k, v) -> stockCopy.put(k, v.clone()));
        Map<String, ReservationStatus> statusCopy = new HashMap<>();
        reservations.forEach((k, v) -> statusCopy.put(k, v.status()));
        Map<String, Reservation> resCopy = new HashMap<>(reservations);
        int publishedSize = published.size();
        try {
            return work.get();
        } catch (RuntimeException e) {
            stock.clear();
            stock.putAll(stockCopy);
            reservations.clear();
            resCopy.forEach((k, r) -> reservations.put(k, new Reservation(r.orderId(), r.sku(), r.quantity(), statusCopy.get(k), r.expiresAt())));
            published.subList(publishedSize, published.size()).clear();
            throw e;
        }
    }

    @Override
    public boolean tryReserve(Sku sku, Quantity q) {
        int[] s = stock.get(sku);
        if (s == null || s[0] < q.value()) return false;
        s[0] -= q.value();
        s[1] += q.value();
        return true;
    }

    @Override
    public void commitReserved(Sku sku, Quantity q) { stock.get(sku)[1] -= q.value(); }

    @Override
    public void releaseReserved(Sku sku, Quantity q) { int[] s = stock.get(sku); s[0] += q.value(); s[1] -= q.value(); }

    @Override
    public void returnSold(Sku sku, Quantity q) { stock.get(sku)[0] += q.value(); }

    @Override
    public void setAvailable(Sku sku, int available) { stock.computeIfAbsent(sku, k -> new int[2])[0] = available; }

    @Override
    public Optional<StockLevel> find(Sku sku) {
        int[] s = stock.get(sku);
        return s == null ? Optional.empty() : Optional.of(new StockLevel(sku, s[0], s[1]));
    }

    @Override
    public List<Reservation> lockByOrder(UUID orderId) {
        return reservations.values().stream().filter(r -> r.orderId().equals(orderId))
                .sorted(Comparator.comparing(Reservation::sku)).toList();
    }

    @Override
    public void insertAll(List<Reservation> list) { list.forEach(r -> reservations.put(r.orderId() + "/" + r.sku(), r)); }

    @Override
    public void updateStatus(Reservation r) { reservations.put(r.orderId() + "/" + r.sku(), r); }

    @Override
    public List<Reservation> lockDue(Instant now, int limit) {
        return reservations.values().stream().filter(r -> r.isDue(now)).limit(limit).toList();
    }

    @Override
    public void stockChanged(StockLevel level) { published.add(level); }
}
