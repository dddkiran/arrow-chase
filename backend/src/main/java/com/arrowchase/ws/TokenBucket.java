package com.arrowchase.ws;

import java.util.function.LongSupplier;

/**
 * Classic token bucket: holds up to {@code capacity} tokens, refills at {@code tokensPerSecond}, and each request
 * spends one. Lets a player click quickly in short bursts while capping their sustained rate.
 *
 * <p>Synchronized because nothing guarantees which gateway thread delivers a session's next frame; the critical
 * section is a few arithmetic operations, so contention is irrelevant.
 */
final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final double capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(int capacity, double tokensPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || tokensPerSecond <= 0) {
            throw new IllegalArgumentException("capacity must be >= 1 and tokensPerSecond > 0");
        }
        this.capacity = capacity;
        this.tokensPerNano = tokensPerSecond / NANOS_PER_SECOND;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    /** Spends one token if there is one. */
    synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * tokensPerNano);
        lastRefillNanos = now;
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }
}
