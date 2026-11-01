package com.jkbootup.ecommerce.inventory.application.port;

import com.jkbootup.ecommerce.inventory.domain.Quantity;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import com.jkbootup.ecommerce.inventory.domain.StockLevel;

import java.util.Optional;

/** Stock counters. Every method is a single atomic statement; none reads before it writes. */
public interface StockRepository {

    /** Moves units from available to reserved only if enough are available. @return false when there are not enough. */
    boolean tryReserve(Sku sku, Quantity quantity);

    /** Reserved units become sold. */
    void commitReserved(Sku sku, Quantity quantity);

    /** Reserved units go back to available. */
    void releaseReserved(Sku sku, Quantity quantity);

    /** Sold units come back to available (cancellation after confirmation). */
    void returnSold(Sku sku, Quantity quantity);

    /** Sets the available count (staff stock-take). Creates the SKU when it is new. */
    void setAvailable(Sku sku, int available);

    Optional<StockLevel> find(Sku sku);
}
