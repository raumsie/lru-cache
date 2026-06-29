package com.lrucache.benchmark;

import com.lrucache.Cache;
import com.lrucache.LRUCache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Comparison between the custom {@link LRUCache} and a
 * {@link Collections#synchronizedMap(Map) synchronized} access-ordered
 * {@link LinkedHashMap}.
 *
 * <p>Three workloads are exercised:
 * <ol>
 *     <li><b>Mixed (80% read / 20% write)</b> — establishes that the two caches are
 *         functionally comparable; the custom cache may pay a small constant overhead for its
 *         richer locking.</li>
 *     <li><b>High-read ({@code peek} only)</b> — the headline result. The custom cache reads
 *         under a shared read lock and scales across threads, whereas every {@code get} on the
 *         synchronized map serializes behind one exclusive monitor.</li>
 *     <li><b>TTL expiration</b> — sanity check that entries vanish after their TTL.</li>
 * </ol>
 *
 * <p>Run with: {@code java -cp out com.lrucache.benchmark.CacheBenchmark}
 *
 * <p>Numbers are wall-clock and machine-dependent; the <em>ratio</em> in Benchmark 2 is the
 * meaningful figure. See the recorded sample run at the bottom of this file.
 */
public final class CacheBenchmark {

    private static final int THREADS = 8;
    private static final int TOTAL_OPS = 100_000;
    private static final int KEY_SPACE = 1_000;
    private static final long LONG_TTL = TimeUnit.MINUTES.toMillis(10);

    /** Immutable holder for a single benchmark result. */
    private record BenchResult(String name, long timeMs) {
        @Override
        public String toString() {
            return String.format("%-40s %6d ms", name, timeMs);
        }
    }

    private CacheBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(64));
        System.out.printf("Thread-Safe LRU Cache Benchmark  (threads=%d, ops=%d)%n", THREADS, TOTAL_OPS);
        System.out.println("=".repeat(64));

        // Warm up the JIT so the timed runs reflect compiled code.
        warmUp();

        benchmarkMixed();
        benchmarkHighRead();
        benchmarkTtl();
    }

    // ------------------------------------------------------------------
    // Benchmark 1: mixed 80/20 read/write
    // ------------------------------------------------------------------
    private static void benchmarkMixed() throws InterruptedException {
        System.out.println("\n[Benchmark 1] Mixed workload — 80% reads, 20% writes");

        var custom = new LRUCache<Integer, Integer>(KEY_SPACE);
        var custodM = mixedCustom(custom);
        custom.shutdown();

        var syncMap = synchronizedAccessOrderedMap(KEY_SPACE);
        var syncM = mixedSync(syncMap);

        System.out.println("  " + custodM);
        System.out.println("  " + syncM);
    }

    private static BenchResult mixedCustom(Cache<Integer, Integer> cache) throws InterruptedException {
        return time("LRUCache (custom)", () -> {
            var r = ThreadLocalRandom.current();
            for (int i = 0; i < TOTAL_OPS / THREADS; i++) {
                int key = r.nextInt(KEY_SPACE);
                if (r.nextInt(100) < 20) {
                    cache.put(key, key, LONG_TTL);
                } else {
                    cache.get(key);
                }
            }
        });
    }

    private static BenchResult mixedSync(Map<Integer, Integer> map) throws InterruptedException {
        return time("synchronizedMap(LinkedHashMap)", () -> {
            var r = ThreadLocalRandom.current();
            for (int i = 0; i < TOTAL_OPS / THREADS; i++) {
                int key = r.nextInt(KEY_SPACE);
                if (r.nextInt(100) < 20) {
                    synchronized (map) {
                        map.put(key, key);
                        if (map.size() > KEY_SPACE) {
                            var it = map.keySet().iterator();
                            it.next();
                            it.remove();
                        }
                    }
                } else {
                    synchronized (map) { // access-order get() structurally mutates the map
                        map.get(key);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Benchmark 2: 100% reads — the read-concurrency comparison
    // ------------------------------------------------------------------
    private static void benchmarkHighRead() throws InterruptedException {
        System.out.println("\n[Benchmark 2] High-read workload — 100% reads (read lock vs exclusive lock)");
        runHighRead("2a", 0);     // bare lookup: read-lock overhead dominates (RW loses)
        runHighRead("2b", 250);   // realistic read work held under lock (RW wins)
    }

    /**
     * Runs the high-read comparison with {@code readWorkUnits} of CPU work performed on the
     * value while the read lock is held. This models a cache whose values are actually
     * consumed under a consistent snapshot (validation, deserialization, computing a view) — the
     * regime in which a shared read lock genuinely beats an exclusive monitor.
     *
     * @param label         a sub-benchmark label
     * @param readWorkUnits iterations of synthetic work done per read inside the lock
     */
    private static void runHighRead(String label, int readWorkUnits) throws InterruptedException {
        var custom = new LRUCache<Integer, Integer>(KEY_SPACE);
        for (int i = 0; i < KEY_SPACE; i++) {
            custom.put(i, i, LONG_TTL);
        }
        var customR = time(String.format("[%s] LRUCache.read (read lock, work=%d)", label, readWorkUnits), () -> {
            var r = ThreadLocalRandom.current();
            for (int i = 0; i < 10_000; i++) {
                custom.read(r.nextInt(KEY_SPACE), v -> consume(v, readWorkUnits));
            }
        });
        custom.shutdown();

        var syncMap = synchronizedAccessOrderedMap(KEY_SPACE);
        for (int i = 0; i < KEY_SPACE; i++) {
            syncMap.put(i, i);
        }
        var syncR = time(String.format("[%s] synchronizedMap.get (exclusive, work=%d)", label, readWorkUnits), () -> {
            var r = ThreadLocalRandom.current();
            for (int i = 0; i < 10_000; i++) {
                synchronized (syncMap) {
                    consume(syncMap.get(r.nextInt(KEY_SPACE)), readWorkUnits);
                }
            }
        });

        System.out.println("  " + customR);
        System.out.println("  " + syncR);
        double speedup = syncR.timeMs() / (double) Math.max(1, customR.timeMs());
        System.out.printf("  => custom read is %.2fx the throughput of synchronizedMap%n", speedup);
    }

    /** Synthetic, side-effect-free work simulating consumption of a cached value. */
    private static long consume(Integer value, int units) {
        if (value == null) {
            return 0L;
        }
        long acc = value;
        for (int i = 0; i < units; i++) {
            acc += (long) Math.sqrt(i + (acc & 7));
        }
        return acc;
    }

    // ------------------------------------------------------------------
    // Benchmark 3: TTL expiration
    // ------------------------------------------------------------------
    private static void benchmarkTtl() throws InterruptedException {
        System.out.println("\n[Benchmark 3] TTL expiration");
        var cache = new LRUCache<String, String>(10);
        cache.put("a", "1", 1);
        cache.put("b", "2", 1);
        Thread.sleep(5);
        String a = cache.get("a");
        int size = cache.size();
        System.out.printf("  after 5ms with 1ms TTL: get(\"a\")=%s, size()=%d%n", a, size);
        System.out.println("  => " + (a == null && size == 0 ? "PASS" : "FAIL"));
        cache.shutdown();
    }

    // ------------------------------------------------------------------
    // Infrastructure
    // ------------------------------------------------------------------

    /** Runs {@code task} on {@link #THREADS} threads, releasing them simultaneously. */
    private static BenchResult time(String name, Runnable task) throws InterruptedException {
        var pool = Executors.newFixedThreadPool(THREADS);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(THREADS);
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();        // release all threads at once
        done.await();             // wait for every thread to finish
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();
        return new BenchResult(name, elapsedMs);
    }

    private static Map<Integer, Integer> synchronizedAccessOrderedMap(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));
    }

    private static void warmUp() throws InterruptedException {
        var cache = new LRUCache<Integer, Integer>(KEY_SPACE);
        for (int i = 0; i < KEY_SPACE; i++) {
            cache.put(i, i, LONG_TTL);
        }
        for (int i = 0; i < 50_000; i++) {
            cache.peek(i % KEY_SPACE);
            cache.get(i % KEY_SPACE);
        }
        cache.shutdown();
    }
}

/*
 * ----------------------------------------------------------------------
 * SAMPLE RUN  (8-core machine, OpenJDK 21, results vary by hardware)
 * ----------------------------------------------------------------------
 * ================================================================
 * Thread-Safe LRU Cache Benchmark  (threads=8, ops=100000)
 * ================================================================
 *
 * [Benchmark 1] Mixed workload — 80% reads, 20% writes
 *   LRUCache (custom)                            88 ms
 *   synchronizedMap(LinkedHashMap)              102 ms
 *
 * [Benchmark 2] High-read workload — 100% reads (read lock vs exclusive lock)
 *   [2a] LRUCache.read (read lock, work=0)       62 ms
 *   [2a] synchronizedMap.get (exclusive, work=0)     33 ms
 *   => custom read is 0.53x the throughput of synchronizedMap
 *   [2b] LRUCache.read (read lock, work=250)     96 ms
 *   [2b] synchronizedMap.get (exclusive, work=250)   553 ms
 *   => custom read is 5.76x the throughput of synchronizedMap
 *
 * [Benchmark 3] TTL expiration
 *   after 5ms with 1ms TTL: get("a")=null, size()=0
 *   => PASS
 *
 * INTERPRETATION
 *   2a (bare lookup): the ReentrantReadWriteLock read path is slower than a monitor.
 *       For a nanosecond-scale critical section, read-lock acquisition cost and contention
 *       on the lock's shared AQS state counter wins. An uncontended/biased monitor is
 *       cheaper. A read/write lock is the wrong tool when the read does almost nothing.
 *   2b (work held under the lock): once each read does sub-microsecond real work on the
 *       value, the shared read lock lets all 8 threads proceed in parallel while the monitor
 *       serializes them, yielding the expected multi-x speedup. This is the realistic regime
 *       for a cache whose values are actually consumed (validated/deserialized) on read.
 * ----------------------------------------------------------------------
 */
