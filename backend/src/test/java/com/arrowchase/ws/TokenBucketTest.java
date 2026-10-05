package com.arrowchase.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private static final long MILLIS = 1_000_000;

    private final AtomicLong now = new AtomicLong();

    @Test
    void allowsABurstUpToCapacityThenRefuses() {
        TokenBucket bucket = new TokenBucket(3, 1, now::get);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    void refillsAtTheConfiguredRate() {
        TokenBucket bucket = new TokenBucket(1, 5, now::get);
        assertThat(bucket.tryAcquire()).isTrue();

        now.addAndGet(199 * MILLIS);
        assertThat(bucket.tryAcquire()).as("one token takes 200 ms at 5/s").isFalse();

        now.addAndGet(MILLIS);
        assertThat(bucket.tryAcquire()).isTrue();
    }

    @Test
    void neverBanksMoreThanCapacity() {
        TokenBucket bucket = new TokenBucket(2, 5, now::get);
        now.addAndGet(60_000 * MILLIS);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).as("a long idle spell must not allow a huge burst").isFalse();
    }

    @Test
    void sustainedFloodIsHeldToTheRefillRate() {
        TokenBucket bucket = new TokenBucket(10, 5, now::get);
        int accepted = 0;
        for (int i = 0; i < 10_000; i++) {       // 10,000 attempts over 10 seconds
            if (bucket.tryAcquire()) {
                accepted++;
            }
            now.addAndGet(MILLIS);
        }
        assertThat(accepted).isBetween(59, 60);  // the burst of 10 plus 5/s for 10 s
    }

    @Test
    void isFullOnlyOnceCompletelyRefilled() {
        TokenBucket bucket = new TokenBucket(2, 5, now::get);
        assertThat(bucket.isFull()).isTrue();

        bucket.tryAcquire();
        assertThat(bucket.isFull()).isFalse();

        now.addAndGet(199 * MILLIS);
        assertThat(bucket.isFull()).isFalse();
        now.addAndGet(MILLIS);
        assertThat(bucket.isFull()).isTrue();
    }

    @Test
    void rejectsNonsenseLimits() {
        assertThatThrownBy(() -> new TokenBucket(0, 5, now::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(5, 0, now::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
