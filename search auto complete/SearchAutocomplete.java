import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// SEARCH AUTOCOMPLETE LLD
// Patterns:
//   Singleton  — AutocompleteService
//   Strategy   — RankingStrategy (frequency / personalized / trending)
//   Factory    — SuggestionFactory (query / product / user / hashtag)
//   Builder    — SearchQuery construction
//   Trie       — Core prefix matching data structure
//   Observer   — SearchEventObserver (analytics + trending)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum SuggestionType { QUERY, PRODUCT, USER, HASHTAG, LOCATION }
enum SearchDomain   { GLOBAL, ECOMMERCE, SOCIAL, MAPS, NEWS }

// ==========================================
// 2. SUGGESTION — core result object
// ==========================================
class Suggestion {
    private final String         text;
    private final SuggestionType type;
    private final SearchDomain   domain;
    private       long           frequency;   // how often searched globally
    private       double         score;       // final ranking score
    private final String         metadata;    // extra context (price, username etc)

    public Suggestion(String text, SuggestionType type,
                      SearchDomain domain, long frequency, String metadata) {
        this.text      = text;
        this.type      = type;
        this.domain    = domain;
        this.frequency = frequency;
        this.metadata  = metadata;
    }

    public void incrementFrequency() { frequency++; }
    public void setScore(double s)   { this.score = s; }

    public String         getText()      { return text; }
    public SuggestionType getType()      { return type; }
    public SearchDomain   getDomain()    { return domain; }
    public long           getFrequency() { return frequency; }
    public double         getScore()     { return score; }
    public String         getMetadata()  { return metadata; }

    @Override public String toString() {
        return String.format("Suggestion[%-30s | %-8s | freq=%-6d | score=%.1f]",
            text, type, frequency, score);
    }
}

// ==========================================
// 3. TRIE NODE + TRIE DATA STRUCTURE
// The core of autocomplete
// ==========================================
class TrieNode {
    final Map<Character, TrieNode> children = new ConcurrentHashMap<>();
    boolean                         isEndOfWord = false;
    // Store top-K suggestions at each node (avoids full DFS on every query)
    final List<Suggestion>          topSuggestions = new CopyOnWriteArrayList<>();

    private static final int TOP_K = 10;

    // Update cached top-K at this node when a suggestion changes
    public synchronized void updateTopK(Suggestion suggestion) {
        // Remove existing entry for same text (update in place)
        topSuggestions.removeIf(s -> s.getText().equals(suggestion.getText()));
        topSuggestions.add(suggestion);
        // Keep only top-K by score
        topSuggestions.sort(Comparator.comparingDouble(Suggestion::getScore).reversed());
        if (topSuggestions.size() > TOP_K) {
            topSuggestions.subList(TOP_K, topSuggestions.size()).clear();
        }
    }
}

class Trie {
    private final TrieNode root = new TrieNode();

    // Insert a suggestion into the trie
    public void insert(Suggestion suggestion) {
        String word = suggestion.getText().toLowerCase();
        TrieNode node = root;

        for (char c : word.toCharArray()) {
            node.children.putIfAbsent(c, new TrieNode());
            node = node.children.get(c);
            node.updateTopK(suggestion); // cache top-K at every prefix node
        }
        node.isEndOfWord = true;
    }

    // Get top-K suggestions for a given prefix
    public List<Suggestion> search(String prefix, int topK) {
        String lower = prefix.toLowerCase().trim();
        TrieNode node = root;

        for (char c : lower.toCharArray()) {
            if (!node.children.containsKey(c)) return Collections.emptyList();
            node = node.children.get(c);
        }

        // Return cached top-K at this prefix node
        return node.topSuggestions.stream()
            .limit(topK)
            .collect(Collectors.toList());
    }

    // Update frequency of an existing suggestion
    public boolean updateFrequency(String text, long newFrequency) {
        String lower = text.toLowerCase();
        TrieNode node = root;

        for (char c : lower.toCharArray()) {
            if (!node.children.containsKey(c)) return false;
            node = node.children.get(c);
        }

        if (node.isEndOfWord) {
            node.topSuggestions.stream()
                .filter(s -> s.getText().equalsIgnoreCase(text))
                .findFirst()
                .ifPresent(s -> {
                    s.setScore(newFrequency);
                    // Re-sort after update — propagate up via re-insert
                });
            return true;
        }
        return false;
    }

    // Check if a prefix exists
    public boolean hasPrefix(String prefix) {
        TrieNode node = root;
        for (char c : prefix.toLowerCase().toCharArray()) {
            if (!node.children.containsKey(c)) return false;
            node = node.children.get(c);
        }
        return true;
    }
}

// ==========================================
// 4. STRATEGY — RANKING ALGORITHMS
// ==========================================
interface RankingStrategy {
    String getName();
    List<Suggestion> rank(List<Suggestion> candidates, String prefix,
                          String userId, Set<String> recentSearches);
}

// Frequency-based: most searched globally
class FrequencyRankingStrategy implements RankingStrategy {
    @Override public String getName() { return "Frequency"; }

    @Override
    public List<Suggestion> rank(List<Suggestion> candidates, String prefix,
                                  String userId, Set<String> recentSearches) {
        candidates.forEach(s -> s.setScore(s.getFrequency()));
        return candidates.stream()
            .sorted(Comparator.comparingDouble(Suggestion::getScore).reversed())
            .collect(Collectors.toList());
    }
}

// Personalized: boost user's recent searches + interests
class PersonalizedRankingStrategy implements RankingStrategy {
    @Override public String getName() { return "Personalized"; }

    @Override
    public List<Suggestion> rank(List<Suggestion> candidates, String prefix,
                                  String userId, Set<String> recentSearches) {
        candidates.forEach(s -> {
            double score = s.getFrequency();
            // Boost if user searched this before
            if (recentSearches.contains(s.getText().toLowerCase())) score *= 3.0;
            // Boost for exact prefix match start
            if (s.getText().toLowerCase().startsWith(prefix.toLowerCase())) score *= 1.5;
            s.setScore(score);
        });
        return candidates.stream()
            .sorted(Comparator.comparingDouble(Suggestion::getScore).reversed())
            .collect(Collectors.toList());
    }
}

// Trending: boost recent surges in search volume
class TrendingRankingStrategy implements RankingStrategy {
    private final Map<String, Long> trendingTerms; // term → recent search count

    public TrendingRankingStrategy(Map<String, Long> trendingTerms) {
        this.trendingTerms = trendingTerms;
    }

    @Override public String getName() { return "Trending"; }

    @Override
    public List<Suggestion> rank(List<Suggestion> candidates, String prefix,
                                  String userId, Set<String> recentSearches) {
        candidates.forEach(s -> {
            double score = s.getFrequency();
            // Boost trending terms significantly
            if (trendingTerms.containsKey(s.getText().toLowerCase())) {
                score += trendingTerms.get(s.getText().toLowerCase()) * 2.0;
            }
            s.setScore(score);
        });
        return candidates.stream()
            .sorted(Comparator.comparingDouble(Suggestion::getScore).reversed())
            .collect(Collectors.toList());
    }
}

// ==========================================
// 5. FACTORY — CREATE SUGGESTIONS BY TYPE
// ==========================================
class SuggestionFactory {
    public static Suggestion createQuery(String text, long frequency) {
        return new Suggestion(text, SuggestionType.QUERY,
            SearchDomain.GLOBAL, frequency, null);
    }

    public static Suggestion createProduct(String name, String price, long searches) {
        return new Suggestion(name, SuggestionType.PRODUCT,
            SearchDomain.ECOMMERCE, searches, "₹" + price);
    }

    public static Suggestion createUser(String username, String displayName, long followers) {
        return new Suggestion(username, SuggestionType.USER,
            SearchDomain.SOCIAL, followers, displayName);
    }

    public static Suggestion createHashtag(String tag, long tweetCount) {
        return new Suggestion(tag, SuggestionType.HASHTAG,
            SearchDomain.SOCIAL, tweetCount, tweetCount + " tweets");
    }

    public static Suggestion createLocation(String place, long searches) {
        return new Suggestion(place, SuggestionType.LOCATION,
            SearchDomain.MAPS, searches, "Location");
    }
}

// ==========================================
// 6. BUILDER — SEARCH QUERY
// ==========================================
class SearchQuery {
    final String       prefix;
    final String       userId;
    final SearchDomain domain;
    final int          topK;
    final String       locale;        // en-IN, en-US etc.

    private SearchQuery(Builder b) {
        this.prefix = b.prefix;
        this.userId = b.userId;
        this.domain = b.domain;
        this.topK   = b.topK;
        this.locale = b.locale;
    }

    static class Builder {
        private final String       prefix;
        private       String       userId = "anonymous";
        private       SearchDomain domain = SearchDomain.GLOBAL;
        private       int          topK   = 10;
        private       String       locale = "en-IN";

        public Builder(String prefix)         { this.prefix = prefix; }
        public Builder userId(String u)       { this.userId = u;  return this; }
        public Builder domain(SearchDomain d) { this.domain = d;  return this; }
        public Builder topK(int k)            { this.topK = k;    return this; }
        public Builder locale(String l)       { this.locale = l;  return this; }
        public SearchQuery build()            { return new SearchQuery(this); }
    }
}

// ==========================================
// 7. OBSERVER — SEARCH EVENT ANALYTICS
// ==========================================
interface SearchEventObserver {
    void onSearch(String prefix, String selectedTerm, String userId);
    void onNoResults(String prefix);
}

class AnalyticsObserver implements SearchEventObserver {
    private final Map<String, AtomicLong> termFrequency = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> noResultTerms = new ConcurrentHashMap<>();

    @Override
    public void onSearch(String prefix, String selectedTerm, String userId) {
        if (selectedTerm != null) {
            termFrequency.computeIfAbsent(selectedTerm.toLowerCase(),
                k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    @Override
    public void onNoResults(String prefix) {
        noResultTerms.computeIfAbsent(prefix.toLowerCase(),
            k -> new AtomicLong(0)).incrementAndGet();
        System.out.println("[Analytics] No results for prefix: '" + prefix + "'");
    }

    public Map<String, AtomicLong> getTermFrequency() { return termFrequency; }

    public void printTopSearches(int n) {
        System.out.println("[Analytics] Top " + n + " searched terms:");
        termFrequency.entrySet().stream()
            .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                Comparator.comparingLong(AtomicLong::get)).reversed())
            .limit(n)
            .forEach(e -> System.out.println("  " + e.getKey() + " → " + e.getValue()));
    }

    public void printNoResultTerms() {
        if (noResultTerms.isEmpty()) return;
        System.out.println("[Analytics] Searches with no results:");
        noResultTerms.forEach((term, count) ->
            System.out.println("  '" + term + "' → " + count + " times"));
    }
}

class TrendingObserver implements SearchEventObserver {
    // Sliding window: term → count in last N minutes
    private final Map<String, AtomicLong> recentSearches = new ConcurrentHashMap<>();

    @Override
    public void onSearch(String prefix, String selectedTerm, String userId) {
        if (selectedTerm != null) {
            recentSearches.computeIfAbsent(selectedTerm.toLowerCase(),
                k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    @Override public void onNoResults(String prefix) {}

    public Map<String, Long> getTrendingTerms(int n) {
        return recentSearches.entrySet().stream()
            .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                Comparator.comparingLong(AtomicLong::get)).reversed())
            .limit(n)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get(),
                (a, b) -> a, LinkedHashMap::new));
    }
}

// ==========================================
// 8. AUTOCOMPLETE SERVICE — SINGLETON
// ==========================================
class AutocompleteService {
    private static AutocompleteService instance;

    // Domain-specific tries — each domain has its own trie
    private final Map<SearchDomain, Trie>     tries          = new ConcurrentHashMap<>();
    // Per-user recent searches for personalization
    private final Map<String, Deque<String>>  userHistory    = new ConcurrentHashMap<>();
    // In-memory cache: prefix → ranked results (Redis in production)
    private final Map<String, List<Suggestion>> prefixCache  = new ConcurrentHashMap<>();
    private static final int                  CACHE_MAX_SIZE = 1000;
    private static final int                  HISTORY_SIZE   = 20;

    private       RankingStrategy             rankingStrategy;
    private final List<SearchEventObserver>   observers      = new ArrayList<>();
    private final AnalyticsObserver           analytics      = new AnalyticsObserver();
    private final TrendingObserver            trending       = new TrendingObserver();

    private AutocompleteService() {
        // Initialize a trie for each domain
        for (SearchDomain domain : SearchDomain.values()) {
            tries.put(domain, new Trie());
        }
        this.rankingStrategy = new FrequencyRankingStrategy();
        observers.add(analytics);
        observers.add(trending);
    }

    public static synchronized AutocompleteService getInstance() {
        if (instance == null) instance = new AutocompleteService();
        return instance;
    }

    // ---- Configuration ----
    public void setRankingStrategy(RankingStrategy strategy) {
        this.rankingStrategy = strategy;
        prefixCache.clear(); // invalidate cache on strategy change
        System.out.println("[Service] Ranking: " + strategy.getName());
    }

    // ---- Populate trie ----
    public void addSuggestion(Suggestion suggestion) {
        Trie trie = tries.get(suggestion.getDomain());
        trie.insert(suggestion);
        // Also add to GLOBAL trie if not already global
        if (suggestion.getDomain() != SearchDomain.GLOBAL) {
            tries.get(SearchDomain.GLOBAL).insert(suggestion);
        }
        // Invalidate cache entries that share this prefix
        invalidatePrefixCache(suggestion.getText());
    }

    // ---- CORE: GET SUGGESTIONS ----
    public List<Suggestion> getSuggestions(SearchQuery query) {
        if (query.prefix.isBlank() || query.prefix.length() < 1) {
            return Collections.emptyList();
        }

        String cacheKey = query.domain + ":" + query.prefix.toLowerCase();

        // 1. Check prefix cache (Redis in production)
        if (prefixCache.containsKey(cacheKey)) {
            System.out.println("[Cache] HIT for prefix: '" + query.prefix + "'");
            return prefixCache.get(cacheKey).stream()
                .limit(query.topK).collect(Collectors.toList());
        }

        System.out.println("[Cache] MISS for prefix: '" + query.prefix + "'");

        // 2. Query trie for candidates
        Trie trie = tries.getOrDefault(query.domain, tries.get(SearchDomain.GLOBAL));
        List<Suggestion> candidates = trie.search(query.prefix, query.topK * 3);

        if (candidates.isEmpty()) {
            observers.forEach(o -> o.onNoResults(query.prefix));
            return Collections.emptyList();
        }

        // 3. Get user history for personalization
        Set<String> history = new HashSet<>(
            userHistory.getOrDefault(query.userId, new ArrayDeque<>()));

        // 4. Apply ranking strategy
        List<Suggestion> ranked = rankingStrategy.rank(
            new ArrayList<>(candidates), query.prefix, query.userId, history);

        // 5. Limit to topK
        List<Suggestion> result = ranked.stream()
            .limit(query.topK).collect(Collectors.toList());

        // 6. Cache result
        if (prefixCache.size() < CACHE_MAX_SIZE) {
            prefixCache.put(cacheKey, result);
        }

        return result;
    }

    // ---- Record user selection (feedback loop) ----
    public void recordSelection(String userId, String prefix, String selectedTerm) {
        // Add to user history
        userHistory.computeIfAbsent(userId, k -> new ArrayDeque<>())
            .addFirst(selectedTerm.toLowerCase());

        // Trim history
        Deque<String> hist = userHistory.get(userId);
        while (hist.size() > HISTORY_SIZE) hist.pollLast();

        // Boost frequency of selected term in trie
        for (Trie trie : tries.values()) {
            trie.updateFrequency(selectedTerm, 0); // placeholder — real: increment
        }

        // Invalidate cache for this prefix
        invalidatePrefixCache(prefix);

        // Notify observers
        observers.forEach(o -> o.onSearch(prefix, selectedTerm, userId));

        System.out.println("[Selection] @" + userId + " selected '" +
            selectedTerm + "' for prefix '" + prefix + "'");
    }

    // ---- Bulk load (from DB / CSV on startup) ----
    public void bulkLoad(List<Suggestion> suggestions) {
        System.out.println("[Service] Bulk loading " + suggestions.size() + " suggestions...");
        suggestions.forEach(this::addSuggestion);
        System.out.println("[Service] Bulk load complete. Cache populated.");
    }

    // ---- Trending update (runs periodically) ----
    public void refreshTrending() {
        Map<String, Long> trendingTerms = trending.getTrendingTerms(50);
        if (!trendingTerms.isEmpty()) {
            this.rankingStrategy = new TrendingRankingStrategy(trendingTerms);
            prefixCache.clear();
            System.out.println("[Trending] Refreshed with " + trendingTerms.size() + " terms");
        }
    }

    private void invalidatePrefixCache(String term) {
        String lower = term.toLowerCase();
        // Invalidate all cache entries whose prefix matches this term's prefixes
        prefixCache.keySet().removeIf(key -> {
            String prefix = key.contains(":") ? key.split(":", 2)[1] : key;
            return lower.startsWith(prefix);
        });
    }

    public void printAnalytics()      { analytics.printTopSearches(10); }
    public void printNoResults()      { analytics.printNoResultTerms(); }

    public Map<String, Long> getTrending(int n) {
        return trending.getTrendingTerms(n);
    }
}

// ==========================================
// 9. MAIN — DRIVER CODE
// ==========================================
public class SearchAutocomplete {
    public static void main(String[] args) {

        AutocompleteService service = AutocompleteService.getInstance();

        // ===== SETUP: Bulk load suggestions =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SETUP: Loading suggestions into Trie");
        System.out.println("=".repeat(55));

        List<Suggestion> suggestions = List.of(
            // Global search queries
            SuggestionFactory.createQuery("iphone 15 price",       950_000),
            SuggestionFactory.createQuery("iphone 15 pro max",     820_000),
            SuggestionFactory.createQuery("iphone 15 review",      640_000),
            SuggestionFactory.createQuery("iphone 14",             580_000),
            SuggestionFactory.createQuery("instagram download",    700_000),
            SuggestionFactory.createQuery("india vs australia",    430_000),
            SuggestionFactory.createQuery("income tax login",      390_000),
            SuggestionFactory.createQuery("ipl 2024 schedule",     520_000),
            SuggestionFactory.createQuery("java interview questions",300_000),
            SuggestionFactory.createQuery("java 21 features",      180_000),
            SuggestionFactory.createQuery("javascript tutorial",   420_000),
            SuggestionFactory.createQuery("jee mains result",      610_000),
            SuggestionFactory.createQuery("system design interview",240_000),
            SuggestionFactory.createQuery("system design lld",     120_000),
            SuggestionFactory.createQuery("swiggy promo code",     310_000),
            SuggestionFactory.createQuery("swiggy one membership", 180_000),

            // E-commerce products
            SuggestionFactory.createProduct("iPhone 15",          "79,900", 950_000),
            SuggestionFactory.createProduct("iPhone 15 Pro",      "1,34,900",820_000),
            SuggestionFactory.createProduct("iPad Pro 2024",      "1,09,900",340_000),

            // Social users
            SuggestionFactory.createUser("@iamSRK",   "Shah Rukh Khan",  45_000_000),
            SuggestionFactory.createUser("@imVkohli", "Virat Kohli",     260_000_000),
            SuggestionFactory.createUser("@indiatoday","India Today",     12_000_000),

            // Hashtags
            SuggestionFactory.createHashtag("#IPL2024",    8_200_000),
            SuggestionFactory.createHashtag("#India",      12_000_000),
            SuggestionFactory.createHashtag("#iPhone15",   3_400_000),

            // Locations
            SuggestionFactory.createLocation("Indiranagar, Bangalore", 820_000),
            SuggestionFactory.createLocation("India Gate, Delhi",      940_000)
        );

        service.bulkLoad(suggestions);

        // ===== SCENARIO 1: Basic prefix search =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Prefix Search (Frequency Ranking)");
        System.out.println("=".repeat(55));

        String[] prefixes = {"ip", "iph", "iphon", "iphone", "iphone 1",
                              "j", "ja", "sys", "sw", "in"};

        service.setRankingStrategy(new FrequencyRankingStrategy());
        for (String prefix : prefixes) {
            System.out.println("\nPrefix: '" + prefix + "'");
            SearchQuery q = new SearchQuery.Builder(prefix).topK(5).build();
            service.getSuggestions(q)
                .forEach(s -> System.out.println("  " + s));
        }

        // ===== SCENARIO 2: Cache hit demonstration =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Cache Hit (same prefix queried again)");
        System.out.println("=".repeat(55));

        System.out.println("First call (cache miss):");
        service.getSuggestions(new SearchQuery.Builder("iphone").topK(5).build());
        System.out.println("\nSecond call (cache hit):");
        service.getSuggestions(new SearchQuery.Builder("iphone").topK(5).build());

        // ===== SCENARIO 3: Personalized ranking =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Personalized Ranking");
        System.out.println("=".repeat(55));

        // Alice previously searched for "java interview questions"
        service.recordSelection("alice", "java", "java interview questions");
        service.recordSelection("alice", "java", "java 21 features");

        service.setRankingStrategy(new PersonalizedRankingStrategy());
        System.out.println("\nAlice searches 'java' (personalized — her history boosted):");
        SearchQuery aliceQuery = new SearchQuery.Builder("java")
            .userId("alice").topK(5).build();
        service.getSuggestions(aliceQuery)
            .forEach(s -> System.out.println("  " + s));

        System.out.println("\nBob searches 'java' (no history — pure frequency):");
        SearchQuery bobQuery = new SearchQuery.Builder("java")
            .userId("bob").topK(5).build();
        service.getSuggestions(bobQuery)
            .forEach(s -> System.out.println("  " + s));

        // ===== SCENARIO 4: Domain-specific search =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: Domain-specific Search");
        System.out.println("=".repeat(55));

        System.out.println("E-commerce domain — prefix 'iph':");
        service.getSuggestions(new SearchQuery.Builder("iph")
            .domain(SearchDomain.ECOMMERCE).topK(5).build())
            .forEach(s -> System.out.println("  " + s));

        System.out.println("\nSocial domain — prefix 'in':");
        service.getSuggestions(new SearchQuery.Builder("in")
            .domain(SearchDomain.SOCIAL).topK(5).build())
            .forEach(s -> System.out.println("  " + s));

        System.out.println("\nGlobal domain — prefix 'in':");
        service.getSuggestions(new SearchQuery.Builder("in")
            .domain(SearchDomain.GLOBAL).topK(5).build())
            .forEach(s -> System.out.println("  " + s));

        // ===== SCENARIO 5: User selection + frequency boost =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: User selections drive frequency boost");
        System.out.println("=".repeat(55));

        // Many users select "ipl 2024 schedule" — trending
        String[] users = {"u1", "u2", "u3", "u4", "u5", "u6", "u7", "u8"};
        for (String u : users) {
            service.recordSelection(u, "ipl", "ipl 2024 schedule");
        }

        service.refreshTrending();
        System.out.println("\nAfter trending refresh — prefix 'ipl':");
        service.getSuggestions(new SearchQuery.Builder("ipl").topK(5).build())
            .forEach(s -> System.out.println("  " + s));

        // ===== SCENARIO 6: No results =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: No Results");
        System.out.println("=".repeat(55));

        service.getSuggestions(new SearchQuery.Builder("zzzzz").topK(5).build());
        service.getSuggestions(new SearchQuery.Builder("xyloph").topK(5).build());

        // ===== SCENARIO 7: Single character prefix =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Single Character Prefix");
        System.out.println("=".repeat(55));

        service.setRankingStrategy(new FrequencyRankingStrategy());
        System.out.println("Prefix 'i' — top 8 suggestions:");
        service.getSuggestions(new SearchQuery.Builder("i").topK(8).build())
            .forEach(s -> System.out.println("  " + s));

        // ===== ANALYTICS =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("ANALYTICS REPORT");
        System.out.println("=".repeat(55));
        service.printAnalytics();
        service.printNoResults();

        System.out.println("\nTrending terms:");
        service.getTrending(5).forEach((term, count) ->
            System.out.println("  " + term + " → " + count + " recent searches"));

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | AutocompleteService
            Strategy   | RankingStrategy (Frequency/Personalized/Trending)
            Factory    | SuggestionFactory (query/product/user/hashtag)
            Builder    | SearchQuery.Builder
            Observer   | SearchEventObserver (Analytics + Trending)
            Trie       | Core prefix data structure (TrieNode + Trie)
            """);
    }
}
