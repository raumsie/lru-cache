package com.lrucache;

import java.util.function.Function;

/**
 * A bounded, thread-safe key/value cache with per-entry time-to-live (TTL) and
 * Least-Recently-Used (LRU) eviction.
 *
 * <p>Implementations are expected to be safe for use by multiple concurrent threads.
 * The contract intentionally distinguishes two read paths:
 * <ul>
 *     <li>{@link #get(Object)} — a <em>recency-mutating</em> read that promotes the entry to
 *         the most-recently-used position. Because it mutates recency order it requires
 *         exclusive (write) access.</li>
 *     <li>{@link #peek(Object)} — a <em>pure</em> read that returns the value without altering
 *         recency order, allowing many readers to proceed concurrently.</li>
 * </ul>
 *
 * <p>This split is the central design point of the implementation: workloads dominated by
 * pure reads can use {@code peek} and scale across cores, whereas a globally synchronized
 * map serializes every read.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface Cache<K, V> {

    /**
     * Returns the value associated with {@code key} and promotes the entry to the
     * most-recently-used position.
     *
     * <p>If the entry is present but has expired, it is removed and {@code null} is returned.
     * This operation mutates the recency ordering and therefore acquires the write lock.
     *
     * @param key the key whose value is requested
     * @return the cached value, or {@code null} if absent or expired
     */
    V get(K key);

    /**
     * Returns the value associated with {@code key} <em>without</em> changing its recency
     * position.
     *
     * <p>This is a read-only operation guarded by the read lock, allowing multiple threads
     * to {@code peek} simultaneously without blocking one another. Expired entries are
     * treated as absent (returning {@code null}) but are not eagerly removed here; reclamation
     * is left to {@link #get(Object)}, {@link #put(Object, Object, long)}, or the background
     * cleaner.
     *
     * @param key the key whose value is requested
     * @return the cached value, or {@code null} if absent or expired
     */
    V peek(K key);

    /**
     * Applies {@code reader} to the value associated with {@code key} <em>while holding the
     * read lock</em>, and returns the result.
     *
     * <p>This is the concurrency-scaling read path. Like {@link #peek(Object)} it does not
     * change recency order, but it additionally guarantees that the entry is neither evicted
     * nor mutated for the duration of {@code reader} — so callers can safely consume the value
     * (deserialize it, validate it, derive a view) under a consistent snapshot. Because the
     * read lock is shared, many threads may run their {@code reader} functions simultaneously,
     * whereas a globally synchronized map would serialize them.
     *
     * <p>{@code reader} must be side-effect-free with respect to the cache and should not call
     * back into write methods of this cache (doing so would deadlock, as the read lock is not
     * upgradable).
     *
     * @param reader the function applied to the live value
     * @param <R>    the result type
     * @return the result of {@code reader}, or {@code null} if the key is absent or expired
     */
    <R> R read(K key, Function<? super V, ? extends R> reader);

    /**
     * Inserts or updates the mapping for {@code key}, promoting it to the most-recently-used
     * position and evicting the least-recently-used entry if capacity is exceeded.
     *
     * @param key       the key to insert or update (must not be {@code null})
     * @param value     the value to associate with the key
     * @param ttlMillis the time-to-live in milliseconds from now; must be positive
     * @throws NullPointerException     if {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code ttlMillis} is not positive
     */
    void put(K key, V value, long ttlMillis);

    /**
     * Returns the number of live (non-expired) entries currently held.
     *
     * @return the current logical size, excluding entries that have expired
     */
    int size();

    /**
     * Removes all entries from the cache.
     */
    void clear();

    /**
     * Gracefully terminates the background eviction task and releases associated resources.
     * After shutdown the cache may still be read from and written to, but expired entries will
     * only be reclaimed lazily on access.
     */
    void shutdown();
}
