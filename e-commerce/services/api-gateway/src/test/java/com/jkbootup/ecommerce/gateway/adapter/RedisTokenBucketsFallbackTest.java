package com.jkbootup.ecommerce.gateway.adapter;

import com.jkbootup.ecommerce.gateway.adapter.admission.RedisTokenBuckets;
import com.jkbootup.ecommerce.gateway.domain.Decision;
import com.jkbootup.ecommerce.gateway.domain.LocalTokenBuckets;
import com.jkbootup.ecommerce.gateway.domain.RatePolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis is unreachable (nothing listens on port 1): requests are still limited, by a stricter local bucket. */
class RedisTokenBucketsFallbackTest {

    @Test
    void redisDownFallsBackToAConservativeLocalLimit() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(50)).build());
        factory.afterPropertiesSet();
        factory.start();
        var meters = new SimpleMeterRegistry();
        var buckets = new RedisTokenBuckets(new ReactiveStringRedisTemplate(factory), new LocalTokenBuckets(100, System::nanoTime),
                0.25, Duration.ofMillis(100), meters);
        try {
            List<Decision> decisions = new ArrayList<>();
            for (int i = 0; i < 15; i++) decisions.add(buckets.take(new RatePolicy("browse", 20, 40), "ip:1.2.3.4").block());
            assertThat(decisions).allMatch(d -> !d.shared());
            assertThat(decisions.stream().filter(Decision::allowed).count()).isEqualTo(10);     // 40 × 0.25
            assertThat(meters.counter("gateway.ratelimit.degraded").count()).isEqualTo(15);
        } finally {
            factory.destroy();
        }
    }
}
