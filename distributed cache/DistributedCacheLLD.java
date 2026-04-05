import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// DISTRIBUTED CACHE LLD
// Patterns:
//   Singleton  — CacheManager (central registry)
//   Strategy   — EvictionStrategy (LRU/LFU/FIFO/TTL)
//   Factory    — CacheFactory (create typed caches)
//   Builder    — CacheConfig, CacheEntry construction
//   Observer   — CacheEventObserver (hit/miss/evict analytics)
//   Decorator  — TTLCache wraps any cache with expiry
//   Template   — ConsistentHashRing (node distribution)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum EvictionPolicy  { LRU, LFU, FIFO, RANDOM, TTL_BASED }
enum CacheEventType  { HIT, MISS, EVICTION, PUT, DELETE, EXPIRE }
enum WritePolicy     { WRITE_THROUGH, WRITE_BACK, WRITE_AROUND }

// ==========================================
// 2. CACHE ENTRY — BUILDER PATTERN
// Stores value + metadata for eviction decisions
// ==========================================
class CacheEntry<V> {
    private final  String        key;
    private        V             value;
    private final  LocalDateTime createdAt;
    private        LocalDateTime lastAccessedAt;
    private        LocalDateTime expiresAt;      // null = no expiry
    private        long          accessCount;
    private        long          insertionOrder;  // for FIFO
    private        long          sizeBytes;       // memory footprint estimate

    private CacheEntry(Builder<V> b) {
        this.key             = b.key;
        this.value           = b.value;
        this.createdAt       = LocalDateTime.now();
        this.lastAccessedAt  = LocalDateTime.now();
        this.expiresAt       = b.expiresAt;
        this.accessCount     = 0;
        this.insertionOrder  = b.insertionOrder;
        this.sizeBytes       = b.sizeBytes;
    }

    public void recordAccess() {
        lastAccessedAt = LocalDateTime.now();
        accessCount++;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public void updateValue(V newValue) { this.value = newValue; }

    public String        getKey()             { return key; }
    public V             getValue()           { return value; }
    public LocalDateTime getLastAccessedAt()  { return lastAccessedAt; }
    public LocalDateTime getExpiresAt()       { return expiresAt; }
    public long          getAccessCount()     { return accessCount; }
    public long          getInsertionOrder()  { return insertionOrder; }
    public long          getSizeBytes()       { return sizeBytes; }

    @Override public String toString() {
        return "Entry[key=" + key + ", val=" + value +
               ", hits=" + accessCount +
               (expiresAt != null ? ", exp=" + expiresAt : "") + "]";
    }

    static class Builder<V> {
        private final  String        key;
        private final  V             value;
        private        LocalDateTime expiresAt     = null;
        private        long          insertionOrder = 0;
        private        long          sizeBytes      = 64; // default estimate

        public Builder(String key, V value)      { this.key = key; this.value = value; }
        public Builder<V> ttlSeconds(long secs)  {
            this.expiresAt = LocalDateTime.now().plusSeconds(secs); return this;
        }
        public Builder<V> order(long order)      { this.insertionOrder = order; return this; }
        public Builder<V> sizeBytes(long bytes)  { this.sizeBytes = bytes;      return this; }
        public CacheEntry<V> build()             { return new CacheEntry<>(this); }
    }
}

// ==========================================
// 3. CACHE CONFIG — BUILDER PATTERN
// ==========================================
class CacheConfig {
    final int          maxSize;
    final EvictionPolicy evictionPolicy;
    final long         defaultTtlSeconds; // 0 = no expiry
    final WritePolicy  writePolicy;
    final boolean      statsEnabled;
    final long         maxMemoryBytes;    // 0 = unlimited

    private CacheConfig(Builder b) {
        this.maxSize           = b.maxSize;
        this.evictionPolicy    = b.evictionPolicy;
        this.defaultTtlSeconds = b.defaultTtlSeconds;
        this.writePolicy       = b.writePolicy;
        this.statsEnabled      = b.statsEnabled;
        this.maxMemoryBytes    = b.maxMemoryBytes;
    }

    static class Builder {
        private int          maxSize           = 1000;
        private EvictionPolicy evictionPolicy  = EvictionPolicy.LRU;
        private long         defaultTtlSeconds = 0;
        private WritePolicy  writePolicy       = WritePolicy.WRITE_THROUGH;
        private boolean      statsEnabled      = true;
        private long         maxMemoryBytes    = 0;

        public Builder maxSize(int s)             { maxSize = s;              return this; }
        public Builder eviction(EvictionPolicy e) { evictionPolicy = e;       return this; }
        public Builder ttl(long secs)             { defaultTtlSeconds = secs; return this; }
        public Builder writePolicy(WritePolicy w) { writePolicy = w;          return this; }
        public Builder maxMemory(long bytes)      { maxMemoryBytes = bytes;   return this; }
        public CacheConfig build()                { return new CacheConfig(this); }
    }

    @Override public String toString() {
        return "CacheConfig[maxSize=" + maxSize + ", policy=" + evictionPolicy +
               ", ttl=" + defaultTtlSeconds + "s, write=" + writePolicy + "]";
    }
}

// ==========================================
// 4. CACHE STATS (for monitoring)
// ==========================================
class CacheStats {
    private final AtomicLong hits       = new AtomicLong(0);
    private final AtomicLong misses     = new AtomicLong(0);
    private final AtomicLong evictions  = new AtomicLong(0);
    private final AtomicLong puts       = new AtomicLong(0);
    private final AtomicLong deletes    = new AtomicLong(0);
    private final AtomicLong expirations= new AtomicLong(0);

    public void recordHit()       { hits.incrementAndGet(); }
    public void recordMiss()      { misses.incrementAndGet(); }
    public void recordEviction()  { evictions.incrementAndGet(); }
    public void recordPut()       { puts.incrementAndGet(); }
    public void recordDelete()    { deletes.incrementAndGet(); }
    public void recordExpiration(){ expirations.incrementAndGet(); }

    public long getHits()         { return hits.get(); }
    public long getMisses()       { return misses.get(); }
    public long getEvictions()    { return evictions.get(); }

    public double getHitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (100.0 * hits.get() / total);
    }

    public void print(String cacheName) {
        System.out.printf("[Stats:%s] hits=%d misses=%d hitRate=%.1f%% " +
            "evictions=%d expires=%d puts=%d%n",
            cacheName, hits.get(), misses.get(), getHitRate(),
            evictions.get(), expirations.get(), puts.get());
    }
}

// ==========================================
// 5. OBSERVER — CACHE EVENTS
// ==========================================
interface CacheEventObserver {
    void onEvent(CacheEventType type, String key, Object value);
}

class LoggingObserver implements CacheEventObserver {
    private final String cacheName;
    public LoggingObserver(String name) { this.cacheName = name; }

    @Override
    public void onEvent(CacheEventType type, String key, Object value) {
        switch (type) {
            case HIT      -> System.out.println("[" + cacheName + "] HIT   → " + key);
            case MISS     -> System.out.println("[" + cacheName + "] MISS  → " + key);
            case EVICTION -> System.out.println("[" + cacheName + "] EVICT → " + key);
            case EXPIRE   -> System.out.println("[" + cacheName + "] EXPIRE→ " + key);
            case PUT      -> System.out.println("[" + cacheName + "] PUT   → " + key + " = " + value);
            case DELETE   -> System.out.println("[" + cacheName + "] DEL   → " + key);
        }
    }
}

// ==========================================
// 6. EVICTION STRATEGY — STRATEGY PATTERN
// ==========================================
interface EvictionStrategy<V> {
    EvictionPolicy getPolicy();
    // Called on every get — update metadata
    void onAccess(CacheEntry<V> entry);
    // Called on every put
    void onInsert(CacheEntry<V> entry);
    // Called on delete
    void onDelete(String key);
    // Pick the entry to evict
    CacheEntry<V> selectEvictionCandidate(Map<String, CacheEntry<V>> entries);
}

// ---- LRU: evict Least Recently Used ----
// Uses a LinkedHashMap internally (access-order)
// Key insight: O(1) get, O(1) put, O(1) eviction
class LRUStrategy<V> implements EvictionStrategy<V> {
    // Tracks access order: most recent at tail, least recent at head
    private final LinkedHashMap<String, CacheEntry<V>> accessOrder =
        new LinkedHashMap<>(16, 0.75f, true); // true = access order

    @Override public EvictionPolicy getPolicy() { return EvictionPolicy.LRU; }

    @Override
    public synchronized void onAccess(CacheEntry<V> entry) {
        accessOrder.get(entry.getKey()); // moves to tail
        entry.recordAccess();
    }

    @Override
    public synchronized void onInsert(CacheEntry<V> entry) {
        accessOrder.put(entry.getKey(), entry);
    }

    @Override
    public synchronized void onDelete(String key) {
        accessOrder.remove(key);
    }

    @Override
    public synchronized CacheEntry<V> selectEvictionCandidate(
            Map<String, CacheEntry<V>> entries) {
        // Head of LinkedHashMap = least recently used
        if (accessOrder.isEmpty()) return null;
        String lruKey = accessOrder.entrySet().iterator().next().getKey();
        return entries.get(lruKey);
    }
}

// ---- LFU: evict Least Frequently Used ----
// Uses min-heap ordered by access count
class LFUStrategy<V> implements EvictionStrategy<V> {
    // PriorityQueue: entry with smallest accessCount at head
    private final PriorityQueue<CacheEntry<V>> freqHeap =
        new PriorityQueue<>(Comparator.comparingLong(CacheEntry::getAccessCount));

    @Override public EvictionPolicy getPolicy() { return EvictionPolicy.LFU; }

    @Override
    public synchronized void onAccess(CacheEntry<V> entry) {
        // Rebuild heap position after count change
        freqHeap.remove(entry);
        entry.recordAccess();
        freqHeap.add(entry);
    }

    @Override
    public synchronized void onInsert(CacheEntry<V> entry) { freqHeap.add(entry); }

    @Override
    public synchronized void onDelete(String key) {
        freqHeap.removeIf(e -> e.getKey().equals(key));
    }

    @Override
    public synchronized CacheEntry<V> selectEvictionCandidate(
            Map<String, CacheEntry<V>> entries) {
        return freqHeap.isEmpty() ? null : freqHeap.peek();
    }
}

// ---- FIFO: evict in insertion order ----
class FIFOStrategy<V> implements EvictionStrategy<V> {
    private final Queue<CacheEntry<V>> insertionQueue = new ArrayDeque<>();

    @Override public EvictionPolicy getPolicy() { return EvictionPolicy.FIFO; }

    @Override public void onAccess(CacheEntry<V> entry) { entry.recordAccess(); }

    @Override
    public synchronized void onInsert(CacheEntry<V> entry) {
        insertionQueue.offer(entry);
    }

    @Override
    public synchronized void onDelete(String key) {
        insertionQueue.removeIf(e -> e.getKey().equals(key));
    }

    @Override
    public synchronized CacheEntry<V> selectEvictionCandidate(
            Map<String, CacheEntry<V>> entries) {
        // Head = oldest insertion
        return insertionQueue.peek();
    }
}

// ==========================================
// 7. CORE CACHE — GENERIC, THREAD-SAFE
// ==========================================
class Cache<V> {
    private final String                          name;
    private final CacheConfig                     config;
    private final Map<String, CacheEntry<V>>      store;
    private final EvictionStrategy<V>             eviction;
    private final CacheStats                      stats;
    private final List<CacheEventObserver>        observers;
    private       long                            insertionCounter = 0;
    private       long                            currentMemoryBytes = 0;

    // Background TTL expiry scanner
    private final ScheduledExecutorService ttlScanner =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttl-scanner-" + name);
            t.setDaemon(true);
            return t;
        });

    public Cache(String name, CacheConfig config, EvictionStrategy<V> eviction) {
        this.name      = name;
        this.config    = config;
        this.eviction  = eviction;
        this.store     = new ConcurrentHashMap<>();
        this.stats     = new CacheStats();
        this.observers = new ArrayList<>();
        this.observers.add(new LoggingObserver(name));

        // Scan for TTL expiry every 1 second
        ttlScanner.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.SECONDS);

        System.out.println("[Cache:" + name + "] Created — " + config);
    }

    // ---- PUT ----
    public synchronized void put(String key, V value) {
        put(key, value, config.defaultTtlSeconds);
    }

    public synchronized void put(String key, V value, long ttlSeconds) {
        // If key exists — update value in place
        if (store.containsKey(key)) {
            CacheEntry<V> existing = store.get(key);
            existing.updateValue(value);
            eviction.onAccess(existing);
            notify(CacheEventType.PUT, key, value);
            stats.recordPut();
            return;
        }

        // Evict if at capacity OR over memory limit
        while (store.size() >= config.maxSize ||
               (config.maxMemoryBytes > 0 &&
                currentMemoryBytes >= config.maxMemoryBytes)) {
            evictOne();
        }

        // Create and store new entry
        CacheEntry<V> entry = new CacheEntry.Builder<>(key, value)
            .ttlSeconds(ttlSeconds > 0 ? ttlSeconds : 0)
            .order(insertionCounter++)
            .build();

        store.put(key, entry);
        eviction.onInsert(entry);
        currentMemoryBytes += entry.getSizeBytes();
        stats.recordPut();
        notify(CacheEventType.PUT, key, value);
    }

    // ---- GET ----
    public Optional<V> get(String key) {
        CacheEntry<V> entry = store.get(key);

        if (entry == null) {
            stats.recordMiss();
            notify(CacheEventType.MISS, key, null);
            return Optional.empty();
        }

        // Lazy expiry check
        if (entry.isExpired()) {
            evictEntry(key, entry, CacheEventType.EXPIRE);
            stats.recordMiss();
            stats.recordExpiration();
            return Optional.empty();
        }

        eviction.onAccess(entry);
        stats.recordHit();
        notify(CacheEventType.HIT, key, entry.getValue());
        return Optional.of(entry.getValue());
    }

    // ---- DELETE ----
    public synchronized boolean delete(String key) {
        CacheEntry<V> entry = store.remove(key);
        if (entry == null) return false;
        eviction.onDelete(key);
        currentMemoryBytes -= entry.getSizeBytes();
        stats.recordDelete();
        notify(CacheEventType.DELETE, key, null);
        return true;
    }

    // ---- EVICTION ----
    private void evictOne() {
        CacheEntry<V> candidate = eviction.selectEvictionCandidate(store);
        if (candidate != null) {
            evictEntry(candidate.getKey(), candidate, CacheEventType.EVICTION);
            stats.recordEviction();
        }
    }

    private void evictEntry(String key, CacheEntry<V> entry, CacheEventType type) {
        store.remove(key);
        eviction.onDelete(key);
        currentMemoryBytes -= Math.max(0, entry.getSizeBytes());
        notify(type, key, null);
    }

    // ---- TTL BACKGROUND SCAN ----
    private void evictExpired() {
        store.entrySet().stream()
            .filter(e -> e.getValue().isExpired())
            .forEach(e -> {
                evictEntry(e.getKey(), e.getValue(), CacheEventType.EXPIRE);
                stats.recordExpiration();
            });
    }

    // ---- UTILITIES ----
    public boolean     containsKey(String key) { return store.containsKey(key) &&
                                                  !store.get(key).isExpired(); }
    public int         size()                  { return store.size(); }
    public void        clear()                 { store.clear(); currentMemoryBytes = 0; }
    public CacheStats  getStats()              { return stats; }
    public String      getName()               { return name; }

    public void printStats() { stats.print(name); }

    public void addObserver(CacheEventObserver observer) { observers.add(observer); }

    private void notify(CacheEventType type, String key, Object value) {
        if (config.statsEnabled) {
            observers.forEach(o -> o.onEvent(type, key, value));
        }
    }

    public void shutdown() { ttlScanner.shutdown(); }
}

// ==========================================
// 8. CONSISTENT HASH RING
// Distributes keys across multiple cache nodes
// Minimises remapping when nodes join/leave
// ==========================================
class ConsistentHashRing {
    private final TreeMap<Long, String>  ring          = new TreeMap<>();
    private final Map<String, Set<Long>> nodePositions = new HashMap<>();
    private final int                    virtualNodes;  // replicas per node

    public ConsistentHashRing(int virtualNodes) {
        this.virtualNodes = virtualNodes;
    }

    // Add a node with N virtual node positions on the ring
    public void addNode(String nodeId) {
        Set<Long> positions = new HashSet<>();
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(nodeId + "-vnode-" + i);
            ring.put(hash, nodeId);
            positions.add(hash);
        }
        nodePositions.put(nodeId, positions);
        System.out.println("[Ring] Added node: " + nodeId +
            " with " + virtualNodes + " virtual positions");
    }

    // Remove a node and its virtual positions
    public void removeNode(String nodeId) {
        Set<Long> positions = nodePositions.remove(nodeId);
        if (positions != null) {
            positions.forEach(ring::remove);
            System.out.println("[Ring] Removed node: " + nodeId);
        }
    }

    // Find which node owns this key
    public String getNode(String key) {
        if (ring.isEmpty()) return null;
        long hash = hash(key);
        // Find the nearest node clockwise on the ring
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            // Wrap around: use the first node in the ring
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    // Consistent hashing function (FNV-1a variant)
    private long hash(String key) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : key.getBytes()) {
            hash ^= b;
            hash *= 0x100000001b3L;
        }
        return Math.abs(hash);
    }

    public List<String> getNodes() {
        return new ArrayList<>(nodePositions.keySet());
    }

    public int getNodeCount() { return nodePositions.size(); }

    public void printDistribution(List<String> keys) {
        Map<String, Long> dist = keys.stream()
            .collect(Collectors.groupingBy(this::getNode, Collectors.counting()));
        System.out.println("[Ring] Key distribution across nodes:");
        dist.forEach((node, count) ->
            System.out.printf("  %-12s → %d keys (%.1f%%)%n",
                node, count, 100.0 * count / keys.size()));
    }
}

// ==========================================
// 9. DISTRIBUTED CACHE (multi-node)
// Routes requests to the correct shard via consistent hashing
// ==========================================
class DistributedCache<V> {
    private final String                   name;
    private final ConsistentHashRing       ring;
    private final Map<String, Cache<V>>    nodes;  // nodeId → Cache
    private final CacheConfig              config;
    private final CacheStats               globalStats = new CacheStats();

    public DistributedCache(String name, CacheConfig config, int virtualNodes) {
        this.name   = name;
        this.config = config;
        this.ring   = new ConsistentHashRing(virtualNodes);
        this.nodes  = new ConcurrentHashMap<>();
    }

    public void addNode(String nodeId) {
        Cache<V> cache = new Cache<>(name + "-" + nodeId, config,
            createEvictionStrategy(config.evictionPolicy));
        nodes.put(nodeId, cache);
        ring.addNode(nodeId);
    }

    public void removeNode(String nodeId) {
        Cache<V> removed = nodes.remove(nodeId);
        if (removed != null) {
            removed.shutdown();
            ring.removeNode(nodeId);
            System.out.println("[Distributed] Node removed: " + nodeId +
                " | Remaining: " + nodes.size());
        }
    }

    public void put(String key, V value) {
        String nodeId = ring.getNode(key);
        if (nodeId == null) {
            System.out.println("[Distributed] No nodes available");
            return;
        }
        nodes.get(nodeId).put(key, value);
        globalStats.recordPut();
    }

    public void put(String key, V value, long ttlSeconds) {
        String nodeId = ring.getNode(key);
        if (nodeId == null) return;
        nodes.get(nodeId).put(key, value, ttlSeconds);
        globalStats.recordPut();
    }

    public Optional<V> get(String key) {
        String nodeId = ring.getNode(key);
        if (nodeId == null) return Optional.empty();
        Optional<V> result = nodes.get(nodeId).get(key);
        if (result.isPresent()) globalStats.recordHit();
        else                    globalStats.recordMiss();
        return result;
    }

    public boolean delete(String key) {
        String nodeId = ring.getNode(key);
        if (nodeId == null) return false;
        boolean deleted = nodes.get(nodeId).delete(key);
        if (deleted) globalStats.recordDelete();
        return deleted;
    }

    @SuppressWarnings("unchecked")
    private EvictionStrategy<V> createEvictionStrategy(EvictionPolicy policy) {
        return switch (policy) {
            case LRU        -> new LRUStrategy<>();
            case LFU        -> new LFUStrategy<>();
            case FIFO       -> new FIFOStrategy<>();
            default         -> new LRUStrategy<>();
        };
    }

    public void printDistribution(List<String> keys) {
        ring.printDistribution(keys);
    }

    public void printAllStats() {
        System.out.println("\n[Distributed Cache: " + name + "] Stats:");
        nodes.forEach((nodeId, cache) -> cache.printStats());
        globalStats.print("GLOBAL");
    }

    public ConsistentHashRing getRing() { return ring; }
    public int getNodeCount()           { return nodes.size(); }

    public void shutdown() { nodes.values().forEach(Cache::shutdown); }
}

// ==========================================
// 10. CACHE FACTORY
// ==========================================
class CacheFactory {
    // Volatile singleton for each named cache (double-checked locking)
    public static <V> Cache<V> createLRU(String name, int maxSize) {
        CacheConfig cfg = new CacheConfig.Builder()
            .maxSize(maxSize).eviction(EvictionPolicy.LRU).build();
        return new Cache<>(name, cfg, new LRUStrategy<>());
    }

    public static <V> Cache<V> createLFU(String name, int maxSize) {
        CacheConfig cfg = new CacheConfig.Builder()
            .maxSize(maxSize).eviction(EvictionPolicy.LFU).build();
        return new Cache<>(name, cfg, new LFUStrategy<>());
    }

    public static <V> Cache<V> createTTL(String name, int maxSize, long ttlSeconds) {
        CacheConfig cfg = new CacheConfig.Builder()
            .maxSize(maxSize).eviction(EvictionPolicy.LRU)
            .ttl(ttlSeconds).build();
        return new Cache<>(name, cfg, new LRUStrategy<>());
    }

    public static <V> Cache<V> createFIFO(String name, int maxSize) {
        CacheConfig cfg = new CacheConfig.Builder()
            .maxSize(maxSize).eviction(EvictionPolicy.FIFO).build();
        return new Cache<>(name, cfg, new FIFOStrategy<>());
    }

    public static <V> DistributedCache<V> createDistributed(
            String name, int maxSizePerNode,
            EvictionPolicy policy, int virtualNodes,
            String... nodeIds) {
        CacheConfig cfg = new CacheConfig.Builder()
            .maxSize(maxSizePerNode).eviction(policy).build();
        DistributedCache<V> cache = new DistributedCache<>(name, cfg, virtualNodes);
        for (String nodeId : nodeIds) cache.addNode(nodeId);
        return cache;
    }
}

// ==========================================
// 11. CACHE MANAGER — SINGLETON
// Registry of all caches in the system
// ==========================================
class CacheManager {
    private static CacheManager instance;
    private final  Map<String, Object> cacheRegistry = new ConcurrentHashMap<>();

    private CacheManager() {}

    public static synchronized CacheManager getInstance() {
        if (instance == null) instance = new CacheManager();
        return instance;
    }

    public <V> void register(String name, Cache<V> cache) {
        cacheRegistry.put(name, cache);
        System.out.println("[Manager] Registered cache: " + name);
    }

    public void registerDistributed(String name, DistributedCache<?> cache) {
        cacheRegistry.put(name, cache);
        System.out.println("[Manager] Registered distributed cache: " + name);
    }

    @SuppressWarnings("unchecked")
    public <V> Cache<V> getCache(String name) {
        return (Cache<V>) cacheRegistry.get(name);
    }

    @SuppressWarnings("unchecked")
    public <V> DistributedCache<V> getDistributedCache(String name) {
        return (DistributedCache<V>) cacheRegistry.get(name);
    }

    public void printAllStats() {
        System.out.println("\n[CacheManager] All cache stats:");
        cacheRegistry.forEach((name, cache) -> {
            if (cache instanceof Cache<?> c) c.printStats();
            else if (cache instanceof DistributedCache<?> dc) dc.printAllStats();
        });
    }
}

// ==========================================
// 12. WRITE POLICIES (simulation)
// ==========================================
class DataStore {
    // Simulates the backing database
    private final Map<String, String> db = new HashMap<>();

    public void write(String key, String value) {
        db.put(key, value);
        System.out.println("[DB] Written: " + key + " = " + value);
    }

    public String read(String key) {
        return db.getOrDefault(key, null);
    }
}

class WriteThroughCache {
    // Every write goes to both cache AND DB immediately
    private final Cache<String>  cache;
    private final DataStore      db;

    public WriteThroughCache(Cache<String> cache, DataStore db) {
        this.cache = cache;
        this.db    = db;
    }

    public void put(String key, String value) {
        cache.put(key, value);  // write to cache
        db.write(key, value);   // AND to DB immediately
    }

    public Optional<String> get(String key) {
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) return cached;
        // Cache miss → read from DB → populate cache
        String fromDb = db.read(key);
        if (fromDb != null) cache.put(key, fromDb);
        return Optional.ofNullable(fromDb);
    }
}

class WriteBackCache {
    // Write to cache only; flush to DB asynchronously (dirty buffer)
    private final Cache<String>      cache;
    private final DataStore          db;
    private final Map<String,String> dirtyBuffer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher =
        Executors.newSingleThreadScheduledExecutor();

    public WriteBackCache(Cache<String> cache, DataStore db, long flushIntervalMs) {
        this.cache = cache;
        this.db    = db;
        // Flush dirty buffer to DB every N milliseconds
        flusher.scheduleAtFixedRate(this::flushDirty,
            flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void put(String key, String value) {
        cache.put(key, value);          // write to cache only
        dirtyBuffer.put(key, value);    // mark dirty — will flush later
        System.out.println("[WriteBack] Buffered: " + key);
    }

    private void flushDirty() {
        if (!dirtyBuffer.isEmpty()) {
            System.out.println("[WriteBack] Flushing " + dirtyBuffer.size() +
                " entries to DB");
            dirtyBuffer.forEach(db::write);
            dirtyBuffer.clear();
        }
    }

    public Optional<String> get(String key) { return cache.get(key); }

    public void shutdown() { flusher.shutdown(); }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class DistributedCacheLLD {
    public static void main(String[] args) throws InterruptedException {

        CacheManager manager = CacheManager.getInstance();

        // ===== SCENARIO 1: LRU Cache =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: LRU Cache (maxSize=4)");
        System.out.println("=".repeat(55));

        Cache<String> lruCache = CacheFactory.createLRU("session-store", 4);
        manager.register("session-store", lruCache);

        lruCache.put("user:1", "Alice");
        lruCache.put("user:2", "Bob");
        lruCache.put("user:3", "Carol");
        lruCache.put("user:4", "Dave");

        // Access user:1 — makes it most recently used
        System.out.println("\nAccessing user:1 to make it MRU:");
        lruCache.get("user:1");

        // user:2 is now LRU (not accessed since put)
        System.out.println("\nAdding user:5 — should evict user:2 (LRU):");
        lruCache.put("user:5", "Eve");

        System.out.println("user:2 in cache: " + lruCache.get("user:2").orElse("EVICTED"));
        System.out.println("user:1 in cache: " + lruCache.get("user:1").orElse("EVICTED"));

        // ===== SCENARIO 2: LFU Cache =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: LFU Cache (maxSize=3)");
        System.out.println("=".repeat(55));

        Cache<Integer> lfuCache = CacheFactory.createLFU("product-cache", 3);
        manager.register("product-cache", lfuCache);

        lfuCache.put("prod:A", 1000);
        lfuCache.put("prod:B", 2000);
        lfuCache.put("prod:C", 3000);

        // Access prod:A 3 times, prod:B 1 time, prod:C 0 times
        System.out.println("\nBuilding access frequencies:");
        lfuCache.get("prod:A"); lfuCache.get("prod:A"); lfuCache.get("prod:A"); // freq=3
        lfuCache.get("prod:B");                                                  // freq=1
        // prod:C never accessed → freq=0

        System.out.println("\nAdding prod:D — should evict prod:C (LFU, freq=0):");
        lfuCache.put("prod:D", 4000);
        System.out.println("prod:C: " + lfuCache.get("prod:C").orElse("EVICTED"));
        System.out.println("prod:A: " + lfuCache.get("prod:A").orElse("EVICTED"));

        // ===== SCENARIO 3: FIFO Cache =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: FIFO Cache (maxSize=3)");
        System.out.println("=".repeat(55));

        Cache<String> fifoCache = CacheFactory.createFIFO("token-cache", 3);

        fifoCache.put("token:1", "jwt_abc");
        fifoCache.put("token:2", "jwt_def");
        fifoCache.put("token:3", "jwt_ghi");
        // Access token:1 — but FIFO doesn't care about access order
        fifoCache.get("token:1");
        System.out.println("\nAdding token:4 — should evict token:1 (oldest insertion):");
        fifoCache.put("token:4", "jwt_jkl");
        System.out.println("token:1: " + fifoCache.get("token:1").orElse("EVICTED"));
        System.out.println("token:2: " + fifoCache.get("token:2").orElse("STILL IN"));

        // ===== SCENARIO 4: TTL Expiry =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: TTL-based Expiry");
        System.out.println("=".repeat(55));

        Cache<String> ttlCache = CacheFactory.createTTL("otp-cache", 100, 0);
        manager.register("otp-cache", ttlCache);

        // OTP expires in 2 seconds
        ttlCache.put("otp:alice", "482910", 2);
        ttlCache.put("otp:bob",   "739201", 10); // expires in 10s

        System.out.println("Immediately: otp:alice = " +
            ttlCache.get("otp:alice").orElse("EXPIRED"));

        System.out.println("Waiting 2.5 seconds...");
        Thread.sleep(2500);

        System.out.println("After 2.5s: otp:alice = " +
            ttlCache.get("otp:alice").orElse("EXPIRED"));
        System.out.println("After 2.5s: otp:bob   = " +
            ttlCache.get("otp:bob").orElse("EXPIRED"));

        // ===== SCENARIO 5: Consistent Hash Ring =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Consistent Hashing Ring");
        System.out.println("=".repeat(55));

        ConsistentHashRing ring = new ConsistentHashRing(100); // 100 virtual nodes
        ring.addNode("node-1");
        ring.addNode("node-2");
        ring.addNode("node-3");

        List<String> keys = List.of(
            "user:1","user:2","user:3","user:4","user:5",
            "product:10","product:20","session:abc","session:xyz",
            "cart:100","cart:200","order:999"
        );

        System.out.println("\nKey → Node routing:");
        keys.forEach(k -> System.out.printf("  %-20s → %s%n", k, ring.getNode(k)));

        ring.printDistribution(keys);

        // ---- Simulate node failure — see key remapping ----
        System.out.println("\n[Ring] node-2 goes down:");
        ring.removeNode("node-2");
        System.out.println("Remapped keys (only node-2's keys moved):");
        keys.forEach(k -> System.out.printf("  %-20s → %s%n", k, ring.getNode(k)));

        ring.printDistribution(keys);

        // ===== SCENARIO 6: Distributed Cache (3 shards) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Distributed Cache (3 shards)");
        System.out.println("=".repeat(55));

        DistributedCache<String> distCache = CacheFactory.createDistributed(
            "global-cache", 1000, EvictionPolicy.LRU, 150,
            "shard-1", "shard-2", "shard-3");

        manager.registerDistributed("global-cache", distCache);

        // Put across shards
        distCache.put("session:alice", "token_alice_xyz");
        distCache.put("session:bob",   "token_bob_abc");
        distCache.put("product:iphone","₹79900");
        distCache.put("rate:client_A", "100");
        distCache.put("otp:9999",      "492810");

        System.out.println("\nGets from distributed cache:");
        System.out.println("session:alice → " + distCache.get("session:alice").orElse("MISS"));
        System.out.println("product:iphone→ " + distCache.get("product:iphone").orElse("MISS"));
        System.out.println("session:carol  → " + distCache.get("session:carol").orElse("MISS"));

        // Add a new shard — consistent hashing means minimal remapping
        System.out.println("\n[Distributed] Adding shard-4 (scale out):");
        distCache.addNode("shard-4");
        System.out.println("Existing keys still reachable:");
        System.out.println("session:alice → " + distCache.get("session:alice").orElse("MISS*"));

        // Remove shard — simulate node failure
        System.out.println("\n[Distributed] shard-1 goes down:");
        distCache.removeNode("shard-1");

        // ===== SCENARIO 7: Write-Through Policy =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Write-Through Policy");
        System.out.println("=".repeat(55));

        DataStore db = new DataStore();
        WriteThroughCache wtCache = new WriteThroughCache(
            CacheFactory.createLRU("wt-cache", 100), db);

        wtCache.put("config:max-connections", "500");
        wtCache.put("config:timeout",         "30s");
        System.out.println("Get from cache: " +
            wtCache.get("config:max-connections").orElse("MISS"));

        // ===== SCENARIO 8: Write-Back Policy =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Write-Back Policy (async DB flush)");
        System.out.println("=".repeat(55));

        WriteBackCache wbCache = new WriteBackCache(
            CacheFactory.createLRU("wb-cache", 100), db, 500);

        wbCache.put("analytics:clicks", "1500");
        wbCache.put("analytics:views",  "8200");
        System.out.println("Dirty buffer will flush to DB in 500ms...");
        Thread.sleep(700); // let flush happen

        System.out.println("Get from cache: " +
            wbCache.get("analytics:clicks").orElse("MISS"));
        wbCache.shutdown();

        // ===== ANALYTICS =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("CACHE STATS REPORT");
        System.out.println("=".repeat(55));
        lruCache.printStats();
        lfuCache.printStats();
        ttlCache.printStats();
        distCache.printAllStats();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | CacheManager
            Strategy   | EvictionStrategy (LRU / LFU / FIFO)
            Factory    | CacheFactory (createLRU / LFU / TTL / Distributed)
            Builder    | CacheEntry.Builder, CacheConfig.Builder
            Observer   | CacheEventObserver (LoggingObserver)
            Decorator  | WriteThroughCache / WriteBackCache wraps Cache
            Template   | ConsistentHashRing (node distribution algorithm)
            """);

        // Cleanup
        lruCache.shutdown();
        lfuCache.shutdown();
        fifoCache.shutdown();
        ttlCache.shutdown();
        distCache.shutdown();
    }
}
