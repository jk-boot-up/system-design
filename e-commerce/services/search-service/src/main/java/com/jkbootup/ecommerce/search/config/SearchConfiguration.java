package com.jkbootup.ecommerce.search.config;

import com.jkbootup.ecommerce.search.adapter.messaging.EventParser;
import com.jkbootup.ecommerce.search.adapter.messaging.IndexingListeners;
import com.jkbootup.ecommerce.search.adapter.opensearch.IndexBootstrap;
import com.jkbootup.ecommerce.search.adapter.opensearch.OpenSearchIndex;
import com.jkbootup.ecommerce.search.application.Indexer;
import com.jkbootup.ecommerce.search.application.ProductSearch;
import com.jkbootup.ecommerce.search.domain.DomainException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

/** Wires the hexagon: the application core is plain Java, adapters are Spring beans. */
@Configuration(proxyBeanMethods = false)
public class SearchConfiguration {

    @Bean
    OpenSearchIndex searchIndex(JsonMapper json, @Value("${search.opensearch.url}") String url,
                                @Value("${search.opensearch.alias:products}") String alias,
                                @Value("${search.opensearch.index:products-v1}") String index,
                                @Value("${search.opensearch.timeout:2s}") Duration timeout,
                                @Value("${search.opensearch.refresh-on-write:false}") boolean refreshOnWrite) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(timeout);
        RestClient http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        new IndexBootstrap(http, alias, index).ensureIndex();         // fail start-up rather than let writes auto-create an index
        return new OpenSearchIndex(http, json, alias, refreshOnWrite);
    }

    @Bean
    Indexer indexer(OpenSearchIndex index, MeterRegistry meters) {
        Indexer indexer = new Indexer(index);
        Gauge.builder("search.events.stale", indexer, Indexer::staleEvents)
                .description("Events ignored because the index already held the same or a newer version").register(meters);
        return indexer;
    }

    @Bean
    ProductSearch productSearch(OpenSearchIndex index) {
        return new ProductSearch(index);
    }

    @Bean
    IndexingListeners indexingListeners(Indexer indexer, JsonMapper json) {
        return new IndexingListeners(indexer, new EventParser(json));
    }

    /**
     * Unreadable events go to {@code <topic>.dlq} at once. Anything else (OpenSearch down, timeouts) is retried with
     * back-off up to 30 s apart, without limit: the partition waits, and no event is skipped or reordered.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, e) -> new TopicPartition(record.topic() + ".dlq", -1));
        var backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(EventParser.BadEventException.class, DomainException.class);
        return handler;
    }
}
