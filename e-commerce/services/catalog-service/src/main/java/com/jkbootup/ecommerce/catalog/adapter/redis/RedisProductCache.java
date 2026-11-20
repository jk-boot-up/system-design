package com.jkbootup.ecommerce.catalog.adapter.redis;

import com.jkbootup.ecommerce.catalog.application.port.ProductCache;
import com.jkbootup.ecommerce.catalog.domain.Money;
import com.jkbootup.ecommerce.catalog.domain.Product;
import com.jkbootup.ecommerce.catalog.domain.ProductDetails;
import com.jkbootup.ecommerce.catalog.domain.ProductImage;
import com.jkbootup.ecommerce.catalog.domain.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Cache-aside product entries in Redis ({@code product:<id>}, JSON). Each entry expires after the configured time
 * (10 minutes) plus or minus up to 10 %, chosen at random, so entries written together (after a restart or an import) do
 * not all expire in the same second. Redis is an optimisation, not a dependency: any Redis error is logged and treated as
 * a miss, so reads fall through to PostgreSQL.
 */
public class RedisProductCache implements ProductCache {

    private static final Logger log = LoggerFactory.getLogger(RedisProductCache.class);

    /** The cached shape; kept separate from the domain so the domain stays free of serialisation concerns. */
    record Entry(UUID id, String sku, String name, String description, UUID categoryId, String brand, String amount,
                 String currency, Map<String, Object> attributes, String status, List<ProductImage> images, long version,
                 Instant createdAt, Instant updatedAt) {}

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    /** Each expiry is the base time scaled by a random factor in [1 - JITTER, 1 + JITTER). */
    static final double JITTER = 0.10;

    private final Duration ttl;
    private final DoubleSupplier random;

    public RedisProductCache(StringRedisTemplate redis, JsonMapper json, Duration ttl) {
        this(redis, json, ttl, () -> ThreadLocalRandom.current().nextDouble());
    }

    RedisProductCache(StringRedisTemplate redis, JsonMapper json, Duration ttl, DoubleSupplier random) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.random = random;
    }

    /** The expiry for one entry: random between ttl × 0.9 and ttl × 1.1, to the millisecond. */
    Duration expiry() {
        double factor = 1 - JITTER + 2 * JITTER * random.getAsDouble();
        return Duration.ofMillis(Math.round(ttl.toMillis() * factor));
    }

    static String key(UUID id) {
        return "product:" + id;
    }

    @Override
    public Optional<Product> get(UUID id) {
        try {
            String value = redis.opsForValue().get(key(id));
            if (value == null) return Optional.empty();
            Entry e = json.readValue(value, Entry.class);
            return Optional.of(new Product(e.id(), e.sku(), new ProductDetails(e.name(), e.description(), e.categoryId(), e.brand(),
                    Money.of(e.amount(), e.currency()), e.attributes(), ProductStatus.valueOf(e.status())), e.images(), e.version(),
                    e.createdAt(), e.updatedAt()));
        } catch (RuntimeException ex) {
            log.warn("Product cache read failed for {}: {}", id, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(Product p) {
        try {
            ProductDetails d = p.details();
            Entry e = new Entry(p.id(), p.sku(), d.name(), d.description(), d.categoryId(), d.brand(),
                    d.price().amount().toPlainString(), d.price().currency().getCurrencyCode(), d.attributes(), d.status().name(),
                    p.images(), p.version(), p.createdAt(), p.updatedAt());
            redis.opsForValue().set(key(p.id()), json.writeValueAsString(e), expiry());
        } catch (RuntimeException ex) {
            log.warn("Product cache write failed for {}: {}", p.id(), ex.getMessage());
        }
    }

    @Override
    public void evict(UUID id) {
        try {
            redis.delete(key(id));
        } catch (RuntimeException ex) {
            // The entry expires on its own within the TTL; that bounds how long a stale copy can be served.
            log.warn("Product cache eviction failed for {}: {}", id, ex.getMessage());
        }
    }
}
