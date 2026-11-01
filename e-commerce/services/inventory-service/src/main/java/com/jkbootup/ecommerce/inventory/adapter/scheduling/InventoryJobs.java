package com.jkbootup.ecommerce.inventory.adapter.scheduling;

import com.jkbootup.ecommerce.inventory.adapter.messaging.OutboxRelay;
import com.jkbootup.ecommerce.inventory.application.ReservationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Background work. The relay runs in every replica; expiry and purge run in exactly one (ShedLock). */
public class InventoryJobs {

    private static final Logger log = LoggerFactory.getLogger(InventoryJobs.class);
    private final ReservationService service;
    private final OutboxRelay relay;

    public InventoryJobs(ReservationService service, OutboxRelay relay) {
        this.service = service;
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval:100ms}")
    public void relayOutbox() {
        try {
            while (relay.relayOnce() > 0) {
                // keep draining while there is a backlog
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed, will retry: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${inventory.reservations.expiry-interval:30s}")
    @SchedulerLock(name = "inventory.expire-reservations", lockAtMostFor = "PT5M")
    public void expireReservations() {
        int expired;
        do {
            expired = service.expireDue(500);
            if (expired > 0) log.info("Expired {} reservations", expired);
        } while (expired == 500);
    }

    @Scheduled(cron = "${inventory.outbox.purge-cron:0 17 3 * * *}")
    @SchedulerLock(name = "inventory.purge-outbox", lockAtMostFor = "PT30M")
    public void purgeOutbox() {
        log.info("Purged {} published outbox rows", relay.purgePublished(7));
    }
}
