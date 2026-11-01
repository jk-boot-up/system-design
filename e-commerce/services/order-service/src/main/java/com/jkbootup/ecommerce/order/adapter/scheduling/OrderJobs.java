package com.jkbootup.ecommerce.order.adapter.scheduling;

import com.jkbootup.ecommerce.order.adapter.persistence.OrderPartitions;
import com.jkbootup.ecommerce.order.application.SagaRecovery;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Saga recovery and partition maintenance; each runs in exactly one replica at a time (ShedLock). */
public class OrderJobs {

    private static final Logger log = LoggerFactory.getLogger(OrderJobs.class);
    private final SagaRecovery recovery;
    private final OrderPartitions partitions;

    public OrderJobs(SagaRecovery recovery, OrderPartitions partitions) {
        this.recovery = recovery;
        this.partitions = partitions;
    }

    @Scheduled(fixedDelayString = "${order.saga.recovery-interval:15s}")
    @SchedulerLock(name = "order.saga-recovery", lockAtMostFor = "PT5M")
    public void recoverSagas() {
        int n = recovery.recoverBatch(100);
        if (n > 0) log.info("Saga recovery looked at {} in-flight orders", n);
    }

    @Scheduled(cron = "${order.partitions.cron:0 7 2 * * *}")
    @SchedulerLock(name = "order.partitions", lockAtMostFor = "PT10M")
    public void maintainPartitions() {
        partitions.ensureMonthsAhead(3);
    }
}
