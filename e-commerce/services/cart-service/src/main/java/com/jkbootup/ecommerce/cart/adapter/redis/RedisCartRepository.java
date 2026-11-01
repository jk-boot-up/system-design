package com.jkbootup.ecommerce.cart.adapter.redis;

import com.jkbootup.ecommerce.cart.application.port.CartRepository;
import com.jkbootup.ecommerce.cart.domain.Cart;
import com.jkbootup.ecommerce.cart.domain.CartOwner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A cart is one Redis hash {@code cart:<owner>} of SKU → quantity, expiring 30 days after the last change. */
public class RedisCartRepository implements CartRepository {

    /** HSET or HDEL plus EXPIRE in one atomic script, so a line never exists without an expiry. */
    private static final RedisScript<Long> SET_LINE = RedisScript.of("""
            if tonumber(ARGV[2]) == 0 then redis.call('HDEL', KEYS[1], ARGV[1])
            else redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) end
            if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            return 1""", Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisCartRepository(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Cart load(CartOwner owner) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(owner));
        Map<String, Integer> items = new LinkedHashMap<>();
        new TreeMap<>(raw).forEach((k, v) -> items.put((String) k, Integer.parseInt((String) v)));   // stable order
        return new Cart(owner, items);
    }

    @Override
    public void setLine(CartOwner owner, String sku, int quantity) {
        redis.execute(SET_LINE, List.of(key(owner)), sku, String.valueOf(quantity), String.valueOf(ttl.toSeconds()));
    }

    @Override
    public void replace(Cart cart) {
        String key = key(cart.owner());
        redis.delete(key);
        if (cart.isEmpty()) return;
        Map<String, String> values = new LinkedHashMap<>();
        cart.items().forEach((sku, q) -> values.put(sku, String.valueOf(q)));
        redis.opsForHash().putAll(key, values);
        redis.expire(key, ttl);
    }

    @Override
    public void delete(CartOwner owner) {
        redis.delete(key(owner));
    }

    private static String key(CartOwner owner) {
        return "cart:" + owner.key();
    }
}
