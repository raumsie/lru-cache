# Thread-Safe LRU Cache with TTL (Java 21)

A bounded, generic, thread-safe **Least-Recently-Used** cache with **per-entry TTL**, built
from scratch on:

- a **custom intrusive doubly-linked list** (sentinel head/tail) for O(1) recency reordering —
  deliberately *not* `java.util.LinkedHashMap`;
- a **`ConcurrentHashMap`** for O(1) key lookup; and
- a single **`ReentrantReadWriteLock`** guarding the linked-list ordering and eviction
  invariants.

## The key idea: `peek`/`read` vs `get`

| Method        | Mutates recency order? | Lock acquired  | Concurrency       |
|---------------|------------------------|----------------|-------------------|
| `get`         | Yes (promotes to MRU)  | **write** lock | exclusive         |
| `peek`        | No (pure read)         | **read** lock  | fully concurrent  |
| `read(k, fn)` | No (read + consume)    | **read** lock  | fully concurrent  |

Because the read methods take only a *shared* read lock, any number of threads can read
simultaneously, whereas `Collections.synchronizedMap(...)` serializes **every** operation behind
one monitor.

### An honest caveat the benchmark makes explicit

The shared-read-lock advantage is **not** free, and it does **not** show up for a bare map
lookup. A `ReentrantReadWriteLock` read acquisition CASes a shared internal counter, so under
contention many readers bounce that cache line; for a nanosecond-scale critical section this
overhead is larger than an (uncontended/biased) monitor's. The benchmark therefore reports two
sub-results:

- **2a — bare lookup (`work=0`):** the read lock is actually *slower* (~0.5x). This is expected
  and correct; a read/write lock is the wrong tool when the read does almost nothing.
- **2b — realistic read (`work=250`):** once each read does even sub-microsecond real work on the
  value under the lock (validation, deserialization, computing a view — modeled by `read(k, fn)`),
  the read lock lets all threads proceed in parallel and beats the monitor by **~5–6x**.

Demonstrating *when* a read/write lock helps — and when it hurts — is the point, and is more
defensible than a single cherry-picked number.

## API

```java
Cache<String, byte[]> cache = new LRUCache<>(/* capacity */ 10_000);

cache.put("k", data, 60_000);   // insert with 60s TTL, promote to MRU
byte[] v = cache.get("k");      // read + promote (write lock)
byte[] p = cache.peek("k");     // read only, no promote (read lock, concurrent)
int len  = cache.read("k", b -> b.length); // consume value under a consistent read-lock snapshot
int n    = cache.size();        // live (non-expired) entry count
cache.clear();                  // drop everything
cache.shutdown();               // stop the background cleaner
```

TTL reclamation is both **lazy** (on `get`/`put` access) and **proactive** (a daemon
`ScheduledExecutorService` sweeps expired entries every 5 seconds to bound memory).

## Project layout

```
src/main/java/com/lrucache/
├── Cache.java                  # public interface
├── LRUCache.java               # implementation
├── Node.java                   # doubly-linked-list node (package-private)
└── benchmark/
    └── CacheBenchmark.java     # custom cache vs synchronizedMap(LinkedHashMap)
src/test/java/com/lrucache/
├── LRUCacheTest.java           # JUnit 5 functional/edge-case tests
└── ConcurrencyTest.java        # 50-thread + concurrent-peek stress tests
```

> Note: tests live under `src/test/java` (standard Maven convention) rather than a
> `tests/` subpackage, so the build tool picks them up automatically.

## Build & run with Maven (recommended)

Requires JDK 21 and Maven 3.9+. No external runtime dependencies; JUnit 5 is test-scoped.

```bash
mvn test                                   # run all unit + concurrency tests
mvn -q compile exec:java                   # run the benchmark
# (mainClass is preconfigured to com.lrucache.benchmark.CacheBenchmark)
```

## Build & run with plain `javac` / `java` (no Maven)

```bash
# 1. Compile the library + benchmark into ./out
javac -d out \
  src/main/java/com/lrucache/Node.java \
  src/main/java/com/lrucache/Cache.java \
  src/main/java/com/lrucache/LRUCache.java \
  src/main/java/com/lrucache/benchmark/CacheBenchmark.java

# 2. Run the benchmark
java -cp out com.lrucache.benchmark.CacheBenchmark
```

To run the JUnit tests without Maven, download the JUnit 5 standalone console launcher
(`junit-platform-console-standalone-<ver>.jar`) and:

```bash
javac -d out -cp junit-platform-console-standalone.jar \
  $(find src -name '*.java')
java -jar junit-platform-console-standalone.jar \
  --class-path out --scan-class-path
```

## IntelliJ IDEA run configurations

1. **Open** the project (IntelliJ detects `pom.xml` and imports it as a Maven project).
   Set the Project SDK to **21** (File ▸ Project Structure ▸ Project ▸ SDK).
2. **Benchmark**: open `CacheBenchmark.java`, click the green ▶ gutter arrow next to `main`,
   choose *Run 'CacheBenchmark.main()'*.
3. **Tests**: right-click the `src/test/java` folder ▸ *Run 'All Tests'*, or click the ▶ arrow
   beside any test class/method.

## Sample benchmark output

```
================================================================
Thread-Safe LRU Cache Benchmark  (threads=8, ops=100000)
================================================================

[Benchmark 1] Mixed workload — 80% reads, 20% writes
  LRUCache (custom)                            41 ms
  synchronizedMap(LinkedHashMap)               38 ms

[Benchmark 2] High-read workload — 100% reads (read lock vs exclusive lock)
  [2a] LRUCache.read (read lock, work=0)       62 ms
  [2a] synchronizedMap.get (exclusive, work=0)     33 ms
  => custom read is 0.53x the throughput of synchronizedMap
  [2b] LRUCache.read (read lock, work=250)     96 ms
  [2b] synchronizedMap.get (exclusive, work=250)   553 ms
  => custom read is 5.76x the throughput of synchronizedMap

[Benchmark 3] TTL expiration
  after 5ms with 1ms TTL: get("a")=null, size()=0
  => PASS
```

(Absolute timings are hardware-dependent; the **ratio** in Benchmark 2 is the meaningful result.)
