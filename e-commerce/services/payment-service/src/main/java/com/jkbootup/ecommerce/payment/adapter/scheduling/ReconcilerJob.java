package com.jkbootup.ecommerce.payment.adapter.scheduling;

import com.jkbootup.ecommerce.payment.application.PaymentReconciler;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Settles unknown payment outcomes and retries failed voids; one replica at a time (ShedLock). */
public class ReconcilerJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerJob.class);
    private final PaymentReconciler reconciler;

    public ReconcilerJob(PaymentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${payment.reconcile.interval:20s}")
    @SchedulerLock(name = "payment.reconcile", lockAtMostFor = "PT5M")
    public void reconcile() {
        int n = reconciler.reconcileBatch(200);
        if (n > 0) log.info("Reconciled {} unsettled payments", n);
    }
}
