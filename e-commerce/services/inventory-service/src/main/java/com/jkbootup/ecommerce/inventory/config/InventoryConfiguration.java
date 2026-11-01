package com.jkbootup.ecommerce.inventory.config;

import com.jkbootup.ecommerce.inventory.adapter.messaging.OrderCancelledListener;
import com.jkbootup.ecommerce.inventory.adapter.messaging.OutboxRelay;
import com.jkbootup.ecommerce.inventory.adapter.persistence.JdbcReservationRepository;
import com.jkbootup.ecommerce.inventory.adapter.persistence.JdbcStockRepository;
import com.jkbootup.ecommerce.inventory.adapter.persistence.OutboxStockEvents;
import com.jkbootup.ecommerce.inventory.adapter.persistence.ProcessedEvents;
import com.jkbootup.ecommerce.inventory.adapter.persistence.SpringTransactions;
import com.jkbootup.ecommerce.inventory.adapter.scheduling.InventoryJobs;
import com.jkbootup.ecommerce.inventory.application.ReservationService;
import com.jkbootup.ecommerce.inventory.application.port.Transactions;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/** Wires the hexagon: the application core is plain Java, adapters are Spring beans. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class InventoryConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Transactions transactions(TransactionTemplate template) {
        return new SpringTransactions(template);
    }

    @Bean
    ReservationService reservationService(JdbcClient jdbc, JsonMapper json, Transactions tx, Clock clock,
                                          @Value("${inventory.reservations.hold-for:15m}") Duration holdFor) {
        return new ReservationService(new JdbcStockRepository(jdbc), new JdbcReservationRepository(jdbc),
                new OutboxStockEvents(jdbc, json), tx, clock, holdFor);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            @Value("${inventory.outbox.batch-size:500}") int batchSize) {
        return new OutboxRelay(jdbc, kafka, tx, batchSize);
    }

    @Bean
    @ConditionalOnProperty(name = "inventory.jobs.enabled", havingValue = "true", matchIfMissing = true)
    InventoryJobs inventoryJobs(ReservationService service, OutboxRelay relay) {
        return new InventoryJobs(service, relay);
    }

    @Bean
    OrderCancelledListener orderCancelledListener(ReservationService service, JdbcClient jdbc, Transactions tx, JsonMapper json) {
        return new OrderCancelledListener(service, new ProcessedEvents(jdbc), tx, json);
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }
}
