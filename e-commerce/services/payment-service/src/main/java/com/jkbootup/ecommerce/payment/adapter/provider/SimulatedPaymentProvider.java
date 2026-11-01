package com.jkbootup.ecommerce.payment.adapter.provider;

import com.jkbootup.ecommerce.payment.application.port.PaymentProvider;
import com.jkbootup.ecommerce.payment.domain.Money;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A stand-in for a real card processor, with the same idempotency semantics. Test tokens:
 * <ul>
 *   <li>{@code tok_ok}: approved</li>
 *   <li>{@code tok_decline}: declined (insufficient funds)</li>
 *   <li>{@code tok_timeout}: approved, but only after the timeout — exercises the unknown-outcome path</li>
 * </ul>
 * Calls are made with a deadline, exactly as a real HTTP client to the provider would be.
 */
public class SimulatedPaymentProvider implements PaymentProvider {

    private record Auth(String ref, String status) {}

    private static final java.util.concurrent.ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Auth> ledger = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Duration slowDelay;

    public SimulatedPaymentProvider(Duration timeout, Duration slowDelay) {
        this.timeout = timeout;
        this.slowDelay = slowDelay;
    }

    @Override
    public Outcome authorize(String key, Money amount, String token) {
        return withDeadline(() -> {
            Auth existing = ledger.get(key);
            if (existing != null) return existing.status().equals("DECLINED") ? new Outcome.Declined("INSUFFICIENT_FUNDS") : new Outcome.Approved(existing.ref());
            switch (token) {
                case "tok_decline" -> {
                    ledger.put(key, new Auth(null, "DECLINED"));
                    return new Outcome.Declined("INSUFFICIENT_FUNDS");
                }
                case "tok_timeout" -> sleep(slowDelay);
                default -> { }
            }
            Auth auth = ledger.computeIfAbsent(key, k -> new Auth("sim_" + UUID.randomUUID(), "AUTHORIZED"));
            return new Outcome.Approved(auth.ref());
        });
    }

    @Override
    public Outcome capture(String key, String ref) {
        return transition(key, "AUTHORIZED", "CAPTURED");
    }

    @Override
    public Outcome voidAuthorization(String key, String ref) {
        return transition(key, "AUTHORIZED", "VOIDED");
    }

    @Override
    public Outcome refund(String key, String ref, Money amount) {
        return transition(key, "CAPTURED", "REFUNDED");
    }

    @Override
    public Optional<Outcome> lookup(String key) {
        Auth a = ledger.get(key);
        if (a == null) return Optional.empty();
        return Optional.of(a.status().equals("DECLINED") ? new Outcome.Declined("INSUFFICIENT_FUNDS") : new Outcome.Approved(a.ref()));
    }

    /** For tests and demos: what the provider holds for a key. */
    public Optional<String> statusOf(String key) {
        return Optional.ofNullable(ledger.get(key)).map(Auth::status);
    }

    private Outcome transition(String key, String from, String to) {
        Auth a = ledger.get(key);
        if (a == null) return new Outcome.Declined("NOT_FOUND");
        if (a.status().equals(to)) return new Outcome.Approved(a.ref());                    // idempotent repeat
        if (!a.status().equals(from)) return new Outcome.Declined("INVALID_STATE_" + a.status());
        ledger.put(key, new Auth(a.ref(), to));
        return new Outcome.Approved(a.ref());
    }

    private Outcome withDeadline(java.util.concurrent.Callable<Outcome> call) {
        var future = CALLS.submit(call);          // not closed per call: closing would wait for the slow call
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Outcome.Unknown("PROVIDER_TIMEOUT");      // the call keeps running and may still succeed
        } catch (Exception e) {
            return new Outcome.Unknown("PROVIDER_ERROR");
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
