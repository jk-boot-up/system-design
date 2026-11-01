package com.jkbootup.ecommerce.inventory.adapter.persistence;

import com.jkbootup.ecommerce.inventory.application.port.Transactions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Unit of Work backed by Spring's transaction manager; joins an outer transaction when one is active. */
public class SpringTransactions implements Transactions {

    private final TransactionTemplate template;

    public SpringTransactions(TransactionTemplate template) {
        this.template = template;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
