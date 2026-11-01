package com.jkbootup.ecommerce.payment.adapter.persistence;

import com.jkbootup.ecommerce.payment.application.port.PaymentRepository;
import com.jkbootup.ecommerce.payment.domain.DomainException;
import com.jkbootup.ecommerce.payment.domain.Money;
import com.jkbootup.ecommerce.payment.domain.Payment;
import com.jkbootup.ecommerce.payment.domain.PaymentStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data Mapper for payments; the append-only payment_attempts table is the audit trail. */
public class JdbcPaymentRepository implements PaymentRepository {

    private static final RowMapper<Payment> ROW = (rs, n) -> new Payment(
            rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getBigDecimal("amount") == null ? null : new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
            rs.getString("payment_token"), PaymentStatus.valueOf(rs.getString("status")), rs.getString("provider_ref"),
            rs.getString("failure_reason"), rs.getBoolean("void_requested"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    public JdbcPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Payment> lockByOrder(UUID orderId) {
        return jdbc.sql("select * from payments where order_id = :o for update").param("o", orderId).query(ROW).optional();
    }

    @Override
    public Optional<Payment> findByOrder(UUID orderId) {
        return jdbc.sql("select * from payments where order_id = :o").param("o", orderId).query(ROW).optional();
    }

    @Override
    public boolean insert(Payment p) {
        try {
            jdbc.sql("""
                            insert into payments (id, order_id, amount, currency, status, payment_token, provider_ref, failure_reason,
                                                  void_requested, version, created_at, updated_at)
                            values (:id, :o, :amount, :cur, :status, :token, :ref, :reason, :void, 0, :ca, :ua)""")
                    .param("id", p.id()).param("o", p.orderId())
                    .param("amount", p.amount() == null ? null : p.amount().amount())
                    .param("cur", p.amount() == null ? null : p.amount().currency().getCurrencyCode())
                    .param("status", p.status().name()).param("token", p.paymentToken()).param("ref", p.providerRef())
                    .param("reason", p.failureReason()).param("void", p.voidRequested())
                    .param("ca", Timestamp.from(p.createdAt())).param("ua", Timestamp.from(p.updatedAt())).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void update(Payment p) {
        int rows = jdbc.sql("""
                        update payments set status = :status, provider_ref = :ref, failure_reason = :reason,
                               void_requested = :void, version = version + 1, updated_at = :ua
                         where id = :id and version = :v""")
                .param("status", p.status().name()).param("ref", p.providerRef()).param("reason", p.failureReason())
                .param("void", p.voidRequested()).param("ua", Timestamp.from(p.updatedAt()))
                .param("id", p.id()).param("v", p.version()).update();
        if (rows != 1) throw new DomainException("CONCURRENT_UPDATE", "Payment " + p.id() + " was changed concurrently");
        p.versionWritten(p.version() + 1);
    }

    @Override
    public List<Payment> findUnsettled(Instant before, int limit) {
        return jdbc.sql("""
                        select * from payments
                         where (status in ('AUTHORIZING', 'UNKNOWN') or (status = 'AUTHORIZED' and void_requested))
                           and updated_at < :before
                         order by updated_at limit :n""")
                .param("before", Timestamp.from(before)).param("n", limit).query(ROW).list();
    }

    @Override
    public void recordAttempt(UUID paymentId, String operation, String outcome, String providerRef, String detail) {
        jdbc.sql("""
                        insert into payment_attempts (id, payment_id, operation, outcome, provider_ref, detail)
                        values (:id, :p, :op, :out, :ref, :detail)""")
                .param("id", UuidV7.generate(Instant.now())).param("p", paymentId).param("op", operation)
                .param("out", outcome).param("ref", providerRef).param("detail", detail).update();
    }
}
