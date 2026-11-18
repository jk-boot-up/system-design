package com.jkbootup.ecommerce.catalog.application.port;

import java.util.function.Supplier;

/** Runs work in one database transaction (Unit of Work); rolls back when the work throws. */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
