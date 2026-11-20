package com.jkbootup.ecommerce.catalog.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightTest {

    @Test
    void fiftyConcurrentMissesForOneKeyRunTheLoaderOnce() throws Exception {
        SingleFlight<String, String> flight = new SingleFlight<>();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                results.add(pool.submit(() -> flight.load("SKU-1", () -> {
                    loads.incrementAndGet();
                    await(release);
                    return "product";
                })));
            }
            while (loads.get() == 0) Thread.onSpinWait();
            Thread.sleep(200);                    // let the other 49 arrive while the first load is still running
            release.countDown();
            for (Future<String> r : results) assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo("product");
        }
        assertThat(loads).hasValue(1);
        assertThat(flight.inFlight()).isZero();
    }

    @Test
    void everyWaiterGetsTheLoadersExceptionAndTheNextMissLoadsAgain() throws Exception {
        SingleFlight<String, String> flight = new SingleFlight<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> first = pool.submit(() -> flight.load("k", () -> {
                started.countDown();
                await(release);
                throw new IllegalStateException("database down");
            }));
            await(started);
            Future<String> second = pool.submit(() -> flight.load("k", () -> "never runs"));
            Thread.sleep(200);
            release.countDown();
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("database down");
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("database down");
        }
        assertThat(flight.load("k", () -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void differentKeysDoNotWaitForEachOther() {
        SingleFlight<String, String> flight = new SingleFlight<>();
        String outer = flight.load("a", () -> "a+" + flight.load("b", () -> "b"));
        assertThat(outer).isEqualTo("a+b");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
