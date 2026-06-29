package com.lrucache;

/**
 * A mutable node in the intrusive doubly-linked list that backs {@link LRUCache}.
 *
 * <p>Each node is simultaneously a member of two structures:
 * <ul>
 *     <li>the {@code ConcurrentHashMap} (keyed by {@link #key}) used for O(1) lookups, and</li>
 *     <li>the doubly-linked list used to maintain recency (LRU) order.</li>
 * </ul>
 *
 * <p>Because the recency order must be mutated in place (re-linking {@link #prev} and
 * {@link #next} pointers when a node is promoted or evicted), this type cannot be a
 * {@code record}: records have final components and therefore cannot model the mutable
 * pointers of a doubly-linked list. A plain mutable class is the correct tool here.
 *
 * <p>This class performs no synchronization of its own. All access to a node's pointer
 * fields is guarded by the single {@code ReentrantReadWriteLock} owned by the enclosing
 * {@link LRUCache}.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
final class Node<K, V> {

    /** The key associated with this entry. {@code null} for sentinel head/tail nodes. */
    final K key;

    /** The value associated with this entry. Mutable so that {@code put} can update in place. */
    V value;

    /**
     * Absolute expiry instant in epoch milliseconds
     * ({@code System.currentTimeMillis() + ttlMillis} at insertion time).
     * A value of {@link Long#MAX_VALUE} is used for sentinel nodes (never expires).
     */
    long expiryTimestamp;

    /** Previous node toward the head (more recently used). */
    Node<K, V> prev;

    /** Next node toward the tail (less recently used). */
    Node<K, V> next;

    /**
     * Creates a new node.
     *
     * @param key             the entry key (may be {@code null} only for sentinels)
     * @param value           the entry value
     * @param expiryTimestamp the absolute expiry instant in epoch milliseconds
     */
    Node(K key, V value, long expiryTimestamp) {
        this.key = key;
        this.value = value;
        this.expiryTimestamp = expiryTimestamp;
    }
}
