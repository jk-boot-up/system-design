package com.jkbootup.ecommerce.order.config;

import com.jkbootup.ecommerce.order.adapter.client.CartHttpClient;
import com.jkbootup.ecommerce.order.adapter.client.InventoryHttpClient;
import com.jkbootup.ecommerce.order.adapter.client.PaymentHttpClient;
import com.jkbootup.ecommerce.order.adapter.messaging.OutboxRelay;
import com.jkbootup.ecommerce.order.adapter.persistence.JdbcOrderRepository;
import com.jkbootup.ecommerce.order.adapter.persistence.OrderPartitions;
import com.jkbootup.ecommerce.order.adapter.persistence.OutboxWriter;
import com.jkbootup.ecommerce.order.adapter.persistence.SpringTransactions;
import com.jkbootup.ecommerce.order.adapter.persistence.UuidV7;
import com.jkbootup.ecommerce.order.adapter.scheduling.OrderJobs;
import com.jkbootup.ecommerce.order.application.CheckoutSaga;
import com.jkbootup.ecommerce.order.application.OrderQueries;
import com.jkbootup.ecommerce.order.application.SagaRecovery;
import com.jkbootup.ecommerce.order.application.port.OrderRepository;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** Wires the hexagon: the application core is plain Java; adapters are Spring beans. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class OrderConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OrderRepository orderRepository(JdbcClient jdbc, JsonMapper json) {
        return new JdbcOrderRepository(jdbc, new OutboxWriter(jdbc, json));
    }

    @Bean
    CheckoutSaga checkoutSaga(OrderRepository orders, TransactionTemplate tx, Clock clock, JsonMapper json,
                              @Value("${clients.cart.url:http://localhost:8083}") String cartUrl,
                              @Value("${clients.inventory.url:http://localhost:8084}") String inventoryUrl,
                              @Value("${clients.payment.url:http://localhost:8086}") String paymentUrl,
                              @Value("${clients.connect-timeout:200ms}") Duration connect,
                              @Value("${clients.read-timeout:800ms}") Duration read,
                              @Value("${clients.payment.read-timeout:2s}") Duration paymentRead) {
        return new CheckoutSaga(orders,
                new CartHttpClient(client(cartUrl, connect, read)),
                new InventoryHttpClient(client(inventoryUrl, connect, read), json),
                new PaymentHttpClient(client(paymentUrl, connect, paymentRead), json),
                new SpringTransactions(tx), new UuidV7(), clock);
    }

    @Bean
    OrderQueries orderQueries(OrderRepository orders) {
        return new OrderQueries(orders);
    }

    @Bean
    SagaRecovery sagaRecovery(OrderRepository orders, CheckoutSaga saga, Clock clock,
                              @Value("${order.saga.stale-after:30s}") Duration staleAfter) {
        return new SagaRecovery(orders, saga, clock, staleAfter);
    }

    @Bean
    OrderPartitions orderPartitions(JdbcClient jdbc) {
        return new OrderPartitions(jdbc);
    }

    /** Make sure next months' partitions exist before taking traffic. */
    @Bean
    ApplicationRunner ensurePartitionsAtStartup(OrderPartitions partitions) {
        return args -> partitions.ensureMonthsAhead(3);
    }

    @Bean
    @ConditionalOnProperty(name = "order.jobs.enabled", havingValue = "true", matchIfMissing = true)
    OrderJobs orderJobs(SagaRecovery recovery) {
        return new OrderJobs(recovery);
    }

    @Bean
    @ConditionalOnProperty(name = "order.jobs.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            @Value("${order.outbox.batch-size:500}") int batchSize) {
        return new OutboxRelay(jdbc, kafka, tx, batchSize);
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }

    private static RestClient client(String baseUrl, Duration connect, Duration read) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
