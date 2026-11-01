package com.jkbootup.ecommerce.order.application;

import com.jkbootup.ecommerce.order.application.port.CartPort;
import com.jkbootup.ecommerce.order.application.port.IdGenerator;
import com.jkbootup.ecommerce.order.application.port.InventoryPort;
import com.jkbootup.ecommerce.order.application.port.OrderRepository;
import com.jkbootup.ecommerce.order.application.port.PaymentPort;
import com.jkbootup.ecommerce.order.application.port.Transactions;
import com.jkbootup.ecommerce.order.domain.Money;
import com.jkbootup.ecommerce.order.domain.Order;
import com.jkbootup.ecommerce.order.domain.OrderEvent;
import com.jkbootup.ecommerce.order.domain.OrderLine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory test doubles for every port of the saga. Calls are recorded so tests can assert compensations. */
final class Fakes {

    final List<String> calls = new ArrayList<>();
    final List<OrderEvent> published = new ArrayList<>();
    final Map<UUID, Order> orders = new HashMap<>();
    final Map<String, UUID> keys = new HashMap<>();

    CartPort.Snapshot cart = new CartPort.Snapshot.Ready(List.of(
            new CartPort.Line("SKU-1", "Trail Runner", 2, Money.of("89.90", "EUR"), true)));
    final Queue<InventoryPort.Reservation> reserve = new LinkedList<>();
    final Queue<InventoryPort.Step> commit = new LinkedList<>();
    final Queue<PaymentPort.Result> authorize = new LinkedList<>();
    final Queue<PaymentPort.Result> capture = new LinkedList<>();
    final Queue<PaymentPort.Result> refund = new LinkedList<>();

    final OrderRepository repository = new OrderRepository() {
        public Optional<Order> findById(UUID id) { return Optional.ofNullable(orders.get(id)); }

        public Optional<Order> findByIdempotencyKey(UUID c, String k) { return Optional.ofNullable(keys.get(c + "/" + k)).map(orders::get); }

        public boolean insert(Order o, String key) {
            if (keys.putIfAbsent(o.customerId() + "/" + key, o.id()) != null) return false;
            orders.put(o.id(), o);
            published.addAll(o.pullEvents());
            return true;
        }

        public void update(Order o) {
            orders.put(o.id(), o);
            o.versionWritten(o.version() + 1);
            published.addAll(o.pullEvents());
        }

        public List<Order> findStuck(Instant now, Duration older, int limit) {
            return orders.values().stream().filter(o -> !o.status().isFinal() && o.status().name().matches("PENDING|CANCELLING"))
                    .limit(limit).toList();
        }

        public List<Order> findByCustomer(UUID c, Instant before, UUID beforeId, int limit) {
            return orders.values().stream().filter(o -> o.customerId().equals(c))
                    .sorted(Comparator.comparing(Order::createdAt).reversed()).limit(limit).toList();
        }
    };

    final CartPort cartPort = id -> { calls.add("cart.snapshot"); return cart; };

    final InventoryPort inventory = new InventoryPort() {
        public Reservation reserve(UUID id, List<OrderLine> lines) { calls.add("inventory.reserve"); return next(reserve, new Reservation.Reserved()); }

        public Step commit(UUID id) { calls.add("inventory.commit"); return next(commit, new Step.Done()); }

        public Step release(UUID id) { calls.add("inventory.release"); return new Step.Done(); }

        public Step returnStock(UUID id) { calls.add("inventory.return"); return new Step.Done(); }
    };

    final PaymentPort payment = new PaymentPort() {
        public Result authorize(UUID id, Money amount, String token) { calls.add("payment.authorize"); return next(authorize, new Result.Approved()); }

        public Result capture(UUID id) { calls.add("payment.capture"); return next(capture, new Result.Approved()); }

        public Result voidAuthorization(UUID id) { calls.add("payment.void"); return new Result.Approved(); }

        public Result refund(UUID id) { calls.add("payment.refund"); return next(refund, new Result.Approved()); }
    };

    final Transactions tx = new Transactions() {
        public <T> T inTransaction(Supplier<T> work) { return work.get(); }
    };

    final IdGenerator ids = at -> UUID.randomUUID();

    private static <T> T next(Queue<T> q, T otherwise) {
        T v = q.poll();
        return v == null ? otherwise : v;
    }
}
