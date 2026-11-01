package com.jkbootup.ecommerce.order.adapter.persistence;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** Keeps monthly partitions of orders and order_lines created three months ahead (daily, one replica). */
public class OrderPartitions {

    private final JdbcClient jdbc;

    public OrderPartitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureMonthsAhead(int months) {
        jdbc.sql("select ensure_order_partitions(:m)").param("m", months).query().singleValue();
    }

    @Scheduled(cron = "${order.partitions.cron:0 7 2 * * *}")
    @SchedulerLock(name = "order.partitions", lockAtMostFor = "PT10M")
    public void maintain() {
        ensureMonthsAhead(3);
    }
}
