package com.jkbootup.ecommerce.order.application.port;

import java.util.function.Supplier;

/** Runs work in one database transaction (Unit of Work). */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
