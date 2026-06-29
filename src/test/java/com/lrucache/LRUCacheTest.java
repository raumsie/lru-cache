package com.lrucache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional unit tests for {@link LRUCache} covering construction, basic CRUD,
 * LRU eviction ordering, the {@code get}/{@code peek} distinction, and TTL behavior.
 */
class LRUCacheTest {

    private static final long LONG_TTL = TimeUnit.MINUTES.toMillis(5);

    private LRUCache<String, String> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.shutdown();
        }
    }

    @Test
    @DisplayName("constructor rejects non-positive capacity")
    void rejectsBadCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, String>(0));
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, String>(-1));
    }

    @Test
    @DisplayName("put then get returns the value")
    void putGet() {
        cache = new LRUCache<>(2);
        cache.put("a", "1", LONG_TTL);
        assertEquals("1", cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("put rejects null key and non-positive TTL")
    void putValidation() {
        cache = new LRUCache<>(2);
        assertThrows(NullPointerException.class, () -> cache.put(null, "x", LONG_TTL));
        assertThrows(IllegalArgumentException.class, () -> cache.put("a", "x", 0));
        assertThrows(IllegalArgumentException.class, () -> cache.put("a", "x", -5));
    }

    @Test
    @DisplayName("get/peek of a null or absent key returns null")
    void missingKeys() {
        cache = new LRUCache<>(2);
        assertNull(cache.get("nope"));
        assertNull(cache.peek("nope"));
        assertNull(cache.get(null));
        assertNull(cache.peek(null));
    }

    @Test
    @DisplayName("duplicate put updates value and does not grow size")
    void duplicatePut() {
        cache = new LRUCache<>(2);
        cache.put("a", "1", LONG_TTL);
        cache.put("a", "2", LONG_TTL);
        assertEquals("2", cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("exceeding capacity evicts the least-recently-used entry")
    void lruEviction() {
        cache = new LRUCache<>(2);
        cache.put("a", "1", LONG_TTL);
        cache.put("b", "2", LONG_TTL);
        // Access "a" so "b" becomes the LRU.
        cache.get("a");
        cache.put("c", "3", LONG_TTL); // evicts "b"

        assertNull(cache.get("b"));
        assertEquals("1", cache.get("a"));
        assertEquals("3", cache.get("c"));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("peek returns the value without promoting recency")
    void peekDoesNotPromote() {
        cache = new LRUCache<>(2);
        cache.put("a", "1", LONG_TTL);
        cache.put("b", "2", LONG_TTL);
        // peek "a" — must NOT save it from eviction.
        assertEquals("1", cache.peek("a"));
        cache.put("c", "3", LONG_TTL); // "a" is still LRU, so it is evicted

        assertNull(cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));
    }

    @Test
    @DisplayName("read applies the function to the live value without promoting recency")
    void readAppliesFunction() {
        cache = new LRUCache<>(2);
        cache.put("a", "hello", LONG_TTL);
        cache.put("b", "2", LONG_TTL);

        assertEquals(5, cache.read("a", String::length));
        // read must NOT promote, so "a" is still LRU and gets evicted on the next put.
        cache.put("c", "3", LONG_TTL);
        assertNull(cache.get("a"));
    }

    @Test
    @DisplayName("read returns null for absent, null, or expired keys")
    void readMissing() throws InterruptedException {
        cache = new LRUCache<>(2);
        assertNull(cache.read("nope", String::length));
        assertNull(cache.read(null, String::length));
        cache.put("a", "x", 1);
        Thread.sleep(10);
        assertNull(cache.read("a", String::length));
    }

    @Test
    @DisplayName("expired entries are reclaimed lazily on get")
    void ttlExpiryOnGet() throws InterruptedException {
        cache = new LRUCache<>(4);
        cache.put("a", "1", 1);
        Thread.sleep(10);
        assertNull(cache.get("a"));
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("expired entries are not returned by peek")
    void ttlExpiryOnPeek() throws InterruptedException {
        cache = new LRUCache<>(4);
        cache.put("a", "1", 1);
        Thread.sleep(10);
        assertNull(cache.peek("a"));
    }

    @Test
    @DisplayName("clear removes all entries")
    void clear() {
        cache = new LRUCache<>(4);
        cache.put("a", "1", LONG_TTL);
        cache.put("b", "2", LONG_TTL);
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
        assertNull(cache.get("b"));
    }

    @Test
    @DisplayName("eviction keeps total entries within capacity")
    void capacityBound() {
        cache = new LRUCache<>(3);
        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, "v" + i, LONG_TTL);
        }
        assertTrue(cache.size() <= 3, "size must not exceed capacity");
    }
}
