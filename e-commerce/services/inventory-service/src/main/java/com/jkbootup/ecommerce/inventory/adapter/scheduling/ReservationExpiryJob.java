package com.jkbootup.ecommerce.inventory.adapter.scheduling;

import com.jkbootup.ecommerce.inventory.application.ReservationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Returns stock held by reservations whose 15-minute hold ran out. Exactly one replica runs it (ShedLock). */
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);
    private static final int BATCH = 500;
    private final ReservationService service;

    public ReservationExpiryJob(ReservationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${inventory.reservations.expiry-interval:30s}")
    @SchedulerLock(name = "inventory.expire-reservations", lockAtMostFor = "PT5M")
    public void expire() {
        int expired;
        do {
            expired = service.expireDue(BATCH);
            if (expired > 0) log.info("Expired {} reservations", expired);
        } while (expired == BATCH);
    }
}
