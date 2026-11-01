package com.jkbootup.ecommerce.inventory.adapter.persistence;

import com.jkbootup.ecommerce.inventory.application.port.ReservationRepository;
import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.Reservation;
import com.jkbootup.ecommerce.inventory.domain.ReservationStatus;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Data Mapper between {@link Reservation} and the reservations table. */
public class JdbcReservationRepository implements ReservationRepository {

    private static final RowMapper<Reservation> ROW = (rs, n) -> new Reservation(
            rs.getObject("order_id", UUID.class), Sku.of(rs.getString("sku")), Quantity.of(rs.getInt("quantity")),
            ReservationStatus.valueOf(rs.getString("status")), rs.getTimestamp("expires_at").toInstant());

    private final JdbcClient jdbc;

    public JdbcReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Reservation> lockByOrder(UUID orderId) {
        return jdbc.sql("select * from reservations where order_id = :id order by sku for update")
                .param("id", orderId).query(ROW).list();
    }

    @Override
    public void insertAll(List<Reservation> reservations) {
        for (Reservation r : reservations) {
            jdbc.sql("""
                            insert into reservations (order_id, sku, warehouse, quantity, status, expires_at)
                            values (:order, :sku, :wh, :q, :status, :exp)""")
                    .param("order", r.orderId()).param("sku", r.sku().value()).param("wh", JdbcStockRepository.WAREHOUSE)
                    .param("q", r.quantity().value()).param("status", r.status().name())
                    .param("exp", Timestamp.from(r.expiresAt())).update();
        }
    }

    @Override
    public void updateStatus(Reservation r) {
        jdbc.sql("update reservations set status = :status, updated_at = now() where order_id = :order and sku = :sku")
                .param("status", r.status().name()).param("order", r.orderId()).param("sku", r.sku().value()).update();
    }

    @Override
    public List<Reservation> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                        select * from reservations
                         where status = 'RESERVED' and expires_at <= :now
                         order by expires_at limit :limit
                         for update skip locked""")
                .param("now", Timestamp.from(now)).param("limit", limit).query(ROW).list();
    }
}
