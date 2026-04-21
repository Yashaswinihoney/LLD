import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

// ==========================================
// CIRCUIT BREAKER LLD
//
// The Circuit Breaker sits between a caller and a downstream service.
// It prevents a slow/failing downstream from cascading failure into
// the caller. Three states:
//
//   CLOSED   — normal, all requests pass through, failures counted
//   OPEN     — downstream is failing, all requests rejected fast
//   HALF_OPEN — probe: let one request through, decide based on outcome
//
// Two failure detection strategies:
//   Count-based  — open after N consecutive failures
//   Rate-based   — open when failure % in sliding window exceeds threshold
//
// Patterns:
//   Singleton  — CircuitBreakerRegistry (global registry)
//   State      — CircuitBreakerState (CLOSED / OPEN / HALF_OPEN)
//   Strategy   — FailureDetectionStrategy (count / rate)
//   Observer   — CircuitBreakerEventObserver (log, alert, metrics)
//   Decorator  — CircuitBreaker wraps any Supplier<T>
//   Builder    — CircuitBreakerConfig
//   Factory    — CircuitBreakerFactory
//   Command    — ProtectedCall (the wrapped downstream call)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum CircuitState   { CLOSED, OPEN, HALF_OPEN }
enum CallOutcome    { SUCCESS, FAILURE, REJECTED, TIMEOUT }
enum DetectionMode  { COUNT_BASED, RATE_BASED }

// ==========================================
// 2. CIRCUIT BREAKER CONFIG — BUILDER PATTERN
// ==========================================
class CircuitBreakerConfig {
    // How many consecutive failures before opening (COUNT_BASED)
    final int    failureCountThreshold;
    // What % failure rate triggers open (RATE_BASED, 0-100)
    final int    failureRateThreshold;
    // Sliding window size for rate-based detection
    final int    slidingWindowSize;
    // How long to stay OPEN before moving to HALF_OPEN (ms)
    final long   openTimeoutMs;
    // How many probe calls in HALF_OPEN before deciding
    final int    halfOpenProbeCount;
    // Call timeout — treat as failure if exceeds this (ms)
    final long   callTimeoutMs;
    // Detection mode
    final DetectionMode mode;
    // Service name — for logging / metrics
    final String  serviceName;

    private CircuitBreakerConfig(Builder b) {
        this.failureCountThreshold = b.failureCountThreshold;
        this.failureRateThreshold  = b.failureRateThreshold;
        this.slidingWindowSize     = b.slidingWindowSize;
        this.openTimeoutMs         = b.openTimeoutMs;
        this.halfOpenProbeCount    = b.halfOpenProbeCount;
        this.callTimeoutMs         = b.callTimeoutMs;
        this.mode                  = b.mode;
        this.serviceName           = b.serviceName;
    }

    @Override public String toString() {
        return "Config[" + serviceName + " | mode=" + mode +
               " | failThreshold=" + (mode == DetectionMode.COUNT_BASED
                   ? failureCountThreshold + " consecutive"
                   : failureRateThreshold + "% in window=" + slidingWindowSize) +
               " | openTimeout=" + openTimeoutMs + "ms" +
               " | callTimeout=" + callTimeoutMs + "ms]";
    }

    static class Builder {
        private int    failureCountThreshold = 5;
        private int    failureRateThreshold  = 50;
        private int    slidingWindowSize     = 10;
        private long   openTimeoutMs         = 30_000;
        private int    halfOpenProbeCount    = 1;
        private long   callTimeoutMs         = 3_000;
        private DetectionMode mode           = DetectionMode.COUNT_BASED;
        private String serviceName           = "unknown-service";

        public Builder failureCountThreshold(int n)  { this.failureCountThreshold = n;  return this; }
        public Builder failureRateThreshold(int pct) { this.failureRateThreshold = pct; return this; }
        public Builder slidingWindowSize(int n)      { this.slidingWindowSize = n;       return this; }
        public Builder openTimeoutMs(long ms)        { this.openTimeoutMs = ms;          return this; }
        public Builder halfOpenProbeCount(int n)     { this.halfOpenProbeCount = n;      return this; }
        public Builder callTimeoutMs(long ms)        { this.callTimeoutMs = ms;          return this; }
        public Builder mode(DetectionMode m)         { this.mode = m;                    return this; }
        public Builder serviceName(String s)         { this.serviceName = s;             return this; }
        public CircuitBreakerConfig build()          { return new CircuitBreakerConfig(this); }
    }
}

// ==========================================
// 3. CALL METRICS — sliding window
//    Tracks success/failure in a circular buffer
// ==========================================
class CallMetrics {
    // Circular buffer of recent outcomes (true=success, false=failure)
    private final boolean[] window;
    private final int       capacity;
    private       int       head       = 0;   // next write position
    private       int       totalCount = 0;   // total calls recorded

    private final AtomicLong successTotal  = new AtomicLong(0);
    private final AtomicLong failureTotal  = new AtomicLong(0);
    private final AtomicLong rejectedTotal = new AtomicLong(0);
    private final AtomicLong timeoutTotal  = new AtomicLong(0);

    // For consecutive failures (count-based detection)
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public CallMetrics(int windowSize) {
        this.capacity = windowSize;
        this.window   = new boolean[windowSize];
    }

    public synchronized void record(CallOutcome outcome) {
        switch (outcome) {
            case SUCCESS -> {
                successTotal.incrementAndGet();
                consecutiveFailures.set(0);   // reset on success
                writeToWindow(true);
            }
            case FAILURE -> {
                failureTotal.incrementAndGet();
                consecutiveFailures.incrementAndGet();
                writeToWindow(false);
            }
            case TIMEOUT -> {
                timeoutTotal.incrementAndGet();
                failureTotal.incrementAndGet();
                consecutiveFailures.incrementAndGet();
                writeToWindow(false);
            }
            case REJECTED -> rejectedTotal.incrementAndGet();
        }
    }

    private void writeToWindow(boolean success) {
        window[head] = success;
        head         = (head + 1) % capacity;
        if (totalCount < capacity) totalCount++;
    }

    public int getConsecutiveFailures()  { return consecutiveFailures.get(); }
    public long getTotalSuccess()         { return successTotal.get(); }
    public long getTotalFailures()        { return failureTotal.get(); }
    public long getTotalRejected()        { return rejectedTotal.get(); }

    // Failure rate across the sliding window (0-100)
    public synchronized int getFailureRatePercent() {
        if (totalCount == 0) return 0;
        int failures = 0;
        for (int i = 0; i < totalCount; i++) {
            if (!window[i]) failures++;
        }
        return (int)(100.0 * failures / totalCount);
    }

    public synchronized int getWindowFillCount() { return totalCount; }

    public void reset() {
        Arrays.fill(window, false);
        head       = 0;
        totalCount = 0;
        consecutiveFailures.set(0);
    }

    @Override public String toString() {
        return String.format("Metrics[success=%d failures=%d rejected=%d timeouts=%d " +
            "consecutiveFails=%d windowRate=%d%%]",
            successTotal.get(), failureTotal.get(), rejectedTotal.get(),
            timeoutTotal.get(), consecutiveFailures.get(),
            getFailureRatePercent());
    }
}

// ==========================================
// 4. CIRCUIT BREAKER EVENT — value object
// ==========================================
class CircuitBreakerEvent {
    private final String       serviceName;
    private final CircuitState from;
    private final CircuitState to;
    private final String       reason;
    private final LocalDateTime occurredAt;

    public CircuitBreakerEvent(String name, CircuitState from,
                                CircuitState to, String reason) {
        this.serviceName = name;
        this.from        = from;
        this.to          = to;
        this.reason      = reason;
        this.occurredAt  = LocalDateTime.now();
    }

    public CircuitState getFrom()     { return from; }
    public CircuitState getTo()       { return to; }
    public String       getReason()   { return reason; }
    public String       getService()  { return serviceName; }

    @Override public String toString() {
        return "[Event] " + serviceName + " | " + from + " → " + to +
               " | " + reason + " | " + occurredAt;
    }
}

// ==========================================
// 5. FAILURE DETECTION STRATEGY — STRATEGY PATTERN
// ==========================================
interface FailureDetectionStrategy {
    String  getName();
    // Should the circuit open based on current metrics?
    boolean shouldOpen(CallMetrics metrics, CircuitBreakerConfig config);
    // Should the circuit close based on probe results in HALF_OPEN?
    boolean shouldClose(CallMetrics probeMetrics, CircuitBreakerConfig config);
}

// Count-based: open after N consecutive failures
class CountBasedStrategy implements FailureDetectionStrategy {
    @Override public String getName() { return "Count-Based"; }

    @Override
    public boolean shouldOpen(CallMetrics metrics, CircuitBreakerConfig config) {
        boolean open = metrics.getConsecutiveFailures() >= config.failureCountThreshold;
        if (open) System.out.printf("[%s] Consecutive failures=%d >= threshold=%d → OPEN%n",
            getName(), metrics.getConsecutiveFailures(), config.failureCountThreshold);
        return open;
    }

    @Override
    public boolean shouldClose(CallMetrics probeMetrics, CircuitBreakerConfig config) {
        // Close if probe succeeded (consecutive failures reset to 0)
        return probeMetrics.getConsecutiveFailures() == 0 &&
               probeMetrics.getTotalSuccess() > 0;
    }
}

// Rate-based: open when failure % in sliding window exceeds threshold
// Window must be full before opening (avoids false positives on startup)
class RateBasedStrategy implements FailureDetectionStrategy {
    @Override public String getName() { return "Rate-Based"; }

    @Override
    public boolean shouldOpen(CallMetrics metrics, CircuitBreakerConfig config) {
        // Don't open until window is filled
        if (metrics.getWindowFillCount() < config.slidingWindowSize) return false;
        int rate  = metrics.getFailureRatePercent();
        boolean open = rate >= config.failureRateThreshold;
        if (open) System.out.printf("[%s] Failure rate=%d%% >= threshold=%d%% → OPEN%n",
            getName(), rate, config.failureRateThreshold);
        return open;
    }

    @Override
    public boolean shouldClose(CallMetrics probeMetrics, CircuitBreakerConfig config) {
        return probeMetrics.getTotalSuccess() >= config.halfOpenProbeCount;
    }
}

// ==========================================
// 6. OBSERVER — STATE CHANGE EVENTS
// ==========================================
interface CircuitBreakerEventObserver {
    void onStateChange(CircuitBreakerEvent event);
    void onCallOutcome(String serviceName, CallOutcome outcome, long durationMs);
}

class LoggingObserver implements CircuitBreakerEventObserver {
    @Override
    public void onStateChange(CircuitBreakerEvent event) {
        System.out.println("[CB-Log] " + event);
    }

    @Override
    public void onCallOutcome(String service, CallOutcome outcome, long ms) {
        System.out.printf("[CB-Log] %s | %s | %dms%n", service, outcome, ms);
    }
}

class AlertObserver implements CircuitBreakerEventObserver {
    @Override
    public void onStateChange(CircuitBreakerEvent event) {
        if (event.getTo() == CircuitState.OPEN) {
            System.out.println("[ALERT] 🔴 Circuit OPENED for: " +
                event.getService() + " — " + event.getReason());
        } else if (event.getTo() == CircuitState.CLOSED) {
            System.out.println("[ALERT] 🟢 Circuit CLOSED (recovered): " +
                event.getService());
        }
    }

    @Override public void onCallOutcome(String s, CallOutcome o, long ms) {}
}

class MetricsObserver implements CircuitBreakerEventObserver {
    private final Map<String, Map<CallOutcome, Long>> callCounts =
        new ConcurrentHashMap<>();

    @Override public void onStateChange(CircuitBreakerEvent e) {}

    @Override
    public void onCallOutcome(String service, CallOutcome outcome, long ms) {
        callCounts.computeIfAbsent(service, k -> new ConcurrentHashMap<>())
                  .merge(outcome, 1L, Long::sum);
    }

    public void printReport() {
        System.out.println("\n[Metrics Report]");
        callCounts.forEach((service, counts) ->
            System.out.println("  " + service + ": " + counts));
    }
}

// ==========================================
// 7. CIRCUIT BREAKER EXCEPTION
// ==========================================
class CircuitBreakerOpenException extends RuntimeException {
    private final String serviceName;
    private final long   retryAfterMs;

    public CircuitBreakerOpenException(String service, long retryAfterMs) {
        super("Circuit OPEN for: " + service +
              " | retry after: " + retryAfterMs + "ms");
        this.serviceName  = service;
        this.retryAfterMs = retryAfterMs;
    }

    public String getServiceName()  { return serviceName; }
    public long   getRetryAfterMs() { return retryAfterMs; }
}

class CallTimeoutException extends RuntimeException {
    public CallTimeoutException(String service, long timeoutMs) {
        super("Call timeout after " + timeoutMs + "ms for: " + service);
    }
}

// ==========================================
// 8. CIRCUIT BREAKER — CORE (Decorator + State)
//
// Wraps any Supplier<T> (downstream call) with circuit breaker logic.
// The state machine lives here.
//
//   CLOSED  → call passes through → if failures hit threshold → OPEN
//   OPEN    → call rejected immediately (fast fail)
//           → after openTimeoutMs → HALF_OPEN
//   HALF_OPEN → let one probe call through
//            → success → CLOSED
//            → failure → OPEN again
// ==========================================
class CircuitBreaker {
    private final CircuitBreakerConfig               config;
    private final FailureDetectionStrategy           strategy;
    private final CallMetrics                        metrics;
    private final List<CircuitBreakerEventObserver>  observers;
    private final AtomicReference<CircuitState>      state;
    private final ScheduledExecutorService           scheduler;

    // Timestamp when circuit opened — used to compute HALF_OPEN transition
    private volatile long openedAtMs = 0;
    // Probe metrics in HALF_OPEN (reset on every OPEN→HALF_OPEN)
    private final CallMetrics probeMetrics;
    // Guard: only one probe call at a time in HALF_OPEN
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);

    public CircuitBreaker(CircuitBreakerConfig config,
                           FailureDetectionStrategy strategy,
                           List<CircuitBreakerEventObserver> observers) {
        this.config       = config;
        this.strategy     = strategy;
        this.metrics      = new CallMetrics(config.slidingWindowSize);
        this.probeMetrics = new CallMetrics(config.halfOpenProbeCount + 1);
        this.observers    = observers;
        this.state        = new AtomicReference<>(CircuitState.CLOSED);
        this.scheduler    = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cb-" + config.serviceName);
            t.setDaemon(true);
            return t;
        });

        System.out.println("[CB] Created: " + config);
    }

    // ==========================================
    // CORE METHOD — execute a protected call
    // ==========================================
    public <T> T execute(Supplier<T> call) {
        CircuitState current = state.get();

        switch (current) {

            // ---- OPEN: reject immediately ----
            case OPEN -> {
                long elapsed  = System.currentTimeMillis() - openedAtMs;
                long remaining = config.openTimeoutMs - elapsed;

                if (elapsed >= config.openTimeoutMs) {
                    // Transition to HALF_OPEN — time to probe
                    transitionTo(CircuitState.OPEN, CircuitState.HALF_OPEN,
                        "open timeout elapsed (" + config.openTimeoutMs + "ms)");
                    return execute(call); // retry with HALF_OPEN logic
                }

                metrics.record(CallOutcome.REJECTED);
                notifyOutcome(CallOutcome.REJECTED, 0);
                throw new CircuitBreakerOpenException(config.serviceName, remaining);
            }

            // ---- HALF_OPEN: allow one probe ----
            case HALF_OPEN -> {
                // Allow only halfOpenProbeCount concurrent probes
                if (halfOpenCalls.incrementAndGet() > config.halfOpenProbeCount) {
                    halfOpenCalls.decrementAndGet();
                    metrics.record(CallOutcome.REJECTED);
                    notifyOutcome(CallOutcome.REJECTED, 0);
                    throw new CircuitBreakerOpenException(config.serviceName,
                        config.openTimeoutMs);
                }

                try {
                    T result = executeWithTimeout(call);
                    // Probe succeeded
                    probeMetrics.record(CallOutcome.SUCCESS);
                    metrics.record(CallOutcome.SUCCESS);
                    notifyOutcome(CallOutcome.SUCCESS, 0);

                    if (strategy.shouldClose(probeMetrics, config)) {
                        halfOpenCalls.set(0);
                        transitionTo(CircuitState.HALF_OPEN, CircuitState.CLOSED,
                            "probe succeeded");
                    }
                    return result;

                } catch (Exception e) {
                    // Probe failed — re-open
                    probeMetrics.record(CallOutcome.FAILURE);
                    metrics.record(CallOutcome.FAILURE);
                    notifyOutcome(CallOutcome.FAILURE, 0);
                    halfOpenCalls.set(0);
                    transitionTo(CircuitState.HALF_OPEN, CircuitState.OPEN,
                        "probe failed: " + e.getMessage());
                    throw e;
                }
            }

            // ---- CLOSED: normal path ----
            default -> { // CLOSED
                long startMs = System.currentTimeMillis();
                try {
                    T result = executeWithTimeout(call);
                    long durationMs = System.currentTimeMillis() - startMs;
                    metrics.record(CallOutcome.SUCCESS);
                    notifyOutcome(CallOutcome.SUCCESS, durationMs);
                    return result;

                } catch (CallTimeoutException e) {
                    long durationMs = System.currentTimeMillis() - startMs;
                    metrics.record(CallOutcome.TIMEOUT);
                    notifyOutcome(CallOutcome.TIMEOUT, durationMs);
                    checkAndOpen("timeout");
                    throw e;

                } catch (Exception e) {
                    long durationMs = System.currentTimeMillis() - startMs;
                    metrics.record(CallOutcome.FAILURE);
                    notifyOutcome(CallOutcome.FAILURE, durationMs);
                    checkAndOpen("failure: " + e.getMessage());
                    throw e;
                }
            }
        }
    }

    // Execute with a hard timeout
    private <T> T executeWithTimeout(Supplier<T> call) {
        Future<T> future = scheduler.submit(call::get);
        try {
            return future.get(config.callTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CallTimeoutException(config.serviceName, config.callTimeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Call interrupted", e);
        }
    }

    // Check if failures warrant opening the circuit
    private void checkAndOpen(String reason) {
        if (strategy.shouldOpen(metrics, config)) {
            transitionTo(CircuitState.CLOSED, CircuitState.OPEN, reason);
        }
    }

    // ==========================================
    // STATE TRANSITION (atomic)
    // ==========================================
    private synchronized void transitionTo(CircuitState expected,
                                            CircuitState next,
                                            String reason) {
        if (!state.compareAndSet(expected, next)) return; // already transitioned

        System.out.printf("[CB] %s: %s → %s (%s)%n",
            config.serviceName, expected, next, reason);

        CircuitBreakerEvent event = new CircuitBreakerEvent(
            config.serviceName, expected, next, reason);
        observers.forEach(o -> o.onStateChange(event));

        if (next == CircuitState.OPEN) {
            openedAtMs = System.currentTimeMillis();
            probeMetrics.reset();
            halfOpenCalls.set(0);
        }

        if (next == CircuitState.CLOSED) {
            metrics.reset();
        }
    }

    // ==========================================
    // MANUAL CONTROLS (for ops / admin panel)
    // ==========================================
    public void forceOpen(String reason) {
        CircuitState current = state.get();
        state.set(CircuitState.OPEN);
        openedAtMs = System.currentTimeMillis();
        System.out.println("[CB] " + config.serviceName + " FORCE OPENED: " + reason);
        observers.forEach(o -> o.onStateChange(new CircuitBreakerEvent(
            config.serviceName, current, CircuitState.OPEN, "FORCED: " + reason)));
    }

    public void forceClose(String reason) {
        CircuitState current = state.get();
        state.set(CircuitState.CLOSED);
        metrics.reset();
        System.out.println("[CB] " + config.serviceName + " FORCE CLOSED: " + reason);
        observers.forEach(o -> o.onStateChange(new CircuitBreakerEvent(
            config.serviceName, current, CircuitState.CLOSED, "FORCED: " + reason)));
    }

    // ==========================================
    // GETTERS
    // ==========================================
    public CircuitState getState()            { return state.get(); }
    public CallMetrics  getMetrics()          { return metrics; }
    public String       getServiceName()      { return config.serviceName; }
    public boolean      isClosed()            { return state.get() == CircuitState.CLOSED; }
    public boolean      isOpen()              { return state.get() == CircuitState.OPEN; }
    public boolean      isHalfOpen()          { return state.get() == CircuitState.HALF_OPEN; }

    private void notifyOutcome(CallOutcome outcome, long durationMs) {
        observers.forEach(o -> o.onCallOutcome(config.serviceName, outcome, durationMs));
    }

    public void shutdown() { scheduler.shutdown(); }

    @Override public String toString() {
        return "CircuitBreaker[" + config.serviceName + " | " + state.get() +
               " | " + metrics + "]";
    }
}

// ==========================================
// 9. CIRCUIT BREAKER FACTORY
// ==========================================
class CircuitBreakerFactory {
    // Count-based CB — simplest, most common
    public static CircuitBreaker countBased(String serviceName,
                                             int failureThreshold,
                                             long openTimeoutMs,
                                             List<CircuitBreakerEventObserver> observers) {
        CircuitBreakerConfig config = new CircuitBreakerConfig.Builder()
            .serviceName(serviceName)
            .mode(DetectionMode.COUNT_BASED)
            .failureCountThreshold(failureThreshold)
            .openTimeoutMs(openTimeoutMs)
            .callTimeoutMs(3_000)
            .build();
        return new CircuitBreaker(config, new CountBasedStrategy(), observers);
    }

    // Rate-based CB — better for high-traffic services
    public static CircuitBreaker rateBased(String serviceName,
                                            int failureRatePct,
                                            int windowSize,
                                            long openTimeoutMs,
                                            List<CircuitBreakerEventObserver> observers) {
        CircuitBreakerConfig config = new CircuitBreakerConfig.Builder()
            .serviceName(serviceName)
            .mode(DetectionMode.RATE_BASED)
            .failureRateThreshold(failureRatePct)
            .slidingWindowSize(windowSize)
            .openTimeoutMs(openTimeoutMs)
            .callTimeoutMs(3_000)
            .build();
        return new CircuitBreaker(config, new RateBasedStrategy(), observers);
    }
}

// ==========================================
// 10. CIRCUIT BREAKER REGISTRY — SINGLETON
//     Manage all CBs in one place
// ==========================================
class CircuitBreakerRegistry {
    private static CircuitBreakerRegistry instance;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    private CircuitBreakerRegistry() {}

    public static synchronized CircuitBreakerRegistry getInstance() {
        if (instance == null) instance = new CircuitBreakerRegistry();
        return instance;
    }

    public CircuitBreaker register(CircuitBreaker cb) {
        breakers.put(cb.getServiceName(), cb);
        System.out.println("[Registry] Registered CB: " + cb.getServiceName());
        return cb;
    }

    public Optional<CircuitBreaker> get(String serviceName) {
        return Optional.ofNullable(breakers.get(serviceName));
    }

    public void printStatus() {
        System.out.println("\n[Registry] Circuit Breaker Status:");
        breakers.forEach((name, cb) ->
            System.out.println("  " + cb));
    }

    public void forceCloseAll(String reason) {
        breakers.values().forEach(cb -> cb.forceClose(reason));
    }

    public Map<String, CircuitState> getAllStates() {
        Map<String, CircuitState> states = new LinkedHashMap<>();
        breakers.forEach((name, cb) -> states.put(name, cb.getState()));
        return states;
    }

    public void shutdownAll() {
        breakers.values().forEach(CircuitBreaker::shutdown);
    }
}

// ==========================================
// 11. RETRY WITH EXPONENTIAL BACKOFF
//     Works alongside the circuit breaker
// ==========================================
class RetryPolicy {
    private final int    maxAttempts;
    private final long   initialDelayMs;
    private final double multiplier;     // backoff multiplier
    private final long   maxDelayMs;
    private final long   jitterMs;       // random jitter to avoid thundering herd

    public RetryPolicy(int maxAttempts, long initialDelayMs,
                        double multiplier, long maxDelayMs, long jitterMs) {
        this.maxAttempts    = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.multiplier     = multiplier;
        this.maxDelayMs     = maxDelayMs;
        this.jitterMs       = jitterMs;
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(3, 100, 2.0, 5_000, 50);
    }

    public <T> T execute(Supplier<T> call, String operationName) {
        long delay     = initialDelayMs;
        Exception last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = call.get();
                if (attempt > 1)
                    System.out.printf("[Retry] %s succeeded on attempt %d%n",
                        operationName, attempt);
                return result;

            } catch (CircuitBreakerOpenException e) {
                // Don't retry when circuit is open — it's open for a reason
                System.out.println("[Retry] Circuit OPEN — no retry for: " + operationName);
                throw e;

            } catch (Exception e) {
                last = e;
                if (attempt == maxAttempts) break;

                long jitter       = (long)(Math.random() * jitterMs);
                long sleepMs      = Math.min(delay + jitter, maxDelayMs);
                System.out.printf("[Retry] %s attempt %d/%d failed: %s | wait %dms%n",
                    operationName, attempt, maxAttempts, e.getMessage(), sleepMs);
                try { Thread.sleep(sleepMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                delay = (long)(delay * multiplier);
            }
        }

        System.out.printf("[Retry] %s exhausted after %d attempts%n",
            operationName, maxAttempts);
        throw new RuntimeException("All retries exhausted for: " + operationName, last);
    }
}

// ==========================================
// 12. SIMULATED DOWNSTREAM SERVICES
// ==========================================

// A service that always works
class HealthyPaymentService {
    private static int callCount = 0;

    public String charge(String orderId, double amount) {
        callCount++;
        System.out.printf("[PaymentSvc] ✅ Charged order=%s amount=%.2f (call#%d)%n",
            orderId, amount, callCount);
        return "txn_" + callCount;
    }
}

// A service that fails intermittently, then recovers
class FlakyNotificationService {
    private int    callCount  = 0;
    private boolean recovered = false;

    public void send(String userId, String message) {
        callCount++;
        if (!recovered && callCount <= 7) {
            System.out.println("[NotifSvc] ❌ Notification service DOWN (simulated)");
            throw new RuntimeException("Connection refused to notification service");
        }
        recovered = true;
        System.out.println("[NotifSvc] ✅ Sent to " + userId + ": " + message);
    }

    public void recover() { recovered = true; }
}

// A slow service that simulates timeout
class SlowInventoryService {
    private int callCount = 0;

    public int getStock(String sku) throws InterruptedException {
        callCount++;
        if (callCount <= 4) {
            System.out.println("[InventorySvc] ⏳ Slow response (simulating)...");
            Thread.sleep(5_000); // 5s — exceeds callTimeout of 3s
        }
        System.out.println("[InventorySvc] ✅ Stock for " + sku + " = 150");
        return 150;
    }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class CircuitBreakerDemo {
    public static void main(String[] args) throws InterruptedException {

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.getInstance();
        MetricsObserver metrics = new MetricsObserver();
        List<CircuitBreakerEventObserver> observers = List.of(
            new LoggingObserver(), new AlertObserver(), metrics);

        // ===== SCENARIO 1: Count-based CB — CLOSED → OPEN → HALF_OPEN → CLOSED =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Count-Based CB (threshold=3 consecutive failures)");
        System.out.println("=".repeat(60));

        CircuitBreaker paymentCB = registry.register(
            CircuitBreakerFactory.countBased(
                "payment-service", 3, 2_000, observers));

        HealthyPaymentService paymentSvc = new HealthyPaymentService();

        // 3 successful calls — CB stays CLOSED
        System.out.println("-- Successful calls --");
        for (int i = 1; i <= 3; i++) {
            try {
                paymentCB.execute(() -> paymentSvc.charge("ORD-00" + 1, 999.00));
            } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
        }
        System.out.println("State after 3 successes: " + paymentCB.getState());

        // Simulate failures by calling a broken downstream
        System.out.println("\n-- Injecting failures --");
        for (int i = 1; i <= 5; i++) {
            try {
                int attempt = i;
                paymentCB.execute(() -> {
                    throw new RuntimeException("DB connection pool exhausted (attempt " +
                        attempt + ")");
                });
            } catch (CircuitBreakerOpenException e) {
                System.out.println("⚡ FAST FAIL: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Failure " + i + ": " + e.getMessage());
            }
        }

        System.out.println("State after failures: " + paymentCB.getState());
        System.out.println(paymentCB.getMetrics());

        // ===== SCENARIO 2: Wait for OPEN → HALF_OPEN transition =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: OPEN → HALF_OPEN (wait for timeout)");
        System.out.println("=".repeat(60));

        System.out.println("Waiting 2.5s for open timeout (configured: 2s)...");
        Thread.sleep(2_500);

        // First call after timeout → HALF_OPEN probe
        System.out.println("State should be HALF_OPEN now, sending probe:");
        try {
            paymentCB.execute(() -> paymentSvc.charge("ORD-PROBE", 100.00));
        } catch (Exception e) {
            System.out.println("Probe error: " + e.getMessage());
        }
        System.out.println("State after probe: " + paymentCB.getState());

        // ===== SCENARIO 3: Flaky service — multiple OPEN/CLOSE cycles =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Flaky Notification Service (fails 7x then recovers)");
        System.out.println("=".repeat(60));

        CircuitBreaker notifCB = registry.register(
            CircuitBreakerFactory.countBased(
                "notification-service", 3, 1_500, observers));

        FlakyNotificationService notifSvc = new FlakyNotificationService();

        for (int i = 1; i <= 12; i++) {
            int userId = i;
            try {
                notifCB.execute(() -> {
                    notifSvc.send("user-" + userId, "Order confirmed!");
                    return null;
                });
            } catch (CircuitBreakerOpenException e) {
                System.out.println("⚡ Notif FAST FAIL (user-" + userId + ")");
            } catch (Exception e) {
                System.out.println("Notif failure " + i + ": " + e.getMessage());
            }

            // Wait for HALF_OPEN when circuit opens
            if (notifCB.isOpen() && i == 4) {
                System.out.println("Waiting 2s for HALF_OPEN...");
                Thread.sleep(2_000);
            }
        }

        System.out.println("Final notif CB state: " + notifCB.getState());

        // ===== SCENARIO 4: Rate-based CB =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Rate-Based CB (50% failure rate in window of 10)");
        System.out.println("=".repeat(60));

        CircuitBreaker inventoryCB = registry.register(
            CircuitBreakerFactory.rateBased(
                "inventory-service", 50, 10, 2_000, observers));

        // Mix of successes and failures
        System.out.println("-- Sending 10 mixed calls (5 success, 5 failure) --");
        for (int i = 1; i <= 10; i++) {
            int call = i;
            try {
                inventoryCB.execute(() -> {
                    if (call % 2 == 0) throw new RuntimeException("Inventory timeout");
                    return "stock:" + call;
                });
            } catch (CircuitBreakerOpenException e) {
                System.out.println("⚡ Inventory FAST FAIL");
            } catch (Exception e) {
                System.out.println("Inventory fail " + call + ": " + e.getMessage());
            }
        }
        System.out.println("Inventory CB state: " + inventoryCB.getState());
        System.out.println("Failure rate: " + inventoryCB.getMetrics().getFailureRatePercent() + "%");

        // ===== SCENARIO 5: Timeout detection =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Timeout Detection (slow service → circuit opens)");
        System.out.println("=".repeat(60));

        CircuitBreaker searchCB = registry.register(
            CircuitBreakerFactory.countBased(
                "search-service", 2, 3_000, observers));

        // Configure with shorter timeout for demo
        CircuitBreakerConfig shortTimeoutConfig = new CircuitBreakerConfig.Builder()
            .serviceName("search-service-demo")
            .mode(DetectionMode.COUNT_BASED)
            .failureCountThreshold(2)
            .openTimeoutMs(3_000)
            .callTimeoutMs(100)   // 100ms timeout — anything slower = failure
            .build();

        CircuitBreaker searchDemoCB = registry.register(
            new CircuitBreaker(shortTimeoutConfig, new CountBasedStrategy(), observers));

        for (int i = 1; i <= 4; i++) {
            try {
                searchDemoCB.execute(() -> {
                    try { Thread.sleep(200); } // 200ms > 100ms timeout
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return "search results";
                });
            } catch (CircuitBreakerOpenException e) {
                System.out.println("⚡ Search FAST FAIL: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Search timeout: " + e.getMessage());
            }
        }
        System.out.println("Search CB state: " + searchDemoCB.getState());

        // ===== SCENARIO 6: Retry with backoff + circuit breaker =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Retry + Backoff (does NOT retry when circuit OPEN)");
        System.out.println("=".repeat(60));

        CircuitBreaker emailCB = registry.register(
            CircuitBreakerFactory.countBased(
                "email-service", 2, 5_000, observers));

        RetryPolicy retry = RetryPolicy.standard();
        int failCount = 0;

        // First: fail enough to open the circuit
        for (int i = 0; i < 3; i++) {
            try {
                emailCB.execute(() -> { throw new RuntimeException("SMTP down"); });
            } catch (Exception ignored) {}
        }

        // Now try with retry — retry should NOT fire when circuit is open
        System.out.println("\nCircuit state: " + emailCB.getState());
        System.out.println("Attempting retry.execute() with open circuit:");
        try {
            retry.execute(() ->
                emailCB.execute(() -> "email sent"), "send-email");
        } catch (CircuitBreakerOpenException e) {
            System.out.println("✓ Correct — retry skipped, circuit open: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }

        // ===== SCENARIO 7: Manual force open / close =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Manual Force Open / Close (ops panel)");
        System.out.println("=".repeat(60));

        CircuitBreaker dbCB = registry.register(
            CircuitBreakerFactory.countBased("db-service", 5, 30_000, observers));

        System.out.println("State before force: " + dbCB.getState());
        dbCB.forceOpen("scheduled maintenance at 2am");
        System.out.println("State after force open: " + dbCB.getState());

        // Maintenance done
        dbCB.forceClose("maintenance complete — db restored");
        System.out.println("State after force close: " + dbCB.getState());

        // ===== REGISTRY STATUS =====
        registry.printStatus();
        metrics.printReport();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | CircuitBreakerRegistry
            State      | CircuitBreaker (CLOSED / OPEN / HALF_OPEN)
            Strategy   | FailureDetectionStrategy (Count / Rate)
            Observer   | CircuitBreakerEventObserver (Log / Alert / Metrics)
            Decorator  | CircuitBreaker.execute() wraps any Supplier<T>
            Builder    | CircuitBreakerConfig.Builder
            Factory    | CircuitBreakerFactory (countBased / rateBased)
            Command    | Supplier<T> — the protected downstream call
            """);

        registry.shutdownAll();
    }
}
