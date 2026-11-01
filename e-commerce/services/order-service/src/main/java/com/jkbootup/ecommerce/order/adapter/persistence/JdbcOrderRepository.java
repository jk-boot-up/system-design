package com.jkbootup.ecommerce.order.adapter.persistence;

import com.jkbootup.ecommerce.order.application.port.OrderRepository;
import com.jkbootup.ecommerce.order.domain.CancelReason;
import com.jkbootup.ecommerce.order.domain.DomainException;
import com.jkbootup.ecommerce.order.domain.Money;
import com.jkbootup.ecommerce.order.domain.Order;
import com.jkbootup.ecommerce.order.domain.OrderEvent;
import com.jkbootup.ecommerce.order.domain.OrderLine;
import com.jkbootup.ecommerce.order.domain.OrderStatus;
import com.jkbootup.ecommerce.order.domain.SagaStep;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Data Mapper for orders on monthly range-partitioned tables. Lookups by id add {@code created_at} taken from the
 * UUIDv7, so PostgreSQL reads a single partition.
 */
public class JdbcOrderRepository implements OrderRepository {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public JdbcOrderRepository(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        Instant createdAt = UuidV7.timestampOf(orderId);
        return load(jdbc.sql("select * from orders where id = :id and created_at = :ca")
                .param("id", orderId).param("ca", Timestamp.from(createdAt)).query(this::header).optional());
    }

    @Override
    public Optional<Order> findByIdempotencyKey(UUID customerId, String key) {
        return jdbc.sql("select order_id from idempotency_keys where customer_id = :c and idempotency_key = :k")
                .param("c", customerId).param("k", key).query(UUID.class).optional().flatMap(this::findById);
    }

    @Override
    public boolean insert(Order order, String idempotencyKey) {
        try {
            jdbc.sql("insert into idempotency_keys (customer_id, idempotency_key, order_id) values (:c, :k, :o)")
                    .param("c", order.customerId()).param("k", idempotencyKey).param("o", order.id()).update();
        } catch (DuplicateKeyException e) {
            return false;
        }
        jdbc.sql("""
                        insert into orders (id, created_at, customer_id, customer_email, status, saga_step, cancel_reason,
                                            total_amount, currency, version, updated_at)
                        values (:id, :ca, :cust, :email, :status, :step, :reason, :total, :cur, 0, :ua)""")
                .param("id", order.id()).param("ca", ts(order.createdAt())).param("cust", order.customerId())
                .param("email", order.customerEmail()).param("status", order.status().name())
                .param("step", order.sagaStep().name()).param("reason", null)
                .param("total", order.total().amount()).param("cur", order.total().currency().getCurrencyCode())
                .param("ua", ts(order.updatedAt())).update();
        for (OrderLine l : order.lines()) {
            jdbc.sql("""
                            insert into order_lines (order_id, order_created_at, line_no, sku, product_name, quantity, unit_price)
                            values (:id, :ca, :n, :sku, :name, :q, :price)""")
                    .param("id", order.id()).param("ca", ts(order.createdAt())).param("n", l.lineNo()).param("sku", l.sku())
                    .param("name", l.productName()).param("q", l.quantity()).param("price", l.unitPrice().amount()).update();
        }
        writeEvents(order);
        return true;
    }

    @Override
    public void update(Order order) {
        int rows = jdbc.sql("""
                        update orders set status = :status, saga_step = :step, cancel_reason = :reason,
                               version = version + 1, updated_at = :ua
                         where id = :id and created_at = :ca and version = :v""")
                .param("status", order.status().name()).param("step", order.sagaStep().name())
                .param("reason", order.cancelReason() == null ? null : order.cancelReason().name())
                .param("ua", ts(order.updatedAt())).param("id", order.id()).param("ca", ts(order.createdAt()))
                .param("v", order.version()).update();
        if (rows != 1) throw new DomainException("CONCURRENT_UPDATE", "Order " + order.id() + " was changed concurrently");
        order.versionWritten(order.version() + 1);
        writeEvents(order);
    }

    @Override
    public List<Order> findStuck(Instant now, Duration olderThan, int limit) {
        List<Order> headers = jdbc.sql("""
                        select * from orders where status in ('PENDING', 'CANCELLING') and updated_at < :cutoff
                         order by updated_at limit :n""")
                .param("cutoff", ts(now.minus(olderThan))).param("n", limit).query(this::header).list();
        return headers.stream().map(h -> load(Optional.of(h)).orElseThrow()).toList();
    }

    @Override
    public List<Order> findByCustomer(UUID customerId, Instant beforeCreatedAt, UUID beforeId, int limit) {
        var query = beforeCreatedAt == null
                ? jdbc.sql("select * from orders where customer_id = :c order by created_at desc, id desc limit :n")
                : jdbc.sql("""
                        select * from orders where customer_id = :c and (created_at, id) < (:ca, :id)
                         order by created_at desc, id desc limit :n""")
                .param("ca", ts(beforeCreatedAt)).param("id", beforeId);
        List<Order> headers = query.param("c", customerId).param("n", limit).query(this::header).list();
        return headers.stream().map(h -> load(Optional.of(h)).orElseThrow()).toList();
    }

    // ---------- mapping ----------

    /** Header row with a single placeholder line; {@link #load} replaces it with the real lines. */
    private Order header(ResultSet rs, int n) throws SQLException {
        Money total = new Money(rs.getBigDecimal("total_amount"), Currency.getInstance(rs.getString("currency")));
        String reason = rs.getString("cancel_reason");
        return new Order(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("customer_email"),
                List.of(new OrderLine(0, "?", "?", 1, total)), OrderStatus.valueOf(rs.getString("status")),
                SagaStep.valueOf(rs.getString("saga_step")), reason == null ? null : CancelReason.valueOf(reason),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private Optional<Order> load(Optional<Order> header) {
        return header.map(h -> {
            Currency cur = h.total().currency();
            List<OrderLine> lines = jdbc.sql("""
                            select * from order_lines where order_id = :id and order_created_at = :ca order by line_no""")
                    .param("id", h.id()).param("ca", ts(h.createdAt()))
                    .query((rs, i) -> new OrderLine(rs.getInt("line_no"), rs.getString("sku"), rs.getString("product_name"),
                            rs.getInt("quantity"), new Money(rs.getBigDecimal("unit_price"), cur)))
                    .list();
            return new Order(h.id(), h.customerId(), h.customerEmail(), lines, h.status(), h.sagaStep(), h.cancelReason(),
                    h.version(), h.createdAt(), h.updatedAt());
        });
    }

    private void writeEvents(Order order) {
        for (OrderEvent e : order.pullEvents()) outbox.append("order.events", "Order", e.orderId().toString(), e.type(), payload(e));
    }

    private static Map<String, Object> payload(OrderEvent e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", e.orderId().toString());
        switch (e) {
            case OrderEvent.OrderPlaced x -> {
                p.put("customerId", x.customerId().toString());
                p.put("total", money(x.total()));
                p.put("lines", lines(x.lines()));
            }
            case OrderEvent.OrderConfirmed x -> {
                p.put("customerId", x.customerId().toString());
                p.put("customerEmail", x.customerEmail());
                p.put("total", money(x.total()));
                p.put("lines", lines(x.lines()));
            }
            case OrderEvent.OrderCancelled x -> {
                p.put("customerId", x.customerId().toString());
                p.put("customerEmail", x.customerEmail());
                p.put("reason", x.reason().name());
                p.put("total", money(x.total()));
            }
        }
        return p;
    }

    private static Map<String, String> money(Money m) {
        return Map.of("amount", m.amount().toPlainString(), "currency", m.currency().getCurrencyCode());
    }

    private static List<Map<String, Object>> lines(List<OrderLine> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrderLine l : lines)
            out.add(Map.of("sku", l.sku(), "productName", l.productName(), "quantity", l.quantity(), "unitPrice", money(l.unitPrice())));
        return out;
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i.truncatedTo(ChronoUnit.MICROS));
    }
}
