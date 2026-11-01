package com.jkbootup.ecommerce.inventory.adapter.persistence;

import com.jkbootup.ecommerce.inventory.application.port.StockRepository;
import com.jkbootup.ecommerce.inventory.domain.DomainException;
import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import com.jkbootup.ecommerce.inventory.domain.StockLevel;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

/** Stock counters in PostgreSQL. Each write is one conditional statement, so concurrent orders can never oversell. */
public class JdbcStockRepository implements StockRepository {

    static final String WAREHOUSE = "MAIN";
    private final JdbcClient jdbc;

    public JdbcStockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryReserve(Sku sku, Quantity quantity) {
        return jdbc.sql("""
                        update stock set available = available - :q, reserved = reserved + :q,
                               version = version + 1, updated_at = now()
                         where sku = :sku and warehouse = :wh and available >= :q""")
                .param("q", quantity.value()).param("sku", sku.value()).param("wh", WAREHOUSE)
                .update() == 1;
    }

    @Override
    public void commitReserved(Sku sku, Quantity quantity) {
        expectOne(jdbc.sql("""
                        update stock set reserved = reserved - :q, version = version + 1, updated_at = now()
                         where sku = :sku and warehouse = :wh and reserved >= :q""")
                .param("q", quantity.value()).param("sku", sku.value()).param("wh", WAREHOUSE).update(), sku);
    }

    @Override
    public void releaseReserved(Sku sku, Quantity quantity) {
        expectOne(jdbc.sql("""
                        update stock set available = available + :q, reserved = reserved - :q,
                               version = version + 1, updated_at = now()
                         where sku = :sku and warehouse = :wh and reserved >= :q""")
                .param("q", quantity.value()).param("sku", sku.value()).param("wh", WAREHOUSE).update(), sku);
    }

    @Override
    public void returnSold(Sku sku, Quantity quantity) {
        expectOne(jdbc.sql("""
                        update stock set available = available + :q, version = version + 1, updated_at = now()
                         where sku = :sku and warehouse = :wh""")
                .param("q", quantity.value()).param("sku", sku.value()).param("wh", WAREHOUSE).update(), sku);
    }

    @Override
    public void setAvailable(Sku sku, int available) {
        jdbc.sql("""
                        insert into stock (sku, warehouse, available) values (:sku, :wh, :a)
                        on conflict (sku, warehouse) do update
                           set available = excluded.available, version = stock.version + 1, updated_at = now()""")
                .param("sku", sku.value()).param("wh", WAREHOUSE).param("a", available).update();
    }

    @Override
    public Optional<StockLevel> find(Sku sku) {
        return jdbc.sql("select available, reserved from stock where sku = :sku and warehouse = :wh")
                .param("sku", sku.value()).param("wh", WAREHOUSE)
                .query((rs, n) -> new StockLevel(sku, rs.getInt("available"), rs.getInt("reserved")))
                .optional();
    }

    private static void expectOne(int rows, Sku sku) {
        if (rows != 1) throw new DomainException("STOCK_INCONSISTENT", "Stock row for " + sku + " did not match the reservation");
    }
}
