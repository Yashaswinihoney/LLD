import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// ==========================================
// URL SHORTENER LLD
// Patterns:
//   Singleton  — UrlShortenerService
//   Strategy   — EncodingStrategy (Base62, MD5, Custom)
//   Factory    — EncodingFactory
//   Builder    — ShortUrlRequest
//   Observer   — AnalyticsObserver
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum UrlStatus { ACTIVE, EXPIRED, DELETED }

// ==========================================
// 2. SHORT URL ENTITY
// ==========================================
class ShortUrl {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final long          id;
    private final String        shortCode;     // e.g. "aB3kX9"
    private final String        longUrl;
    private final String        userId;        // who created it
    private final LocalDateTime createdAt;
    private final LocalDateTime expiresAt;     // null = never expires
    private       UrlStatus     status;
    private       long          clickCount;
    private final String        customAlias;   // null if auto-generated

    public ShortUrl(String shortCode, String longUrl, String userId,
                    LocalDateTime expiresAt, String customAlias) {
        this.id          = idGen.getAndIncrement();
        this.shortCode   = shortCode;
        this.longUrl     = longUrl;
        this.userId      = userId;
        this.createdAt   = LocalDateTime.now();
        this.expiresAt   = expiresAt;
        this.status      = UrlStatus.ACTIVE;
        this.clickCount  = 0;
        this.customAlias = customAlias;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public synchronized void incrementClick() { clickCount++; }

    public long          getId()          { return id; }
    public String        getShortCode()   { return shortCode; }
    public String        getLongUrl()     { return longUrl; }
    public String        getUserId()      { return userId; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public LocalDateTime getExpiresAt()   { return expiresAt; }
    public UrlStatus     getStatus()      { return status; }
    public long          getClickCount()  { return clickCount; }
    public String        getCustomAlias() { return customAlias; }
    public void          setStatus(UrlStatus s) { this.status = s; }

    @Override public String toString() {
        return "ShortUrl[code=" + shortCode + ", url=" + longUrl +
               ", clicks=" + clickCount + ", status=" + status +
               (expiresAt != null ? ", expires=" + expiresAt : "") + "]";
    }
}

// ==========================================
// 3. BUILDER — SHORT URL REQUEST
// ==========================================
class ShortUrlRequest {
    final String        longUrl;
    final String        userId;
    final String        customAlias;   // null = auto-generate
    final LocalDateTime expiresAt;     // null = no expiry
    final int           maxClicks;     // 0 = unlimited

    private ShortUrlRequest(Builder b) {
        this.longUrl     = b.longUrl;
        this.userId      = b.userId;
        this.customAlias = b.customAlias;
        this.expiresAt   = b.expiresAt;
        this.maxClicks   = b.maxClicks;
    }

    static class Builder {
        private final String longUrl;
        private       String        userId      = "anonymous";
        private       String        customAlias = null;
        private       LocalDateTime expiresAt   = null;
        private       int           maxClicks   = 0;

        public Builder(String longUrl)               { this.longUrl = longUrl; }
        public Builder userId(String u)              { this.userId = u;        return this; }
        public Builder customAlias(String a)         { this.customAlias = a;   return this; }
        public Builder expiresAt(LocalDateTime dt)   { this.expiresAt = dt;    return this; }
        public Builder maxClicks(int n)              { this.maxClicks = n;     return this; }
        public ShortUrlRequest build()               { return new ShortUrlRequest(this); }
    }
}

// ==========================================
// 4. STRATEGY PATTERN — ENCODING ALGORITHMS
// ==========================================
interface EncodingStrategy {
    String getName();
    // Generates a short code for the given numeric ID
    String encode(long id);
    // Decodes a short code back to its numeric ID
    long   decode(String code);
}

// Base62: uses 0-9, a-z, A-Z — 62 chars, 6 chars = 62^6 = ~56 billion combos
class Base62Strategy implements EncodingStrategy {
    private static final String CHARS =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int    BASE  = 62;
    private static final int    LEN   = 6; // target length

    @Override public String getName() { return "Base62"; }

    @Override
    public String encode(long id) {
        StringBuilder sb = new StringBuilder();
        long n = id;
        while (n > 0) {
            sb.append(CHARS.charAt((int)(n % BASE)));
            n /= BASE;
        }
        // Pad to LEN characters
        while (sb.length() < LEN) sb.append('0');
        return sb.reverse().toString();
    }

    @Override
    public long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            result = result * BASE + CHARS.indexOf(c);
        }
        return result;
    }
}

// MD5-based: takes first 6 chars of MD5 hash of the URL
// Simpler but has collision risk — production uses with collision check
class MD5Strategy implements EncodingStrategy {
    private static final int CODE_LENGTH = 7;

    @Override public String getName() { return "MD5"; }

    @Override
    public String encode(long id) {
        // In real impl: MD5(longUrl) truncated to 7 chars
        // Here we simulate with a hash of the id
        String hash = Integer.toHexString((int)(id * 2654435761L >>> 16));
        while (hash.length() < CODE_LENGTH) hash = "0" + hash;
        return hash.substring(0, CODE_LENGTH);
    }

    @Override
    public long decode(String code) {
        // MD5 is not reversible — lookup by code in DB
        throw new UnsupportedOperationException("MD5 codes require DB lookup");
    }
}

// Custom alphabet: allows vanity characters (e.g. no 0,O,1,l for readability)
class ReadableBase58Strategy implements EncodingStrategy {
    // Remove confusing characters: 0, O, I, l
    private static final String CHARS =
        "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int    BASE  = 58;
    private static final int    LEN   = 6;

    @Override public String getName() { return "Base58 (Readable)"; }

    @Override
    public String encode(long id) {
        StringBuilder sb = new StringBuilder();
        long n = id;
        while (n > 0) {
            sb.append(CHARS.charAt((int)(n % BASE)));
            n /= BASE;
        }
        while (sb.length() < LEN) sb.append('1');
        return sb.reverse().toString();
    }

    @Override
    public long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            result = result * BASE + CHARS.indexOf(c);
        }
        return result;
    }
}

// ==========================================
// 5. FACTORY — CREATE ENCODING STRATEGY
// ==========================================
class EncodingFactory {
    public enum Algorithm { BASE62, MD5, BASE58_READABLE }

    public static EncodingStrategy create(Algorithm algo) {
        return switch (algo) {
            case BASE62          -> new Base62Strategy();
            case MD5             -> new MD5Strategy();
            case BASE58_READABLE -> new ReadableBase58Strategy();
        };
    }
}

// ==========================================
// 6. OBSERVER PATTERN — ANALYTICS
// ==========================================
interface ClickObserver {
    void onRedirect(ShortUrl url, String clientIp, String userAgent);
}

class AnalyticsService implements ClickObserver {
    private final Map<String, List<String>> clickLog = new ConcurrentHashMap<>();

    @Override
    public void onRedirect(ShortUrl url, String clientIp, String userAgent) {
        String key = url.getShortCode();
        clickLog.computeIfAbsent(key, k -> new ArrayList<>())
                .add(LocalDateTime.now() + " | " + clientIp + " | " + userAgent);
        System.out.println("[Analytics] Click on " + key +
            " from " + clientIp + " | Agent: " + userAgent);
    }

    public List<String> getClickLog(String shortCode) {
        return clickLog.getOrDefault(shortCode, Collections.emptyList());
    }

    public void printReport(String shortCode) {
        System.out.println("[Analytics] Report for /" + shortCode + ":");
        getClickLog(shortCode).forEach(entry -> System.out.println("  " + entry));
    }
}

class RateLimitObserver implements ClickObserver {
    private final Map<String, AtomicLong> ipHits = new ConcurrentHashMap<>();
    private final long MAX_HITS_PER_MIN = 10;

    @Override
    public void onRedirect(ShortUrl url, String clientIp, String userAgent) {
        AtomicLong hits = ipHits.computeIfAbsent(clientIp, k -> new AtomicLong(0));
        long count = hits.incrementAndGet();
        if (count > MAX_HITS_PER_MIN) {
            System.out.println("[RateLimit] WARNING: IP " + clientIp +
                " hit " + count + " times — potential abuse");
        }
    }
}

// ==========================================
// 7. URL VALIDATOR
// ==========================================
class UrlValidator {
    private static final Set<String> BLOCKED_DOMAINS = Set.of(
        "malware.com", "phishing.site", "spam.net"
    );

    public boolean isValid(String url) {
        if (url == null || url.isBlank()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            System.out.println("[Validator] URL must start with http/https: " + url);
            return false;
        }
        for (String blocked : BLOCKED_DOMAINS) {
            if (url.contains(blocked)) {
                System.out.println("[Validator] Blocked domain: " + url);
                return false;
            }
        }
        return true;
    }
}

// ==========================================
// 8. URL SHORTENER SERVICE — SINGLETON
// ==========================================
class UrlShortenerService {
    private static UrlShortenerService instance;

    // In-memory stores (Redis + DB in production)
    private final Map<String, ShortUrl>  codeToUrl  = new ConcurrentHashMap<>();
    private final Map<String, ShortUrl>  longToCode = new ConcurrentHashMap<>();
    private final Map<String, ShortUrl>  aliasMap   = new ConcurrentHashMap<>();

    private       EncodingStrategy        encoder;
    private final UrlValidator            validator  = new UrlValidator();
    private final List<ClickObserver>     observers  = new ArrayList<>();
    private       String                  baseUrl    = "https://sho.rt/";
    private final AtomicLong              idCounter  = new AtomicLong(100_000L);

    private UrlShortenerService() {
        this.encoder = EncodingFactory.create(EncodingFactory.Algorithm.BASE62);
        // Register default observers
        observers.add(new AnalyticsService());
        observers.add(new RateLimitObserver());
    }

    public static synchronized UrlShortenerService getInstance() {
        if (instance == null) instance = new UrlShortenerService();
        return instance;
    }

    // ---- Configuration ----
    public void setEncoder(EncodingStrategy strategy) {
        this.encoder = strategy;
        System.out.println("[Service] Encoding switched to: " + strategy.getName());
    }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void addObserver(ClickObserver o) { observers.add(o); }

    // ---- SHORTEN URL ----
    public String shorten(ShortUrlRequest request) {
        // 1. Validate
        if (!validator.isValid(request.longUrl)) {
            System.out.println("[Service] Invalid URL rejected: " + request.longUrl);
            return null;
        }

        // 2. Check if already shortened (deduplication)
        if (longToCode.containsKey(request.longUrl) && request.customAlias == null) {
            ShortUrl existing = longToCode.get(request.longUrl);
            if (!existing.isExpired() && existing.getStatus() == UrlStatus.ACTIVE) {
                System.out.println("[Service] Existing short URL returned: " +
                    baseUrl + existing.getShortCode());
                return baseUrl + existing.getShortCode();
            }
        }

        // 3. Handle custom alias
        String code;
        if (request.customAlias != null) {
            if (aliasMap.containsKey(request.customAlias)) {
                System.out.println("[Service] Custom alias already taken: " +
                    request.customAlias);
                return null;
            }
            code = request.customAlias;
        } else {
            // 4. Generate unique code
            code = generateUniqueCode();
        }

        // 5. Create and store ShortUrl
        ShortUrl shortUrl = new ShortUrl(
            code, request.longUrl, request.userId,
            request.expiresAt, request.customAlias
        );

        codeToUrl.put(code, shortUrl);
        longToCode.put(request.longUrl, shortUrl);
        if (request.customAlias != null) aliasMap.put(code, shortUrl);

        String result = baseUrl + code;
        System.out.println("[Service] Shortened: " + request.longUrl +
            " → " + result + " [" + encoder.getName() + "]");
        return result;
    }

    private String generateUniqueCode() {
        String code;
        do {
            long id = idCounter.getAndIncrement();
            code = encoder.encode(id);
        } while (codeToUrl.containsKey(code)); // collision check
        return code;
    }

    // ---- REDIRECT ----
    public String redirect(String code, String clientIp, String userAgent) {
        ShortUrl url = codeToUrl.get(code);

        if (url == null) {
            System.out.println("[Service] Code not found: " + code);
            return null;
        }

        if (url.isExpired()) {
            url.setStatus(UrlStatus.EXPIRED);
            System.out.println("[Service] URL expired: " + code);
            return null;
        }

        if (url.getStatus() != UrlStatus.ACTIVE) {
            System.out.println("[Service] URL not active: " + code +
                " (" + url.getStatus() + ")");
            return null;
        }

        // Increment click counter
        url.incrementClick();

        // Notify all observers
        final ShortUrl finalUrl = url;
        observers.forEach(o -> o.onRedirect(finalUrl, clientIp, userAgent));

        System.out.println("[Service] Redirect: /" + code +
            " → " + url.getLongUrl() + " (click #" + url.getClickCount() + ")");
        return url.getLongUrl();
    }

    // ---- DELETE ----
    public boolean delete(String code, String userId) {
        ShortUrl url = codeToUrl.get(code);
        if (url == null) { System.out.println("[Service] Not found: " + code); return false; }
        if (!url.getUserId().equals(userId)) {
            System.out.println("[Service] Unauthorized delete attempt by: " + userId);
            return false;
        }
        url.setStatus(UrlStatus.DELETED);
        codeToUrl.remove(code);
        longToCode.remove(url.getLongUrl());
        System.out.println("[Service] Deleted: " + code);
        return true;
    }

    // ---- GET STATS ----
    public void printStats(String code) {
        ShortUrl url = codeToUrl.get(code);
        if (url == null) { System.out.println("Not found: " + code); return; }
        System.out.println("[Stats] " + url);
    }

    public Map<String, ShortUrl> getAllUrls() { return Collections.unmodifiableMap(codeToUrl); }
}

// ==========================================
// 9. MAIN — DRIVER CODE
// ==========================================
public class UrlShortener {
    public static void main(String[] args) throws InterruptedException {

        UrlShortenerService service = UrlShortenerService.getInstance();

        // ===== SCENARIO 1: Basic shortening (Base62) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Basic URL Shortening (Base62)");
        System.out.println("=".repeat(55));

        String s1 = service.shorten(new ShortUrlRequest.Builder(
            "https://www.amazon.com/dp/B09G9HD6PD?ref=something&tag=abc")
            .userId("alice")
            .build());

        String s2 = service.shorten(new ShortUrlRequest.Builder(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .userId("bob")
            .build());

        // ===== SCENARIO 2: Deduplication =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Deduplication (same URL → same code)");
        System.out.println("=".repeat(55));

        String s3 = service.shorten(new ShortUrlRequest.Builder(
            "https://www.amazon.com/dp/B09G9HD6PD?ref=something&tag=abc")
            .userId("carol")
            .build());
        System.out.println("s1 == s3 (dedup): " + s1.equals(s3));

        // ===== SCENARIO 3: Custom alias =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Custom Alias");
        System.out.println("=".repeat(55));

        String custom = service.shorten(new ShortUrlRequest.Builder(
            "https://docs.google.com/presentation/d/1abc123/edit")
            .userId("alice")
            .customAlias("my-slides")
            .build());

        // Try to take the same alias again
        service.shorten(new ShortUrlRequest.Builder(
            "https://other-site.com")
            .customAlias("my-slides")
            .build());

        // ===== SCENARIO 4: Expiring URL =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: URL with Expiry");
        System.out.println("=".repeat(55));

        String expiring = service.shorten(new ShortUrlRequest.Builder(
            "https://sale.flipkart.com/flash-deal-today")
            .userId("flipkart-admin")
            .expiresAt(LocalDateTime.now().plusSeconds(2)) // expires in 2 seconds
            .build());

        // Extract code
        String expCode = expiring.replace("https://sho.rt/", "");
        System.out.println("[Redirect before expiry]:");
        service.redirect(expCode, "192.168.1.1", "Chrome/120");

        System.out.println("\n[Waiting 3 seconds for expiry...]");
        Thread.sleep(3000);
        System.out.println("[Redirect after expiry]:");
        service.redirect(expCode, "192.168.1.2", "Firefox/121");

        // ===== SCENARIO 5: Redirect + Analytics =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Redirects + Analytics");
        System.out.println("=".repeat(55));

        String code1 = s1.replace("https://sho.rt/", "");
        service.redirect(code1, "203.0.113.1", "Chrome/120");
        service.redirect(code1, "203.0.113.2", "Safari/17");
        service.redirect(code1, "203.0.113.3", "Mozilla/5");
        service.printStats(code1);

        // ===== SCENARIO 6: Strategy swap to Base58 =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Switch Encoding to Base58 (Readable)");
        System.out.println("=".repeat(55));

        service.setEncoder(EncodingFactory.create(
            EncodingFactory.Algorithm.BASE58_READABLE));

        service.shorten(new ShortUrlRequest.Builder(
            "https://github.com/torvalds/linux/blob/master/README")
            .userId("developer")
            .build());

        // ===== SCENARIO 7: Invalid + blocked URLs =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Invalid and Blocked URLs");
        System.out.println("=".repeat(55));

        service.shorten(new ShortUrlRequest.Builder("not-a-valid-url").build());
        service.shorten(new ShortUrlRequest.Builder(
            "https://malware.com/download/virus.exe").build());
        service.shorten(new ShortUrlRequest.Builder("").build());

        // ===== SCENARIO 8: Delete URL =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Delete URL");
        System.out.println("=".repeat(55));

        String code2 = s2.replace("https://sho.rt/", "");
        service.delete(code2, "hacker");   // unauthorized
        service.delete(code2, "bob");      // authorized
        service.redirect(code2, "1.2.3.4", "Chrome"); // should fail

        // ===== SCENARIO 9: Unknown code redirect =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 9: Unknown Short Code");
        System.out.println("=".repeat(55));

        service.redirect("xxxxxx", "9.9.9.9", "Bot/1.0");

        // ===== SUMMARY =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("ACTIVE SHORT URLS");
        System.out.println("=".repeat(55));
        service.getAllUrls().values().forEach(System.out::println);

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|----------------------------------------------
            Singleton  | UrlShortenerService
            Strategy   | EncodingStrategy (Base62 / MD5 / Base58)
            Factory    | EncodingFactory.create(algo)
            Builder    | ShortUrlRequest.Builder
            Observer   | ClickObserver (Analytics / RateLimit)
            """);
    }
}
