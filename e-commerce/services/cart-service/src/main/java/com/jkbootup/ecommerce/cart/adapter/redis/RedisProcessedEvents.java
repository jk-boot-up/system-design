package com.jkbootup.ecommerce.cart.adapter.redis;

import com.jkbootup.ecommerce.cart.application.port.ProcessedEvents;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** {@code SET cart:seen:<eventId> 1 NX EX 14d}: only the first delivery of an event is applied. */
public class RedisProcessedEvents implements ProcessedEvents {

    private final StringRedisTemplate redis;

    public RedisProcessedEvents(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean firstTime(String eventId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("cart:seen:" + eventId, "1", Duration.ofDays(14)));
    }
}
