import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.Collectors;

// ─────────────────────────────────────────────
//  1. CacheEntry  — wraps value + expiry time
// ─────────────────────────────────────────────
class CacheEntry<V> {
    private final V value;
    private final long expiryTimeMs;

    public CacheEntry(V value, long ttlMs) {
        this.value = value;
        this.expiryTimeMs = System.currentTimeMillis() + ttlMs;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTimeMs;
    }

    public V getValue()           { return value; }
    public long getExpiryTimeMs() { return expiryTimeMs; }

    @Override
    public String toString() {
        return "CacheEntry{value=" + value
             + ", expiresIn=" + Math.max(0, expiryTimeMs - System.currentTimeMillis()) + "ms}";
    }
}

// ─────────────────────────────────────────────
//  2. Cache  — public interface
// ─────────────────────────────────────────────
interface Cache<K, V> {
    void     put(K key, V value, long ttlMs);
    Optional<V> get(K key);
    void     delete(K key);
    int      size();
    void     shutdown();
}

// ─────────────────────────────────────────────
//  3. EvictionPolicy  — strategy interface
// ─────────────────────────────────────────────
interface EvictionPolicy<K, V> {
    void evict(Map<K, CacheEntry<V>> store);
}

// ─────────────────────────────────────────────
//  4. TTLEvictionPolicy  — removes expired keys
// ─────────────────────────────────────────────
class TTLEvictionPolicy<K, V> implements EvictionPolicy<K, V> {

    @Override
    public void evict(Map<K, CacheEntry<V>> store) {
        int before = store.size();
        store.entrySet().removeIf(entry -> entry.getValue().isExpired());
        int evicted = before - store.size();
        if (evicted > 0) {
            System.out.println("[ActiveEviction] Removed " + evicted + " expired key(s). "
                             + "Store size: " + store.size());
        }
    }
}

// ─────────────────────────────────────────────
//  5. TTLCacheImpl  — core implementation
// ─────────────────────────────────────────────
class TTLCacheImpl<K, V> implements Cache<K, V> {

    private final ConcurrentHashMap<K, CacheEntry<V>> store;
    private final ScheduledExecutorService scheduler;
    private final EvictionPolicy<K, V> evictionPolicy;

    public TTLCacheImpl(long cleanupIntervalMs) {
        this.store = new ConcurrentHashMap<>();
        this.evictionPolicy = new TTLEvictionPolicy<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttl-cache-cleanup");
            t.setDaemon(true); // won't block JVM shutdown
            return t;
        });
        startActiveEviction(cleanupIntervalMs);
    }

    // ── Active eviction (periodic sweep) ───────────────────────
    private void startActiveEviction(long intervalMs) {
        scheduler.scheduleAtFixedRate(
            () -> evictionPolicy.evict(store),
            intervalMs, intervalMs, TimeUnit.MILLISECONDS
        );
    }

    // ── put ─────────────────────────────────────────────────────
    @Override
    public void put(K key, V value, long ttlMs) {
        if (key == null)  throw new IllegalArgumentException("Key cannot be null");
        if (ttlMs <= 0)   throw new IllegalArgumentException("TTL must be positive");
        store.put(key, new CacheEntry<>(value, ttlMs));
    }

    // ── get  (lazy eviction on access) ─────────────────────────
    @Override
    public Optional<V> get(K key) {
        CacheEntry<V> entry = store.get(key);
        if (entry == null) return Optional.empty();

        if (entry.isExpired()) {
            // CAS-style: won't remove a freshly re-put entry
            store.remove(key, entry);
            System.out.println("[LazyEviction] Key '" + key + "' expired on access.");
            return Optional.empty();
        }
        return Optional.of(entry.getValue());
    }

    // ── delete ──────────────────────────────────────────────────
    @Override
    public void delete(K key) {
        store.remove(key);
    }

    // ── size (only live keys) ───────────────────────────────────
    @Override
    public int size() {
        return (int) store.values().stream()
                          .filter(e -> !e.isExpired())
                          .count();
    }

    // ── shutdown ────────────────────────────────────────────────
    @Override
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS))
                scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("[Cache] Shutdown complete.");
    }

    // ── debug helper ────────────────────────────────────────────
    public void printStore() {
        System.out.println("[Store Snapshot]");
        store.forEach((k, v) ->
            System.out.println("  " + k + " => " + v + (v.isExpired() ? " [EXPIRED]" : " [LIVE]"))
        );
    }
}

// ─────────────────────────────────────────────
//  6. CacheFactory  — factory / builder
// ─────────────────────────────────────────────
class CacheFactory {

    /** Default cleanup sweep every 5 seconds */
    public static <K, V> Cache<K, V> createTTLCache() {
        return new TTLCacheImpl<>(5_000L);
    }

    /** Custom cleanup interval */
    public static <K, V> Cache<K, V> createTTLCache(long cleanupIntervalMs) {
        return new TTLCacheImpl<>(cleanupIntervalMs);
    }
}

// ─────────────────────────────────────────────
//  7. TTLCache  — main / demo runner
// ─────────────────────────────────────────────
public class TTLCache {

    public static void main(String[] args) throws InterruptedException {

        System.out.println("═══════════════════════════════════════");
        System.out.println("         TTL Cache — Demo               ");
        System.out.println("═══════════════════════════════════════\n");

        // Cleanup sweep every 1 second for demo visibility
        Cache<String, String> cache = CacheFactory.createTTLCache(1_000L);

        // ── Test 1: Basic put & get ───────────────────────────
        System.out.println("── Test 1: Basic put & get ──");
        cache.put("session:u1", "token_abc", 2_000L);   // 2 s TTL
        cache.put("session:u2", "token_xyz", 5_000L);   // 5 s TTL
        cache.put("config:theme", "dark",     10_000L); // 10 s TTL

        System.out.println("get(session:u1)  → " + cache.get("session:u1"));
        System.out.println("get(session:u2)  → " + cache.get("session:u2"));
        System.out.println("get(config:theme)→ " + cache.get("config:theme"));
        System.out.println("Cache size (live): " + cache.size());

        // ── Test 2: Expiry (lazy eviction) ───────────────────
        System.out.println("\n── Test 2: Wait 3s — session:u1 should expire ──");
        Thread.sleep(3_000);
        System.out.println("get(session:u1)  → " + cache.get("session:u1")); // expired
        System.out.println("get(session:u2)  → " + cache.get("session:u2")); // still live
        System.out.println("Cache size (live): " + cache.size());

        // ── Test 3: Explicit delete ───────────────────────────
        System.out.println("\n── Test 3: Explicit delete ──");
        cache.delete("config:theme");
        System.out.println("get(config:theme)→ " + cache.get("config:theme")); // gone

        // ── Test 4: Overwrite with new TTL ───────────────────
        System.out.println("\n── Test 4: Re-put key with fresh TTL ──");
        cache.put("session:u2", "token_NEW", 10_000L);
        System.out.println("get(session:u2)  → " + cache.get("session:u2")); // refreshed

        // ── Test 5: Null / invalid inputs ────────────────────
        System.out.println("\n── Test 5: Invalid inputs ──");
        try {
            cache.put(null, "value", 1000);
        } catch (IllegalArgumentException e) {
            System.out.println("Caught: " + e.getMessage());
        }
        try {
            cache.put("key", "value", -1);
        } catch (IllegalArgumentException e) {
            System.out.println("Caught: " + e.getMessage());
        }

        // ── Test 6: Active eviction sweep ─────────────────────
        System.out.println("\n── Test 6: Active sweep — add 3 keys with 1s TTL, wait 2s ──");
        cache.put("otp:1001", "482910", 1_000L);
        cache.put("otp:1002", "774321", 1_000L);
        cache.put("otp:1003", "992847", 1_000L);
        System.out.println("Before sweep — size: " + cache.size());
        Thread.sleep(2_000); // active sweeper fires in this window
        System.out.println("After sweep  — size: " + cache.size());

        // ── Shutdown ──────────────────────────────────────────
        System.out.println("\n── Shutdown ──");
        cache.shutdown();
    }
}
