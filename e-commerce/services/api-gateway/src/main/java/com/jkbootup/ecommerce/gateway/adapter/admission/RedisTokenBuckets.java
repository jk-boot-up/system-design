package com.jkbootup.ecommerce.gateway.adapter.admission;

import com.jkbootup.ecommerce.gateway.domain.Decision;
import com.jkbootup.ecommerce.gateway.domain.LocalTokenBuckets;
import com.jkbootup.ecommerce.gateway.domain.RatePolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Token buckets shared by every gateway replica, in Redis. Refill and take happen in one Lua script, so concurrent
 * requests on different replicas can never both spend the last token. If Redis does not answer within the timeout,
 * the request is decided by a local bucket with a scaled-down policy instead: the gateway neither fails closed (an
 * outage for everyone) nor fails open (no protection during a flash sale).
 */
public class RedisTokenBuckets {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBuckets.class);

    /** Same algorithm as LocalTokenBuckets; time comes from Redis so every replica uses one clock. */
    @SuppressWarnings("unchecked")
    static final RedisScript<List<Long>> SCRIPT = RedisScript.of("""
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) + tonumber(t[2]) / 1000000
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
            local tokens = tonumber(state[1]) or burst
            local at = tonumber(state[2]) or now
            tokens = math.min(burst, tokens + math.max(0, now - at) * rate)
            local allowed = 0
            if tokens >= 1 then tokens = tokens - 1; allowed = 1 end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(now))
            redis.call('EXPIRE', KEYS[1], math.ceil(burst / rate * 2) + 1)
            return { allowed, math.floor(tokens * 1000) }""", (Class<List<Long>>) (Class<?>) List.class);

    private final ReactiveStringRedisTemplate redis;
    private final LocalTokenBuckets local;
    private final double degradedFactor;
    private final Duration timeout;
    private final Counter degraded;

    public RedisTokenBuckets(ReactiveStringRedisTemplate redis, LocalTokenBuckets local, double degradedFactor, Duration timeout,
                             MeterRegistry meters) {
        this.redis = redis;
        this.local = local;
        this.degradedFactor = degradedFactor;
        this.timeout = timeout;
        this.degraded = Counter.builder("gateway.ratelimit.degraded")
                .description("Requests decided by a local bucket because Redis did not answer").register(meters);
    }

    public Mono<Decision> take(RatePolicy policy, String key) {
        return redis.execute(SCRIPT, List.of("rate:" + policy.name() + ":" + key),
                        List.of(Double.toString(policy.ratePerSecond()), Integer.toString(policy.burst())))
                .next()
                .map(r -> {
                    boolean allowed = r.get(0) == 1L;
                    double left = r.get(1) / 1000.0;
                    return new Decision(allowed, (long) Math.floor(left), allowed ? 0 : policy.retryAfterSeconds(left), true);
                })
                .timeout(timeout)
                .onErrorResume(e -> {
                    degraded.increment();
                    log.debug("Rate limit store unavailable ({}); deciding locally", e.toString());
                    return Mono.just(local.take(policy.scaled(degradedFactor), key));
                });
    }
}
