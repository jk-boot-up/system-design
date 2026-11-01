package com.jkbootup.ecommerce.cart.adapter.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jkbootup.ecommerce.cart.application.port.PricingPort;
import com.jkbootup.ecommerce.cart.domain.Money;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prices from catalog-service, cached for 30 s per SKU in memory (Caffeine). Only missing SKUs are fetched, in one
 * call. When catalog-service is unavailable the result is empty and the cart is shown with prices pending.
 */
public class CatalogPricingClient implements PricingPort {

    private final RestClient http;
    private final ResilientCall resilient = new ResilientCall("catalog");
    private final Cache<String, ProductPrice> cache;

    public CatalogPricingClient(RestClient http, Duration cacheFor) {
        this.http = http;
        this.cache = Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(cacheFor).build();
    }

    @Override
    public Optional<Map<String, ProductPrice>> prices(Collection<String> skus) {
        Map<String, ProductPrice> result = new HashMap<>(cache.getAllPresent(skus));
        List<String> missing = skus.stream().filter(s -> !result.containsKey(s)).toList();
        if (missing.isEmpty()) return Optional.of(result);
        Optional<Map<String, ProductPrice>> fetched = resilient.run(true, () -> Optional.of(fetch(missing)), f -> Optional.empty());
        if (fetched.isEmpty()) return Optional.empty();
        cache.putAll(fetched.get());
        result.putAll(fetched.get());
        return Optional.of(result);
    }

    private Map<String, ProductPrice> fetch(List<String> skus) {
        JsonNode body = http.post().uri("/internal/products/prices").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("skus", skus)).retrieve().body(JsonNode.class);
        Map<String, ProductPrice> out = new HashMap<>();
        for (JsonNode item : body.path("items")) {
            JsonNode price = item.path("price");
            out.put(item.path("sku").asString(), new ProductPrice(item.path("sku").asString(), item.path("name").asString(),
                    Money.of(price.path("amount").asString(), price.path("currency").asString()),
                    "ACTIVE".equals(item.path("status").asString())));
        }
        return out;
    }
}
