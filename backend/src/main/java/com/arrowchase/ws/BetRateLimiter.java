package com.arrowchase.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Bet rate limit per player, shared by all of that player's connections, so opening more tabs or sockets does not
 * buy more bets, and neither does reconnecting.
 *
 * <p>Memory stays bounded without a timer: once the map passes {@code sweepThreshold}, full buckets are dropped.
 * A full bucket behaves exactly like a new one, so dropping it changes nobody's limit. (A request racing the sweep
 * can at worst spend a token from a bucket that was full anyway.)
 */
final class BetRateLimiter {

    private final int burst;
    private final double betsPerSecond;
    private final int sweepThreshold;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    BetRateLimiter(int burst, double betsPerSecond, int sweepThreshold, LongSupplier nanoClock) {
        this.burst = burst;
        this.betsPerSecond = betsPerSecond;
        this.sweepThreshold = sweepThreshold;
        this.nanoClock = nanoClock;
    }

    /** Spends one of the player's tokens if they have one. */
    boolean tryAcquire(String playerId) {
        TokenBucket bucket = buckets.get(playerId);
        if (bucket == null) {
            if (buckets.size() >= sweepThreshold) {
                buckets.values().removeIf(TokenBucket::isFull);
            }
            bucket = buckets.computeIfAbsent(playerId, id -> new TokenBucket(burst, betsPerSecond, nanoClock));
        }
        return bucket.tryAcquire();
    }

    int trackedPlayers() {
        return buckets.size();
    }
}
