package com.jkbootup.ecommerce.order.adapter.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Keeps monthly partitions of orders and order_lines created ahead of time. */
public class OrderPartitions {

    private final JdbcClient jdbc;

    public OrderPartitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureMonthsAhead(int months) {
        jdbc.sql("select ensure_order_partitions(:m)").param("m", months).query().singleValue();
    }
}
