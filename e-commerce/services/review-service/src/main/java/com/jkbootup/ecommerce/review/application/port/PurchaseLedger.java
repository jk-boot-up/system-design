package com.jkbootup.ecommerce.review.application.port;

import java.time.Instant;
import java.util.UUID;

/** Who bought what, from order.OrderConfirmed. Recording a purchase twice changes nothing. */
public interface PurchaseLedger {

    void record(UUID customerId, String sku, Instant purchasedAt);

    boolean hasBought(UUID customerId, String sku);
}
