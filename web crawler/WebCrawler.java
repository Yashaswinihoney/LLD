import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// WEB CRAWLER LLD
//
// Scope:
//   - Seed URLs → fetch → parse HTML → extract links → schedule new URLs
//   - Politeness: one request per domain at a time, respect robots.txt
//   - Duplicate detection: MD5 fingerprint of content + URL seen set
//   - Priority queue: URLs ranked by priority score
//   - Pluggable storage: in-memory for LLD, Kafka + DB for HLD
//
// Patterns:
//   Singleton  — CrawlerOrchestrator, URLFrontier
//   Strategy   — URLPriorityStrategy (FIFO / domain-rank / freshness)
//   Observer   — CrawlEventObserver (logger, indexer, storage)
//   Factory    — CrawlerWorkerFactory
//   Builder    — CrawlJob, CrawledPage construction
//   State      — CrawlStatus (PENDING→IN_PROGRESS→DONE/FAILED/SKIPPED)
//   Iterator   — LinkExtractorIterator (parse links from HTML)
//   Command    — CrawlCommand (fetch + parse + schedule, retryable)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum CrawlStatus   { PENDING, IN_PROGRESS, DONE, FAILED, SKIPPED, ROBOTS_BLOCKED }
enum URLPriority   { CRITICAL, HIGH, NORMAL, LOW, SEED }
enum ContentType   { HTML, PDF, IMAGE, VIDEO, JSON, XML, UNKNOWN }
enum CrawlDepth    { SEED, SHALLOW, DEEP }

// ==========================================
// 2. NORMALIZED URL — VALUE OBJECT
// Strips fragments, normalises scheme, sorts query params
// ==========================================
class NormalizedURL {
    private final String normalized;
    private final String domain;
    private final String scheme;
    private final String path;

    public NormalizedURL(String raw) {
        String url = raw.trim();
        // Remove fragment (#section)
        int hashIdx = url.indexOf('#');
        if (hashIdx != -1) url = url.substring(0, hashIdx);
        // Lowercase scheme + domain
        try {
            URI uri     = URI.create(url);
            this.scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
            this.domain = uri.getHost()   != null ? uri.getHost().toLowerCase()   : "";
            this.path   = uri.getPath()   != null ? uri.getPath()                 : "/";
            // Rebuild normalised form
            String q    = uri.getQuery() != null
                ? sortQueryParams(uri.getQuery())
                : null;
            this.normalized = scheme + "://" + domain + path +
                              (q != null ? "?" + q : "");
        } catch (Exception e) {
            this.normalized = url;
            this.domain     = extractDomain(url);
            this.scheme     = "https";
            this.path       = "/";
        }
    }

    private String sortQueryParams(String query) {
        String[] pairs = query.split("&");
        Arrays.sort(pairs);
        return String.join("&", pairs);
    }

    private String extractDomain(String url) {
        try {
            int start = url.indexOf("://") + 3;
            int end   = url.indexOf('/', start);
            return end == -1 ? url.substring(start) : url.substring(start, end);
        } catch (Exception e) { return "unknown"; }
    }

    public String get()       { return normalized; }
    public String getDomain() { return domain; }
    public String getScheme() { return scheme; }
    public String getPath()   { return path; }

    public boolean isValid() {
        return (scheme.equals("http") || scheme.equals("https")) &&
               !domain.isEmpty() &&
               normalized.length() < 2048;
    }

    @Override public boolean equals(Object o) {
        return o instanceof NormalizedURL n && normalized.equals(n.normalized);
    }
    @Override public int hashCode()   { return normalized.hashCode(); }
    @Override public String toString(){ return normalized; }
}

// ==========================================
// 3. CRAWL JOB — BUILDER PATTERN
// One unit of work: fetch this URL
// ==========================================
class CrawlJob {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long          jobId;
    private final  NormalizedURL url;
    private        CrawlStatus   status;
    private        URLPriority   priority;
    private        int           depth;          // hops from seed URL
    private        int           retryCount;
    private final  int           maxRetries;
    private final  String        parentUrl;      // URL that linked to this
    private final  LocalDateTime createdAt;
    private        LocalDateTime lastAttemptAt;
    private        String        failureReason;

    private CrawlJob(Builder b) {
        this.jobId      = idGen.getAndIncrement();
        this.url        = b.url;
        this.status     = CrawlStatus.PENDING;
        this.priority   = b.priority;
        this.depth      = b.depth;
        this.maxRetries = b.maxRetries;
        this.parentUrl  = b.parentUrl;
        this.retryCount = 0;
        this.createdAt  = LocalDateTime.now();
    }

    // ---- State transitions ----
    public void markInProgress() {
        status        = CrawlStatus.IN_PROGRESS;
        lastAttemptAt = LocalDateTime.now();
    }

    public void markDone()   { status = CrawlStatus.DONE; }

    public void markFailed(String reason) {
        retryCount++;
        failureReason = reason;
        if (retryCount >= maxRetries) {
            status = CrawlStatus.FAILED;
            System.out.println("[Job #" + jobId + "] FAILED after " + maxRetries +
                " retries: " + reason);
        } else {
            status = CrawlStatus.PENDING; // re-queue
        }
    }

    public void markSkipped(String reason) {
        status        = CrawlStatus.SKIPPED;
        failureReason = reason;
    }

    public void markRobotsBlocked() { status = CrawlStatus.ROBOTS_BLOCKED; }

    public boolean canRetry()  { return retryCount < maxRetries; }

    public long          getJobId()     { return jobId; }
    public NormalizedURL getUrl()       { return url; }
    public CrawlStatus   getStatus()    { return status; }
    public URLPriority   getPriority()  { return priority; }
    public int           getDepth()     { return depth; }
    public int           getRetryCount(){ return retryCount; }
    public String        getParentUrl() { return parentUrl; }

    @Override public String toString() {
        return String.format("CrawlJob[#%d | %-50s | %s | depth=%d | retry=%d]",
            jobId, url.get(), status, depth, retryCount);
    }

    static class Builder {
        private final NormalizedURL url;
        private       URLPriority   priority   = URLPriority.NORMAL;
        private       int           depth      = 0;
        private       int           maxRetries = 3;
        private       String        parentUrl  = null;

        public Builder(String url)              { this.url = new NormalizedURL(url); }
        public Builder(NormalizedURL url)       { this.url = url; }
        public Builder priority(URLPriority p)  { this.priority = p;   return this; }
        public Builder depth(int d)             { this.depth = d;       return this; }
        public Builder maxRetries(int r)        { this.maxRetries = r;  return this; }
        public Builder parentUrl(String p)      { this.parentUrl = p;   return this; }
        public CrawlJob build()                 { return new CrawlJob(this); }
    }
}

// ==========================================
// 4. CRAWLED PAGE — BUILDER PATTERN
// Result after fetching a URL
// ==========================================
class CrawledPage {
    private final long          pageId;
    private final NormalizedURL url;
    private final int           httpStatus;
    private final String        htmlContent;
    private final ContentType   contentType;
    private final long          contentSizeBytes;
    private final String        contentFingerprint; // MD5 hash for dup detection
    private final List<String>  extractedLinks;
    private final String        title;
    private final LocalDateTime crawledAt;
    private final long          fetchDurationMs;

    private CrawledPage(Builder b) {
        this.pageId             = b.pageId;
        this.url                = b.url;
        this.httpStatus         = b.httpStatus;
        this.htmlContent        = b.htmlContent;
        this.contentType        = b.contentType;
        this.contentSizeBytes   = b.htmlContent.length();
        this.contentFingerprint = computeMD5(b.htmlContent);
        this.extractedLinks     = List.copyOf(b.extractedLinks);
        this.title              = b.title;
        this.crawledAt          = LocalDateTime.now();
        this.fetchDurationMs    = b.fetchDurationMs;
    }

    private String computeMD5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(content.hashCode()); }
    }

    public long          getPageId()             { return pageId; }
    public NormalizedURL getUrl()                { return url; }
    public int           getHttpStatus()         { return httpStatus; }
    public String        getHtmlContent()        { return htmlContent; }
    public ContentType   getContentType()        { return contentType; }
    public long          getContentSizeBytes()   { return contentSizeBytes; }
    public String        getContentFingerprint() { return contentFingerprint; }
    public List<String>  getExtractedLinks()     { return extractedLinks; }
    public String        getTitle()              { return title; }
    public long          getFetchDurationMs()    { return fetchDurationMs; }

    @Override public String toString() {
        return String.format("Page[%-50s | %d | %d links | %dms | fp=%s]",
            url.get(), httpStatus, extractedLinks.size(),
            fetchDurationMs, contentFingerprint.substring(0, 8));
    }

    static class Builder {
        private static final AtomicLong pageIdGen = new AtomicLong(1);
        private final long          pageId = pageIdGen.getAndIncrement();
        private final NormalizedURL url;
        private       int           httpStatus   = 200;
        private       String        htmlContent  = "";
        private       ContentType   contentType  = ContentType.HTML;
        private       List<String>  extractedLinks = new ArrayList<>();
        private       String        title        = "";
        private       long          fetchDurationMs = 0;

        public Builder(NormalizedURL url)        { this.url = url; }
        public Builder httpStatus(int s)         { this.httpStatus = s;        return this; }
        public Builder htmlContent(String h)     { this.htmlContent = h;       return this; }
        public Builder contentType(ContentType c){ this.contentType = c;       return this; }
        public Builder links(List<String> l)     { this.extractedLinks = l;    return this; }
        public Builder title(String t)           { this.title = t;             return this; }
        public Builder fetchDurationMs(long ms)  { this.fetchDurationMs = ms;  return this; }
        public CrawledPage build()               { return new CrawledPage(this); }
    }
}

// ==========================================
// 5. ROBOTS.TXT PARSER
// Respects Disallow rules per domain
// ==========================================
class RobotsParser {
    // domain → set of disallowed path prefixes
    private final Map<String, Set<String>> disallowedPaths = new ConcurrentHashMap<>();
    // domain → crawl-delay in seconds (default 1s)
    private final Map<String, Integer>     crawlDelays     = new ConcurrentHashMap<>();

    // In real crawler: fetch https://domain/robots.txt and parse
    // Here we simulate with hardcoded rules
    public void loadRules(String domain, String robotsTxt) {
        Set<String> disallowed = new HashSet<>();
        int         delay      = 1;
        String      agent      = "*"; // target our crawler agent

        for (String line : robotsTxt.split("\n")) {
            line = line.trim();
            if (line.startsWith("Disallow:")) {
                String path = line.substring("Disallow:".length()).trim();
                if (!path.isEmpty()) disallowed.add(path);
            } else if (line.startsWith("Crawl-delay:")) {
                try { delay = Integer.parseInt(
                    line.substring("Crawl-delay:".length()).trim()); }
                catch (NumberFormatException ignored) {}
            }
        }

        disallowedPaths.put(domain, disallowed);
        crawlDelays.put(domain, delay);
        System.out.println("[Robots] Loaded rules for " + domain +
            " | disallowed=" + disallowed.size() + " paths | delay=" + delay + "s");
    }

    public boolean isAllowed(NormalizedURL url) {
        Set<String> rules = disallowedPaths.get(url.getDomain());
        if (rules == null) return true;  // no rules = allowed
        String path = url.getPath();
        return rules.stream().noneMatch(path::startsWith);
    }

    public int getCrawlDelay(String domain) {
        return crawlDelays.getOrDefault(domain, 1);
    }
}

// ==========================================
// 6. POLITENESS MANAGER
// Ensures one active request per domain at a time
// and respects crawl-delay between requests
// ==========================================
class PolitenessManager {
    // domain → timestamp of last request (ms)
    private final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();
    // domain → lock object (only one fetch at a time per domain)
    private final Map<String, Object> domainLocks  = new ConcurrentHashMap<>();
    private final RobotsParser         robotsParser;

    public PolitenessManager(RobotsParser robotsParser) {
        this.robotsParser = robotsParser;
    }

    public boolean canFetch(String domain) {
        long last   = lastFetchTime.getOrDefault(domain, 0L);
        int  delay  = robotsParser.getCrawlDelay(domain) * 1000; // to ms
        long elapsed = System.currentTimeMillis() - last;
        return elapsed >= delay;
    }

    public void waitIfNeeded(String domain) throws InterruptedException {
        long last    = lastFetchTime.getOrDefault(domain, 0L);
        int  delayMs = robotsParser.getCrawlDelay(domain) * 1000;
        long elapsed = System.currentTimeMillis() - last;
        long toWait  = delayMs - elapsed;
        if (toWait > 0) {
            System.out.println("[Politeness] Waiting " + toWait +
                "ms before next fetch for: " + domain);
            Thread.sleep(toWait);
        }
    }

    public void recordFetch(String domain) {
        lastFetchTime.put(domain, System.currentTimeMillis());
    }

    public Object getDomainLock(String domain) {
        return domainLocks.computeIfAbsent(domain, k -> new Object());
    }
}

// ==========================================
// 7. URL PRIORITY STRATEGY — STRATEGY PATTERN
// ==========================================
interface URLPriorityStrategy {
    String getName();
    int    computePriority(CrawlJob job); // higher = fetched sooner
}

// FIFO — simply order by job creation time
class FIFOStrategy implements URLPriorityStrategy {
    @Override public String getName() { return "FIFO"; }
    @Override public int computePriority(CrawlJob job) {
        // Lower job ID = older = higher priority
        return (int)(Long.MAX_VALUE - job.getJobId());
    }
}

// Domain-rank: prioritise high-value domains
class DomainRankStrategy implements URLPriorityStrategy {
    private final Map<String, Integer> domainRanks;

    public DomainRankStrategy(Map<String, Integer> ranks) {
        this.domainRanks = ranks;
    }

    @Override public String getName() { return "Domain-Rank"; }

    @Override
    public int computePriority(CrawlJob job) {
        String domain = job.getUrl().getDomain();
        int    rank   = domainRanks.getOrDefault(domain, 50);
        // Higher domain rank = higher priority
        // Seed URLs always highest
        int depthPenalty = job.getDepth() * 5;
        return rank - depthPenalty;
    }
}

// Freshness: re-crawl frequently updated pages sooner
class FreshnessStrategy implements URLPriorityStrategy {
    @Override public String getName() { return "Freshness"; }

    @Override
    public int computePriority(CrawlJob job) {
        // Seed URLs: 100, shallow (depth 1): 80, deeper = lower priority
        return Math.max(0, 100 - job.getDepth() * 20);
    }
}

// ==========================================
// 8. URL FRONTIER — SINGLETON
// Priority queue of pending CrawlJobs
// Maintains seen-set to avoid re-visiting URLs
// ==========================================
class URLFrontier {
    private static URLFrontier instance;

    // Priority queue: higher computedPriority polled first
    private final PriorityBlockingQueue<CrawlJob> queue =
        new PriorityBlockingQueue<>(1000, Comparator.comparingInt(
            j -> -strategy.computePriority(j)));   // negate = max-heap

    // Seen URLs — bloom filter in HLD, HashSet in LLD
    private final Set<String>      seenURLs  = ConcurrentHashMap.newKeySet();
    // Domain → jobs pending (for politeness ordering)
    private final Map<String, Integer> domainDepth = new ConcurrentHashMap<>();

    private URLPriorityStrategy strategy = new FIFOStrategy();
    private long                totalAdded   = 0;
    private long                totalSkipped = 0;

    private URLFrontier() {}

    public static synchronized URLFrontier getInstance() {
        if (instance == null) instance = new URLFrontier();
        return instance;
    }

    public void setStrategy(URLPriorityStrategy s) {
        this.strategy = s;
        System.out.println("[Frontier] Strategy: " + s.getName());
    }

    // Add a new job — returns false if URL already seen
    public boolean add(CrawlJob job) {
        String urlKey = job.getUrl().get();

        if (!job.getUrl().isValid()) {
            totalSkipped++;
            return false;
        }

        if (seenURLs.contains(urlKey)) {
            totalSkipped++;
            return false;
        }

        seenURLs.add(urlKey);
        queue.offer(job);
        totalAdded++;
        domainDepth.put(job.getUrl().getDomain(),
            Math.min(job.getDepth(), domainDepth.getOrDefault(
                job.getUrl().getDomain(), Integer.MAX_VALUE)));
        return true;
    }

    // Bulk add — from extracted links
    public int addAll(List<CrawlJob> jobs) {
        int added = 0;
        for (CrawlJob job : jobs) if (add(job)) added++;
        return added;
    }

    // Poll next job ready to process
    public CrawlJob poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isEmpty()       { return queue.isEmpty(); }
    public int     size()          { return queue.size(); }
    public long    getTotalAdded() { return totalAdded; }
    public long    getSkipped()    { return totalSkipped; }
    public boolean hasSeen(String url){ return seenURLs.contains(url); }

    @Override public String toString() {
        return "URLFrontier[queued=" + queue.size() + " seen=" + seenURLs.size() +
               " added=" + totalAdded + " skipped=" + totalSkipped + "]";
    }
}

// ==========================================
// 9. DUPLICATE CONTENT DETECTOR
// Fingerprints page content to avoid storing duplicates
// ==========================================
class DuplicateDetector {
    // content fingerprint → first URL that had this content
    private final Map<String, String> fingerprintToUrl = new ConcurrentHashMap<>();

    // Returns true if this is a duplicate
    public boolean isDuplicate(CrawledPage page) {
        String fp        = page.getContentFingerprint();
        String existing  = fingerprintToUrl.putIfAbsent(fp, page.getUrl().get());
        if (existing != null && !existing.equals(page.getUrl().get())) {
            System.out.println("[DupDetector] Duplicate content: " +
                page.getUrl() + " == " + existing);
            return true;
        }
        return false;
    }

    public int getDuplicateCount() {
        // total seen - unique fingerprints ≈ duplicates
        return 0; // simplified
    }
}

// ==========================================
// 10. LINK EXTRACTOR — ITERATOR PATTERN
// Parses HTML and yields discovered links
// ==========================================
class LinkExtractorIterator implements Iterator<String> {
    private final List<String>  links;
    private       int           cursor = 0;

    public LinkExtractorIterator(String html, String baseUrl) {
        this.links = extractLinks(html, baseUrl);
    }

    private List<String> extractLinks(String html, String baseUrl) {
        // Simplified regex-like extraction for LLD
        // In production: use JSoup or HtmlParser library
        List<String> found    = new ArrayList<>();
        String       lower    = html.toLowerCase();
        int          pos      = 0;

        while ((pos = lower.indexOf("href=\"", pos)) != -1) {
            pos += 6;
            int end = html.indexOf('"', pos);
            if (end == -1) break;

            String href = html.substring(pos, end);

            // Resolve relative URLs
            if (href.startsWith("http://") || href.startsWith("https://")) {
                found.add(href);
            } else if (href.startsWith("/")) {
                // Relative path — prepend base domain
                try {
                    URI base   = URI.create(baseUrl);
                    String abs = base.getScheme() + "://" + base.getHost() + href;
                    found.add(abs);
                } catch (Exception ignored) {}
            }
            // Skip mailto:, javascript:, #fragments, etc.
            pos = end;
        }

        return found;
    }

    @Override public boolean hasNext() { return cursor < links.size(); }
    @Override public String  next()    { return links.get(cursor++); }

    public List<String> toList()       { return links; }
    public int          count()        { return links.size(); }
}

// ==========================================
// 11. HTTP FETCHER (simulated)
// In production: Apache HttpClient / OkHttp with connection pool
// ==========================================
class HTTPFetcher {
    // Simulate network fetch with randomised responses
    public CrawledPage fetch(NormalizedURL url) throws Exception {
        long startMs = System.currentTimeMillis();

        // Simulate failures for some domains
        if (url.getDomain().contains("flaky")) {
            if (Math.random() < 0.5) throw new RuntimeException("Connection refused");
        }

        // Simulate timeout for some URLs
        if (url.getPath().contains("slow")) {
            Thread.sleep(100); // simulate slow page
        }

        // Generate mock HTML content
        String html   = generateMockHtml(url.get());
        long   elapsed = System.currentTimeMillis() - startMs;

        return new CrawledPage.Builder(url)
            .httpStatus(200)
            .htmlContent(html)
            .contentType(ContentType.HTML)
            .title("Page: " + url.getPath())
            .fetchDurationMs(elapsed)
            .links(new LinkExtractorIterator(html, url.get()).toList())
            .build();
    }

    private String generateMockHtml(String url) {
        // Generate HTML with 3-5 outbound links per page
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><title>Page at ").append(url).append("</title></head><body>");
        sb.append("<h1>Content of ").append(url).append("</h1>");

        // Add some internal + external links
        String domain = url.contains("example.com") ? "example.com" : "test.org";
        sb.append("<a href=\"/page-").append((int)(Math.random() * 100)).append("\">link1</a>");
        sb.append("<a href=\"/page-").append((int)(Math.random() * 100)).append("\">link2</a>");
        sb.append("<a href=\"https://other.com/page\">external</a>");
        sb.append("</body></html>");

        return sb.toString();
    }
}

// ==========================================
// 12. OBSERVER — CRAWL EVENTS
// ==========================================
interface CrawlEventObserver {
    void onPageCrawled(CrawledPage page);
    void onPageFailed(CrawlJob job, String reason);
    void onURLDiscovered(String url, int depth);
}

class IndexerObserver implements CrawlEventObserver {
    private final List<CrawledPage> index = new CopyOnWriteArrayList<>();

    @Override
    public void onPageCrawled(CrawledPage page) {
        index.add(page);
        System.out.printf("[Indexer] Indexed: %-50s | %d links | fp=%s%n",
            page.getUrl().get(), page.getExtractedLinks().size(),
            page.getContentFingerprint().substring(0, 8));
    }

    @Override
    public void onPageFailed(CrawlJob job, String reason) {
        System.out.println("[Indexer] Failed: " + job.getUrl() + " | " + reason);
    }

    @Override
    public void onURLDiscovered(String url, int depth) { /* no-op for indexer */ }

    public int getIndexedCount() { return index.size(); }
    public List<CrawledPage> getIndex() { return index; }
}

class StatsObserver implements CrawlEventObserver {
    private long successCount  = 0;
    private long failureCount  = 0;
    private long discoveredURLs= 0;
    private long totalBytes    = 0;

    @Override public synchronized void onPageCrawled(CrawledPage page) {
        successCount++;
        totalBytes    += page.getContentSizeBytes();
        discoveredURLs += page.getExtractedLinks().size();
    }
    @Override public synchronized void onPageFailed(CrawlJob job, String r) {
        failureCount++;
    }
    @Override public void onURLDiscovered(String url, int depth) {}

    public void printReport() {
        System.out.println("\n[Stats] Crawl Report:");
        System.out.printf("  Pages crawled:    %d%n", successCount);
        System.out.printf("  Pages failed:     %d%n", failureCount);
        System.out.printf("  URLs discovered:  %d%n", discoveredURLs);
        System.out.printf("  Total bytes:      %d%n", totalBytes);
        System.out.printf("  Success rate:     %.1f%%%n",
            (successCount + failureCount) > 0
                ? 100.0 * successCount / (successCount + failureCount)
                : 0);
    }
}

// ==========================================
// 13. CRAWL COMMAND — COMMAND PATTERN
// One unit of fetch + parse + schedule work
// Retryable on failure
// ==========================================
class CrawlCommand {
    private final CrawlJob              job;
    private final HTTPFetcher           fetcher;
    private final RobotsParser          robots;
    private final PolitenessManager     politeness;
    private final DuplicateDetector     dupDetector;
    private final URLFrontier           frontier;
    private final List<CrawlEventObserver> observers;
    private final int                   maxDepth;

    public CrawlCommand(CrawlJob job, HTTPFetcher fetcher,
                         RobotsParser robots, PolitenessManager politeness,
                         DuplicateDetector dupDetector, URLFrontier frontier,
                         List<CrawlEventObserver> observers, int maxDepth) {
        this.job        = job;
        this.fetcher    = fetcher;
        this.robots     = robots;
        this.politeness = politeness;
        this.dupDetector= dupDetector;
        this.frontier   = frontier;
        this.observers  = observers;
        this.maxDepth   = maxDepth;
    }

    public void execute() {
        NormalizedURL url = job.getUrl();

        // ---- Step 1: Robots.txt check ----
        if (!robots.isAllowed(url)) {
            job.markRobotsBlocked();
            System.out.println("[Command] robots.txt blocked: " + url);
            return;
        }

        // ---- Step 2: Politeness — wait if needed ----
        synchronized (politeness.getDomainLock(url.getDomain())) {
            try {
                politeness.waitIfNeeded(url.getDomain());
                job.markInProgress();

                // ---- Step 3: Fetch ----
                long start = System.currentTimeMillis();
                CrawledPage page = fetcher.fetch(url);
                politeness.recordFetch(url.getDomain());

                System.out.printf("[Command] Fetched: %-50s | %dms%n",
                    url.get(), System.currentTimeMillis() - start);

                // ---- Step 4: Duplicate content check ----
                if (dupDetector.isDuplicate(page)) {
                    job.markSkipped("duplicate content");
                    return;
                }

                // ---- Step 5: Mark done + notify observers ----
                job.markDone();
                observers.forEach(o -> o.onPageCrawled(page));

                // ---- Step 6: Schedule extracted links (if within depth limit) ----
                if (job.getDepth() < maxDepth) {
                    List<CrawlJob> newJobs = page.getExtractedLinks().stream()
                        .map(link -> new CrawlJob.Builder(link)
                            .depth(job.getDepth() + 1)
                            .parentUrl(url.get())
                            .priority(URLPriority.NORMAL)
                            .build())
                        .collect(Collectors.toList());

                    int added = frontier.addAll(newJobs);
                    if (added > 0)
                        System.out.println("[Command] Scheduled " + added +
                            " new URLs from: " + url.get());

                    page.getExtractedLinks().forEach(link ->
                        observers.forEach(o -> o.onURLDiscovered(link, job.getDepth() + 1)));
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.markFailed("interrupted");
            } catch (Exception e) {
                politeness.recordFetch(url.getDomain()); // still record attempt
                job.markFailed(e.getMessage());
                observers.forEach(o -> o.onPageFailed(job, e.getMessage()));
            }
        }
    }
}

// ==========================================
// 14. CRAWLER WORKER FACTORY
// ==========================================
class CrawlerWorkerFactory {
    public static Runnable createWorker(String workerId,
                                         URLFrontier frontier,
                                         HTTPFetcher fetcher,
                                         RobotsParser robots,
                                         PolitenessManager politeness,
                                         DuplicateDetector dupDetector,
                                         List<CrawlEventObserver> observers,
                                         int maxDepth) {
        return () -> {
            System.out.println("[Worker-" + workerId + "] Started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    CrawlJob job = frontier.poll(500);
                    if (job == null) {
                        if (frontier.isEmpty()) break; // no more work
                        continue;
                    }
                    System.out.println("[Worker-" + workerId + "] Processing: " +
                        job.getUrl().get());
                    new CrawlCommand(job, fetcher, robots, politeness,
                        dupDetector, frontier, observers, maxDepth).execute();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("[Worker-" + workerId + "] Done");
        };
    }
}

// ==========================================
// 15. CRAWLER ORCHESTRATOR — SINGLETON
// ==========================================
class CrawlerOrchestrator {
    private static CrawlerOrchestrator instance;

    private final URLFrontier              frontier     = URLFrontier.getInstance();
    private final RobotsParser             robots       = new RobotsParser();
    private final PolitenessManager        politeness   = new PolitenessManager(robots);
    private final DuplicateDetector        dupDetector  = new DuplicateDetector();
    private final HTTPFetcher              fetcher      = new HTTPFetcher();
    private final List<CrawlEventObserver> observers    = new ArrayList<>();
    private final IndexerObserver          indexer      = new IndexerObserver();
    private final StatsObserver            stats        = new StatsObserver();

    private int maxDepth    = 2;
    private int workerCount = 3;

    private CrawlerOrchestrator() {
        observers.add(indexer);
        observers.add(stats);
    }

    public static synchronized CrawlerOrchestrator getInstance() {
        if (instance == null) instance = new CrawlerOrchestrator();
        return instance;
    }

    public void configure(int maxDepth, int workerCount, URLPriorityStrategy strategy) {
        this.maxDepth    = maxDepth;
        this.workerCount = workerCount;
        frontier.setStrategy(strategy);
        System.out.printf("[Orchestrator] Configured: depth=%d workers=%d strategy=%s%n",
            maxDepth, workerCount, strategy.getName());
    }

    public void loadRobots(String domain, String robotsTxt) {
        robots.loadRules(domain, robotsTxt);
    }

    public void addObserver(CrawlEventObserver observer) {
        observers.add(observer);
    }

    public void seed(List<String> seedURLs) {
        System.out.println("[Orchestrator] Seeding " + seedURLs.size() + " URLs");
        seedURLs.forEach(url ->
            frontier.add(new CrawlJob.Builder(url)
                .priority(URLPriority.SEED)
                .depth(0)
                .build()));
    }

    public void run() throws InterruptedException {
        System.out.println("[Orchestrator] Starting crawl with " +
            workerCount + " workers");

        ExecutorService pool = Executors.newFixedThreadPool(workerCount,
            r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });

        for (int i = 0; i < workerCount; i++) {
            pool.submit(CrawlerWorkerFactory.createWorker(
                "W" + i, frontier, fetcher, robots, politeness,
                dupDetector, observers, maxDepth));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        System.out.println("[Orchestrator] Crawl complete");
    }

    public URLFrontier    getFrontier()   { return frontier; }
    public IndexerObserver getIndexer()   { return indexer; }
    public void printStats()             { stats.printReport(); }
    public void printFrontierStatus()    { System.out.println(frontier); }
}

// ==========================================
// 16. MAIN — DRIVER CODE
// ==========================================
public class WebCrawler {
    public static void main(String[] args) throws InterruptedException {

        CrawlerOrchestrator crawler = CrawlerOrchestrator.getInstance();

        // ===== SETUP =====
        crawler.configure(2, 3,
            new DomainRankStrategy(Map.of(
                "example.com", 90,
                "test.org",    70,
                "other.com",   50)));

        // Load robots.txt rules
        crawler.loadRobots("example.com",
            "User-agent: *\nDisallow: /admin\nDisallow: /private\nCrawl-delay: 1");
        crawler.loadRobots("test.org",
            "User-agent: *\nDisallow: /login\nCrawl-delay: 2");

        // ===== SCENARIO 1: URL normalisation =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: URL Normalisation");
        System.out.println("=".repeat(60));

        String[] rawURLs = {
            "HTTPS://Example.COM/path?b=2&a=1#section",
            "https://example.com/path?a=1&b=2",   // same after normalisation
            "https://example.com/path?a=1&b=2#other", // same — fragment removed
            "http://Test.Org/page/",
        };

        for (String raw : rawURLs) {
            NormalizedURL norm = new NormalizedURL(raw);
            System.out.printf("  %-50s → %s%n", raw, norm.get());
        }

        // ===== SCENARIO 2: Seen-set deduplication =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: URL Frontier Deduplication");
        System.out.println("=".repeat(60));

        URLFrontier frontier = URLFrontier.getInstance();
        boolean a1 = frontier.add(new CrawlJob.Builder("https://example.com/").build());
        boolean a2 = frontier.add(new CrawlJob.Builder("https://example.com/").build());
        boolean a3 = frontier.add(new CrawlJob.Builder("https://example.com/about").build());

        System.out.println("First add:       " + a1);  // true
        System.out.println("Duplicate add:   " + a2);  // false — already seen
        System.out.println("Different path:  " + a3);  // true
        System.out.println(frontier);

        // ===== SCENARIO 3: Robots.txt enforcement =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Robots.txt Enforcement");
        System.out.println("=".repeat(60));

        RobotsParser robots = new RobotsParser();
        robots.loadRules("example.com",
            "User-agent: *\nDisallow: /admin\nDisallow: /private\nCrawl-delay: 1");

        String[] testPaths = {
            "https://example.com/",
            "https://example.com/about",
            "https://example.com/admin/dashboard",  // blocked
            "https://example.com/private/data",      // blocked
            "https://example.com/products",
        };

        for (String p : testPaths) {
            NormalizedURL u = new NormalizedURL(p);
            System.out.printf("  %-45s → %s%n", p, robots.isAllowed(u) ? "ALLOWED" : "BLOCKED");
        }

        // ===== SCENARIO 4: Priority queue ordering =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Priority Strategy — Domain-Rank Ordering");
        System.out.println("=".repeat(60));

        URLFrontier f2 = new URLFrontier() {
            // We test strategy output directly
        };

        DomainRankStrategy rankStrat = new DomainRankStrategy(Map.of(
            "example.com", 90, "test.org", 70, "low.io", 20));

        List<CrawlJob> jobs = List.of(
            new CrawlJob.Builder("https://low.io/page").depth(0).build(),
            new CrawlJob.Builder("https://test.org/page").depth(0).build(),
            new CrawlJob.Builder("https://example.com/page").depth(0).build());

        jobs.forEach(j -> System.out.printf("  %-40s priority=%d%n",
            j.getUrl().get(), rankStrat.computePriority(j)));

        // ===== SCENARIO 5: Content fingerprinting (duplicate detection) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Duplicate Content Detection");
        System.out.println("=".repeat(60));

        DuplicateDetector dup = new DuplicateDetector();
        String sameContent = "<html><body>Same content everywhere</body></html>";

        CrawledPage p1 = new CrawledPage.Builder(new NormalizedURL("https://a.com/page1"))
            .htmlContent(sameContent).build();
        CrawledPage p2 = new CrawledPage.Builder(new NormalizedURL("https://b.com/page2"))
            .htmlContent(sameContent).build();      // same content, different URL
        CrawledPage p3 = new CrawledPage.Builder(new NormalizedURL("https://c.com/page3"))
            .htmlContent("<html>Unique content here</html>").build();

        System.out.println("Page1 duplicate: " + dup.isDuplicate(p1)); // false — first seen
        System.out.println("Page2 duplicate: " + dup.isDuplicate(p2)); // true  — same fingerprint
        System.out.println("Page3 duplicate: " + dup.isDuplicate(p3)); // false — unique

        // ===== SCENARIO 6: Link extraction =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Link Extraction from HTML");
        System.out.println("=".repeat(60));

        String html = """
            <html><body>
              <a href="/about">About</a>
              <a href="/products/123">Product</a>
              <a href="https://external.com/page">External</a>
              <a href="mailto:info@example.com">Email</a>
              <a href="#section">Fragment</a>
              <a href="https://another.com/page?a=1&b=2">Another</a>
            </body></html>
            """;

        LinkExtractorIterator extractor =
            new LinkExtractorIterator(html, "https://example.com/home");
        System.out.println("Extracted " + extractor.count() + " links:");
        extractor.toList().forEach(l -> System.out.println("  " + l));

        // ===== SCENARIO 7: Full crawl run =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Full Crawl (3 workers, depth=2)");
        System.out.println("=".repeat(60));

        crawler.seed(List.of(
            "https://example.com/",
            "https://test.org/home",
            "https://example.com/products"));

        crawler.run();

        // ===== RESULTS =====
        crawler.printFrontierStatus();
        crawler.printStats();

        System.out.println("\n[Indexed pages: " +
            crawler.getIndexer().getIndexedCount() + "]");
        crawler.getIndexer().getIndex().stream()
            .limit(5)
            .forEach(p -> System.out.println("  " + p));

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | CrawlerOrchestrator, URLFrontier
            Strategy   | URLPriorityStrategy (FIFO / DomainRank / Freshness)
            Observer   | CrawlEventObserver (Indexer / Stats)
            Factory    | CrawlerWorkerFactory
            Builder    | CrawlJob.Builder, CrawledPage.Builder
            State      | CrawlStatus (PENDING→IN_PROGRESS→DONE/FAILED/SKIPPED)
            Iterator   | LinkExtractorIterator (HTML → links)
            Command    | CrawlCommand (fetch + parse + schedule, retryable)
            """);
    }
}
