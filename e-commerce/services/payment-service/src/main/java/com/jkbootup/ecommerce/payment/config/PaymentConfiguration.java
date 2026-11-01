package com.jkbootup.ecommerce.payment.config;

import com.jkbootup.ecommerce.payment.adapter.messaging.OutboxRelay;
import com.jkbootup.ecommerce.payment.adapter.persistence.JdbcPaymentRepository;
import com.jkbootup.ecommerce.payment.adapter.persistence.OutboxPaymentEvents;
import com.jkbootup.ecommerce.payment.adapter.persistence.OutboxWriter;
import com.jkbootup.ecommerce.payment.adapter.persistence.SpringTransactions;
import com.jkbootup.ecommerce.payment.adapter.persistence.UuidV7;
import com.jkbootup.ecommerce.payment.adapter.provider.SimulatedPaymentProvider;
import com.jkbootup.ecommerce.payment.adapter.scheduling.ReconcilerJob;
import com.jkbootup.ecommerce.payment.application.PaymentReconciler;
import com.jkbootup.ecommerce.payment.application.PaymentService;
import com.jkbootup.ecommerce.payment.application.port.PaymentProvider;
import com.jkbootup.ecommerce.payment.application.port.PaymentRepository;
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
import java.time.Instant;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class PaymentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentRepository paymentRepository(JdbcClient jdbc) {
        return new JdbcPaymentRepository(jdbc);
    }

    /** The simulated provider; a real provider is another PaymentProvider adapter selected here. */
    @Bean
    PaymentProvider paymentProvider(@Value("${payment.provider.timeout:1500ms}") Duration timeout,
                                    @Value("${payment.provider.slow-delay:3s}") Duration slowDelay) {
        return new SimulatedPaymentProvider(timeout, slowDelay);
    }

    @Bean
    PaymentService paymentService(PaymentRepository repo, PaymentProvider provider, JdbcClient jdbc, JsonMapper json,
                                  TransactionTemplate tx, Clock clock) {
        return new PaymentService(repo, provider, new OutboxPaymentEvents(new OutboxWriter(jdbc, json)),
                new SpringTransactions(tx), clock, () -> UuidV7.generate(Instant.now()));
    }

    @Bean
    PaymentReconciler paymentReconciler(PaymentRepository repo, PaymentService service, Clock clock,
                                        @Value("${payment.reconcile.settle-after:30s}") Duration settleAfter) {
        return new PaymentReconciler(repo, service, clock, settleAfter);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.jobs.enabled", havingValue = "true", matchIfMissing = true)
    ReconcilerJob reconcilerJob(PaymentReconciler reconciler) {
        return new ReconcilerJob(reconciler);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.jobs.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            @Value("${payment.outbox.batch-size:500}") int batchSize) {
        return new OutboxRelay(jdbc, kafka, tx, batchSize);
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }
}
