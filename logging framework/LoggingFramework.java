import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// ==========================================
// LOGGING FRAMEWORK LLD
// Patterns:
//   Singleton   — Logger (one instance per name)
//   Chain of Responsibility — log level filtering
//   Strategy    — LogFormatter (text vs JSON vs XML)
//   Observer    — LogAppender (console, file, remote)
//   Builder     — LogRecord construction
//   Factory     — LoggerFactory
// ==========================================

// ==========================================
// 1. LOG LEVEL ENUM (ordered by severity)
// ==========================================
enum LogLevel {
    DEBUG(0), INFO(1), WARN(2), ERROR(3), FATAL(4);

    private final int severity;
    LogLevel(int severity) { this.severity = severity; }
    public int getSeverity() { return severity; }
    public boolean isEnabledFor(LogLevel configured) {
        return this.severity >= configured.severity;
    }
}

// ==========================================
// 2. LOG RECORD — BUILDER PATTERN
// Immutable value object carrying all log data
// ==========================================
class LogRecord {
    private final String        loggerName;
    private final LogLevel      level;
    private final String        message;
    private final String        threadName;
    private final LocalDateTime timestamp;
    private final String        className;
    private final String        methodName;
    private final Throwable     throwable;
    private final Map<String, String> mdc; // Mapped Diagnostic Context

    private LogRecord(Builder b) {
        this.loggerName = b.loggerName;
        this.level      = b.level;
        this.message    = b.message;
        this.threadName = Thread.currentThread().getName();
        this.timestamp  = LocalDateTime.now();
        this.className  = b.className;
        this.methodName = b.methodName;
        this.throwable  = b.throwable;
        this.mdc        = Collections.unmodifiableMap(new HashMap<>(b.mdc));
    }

    public String        getLoggerName() { return loggerName; }
    public LogLevel      getLevel()      { return level; }
    public String        getMessage()    { return message; }
    public String        getThreadName() { return threadName; }
    public LocalDateTime getTimestamp()  { return timestamp; }
    public String        getClassName()  { return className; }
    public String        getMethodName() { return methodName; }
    public Throwable     getThrowable()  { return throwable; }
    public Map<String, String> getMdc()  { return mdc; }

    // ---- BUILDER ----
    static class Builder {
        private String loggerName = "root";
        private LogLevel level;
        private String message;
        private String className  = "";
        private String methodName = "";
        private Throwable throwable;
        private final Map<String, String> mdc = new HashMap<>();

        public Builder loggerName(String n) { this.loggerName = n; return this; }
        public Builder level(LogLevel l)    { this.level = l;       return this; }
        public Builder message(String m)    { this.message = m;     return this; }
        public Builder className(String c)  { this.className = c;   return this; }
        public Builder methodName(String m) { this.methodName = m;  return this; }
        public Builder throwable(Throwable t){ this.throwable = t;  return this; }
        public Builder mdc(String k, String v){ this.mdc.put(k,v); return this; }
        public LogRecord build()            { return new LogRecord(this); }
    }
}

// ==========================================
// 3. FORMATTER — STRATEGY PATTERN
// Converts LogRecord → String in different formats
// ==========================================
interface LogFormatter {
    String getName();
    String format(LogRecord record);
}

class TextFormatter implements LogFormatter {
    private static final DateTimeFormatter DTF =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override public String getName() { return "TEXT"; }

    @Override
    public String format(LogRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getTimestamp().format(DTF))
          .append(" [").append(r.getThreadName()).append("]")
          .append(" ").append(String.format("%-5s", r.getLevel()))
          .append(" ").append(r.getLoggerName())
          .append(" - ").append(r.getMessage());

        if (!r.getMdc().isEmpty()) {
            sb.append(" {MDC:").append(r.getMdc()).append("}");
        }
        if (r.getThrowable() != null) {
            sb.append("\n  Exception: ").append(r.getThrowable().getMessage());
        }
        return sb.toString();
    }
}

class JsonFormatter implements LogFormatter {
    @Override public String getName() { return "JSON"; }

    @Override
    public String format(LogRecord r) {
        return "{" +
            "\"ts\":\"" + r.getTimestamp() + "\"," +
            "\"level\":\"" + r.getLevel() + "\"," +
            "\"logger\":\"" + r.getLoggerName() + "\"," +
            "\"thread\":\"" + r.getThreadName() + "\"," +
            "\"msg\":\"" + r.getMessage().replace("\"", "\\\"") + "\"," +
            "\"mdc\":" + mapToJson(r.getMdc()) +
            (r.getThrowable() != null ?
                ",\"error\":\"" + r.getThrowable().getMessage() + "\"" : "") +
            "}";
    }

    private String mapToJson(Map<String, String> map) {
        if (map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) ->
            sb.append("\"").append(k).append("\":\"").append(v).append("\","));
        sb.setCharAt(sb.length() - 1, '}');
        return sb.toString();
    }
}

class PatternFormatter implements LogFormatter {
    private final String pattern; // e.g. "%d [%t] %L %n - %m"

    public PatternFormatter(String pattern) { this.pattern = pattern; }

    @Override public String getName() { return "PATTERN"; }

    @Override
    public String format(LogRecord r) {
        return pattern
            .replace("%d", r.getTimestamp().toString())
            .replace("%t", r.getThreadName())
            .replace("%L", r.getLevel().toString())
            .replace("%n", r.getLoggerName())
            .replace("%c", r.getClassName())
            .replace("%m", r.getMessage());
    }
}

// ==========================================
// 4. FILTER — CHAIN OF RESPONSIBILITY
// Each filter decides: pass record on, block, or transform
// ==========================================
interface LogFilter {
    boolean accept(LogRecord record);
}

class LevelFilter implements LogFilter {
    private final LogLevel minLevel;
    public LevelFilter(LogLevel minLevel) { this.minLevel = minLevel; }

    @Override
    public boolean accept(LogRecord r) {
        return r.getLevel().isEnabledFor(minLevel);
    }
}

class KeywordFilter implements LogFilter {
    private final List<String> blockedKeywords;

    public KeywordFilter(String... keywords) {
        this.blockedKeywords = List.of(keywords);
    }

    @Override
    public boolean accept(LogRecord r) {
        String msg = r.getMessage().toLowerCase();
        return blockedKeywords.stream().noneMatch(kw -> msg.contains(kw.toLowerCase()));
    }
}

class SamplingFilter implements LogFilter {
    private final double sampleRate; // 0.0 to 1.0 — useful for DEBUG in production
    private final Random random = new Random();

    public SamplingFilter(double sampleRate) { this.sampleRate = sampleRate; }

    @Override
    public boolean accept(LogRecord r) {
        if (r.getLevel().getSeverity() >= LogLevel.WARN.getSeverity()) return true;
        return random.nextDouble() < sampleRate;
    }
}

// ==========================================
// 5. APPENDER — OBSERVER PATTERN
// Each appender is a subscriber that receives log records
// ==========================================
interface LogAppender {
    String getName();
    void append(LogRecord record);
    void setFormatter(LogFormatter formatter);
    void addFilter(LogFilter filter);
    void close();
}

abstract class BaseAppender implements LogAppender {
    protected final String name;
    protected LogFormatter formatter = new TextFormatter();
    protected final List<LogFilter> filters = new ArrayList<>();

    protected BaseAppender(String name) { this.name = name; }

    @Override public String getName() { return name; }
    @Override public void setFormatter(LogFormatter f) { this.formatter = f; }
    @Override public void addFilter(LogFilter f) { filters.add(f); }

    protected boolean passesFilters(LogRecord r) {
        return filters.stream().allMatch(f -> f.accept(r));
    }

    @Override public void close() {} // override if needed
}

// Console Appender
class ConsoleAppender extends BaseAppender {
    private final PrintStream out;

    public ConsoleAppender(String name) {
        super(name);
        this.out = System.out;
    }

    @Override
    public void append(LogRecord record) {
        if (!passesFilters(record)) return;
        String line = formatter.format(record);
        // Color-code by level
        String colored = switch (record.getLevel()) {
            case DEBUG -> "\u001B[36m" + line + "\u001B[0m"; // Cyan
            case INFO  -> "\u001B[32m" + line + "\u001B[0m"; // Green
            case WARN  -> "\u001B[33m" + line + "\u001B[0m"; // Yellow
            case ERROR -> "\u001B[31m" + line + "\u001B[0m"; // Red
            case FATAL -> "\u001B[35m" + line + "\u001B[0m"; // Magenta
        };
        out.println(colored);
    }
}

// File Appender (with rolling)
class FileAppender extends BaseAppender {
    private final String filePath;
    private final long   maxSizeBytes;
    private long         currentSizeBytes = 0;
    private int          rollCount = 0;
    private PrintWriter  writer;

    public FileAppender(String name, String filePath, long maxSizeBytes) {
        super(name);
        this.filePath     = filePath;
        this.maxSizeBytes = maxSizeBytes;
        openFile(filePath);
    }

    private void openFile(String path) {
        try {
            writer = new PrintWriter(new FileWriter(path, true));
        } catch (IOException e) {
            System.err.println("[FileAppender] Failed to open: " + path);
        }
    }

    @Override
    public synchronized void append(LogRecord record) {
        if (!passesFilters(record)) return;
        String line = formatter.format(record);
        writer.println(line);
        writer.flush();
        currentSizeBytes += line.getBytes().length;

        // Roll file if size exceeded
        if (currentSizeBytes >= maxSizeBytes) rollFile();
    }

    private void rollFile() {
        writer.close();
        rollCount++;
        String rolled = filePath + "." + rollCount;
        new File(filePath).renameTo(new File(rolled));
        System.out.println("[FileAppender] Rolled → " + rolled);
        openFile(filePath);
        currentSizeBytes = 0;
    }

    @Override
    public synchronized void close() {
        if (writer != null) writer.close();
    }
}

// Async Appender — wraps another appender, buffers writes
class AsyncAppender extends BaseAppender {
    private final LogAppender delegate;
    private final BlockingQueue<LogRecord> queue;
    private final ExecutorService executor;
    private volatile boolean running = true;
    private final AtomicLong droppedCount = new AtomicLong(0);

    public AsyncAppender(String name, LogAppender delegate, int bufferSize) {
        super(name);
        this.delegate = delegate;
        this.queue    = new LinkedBlockingQueue<>(bufferSize);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-log-writer");
            t.setDaemon(true);
            return t;
        });

        // Background thread drains queue
        executor.submit(() -> {
            while (running || !queue.isEmpty()) {
                try {
                    LogRecord r = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (r != null) delegate.append(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    @Override
    public void append(LogRecord record) {
        if (!queue.offer(record)) {
            droppedCount.incrementAndGet();
            // Never block the caller — drop if full
        }
    }

    public long getDroppedCount() { return droppedCount.get(); }

    @Override
    public void close() {
        running = false;
        executor.shutdown();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        delegate.close();
    }
}

// Remote Appender — sends to central log server (e.g. Elasticsearch/Loki)
class RemoteAppender extends BaseAppender {
    private final String endpoint;
    private final List<LogRecord> buffer = new ArrayList<>();
    private final int batchSize;

    public RemoteAppender(String name, String endpoint, int batchSize) {
        super(name);
        this.endpoint  = endpoint;
        this.batchSize = batchSize;
        this.formatter = new JsonFormatter(); // remote always JSON
    }

    @Override
    public synchronized void append(LogRecord record) {
        if (!passesFilters(record)) return;
        buffer.add(record);
        if (buffer.size() >= batchSize) flush();
    }

    private void flush() {
        System.out.println("[RemoteAppender] Sending " + buffer.size() +
            " records to " + endpoint);
        // Simulate HTTP POST to Elasticsearch / Loki / Splunk
        buffer.forEach(r -> {/* HTTP call */});
        buffer.clear();
    }

    @Override public void close() { if (!buffer.isEmpty()) flush(); }
}

// ==========================================
// 6. LOGGER — CORE CLASS
// One per name (package/class), hierarchy-aware
// ==========================================
class Logger {
    private final String name;
    private LogLevel level;
    private final List<LogAppender> appenders = new ArrayList<>();
    private Logger parent; // hierarchy: com.app → com → root
    private boolean propagate = true; // send to parent appenders too
    private final MDCContext mdc;

    Logger(String name, LogLevel level, MDCContext mdc) {
        this.name  = name;
        this.level = level;
        this.mdc   = mdc;
    }

    public void addAppender(LogAppender appender) { appenders.add(appender); }
    public void setLevel(LogLevel level)           { this.level = level; }
    public void setParent(Logger parent)           { this.parent = parent; }
    public void setPropagate(boolean p)            { this.propagate = p; }
    public String getName()                        { return name; }

    // Core log methods
    public void debug(String msg) { log(LogLevel.DEBUG, msg, null); }
    public void info(String msg)  { log(LogLevel.INFO,  msg, null); }
    public void warn(String msg)  { log(LogLevel.WARN,  msg, null); }
    public void error(String msg) { log(LogLevel.ERROR, msg, null); }
    public void fatal(String msg) { log(LogLevel.FATAL, msg, null); }

    public void error(String msg, Throwable t) { log(LogLevel.ERROR, msg, t); }
    public void warn(String msg,  Throwable t) { log(LogLevel.WARN,  msg, t); }

    private void log(LogLevel msgLevel, String msg, Throwable t) {
        if (!msgLevel.isEnabledFor(level)) return; // fast path

        // Capture calling class + method via stack trace
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String callerClass  = stack.length > 3 ? stack[3].getClassName()  : "";
        String callerMethod = stack.length > 3 ? stack[3].getMethodName() : "";

        LogRecord record = new LogRecord.Builder()
            .loggerName(name)
            .level(msgLevel)
            .message(msg)
            .className(callerClass)
            .methodName(callerMethod)
            .throwable(t)
            .mdc("requestId", mdc.get("requestId"))
            .mdc("userId",    mdc.get("userId"))
            .build();

        dispatch(record);
    }

    private void dispatch(LogRecord record) {
        // Append to own appenders
        appenders.forEach(a -> a.append(record));

        // Propagate to parent (like Log4j hierarchy)
        if (propagate && parent != null) {
            parent.dispatch(record);
        }
    }

    public void close() { appenders.forEach(LogAppender::close); }
}

// ==========================================
// 7. MDC — MAPPED DIAGNOSTIC CONTEXT
// Thread-local key-value store for correlation IDs
// ==========================================
class MDCContext {
    private final ThreadLocal<Map<String, String>> context =
        ThreadLocal.withInitial(HashMap::new);

    public void put(String key, String value) { context.get().put(key, value); }
    public String get(String key)             { return context.get().getOrDefault(key, ""); }
    public void remove(String key)            { context.get().remove(key); }
    public void clear()                       { context.get().clear(); }
    public Map<String, String> getAll()       { return Collections.unmodifiableMap(context.get()); }
}

// ==========================================
// 8. LOGGER FACTORY — FACTORY + SINGLETON REGISTRY
// getLogger("name") always returns same instance for same name
// ==========================================
class LoggerFactory {
    private static final Map<String, Logger>  loggers     = new ConcurrentHashMap<>();
    private static final MDCContext           mdc         = new MDCContext();
    private static       LogLevel             rootLevel   = LogLevel.DEBUG;
    private static       List<LogAppender>    rootAppenders = new ArrayList<>();

    // Configure root logger appenders (shared)
    public static void configureRoot(LogLevel level, LogAppender... appenders) {
        rootLevel = level;
        rootAppenders = new ArrayList<>(List.of(appenders));
        // Apply to root logger
        Logger root = getOrCreate("root");
        root.setLevel(level);
        for (LogAppender a : appenders) root.addAppender(a);
    }

    // Get or create logger by name (singleton per name)
    public static Logger getLogger(String name) {
        return loggers.computeIfAbsent(name, n -> {
            Logger logger = new Logger(n, rootLevel, mdc);
            // Set parent based on name hierarchy
            Logger parent = findParent(n);
            logger.setParent(parent);
            return logger;
        });
    }

    // Convenience: get logger for a class
    public static Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    private static Logger getOrCreate(String name) {
        return loggers.computeIfAbsent(name, n -> new Logger(n, rootLevel, mdc));
    }

    private static Logger findParent(String name) {
        // com.app.service → parent is com.app → com → root
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String parentName = name.substring(0, dot);
            return getOrCreate(parentName);
        }
        return getOrCreate("root");
    }

    public static MDCContext getMDC() { return mdc; }

    public static void shutdown() {
        loggers.values().forEach(Logger::close);
        rootAppenders.forEach(LogAppender::close);
        loggers.clear();
    }
}

// ==========================================
// 9. MAIN — DRIVER CODE
// ==========================================
public class LoggingFramework {
    public static void main(String[] args) throws InterruptedException {

        // ---- SETUP: Configure root logger ----
        ConsoleAppender console = new ConsoleAppender("CONSOLE");
        console.addFilter(new LevelFilter(LogLevel.DEBUG));
        console.setFormatter(new TextFormatter());

        FileAppender file = new FileAppender("FILE",
            "/tmp/app.log", 1024 * 10); // 10KB roll size
        file.setFormatter(new PatternFormatter("%d [%t] %L %n - %m"));
        file.addFilter(new LevelFilter(LogLevel.INFO));

        RemoteAppender remote = new RemoteAppender(
            "REMOTE", "https://logs.elastic.co/bulk", 3);
        remote.addFilter(new LevelFilter(LogLevel.WARN));

        AsyncAppender asyncRemote = new AsyncAppender(
            "ASYNC_REMOTE", remote, 1000);

        LoggerFactory.configureRoot(LogLevel.DEBUG, console, asyncRemote);

        // ===== SCENARIO 1: Basic logging =====
        System.out.println("\n===== Scenario 1: Basic Logging Levels =====");
        Logger log = LoggerFactory.getLogger("com.app.service.OrderService");
        log.debug("Fetching order details from DB");
        log.info("Order #12345 placed successfully");
        log.warn("Payment gateway response slow: 2300ms");
        log.error("Failed to send confirmation email");
        log.fatal("Database connection pool exhausted!");

        // ===== SCENARIO 2: MDC — request correlation =====
        System.out.println("\n===== Scenario 2: MDC Correlation IDs =====");
        MDCContext mdc = LoggerFactory.getMDC();

        // Simulate request 1
        mdc.put("requestId", "req-abc-123");
        mdc.put("userId", "user-99");
        Logger payLog = LoggerFactory.getLogger("com.app.service.PaymentService");
        payLog.info("Processing payment of Rs5000");
        payLog.warn("Retry attempt 1 for payment gateway");
        payLog.error("Payment failed after 3 retries");
        mdc.clear();

        // Simulate request 2
        mdc.put("requestId", "req-xyz-456");
        mdc.put("userId", "user-42");
        payLog.info("Processing payment of Rs1200");
        payLog.info("Payment successful");
        mdc.clear();

        // ===== SCENARIO 3: Exception logging =====
        System.out.println("\n===== Scenario 3: Exception Logging =====");
        Logger exLog = LoggerFactory.getLogger("com.app.repository.UserRepository");
        try {
            throw new RuntimeException("Connection to PostgreSQL timed out after 5000ms");
        } catch (Exception e) {
            exLog.error("Database query failed", e);
        }

        // ===== SCENARIO 4: Logger hierarchy =====
        System.out.println("\n===== Scenario 4: Logger Hierarchy =====");
        Logger rootLogger = LoggerFactory.getLogger("com.app");
        rootLogger.setLevel(LogLevel.WARN); // suppress DEBUG/INFO for com.app subtree

        Logger childLogger = LoggerFactory.getLogger("com.app.service.InventoryService");
        childLogger.debug("This won't print — parent is WARN");
        childLogger.warn("Low stock alert: item #99");
        childLogger.error("Stock sync failed");

        // ===== SCENARIO 5: Sampling filter =====
        System.out.println("\n===== Scenario 5: Sampling Filter (50% DEBUG) =====");
        ConsoleAppender sampledConsole = new ConsoleAppender("SAMPLED");
        sampledConsole.addFilter(new SamplingFilter(0.5)); // 50% of DEBUG sampled
        sampledConsole.setFormatter(new JsonFormatter());

        Logger sampledLog = LoggerFactory.getLogger("com.app.service.SearchService");
        sampledLog.addAppender(sampledConsole);
        sampledLog.setPropagate(false); // don't bubble to root

        for (int i = 1; i <= 6; i++) {
            sampledLog.debug("Search query #" + i + " completed in 12ms");
        }
        sampledLog.error("Search index unavailable"); // errors always pass filter

        // ===== SCENARIO 6: Keyword filter =====
        System.out.println("\n===== Scenario 6: Keyword Filter (block 'password') =====");
        ConsoleAppender secureConsole = new ConsoleAppender("SECURE");
        secureConsole.addFilter(new KeywordFilter("password", "secret", "token"));

        Logger authLog = LoggerFactory.getLogger("com.app.auth.AuthService");
        authLog.addAppender(secureConsole);
        authLog.setPropagate(false);

        authLog.info("User alice logged in successfully");
        authLog.warn("Attempted to log password in message — should be blocked");
        authLog.error("Login failed for user bob");

        // ===== SCENARIO 7: Concurrent logging (thread safety) =====
        System.out.println("\n===== Scenario 7: Concurrent Logging (5 threads) =====");
        Logger concLog = LoggerFactory.getLogger("com.app.concurrent.WorkerService");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        for (int i = 1; i <= 5; i++) {
            final int id = i;
            pool.submit(() -> {
                MDCContext threadMdc = LoggerFactory.getMDC();
                threadMdc.put("requestId", "req-thread-" + id);
                concLog.info("Worker " + id + " started processing");
                concLog.info("Worker " + id + " completed task");
                threadMdc.clear();
            });
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        // ===== SCENARIO 8: JSON formatter =====
        System.out.println("\n===== Scenario 8: JSON Formatted Output =====");
        ConsoleAppender jsonConsole = new ConsoleAppender("JSON_CONSOLE");
        jsonConsole.setFormatter(new JsonFormatter());

        Logger jsonLog = LoggerFactory.getLogger("com.app.api.ApiGateway");
        jsonLog.addAppender(jsonConsole);
        jsonLog.setPropagate(false);
        jsonLog.info("Incoming request: POST /orders");
        jsonLog.warn("Rate limit at 80% for client_A");
        jsonLog.error("Downstream timeout from InventoryService");

        // Shutdown
        Thread.sleep(200); // let async flush
        LoggerFactory.shutdown();

        System.out.println("\n[Framework] Shutdown complete.");
        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern                  | Class
            -------------------------|--------------------------------------------
            Singleton                | LoggerFactory (one Logger per name)
            Builder                  | LogRecord.Builder
            Strategy                 | LogFormatter (Text / JSON / Pattern)
            Chain of Responsibility  | LogFilter chain (Level / Keyword / Sampling)
            Observer                 | LogAppender (Console / File / Remote / Async)
            Factory                  | LoggerFactory.getLogger()
            Decorator                | AsyncAppender wraps any LogAppender
            """);
    }
}
