package com.lrucache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A bounded, thread-safe LRU cache with per-entry TTL.
 *
 * <h2>Design</h2>
 * <p>The cache combines two structures:
 * <ul>
 *     <li>a {@link ConcurrentHashMap} from key to {@link Node} for O(1) lookup, and</li>
 *     <li>a custom intrusive doubly-linked list (with sentinel head/tail) that orders nodes
 *         by recency — {@code head.next} is the most-recently-used (MRU) node and
 *         {@code tail.prev} is the least-recently-used (LRU) node.</li>
 * </ul>
 * Java's {@link java.util.LinkedHashMap} is deliberately not used; the list is implemented by
 * hand to demonstrate the underlying data structure and to give precise control over locking.
 *
 * <h2>Concurrency model</h2>
 * <p>The map handles its own internal concurrency, but the <em>logical structure</em> — the
 * linked-list ordering and the size/eviction invariants — is guarded by a single
 * {@link ReentrantReadWriteLock}:
 * <ul>
 *     <li>{@link #get(Object)} and {@link #put(Object, Object, long)} mutate list order and
 *         therefore take the <strong>write</strong> lock (exclusive).</li>
 *     <li>{@link #peek(Object)} performs no mutation and takes the <strong>read</strong> lock,
 *         so any number of {@code peek} calls run concurrently.</li>
 * </ul>
 * Under a read-heavy workload this lets {@code peek} scale across cores, in contrast to a
 * {@code Collections.synchronizedMap} which serializes every operation behind one monitor.
 *
 * <h2>TTL and reclamation</h2>
 * <p>Each entry carries an absolute expiry timestamp. Expired entries are reclaimed:
 * <ul>
 *     <li><em>lazily</em>, when touched by {@code get} or {@code put}, and</li>
 *     <li><em>proactively</em>, by a {@link ScheduledExecutorService} that sweeps every five
 *         seconds to bound memory growth from keys that are never read again.</li>
 * </ul>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class LRUCache<K, V> implements Cache<K, V> {

    /** Default period, in seconds, between background eviction sweeps. */
    private static final long CLEANUP_PERIOD_SECONDS = 5L;

    private final int capacity;

    /** O(1) key lookup. The map itself is thread-safe; list order is lock-guarded. */
    private final ConcurrentHashMap<K, Node<K, V>> map;

    /** Sentinel nodes bracketing the recency list; they never hold real entries. */
    private final Node<K, V> head;
    private final Node<K, V> tail;

    /** Guards the doubly-linked list ordering and the eviction/size invariants. */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    private final ScheduledExecutorService cleaner;

    /**
     * Creates a cache with the given maximum capacity and a background cleaner that sweeps
     * expired entries every {@value #CLEANUP_PERIOD_SECONDS} seconds.
     *
     * @param capacity the maximum number of entries; must be positive
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.capacity = capacity;
        // Size the map for the known capacity to limit resizing under load.
        this.map = new ConcurrentHashMap<>(Math.max(16, capacity * 4 / 3 + 1));

        this.head = new Node<>(null, null, Long.MAX_VALUE);
        this.tail = new Node<>(null, null, Long.MAX_VALUE);
        this.head.next = this.tail;
        this.tail.prev = this.head;

        this.cleaner = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        this.cleaner.scheduleAtFixedRate(
                this::evictExpired,
                CLEANUP_PERIOD_SECONDS,
                CLEANUP_PERIOD_SECONDS,
                TimeUnit.SECONDS);
    }

    /** {@inheritDoc} */
    @Override
    public V get(K key) {
        if (key == null) {
            return null;
        }
        writeLock.lock(); // Write lock: promoting to head mutates list order.
        try {
            var node = map.get(key);
            if (node == null) {
                return null;
            }
            if (isExpired(node)) {
                unlink(node);
                map.remove(key, node);
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public V peek(K key) {
        if (key == null) {
            return null;
        }
        readLock.lock(); // Read lock: pure read, no list mutation, fully concurrent.
        try {
            var node = map.get(key);
            if (node == null || isExpired(node)) {
                return null;
            }
            return node.value;
        } finally {
            readLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public <R> R read(K key, java.util.function.Function<? super V, ? extends R> reader) {
        if (key == null) {
            return null;
        }
        readLock.lock(); // Read lock held across reader: consistent snapshot, fully concurrent.
        try {
            var node = map.get(key);
            if (node == null || isExpired(node)) {
                return null;
            }
            return reader.apply(node.value);
        } finally {
            readLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void put(K key, V value, long ttlMillis) {
        if (key == null) {
            throw new NullPointerException("key must not be null");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive, was " + ttlMillis);
        }
        var expiry = System.currentTimeMillis() + ttlMillis;

        writeLock.lock();
        try {
            var existing = map.get(key);
            if (existing != null) {
                // Update in place and promote.
                existing.value = value;
                existing.expiryTimestamp = expiry;
                moveToHead(existing);
            } else {
                var node = new Node<>(key, value, expiry);
                map.put(key, node);
                addToHead(node);

                // Evict the LRU entry if we are over capacity.
                if (map.size() > capacity) {
                    var lru = tail.prev;
                    if (lru != head) {
                        unlink(lru);
                        map.remove(lru.key, lru);
                    }
                }
            }

            // Opportunistic cleanup: if expired entries may be accumulating, sweep them.
            // Threshold is 10% of capacity to keep the amortized cost low.
            if (map.size() > capacity + Math.max(1, capacity / 10)) {
                evictExpiredLocked();
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
        readLock.lock();
        try {
            var now = System.currentTimeMillis();
            var count = 0;
            for (var node : map.values()) {
                if (now <= node.expiryTimestamp) {
                    count++;
                }
            }
            return count;
        } finally {
            readLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        writeLock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() {
        cleaner.shutdown();
        try {
            if (!cleaner.awaitTermination(2, TimeUnit.SECONDS)) {
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the configured maximum capacity.
     *
     * @return the maximum number of entries the cache will retain
     */
    public int capacity() {
        return capacity;
    }

    // ---------------------------------------------------------------------
    // Internal helpers — all assume the appropriate lock is already held.
    // ---------------------------------------------------------------------

    /**
     * Tests whether a node has passed its expiry instant.
     *
     * @param node the node to test
     * @return {@code true} if the node is expired
     */
    private static boolean isExpired(Node<?, ?> node) {
        return System.currentTimeMillis() > node.expiryTimestamp;
    }

    /** Removes a node from the linked list (no map mutation). Write lock required. */
    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    /** Inserts a node immediately after the head sentinel (MRU). Write lock required. */
    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    /** Moves an existing list member to the MRU position. Write lock required. */
    private void moveToHead(Node<K, V> node) {
        // Already MRU? Nothing to do.
        if (head.next == node) {
            return;
        }
        node.prev.next = node.next;
        node.next.prev = node.prev;
        addToHead(node);
    }

    /** Background-task entry point: acquires the write lock then sweeps expired entries. */
    private void evictExpired() {
        writeLock.lock();
        try {
            evictExpiredLocked();
        } finally {
            writeLock.unlock();
        }
    }

    /** Sweeps expired entries. Write lock required. */
    private void evictExpiredLocked() {
        var now = System.currentTimeMillis();
        var node = tail.prev;
        // Walk from LRU toward MRU; expired entries can be anywhere, so scan all.
        while (node != head) {
            var prev = node.prev;
            if (now > node.expiryTimestamp) {
                unlink(node);
                map.remove(node.key, node);
            }
            node = prev;
        }
    }

    /** Creates daemon threads so a forgotten {@code shutdown()} never blocks JVM exit. */
    private static ThreadFactory daemonThreadFactory() {
        var counter = new AtomicLong();
        return runnable -> {
            var t = new Thread(runnable, "lru-cache-cleaner-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
