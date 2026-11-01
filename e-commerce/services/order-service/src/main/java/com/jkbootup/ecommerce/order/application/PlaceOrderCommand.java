package com.jkbootup.ecommerce.order.application;

import java.util.UUID;

public record PlaceOrderCommand(UUID customerId, String customerEmail, String idempotencyKey, String paymentToken) {}
