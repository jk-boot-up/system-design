package com.jkbootup.ecommerce.order.adapter.scheduling;

import com.jkbootup.ecommerce.order.application.SagaRecovery;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Drives interrupted sagas to a final state; exactly one replica runs it at a time (ShedLock). */
public class OrderJobs {

    private static final Logger log = LoggerFactory.getLogger(OrderJobs.class);
    private final SagaRecovery recovery;

    public OrderJobs(SagaRecovery recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${order.saga.recovery-interval:15s}")
    @SchedulerLock(name = "order.saga-recovery", lockAtMostFor = "PT5M")
    public void recoverSagas() {
        int n = recovery.recoverBatch(100);
        if (n > 0) log.info("Saga recovery looked at {} in-flight orders", n);
    }
}
