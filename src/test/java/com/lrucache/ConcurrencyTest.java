package com.lrucache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency stress tests for {@link LRUCache}. These do not assert on exact contents
 * (which are nondeterministic under contention) but verify the structural invariants hold and
 * that no exceptions escape from concurrent mutation.
 */
class ConcurrencyTest {

    private static final long LONG_TTL = TimeUnit.MINUTES.toMillis(5);

    @Test
    @Timeout(30)
    @DisplayName("50 threads of random put/get keep size within capacity and throw nothing")
    void stressPutGet() throws InterruptedException {
        final int capacity = 100;
        final int threads = 50;
        final int opsPerThread = 5_000;
        final int keySpace = 500;

        var cache = new LRUCache<Integer, Integer>(capacity);
        var error = new AtomicReference<Throwable>();
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        var r = ThreadLocalRandom.current();
                        for (int i = 0; i < opsPerThread; i++) {
                            int key = r.nextInt(keySpace);
                            switch (r.nextInt(3)) {
                                case 0 -> cache.put(key, key, LONG_TTL);
                                case 1 -> cache.get(key);
                                default -> cache.peek(key);
                            }
                        }
                    } catch (Throwable th) {
                        error.compareAndSet(null, th);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(25, TimeUnit.SECONDS), "threads did not finish in time");

            assertNull(error.get(), () -> "unexpected exception: " + error.get());
            assertTrue(cache.size() <= capacity,
                    () -> "size " + cache.size() + " exceeded capacity " + capacity);
        } finally {
            pool.shutdownNow();
            cache.shutdown();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("concurrent peeks proceed without blocking each other or corrupting state")
    void stressConcurrentPeek() throws InterruptedException {
        final int capacity = 1_000;
        final int threads = 16;
        final int opsPerThread = 20_000;

        var cache = new LRUCache<Integer, Integer>(capacity);
        for (int i = 0; i < capacity; i++) {
            cache.put(i, i, LONG_TTL);
        }

        var error = new AtomicReference<Throwable>();
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        var r = ThreadLocalRandom.current();
                        for (int i = 0; i < opsPerThread; i++) {
                            cache.peek(r.nextInt(capacity));
                        }
                    } catch (Throwable th) {
                        error.compareAndSet(null, th);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(25, TimeUnit.SECONDS), "threads did not finish in time");
            assertNull(error.get(), () -> "unexpected exception: " + error.get());
            assertTrue(cache.size() <= capacity);
        } finally {
            pool.shutdownNow();
            cache.shutdown();
        }
    }
}
