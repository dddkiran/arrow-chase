package com.arrowchase.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BetRateLimiterTest {

    private static final long MILLIS = 1_000_000;

    private final AtomicLong now = new AtomicLong();

    @Test
    void aPlayerHasOneLimitHoweverManyConnectionsTheyOpen() {
        BetRateLimiter limiter = new BetRateLimiter(3, 1, 100, now::get);

        // The limiter only sees the player id, so bets from a second tab or socket draw on the same bucket.
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void playersDoNotShareALimit() {
        BetRateLimiter limiter = new BetRateLimiter(1, 1, 100, now::get);

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
        assertThat(limiter.tryAcquire("bob")).as("alice's flood must not lock bob out").isTrue();
    }

    @Test
    void sweepDropsOnlyIdleLimitersSoNobodyGetsAFreshBurst() {
        BetRateLimiter limiter = new BetRateLimiter(2, 1, 2, now::get);
        limiter.tryAcquire("idle");
        now.addAndGet(5_000 * MILLIS);          // "idle" refills completely
        limiter.tryAcquire("busy");
        limiter.tryAcquire("busy");             // "busy" is now empty

        limiter.tryAcquire("newcomer");         // the map is at the threshold, so this sweeps

        assertThat(limiter.trackedPlayers()).as("idle dropped, busy kept, newcomer added").isEqualTo(2);
        assertThat(limiter.tryAcquire("busy")).as("busy must still be limited after the sweep").isFalse();
    }

    @Test
    void anIdlePlayerIsDroppedWithoutLosingAnything() {
        BetRateLimiter limiter = new BetRateLimiter(2, 1, 1, now::get);
        limiter.tryAcquire("alice");
        now.addAndGet(5_000 * MILLIS);

        limiter.tryAcquire("bob");              // sweeps alice's full bucket

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).as("a recreated bucket still holds only the burst").isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }
}
