import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// ==========================================
// RATE LIMITER LLD
// Patterns: Strategy (algorithm), Factory (create limiter),
//           Singleton (RateLimiterManager), Decorator (chained limits)
// Algorithms: Token Bucket, Leaky Bucket,
//             Fixed Window Counter, Sliding Window Log
// ==========================================

// ==========================================
// 1. RATE LIMITER RESULT
// ==========================================
class RateLimitResult {
    private final boolean allowed;
    private final String  reason;
    private final long    retryAfterMs; // how long to wait before retrying

    public RateLimitResult(boolean allowed, String reason, long retryAfterMs) {
        this.allowed      = allowed;
        this.reason       = reason;
        this.retryAfterMs = retryAfterMs;
    }

    public boolean isAllowed()       { return allowed; }
    public String  getReason()       { return reason; }
    public long    getRetryAfterMs() { return retryAfterMs; }

    @Override public String toString() {
        return allowed
            ? "ALLOWED  [" + reason + "]"
            : "REJECTED [" + reason + "] retry after " + retryAfterMs + "ms";
    }
}

// ==========================================
// 2. STRATEGY INTERFACE
// ==========================================
interface RateLimitStrategy {
    String getAlgorithmName();

    // Core check — is this clientId allowed right now?
    RateLimitResult tryAcquire(String clientId);

    // Reset state for a client (useful for testing)
    void reset(String clientId);
}

// ==========================================
// 3A. TOKEN BUCKET
// Tokens refill at a steady rate.
// Bursts allowed up to bucket capacity.
// Best for: APIs that allow short bursts (Razorpay, Stripe)
// ==========================================
class TokenBucketStrategy implements RateLimitStrategy {
    private final int    capacity;       // max tokens
    private final double refillRatePerMs; // tokens added per millisecond

    // Per-client state
    private final ConcurrentHashMap<String, double[]> buckets  = new ConcurrentHashMap<>();
    // buckets[clientId] = [currentTokens, lastRefillTimestampMs]
    private final ConcurrentHashMap<String, Object>   locks    = new ConcurrentHashMap<>();

    public TokenBucketStrategy(int capacity, int refillRatePerSecond) {
        this.capacity        = capacity;
        this.refillRatePerMs = refillRatePerSecond / 1000.0;
    }

    @Override public String getAlgorithmName() { return "Token Bucket"; }

    @Override
    public RateLimitResult tryAcquire(String clientId) {
        locks.computeIfAbsent(clientId, k -> new Object());
        Object lock = locks.get(clientId);

        synchronized (lock) {
            long now = System.currentTimeMillis();
            double[] bucket = buckets.computeIfAbsent(clientId,
                k -> new double[]{capacity, now}); // start full

            // Refill tokens based on elapsed time
            double elapsed = now - bucket[1];
            double tokensToAdd = elapsed * refillRatePerMs;
            bucket[0] = Math.min(capacity, bucket[0] + tokensToAdd);
            bucket[1] = now;

            if (bucket[0] >= 1.0) {
                bucket[0] -= 1.0;
                return new RateLimitResult(true,
                    "tokens remaining: " + String.format("%.1f", bucket[0]), 0);
            } else {
                // Time until next token available
                long retryAfter = (long) ((1.0 - bucket[0]) / refillRatePerMs);
                return new RateLimitResult(false, "bucket empty", retryAfter);
            }
        }
    }

    @Override public void reset(String clientId) { buckets.remove(clientId); }
}

// ==========================================
// 3B. LEAKY BUCKET
// Requests fill a queue; processed at fixed rate.
// Bursts are smoothed out — no spikes reach downstream.
// Best for: Protecting downstream services (Swiggy order pipeline)
// ==========================================
class LeakyBucketStrategy implements RateLimitStrategy {
    private final int  capacity;       // max queue size
    private final long leakIntervalMs; // one request leaks every N ms

    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();
    // buckets[clientId] = [currentQueueSize, lastLeakTimestampMs]
    private final ConcurrentHashMap<String, Object> locks   = new ConcurrentHashMap<>();

    public LeakyBucketStrategy(int capacity, int requestsPerSecond) {
        this.capacity      = capacity;
        this.leakIntervalMs = 1000L / requestsPerSecond;
    }

    @Override public String getAlgorithmName() { return "Leaky Bucket"; }

    @Override
    public RateLimitResult tryAcquire(String clientId) {
        locks.computeIfAbsent(clientId, k -> new Object());
        Object lock = locks.get(clientId);

        synchronized (lock) {
            long now = System.currentTimeMillis();
            long[] bucket = buckets.computeIfAbsent(clientId, k -> new long[]{0, now});

            // Leak: drain requests that have been processed since last check
            long elapsed   = now - bucket[1];
            long leaked    = elapsed / leakIntervalMs;
            bucket[0]      = Math.max(0, bucket[0] - leaked);
            bucket[1]      = now;

            if (bucket[0] < capacity) {
                bucket[0]++;
                return new RateLimitResult(true,
                    "queue size: " + bucket[0] + "/" + capacity, 0);
            } else {
                long retryAfter = leakIntervalMs; // one slot opens per interval
                return new RateLimitResult(false, "bucket full", retryAfter);
            }
        }
    }

    @Override public void reset(String clientId) { buckets.remove(clientId); }
}

// ==========================================
// 3C. FIXED WINDOW COUNTER
// Count requests in current time window.
// Simple, low memory — but has boundary spike problem.
// Best for: Simple API quotas (100 req/min per user)
// ==========================================
class FixedWindowStrategy implements RateLimitStrategy {
    private final int  limit;      // max requests per window
    private final long windowMs;   // window size in milliseconds

    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();
    // windows[clientId] = [count, windowStartMs]
    private final ConcurrentHashMap<String, Object> locks   = new ConcurrentHashMap<>();

    public FixedWindowStrategy(int limit, long windowMs) {
        this.limit    = limit;
        this.windowMs = windowMs;
    }

    @Override public String getAlgorithmName() { return "Fixed Window Counter"; }

    @Override
    public RateLimitResult tryAcquire(String clientId) {
        locks.computeIfAbsent(clientId, k -> new Object());
        Object lock = locks.get(clientId);

        synchronized (lock) {
            long now    = System.currentTimeMillis();
            long[] win  = windows.computeIfAbsent(clientId, k -> new long[]{0, now});

            // New window if current one expired
            if (now - win[1] >= windowMs) {
                win[0] = 0;
                win[1] = now;
            }

            if (win[0] < limit) {
                win[0]++;
                return new RateLimitResult(true,
                    "count: " + win[0] + "/" + limit + " in window", 0);
            } else {
                long retryAfter = windowMs - (now - win[1]);
                return new RateLimitResult(false,
                    "window limit reached (" + limit + ")", retryAfter);
            }
        }
    }

    @Override public void reset(String clientId) { windows.remove(clientId); }
}

// ==========================================
// 3D. SLIDING WINDOW LOG
// Store timestamp of each request in a log.
// Accurate but memory-intensive.
// Best for: Precise rate limiting (financial APIs, login attempts)
// ==========================================
class SlidingWindowLogStrategy implements RateLimitStrategy {
    private final int  limit;    // max requests in window
    private final long windowMs; // window size

    private final ConcurrentHashMap<String, Deque<Long>> logs  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object>      locks = new ConcurrentHashMap<>();

    public SlidingWindowLogStrategy(int limit, long windowMs) {
        this.limit    = limit;
        this.windowMs = windowMs;
    }

    @Override public String getAlgorithmName() { return "Sliding Window Log"; }

    @Override
    public RateLimitResult tryAcquire(String clientId) {
        locks.computeIfAbsent(clientId, k -> new Object());
        Object lock = locks.get(clientId);

        synchronized (lock) {
            long now      = System.currentTimeMillis();
            long windowStart = now - windowMs;

            Deque<Long> log = logs.computeIfAbsent(clientId, k -> new ArrayDeque<>());

            // Remove timestamps outside the window
            while (!log.isEmpty() && log.peekFirst() <= windowStart) {
                log.pollFirst();
            }

            if (log.size() < limit) {
                log.addLast(now);
                return new RateLimitResult(true,
                    "requests in window: " + log.size() + "/" + limit, 0);
            } else {
                // Retry after oldest request in log falls out of window
                long retryAfter = log.peekFirst() - windowStart;
                return new RateLimitResult(false,
                    "window full (" + limit + " requests)", retryAfter);
            }
        }
    }

    @Override public void reset(String clientId) { logs.remove(clientId); }
}

// ==========================================
// 3E. SLIDING WINDOW COUNTER (hybrid)
// Approximates sliding window using two fixed windows.
// Low memory + more accurate than Fixed Window.
// Best for: High-scale APIs (Google, AWS)
// ==========================================
class SlidingWindowCounterStrategy implements RateLimitStrategy {
    private final int  limit;
    private final long windowMs;

    // Per client: [prevCount, currCount, currWindowStart]
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks   = new ConcurrentHashMap<>();

    public SlidingWindowCounterStrategy(int limit, long windowMs) {
        this.limit    = limit;
        this.windowMs = windowMs;
    }

    @Override public String getAlgorithmName() { return "Sliding Window Counter"; }

    @Override
    public RateLimitResult tryAcquire(String clientId) {
        locks.computeIfAbsent(clientId, k -> new Object());
        Object lock = locks.get(clientId);

        synchronized (lock) {
            long now = System.currentTimeMillis();
            long[] w = windows.computeIfAbsent(clientId, k -> new long[]{0, 0, now});

            // Slide window if needed
            long elapsed = now - w[2];
            if (elapsed >= windowMs) {
                w[0] = w[1]; // previous = current
                w[1] = 0;    // reset current
                w[2] = now;
                elapsed = 0;
            }

            // Weight: how much of the previous window overlaps current
            double prevWeight = 1.0 - (double) elapsed / windowMs;
            double estimated  = w[0] * prevWeight + w[1];

            if (estimated < limit) {
                w[1]++;
                return new RateLimitResult(true,
                    "estimated count: " + String.format("%.1f", estimated + 1) + "/" + limit, 0);
            } else {
                long retryAfter = windowMs - elapsed;
                return new RateLimitResult(false,
                    "estimated limit exceeded (" + String.format("%.1f", estimated) + ")", retryAfter);
            }
        }
    }

    @Override public void reset(String clientId) { windows.remove(clientId); }
}

// ==========================================
// 4. DECORATOR PATTERN — CHAIN MULTIPLE LIMITERS
// Example: per-second limit AND per-day limit on the same client
// ==========================================
class ChainedRateLimiter implements RateLimitStrategy {
    private final List<RateLimitStrategy> chain;

    public ChainedRateLimiter(RateLimitStrategy... strategies) {
        this.chain = List.of(strategies);
    }

    @Override public String getAlgorithmName() {
        return "Chained[" + chain.stream()
            .map(RateLimitStrategy::getAlgorithmName)
            .reduce((a, b) -> a + " + " + b)
            .orElse("") + "]";
    }

    @Override
    public RateLimitResult tryAcquire(String clientId) {
        // ALL limiters must allow — reject on first failure
        for (RateLimitStrategy strategy : chain) {
            RateLimitResult result = strategy.tryAcquire(clientId);
            if (!result.isAllowed()) {
                return new RateLimitResult(false,
                    getAlgorithmName() + " → blocked by " + strategy.getAlgorithmName()
                    + ": " + result.getReason(),
                    result.getRetryAfterMs());
            }
        }
        return new RateLimitResult(true, "all limiters passed", 0);
    }

    @Override
    public void reset(String clientId) {
        chain.forEach(s -> s.reset(clientId));
    }
}

// ==========================================
// 5. FACTORY PATTERN — CREATE LIMITER BY TYPE
// ==========================================
class RateLimiterFactory {
    public enum Algorithm {
        TOKEN_BUCKET, LEAKY_BUCKET,
        FIXED_WINDOW, SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER
    }

    // Sensible defaults — override via config in production
    public static RateLimitStrategy create(Algorithm algo, int limit, long windowMs) {
        return switch (algo) {
            case TOKEN_BUCKET ->
                new TokenBucketStrategy(limit, (int)(limit * 1000 / windowMs));
            case LEAKY_BUCKET ->
                new LeakyBucketStrategy(limit, (int)(limit * 1000 / windowMs));
            case FIXED_WINDOW ->
                new FixedWindowStrategy(limit, windowMs);
            case SLIDING_WINDOW_LOG ->
                new SlidingWindowLogStrategy(limit, windowMs);
            case SLIDING_WINDOW_COUNTER ->
                new SlidingWindowCounterStrategy(limit, windowMs);
        };
    }

    // Convenience: create chained limiter (per-second + per-day)
    public static RateLimitStrategy createChained(
            Algorithm algo, int perSecLimit, int perDayLimit) {
        return new ChainedRateLimiter(
            create(algo, perSecLimit, 1_000),
            create(algo, perDayLimit, 86_400_000L)
        );
    }
}

// ==========================================
// 6. RATE LIMITER MANAGER — SINGLETON
// Central registry: clientId → limiter
// Different clients can have different strategies
// ==========================================
class RateLimiterManager {
    private static RateLimiterManager instance;

    // clientId → their assigned strategy
    private final ConcurrentHashMap<String, RateLimitStrategy> limiters = new ConcurrentHashMap<>();

    // Default strategy for unregistered clients (safe fallback)
    private RateLimitStrategy defaultStrategy =
        RateLimiterFactory.create(
            RateLimiterFactory.Algorithm.TOKEN_BUCKET, 10, 1_000);

    private RateLimiterManager() {}

    public static synchronized RateLimiterManager getInstance() {
        if (instance == null) instance = new RateLimiterManager();
        return instance;
    }

    public void registerClient(String clientId, RateLimitStrategy strategy) {
        limiters.put(clientId, strategy);
        System.out.println("[Manager] Registered client: " + clientId +
            " → " + strategy.getAlgorithmName());
    }

    public void setDefaultStrategy(RateLimitStrategy strategy) {
        this.defaultStrategy = strategy;
    }

    public RateLimitResult check(String clientId) {
        RateLimitStrategy strategy = limiters.getOrDefault(clientId, defaultStrategy);
        RateLimitResult result = strategy.tryAcquire(clientId);
        System.out.printf("[%s] client=%-15s %s%n",
            strategy.getAlgorithmName(), clientId, result);
        return result;
    }

    public void resetClient(String clientId) {
        RateLimitStrategy s = limiters.get(clientId);
        if (s != null) s.reset(clientId);
    }

    public Map<String, RateLimitStrategy> getAllLimiters() { return limiters; }
}

// ==========================================
// 7. MAIN — DRIVER CODE
// ==========================================
public class RateLimiter {
    public static void main(String[] args) throws InterruptedException {

        RateLimiterManager manager = RateLimiterManager.getInstance();

        // ===== SCENARIO 1: TOKEN BUCKET =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Token Bucket (5 tokens, refill 2/sec)");
        System.out.println("=".repeat(55));

        manager.registerClient("alice",
            new TokenBucketStrategy(5, 2)); // capacity=5, refill 2/sec

        // Fire 7 rapid requests — first 5 pass, 6th and 7th rejected
        for (int i = 1; i <= 7; i++) {
            manager.check("alice");
        }

        System.out.println("\n  [Wait 1 second for tokens to refill...]");
        Thread.sleep(1000);
        System.out.println("  After wait — 2 new tokens refilled:");
        manager.check("alice");
        manager.check("alice");
        manager.check("alice"); // should fail

        // ===== SCENARIO 2: FIXED WINDOW =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Fixed Window (3 req/sec window)");
        System.out.println("=".repeat(55));

        manager.registerClient("bob",
            new FixedWindowStrategy(3, 1_000));

        for (int i = 1; i <= 5; i++) manager.check("bob"); // 3 pass, 2 fail

        System.out.println("\n  [Wait for new window...]");
        Thread.sleep(1100);
        System.out.println("  New window — counter reset:");
        for (int i = 1; i <= 3; i++) manager.check("bob"); // 3 pass again

        // ===== SCENARIO 3: SLIDING WINDOW LOG =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Sliding Window Log (4 req / 2 sec)");
        System.out.println("=".repeat(55));

        manager.registerClient("carol",
            new SlidingWindowLogStrategy(4, 2_000));

        for (int i = 1; i <= 6; i++) manager.check("carol");

        Thread.sleep(2100);
        System.out.println("  After window slides — old requests dropped:");
        manager.check("carol");
        manager.check("carol");

        // ===== SCENARIO 4: LEAKY BUCKET =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: Leaky Bucket (capacity=3, leaks 2/sec)");
        System.out.println("=".repeat(55));

        manager.registerClient("dave",
            new LeakyBucketStrategy(3, 2));

        for (int i = 1; i <= 5; i++) manager.check("dave");

        // ===== SCENARIO 5: SLIDING WINDOW COUNTER =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Sliding Window Counter (5 req / 1 sec)");
        System.out.println("=".repeat(55));

        manager.registerClient("eve",
            new SlidingWindowCounterStrategy(5, 1_000));

        for (int i = 1; i <= 7; i++) manager.check("eve");

        // ===== SCENARIO 6: CHAINED LIMITERS =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Chained — 3/sec AND 8/day (Token Bucket)");
        System.out.println("=".repeat(55));

        manager.registerClient("frank",
            new ChainedRateLimiter(
                new TokenBucketStrategy(3, 3),  // 3 per second
                new FixedWindowStrategy(8, 86_400_000L) // 8 per day
            ));

        for (int i = 1; i <= 5; i++) manager.check("frank");

        // ===== SCENARIO 7: UNREGISTERED CLIENT (default strategy) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Unknown client — uses default Token Bucket");
        System.out.println("=".repeat(55));

        manager.check("unknown-api-consumer"); // uses default

        // ===== SCENARIO 8: CONCURRENT REQUESTS (thread safety test) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Concurrent requests (10 threads)");
        System.out.println("=".repeat(55));

        manager.registerClient("concurrent-client",
            new TokenBucketStrategy(5, 10)); // only 5 tokens

        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicInteger passed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                RateLimitResult r = manager.check("concurrent-client");
                if (r.isAllowed()) passed.incrementAndGet();
                else rejected.incrementAndGet();
            }));
        }
        futures.forEach(f -> { try { f.get(); } catch (Exception e) {} });
        pool.shutdown();

        System.out.println("\n[Concurrent] Passed: " + passed + " | Rejected: " + rejected);
        System.out.println("(Expected: 5 passed, 5 rejected — thread-safe)");

        // ===== ALGORITHM COMPARISON =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("ALGORITHM COMPARISON SUMMARY");
        System.out.println("=".repeat(55));
        System.out.println("""
            Algorithm              | Burst | Memory | Accuracy | Best For
            -----------------------|-------|--------|----------|------------------
            Token Bucket           | YES   | Low    | High     | API bursts
            Leaky Bucket           | NO    | Low    | Medium   | Smooth traffic
            Fixed Window Counter   | YES*  | Low    | Medium   | Simple quotas
            Sliding Window Log     | NO    | High   | Exact    | Financial APIs
            Sliding Window Counter | NO    | Low    | ~95%     | High-scale APIs
            (*boundary spike problem)
            """);
    }
}
