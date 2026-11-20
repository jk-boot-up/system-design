package com.jkbootup.ecommerce.catalog.application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Collapses concurrent loads of the same key into one: the first caller runs the loader, every caller that arrives
 * while it runs waits for that result (or that exception) instead of running its own. Once the load finishes the key is
 * forgotten, so the next miss loads again. This bounds a cache stampede on one hot key to one database read per pod.
 */
final class SingleFlight<K, V> {

    private final ConcurrentMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    V load(K key, Supplier<V> loader) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
        if (running != null) return join(running);
        try {
            V value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** Callers that arrive after this start a new load instead of waiting for one that may read old data. */
    void forget(K key) {
        inFlight.remove(key);
    }

    int inFlight() {
        return inFlight.size();
    }

    private static <V> V join(CompletableFuture<V> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }
}
