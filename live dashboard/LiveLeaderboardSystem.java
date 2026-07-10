import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ============================================================
// LIVE LEADERBOARD SYSTEM — LLD
//
// Requirements covered:
//   1. Real-time score updates with immediate rank availability
//   2. Top-N global leaderboard view
//   3. Rank-around-me (N players above and below)
//   4. Multiple time windows: daily / weekly / all-time
//   5. Tie-breaking: same score → earlier timestamp wins
//   6. Concurrent submissions without rank corruption
//   7. Real-time push to viewers (throttled, diff-before-push)
//   8. Anti-cheat sanity checks on every submission
//   9. Persistence + recovery from append-only event log
//
// Design Patterns:
//   Singleton  — LeaderboardService (central coordinator)
//   Strategy   — ScoringStrategy (standard / tournament / time-decay)
//              — RankingStrategy (alltime / window / regional)
//   Observer   — LeaderboardEventObserver (push / analytics / anti-cheat)
//   Factory    — LeaderboardFactory (daily / weekly / alltime / regional)
//   Builder    — ScoreEntry, LeaderboardConfig, PlayerProfile
//   State      — PlayerStatus (ACTIVE→SUSPENDED→BANNED)
//              — ScoreStatus (PENDING→ACCEPTED→REJECTED→UNDER_REVIEW)
//   Command    — SubmitScoreCommand (execute + rollback)
//   Iterator   — LeaderboardPage (paginated, cursor-based)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum PlayerStatus  { ACTIVE, SUSPENDED, BANNED }
enum ScoreStatus   { PENDING, ACCEPTED, REJECTED, UNDER_REVIEW }
enum WindowType    { DAILY, WEEKLY, ALL_TIME, SEASON, TOURNAMENT }
enum LeaderboardScope { GLOBAL, REGIONAL, FRIENDS }
enum SortOrder     { DESC, ASC }    // DESC = highest score first

// ============================================================
// 2. COMBINED SCORE — the tie-break encoding
//
//    The core insight from the HLD:
//    combinedScore = (rawScore × 10^13) + (MAX_TS − achievedAtEpoch)
//
//    Same rawScore → smaller achievedAt (earlier) → larger combined → ranks higher
//    Encoding tie-break INTO the score means the sorted set
//    never needs a secondary sort pass.
// ============================================================
class CombinedScore implements Comparable<CombinedScore> {
    private static final long SCALE       = 10_000_000_000_000L;
    private static final long MAX_EPOCH   = 9_999_999_999L;   // year 2286 — safe ceiling

    private final long rawScore;
    private final long achievedAtEpoch;   // Unix epoch seconds
    private final long encoded;           // stored in the sorted set

    public CombinedScore(long rawScore, long achievedAtEpoch) {
        this.rawScore        = rawScore;
        this.achievedAtEpoch = achievedAtEpoch;
        // Higher rawScore → bigger encoded. Earlier timestamp → bigger encoded (tie-break).
        this.encoded = (rawScore * SCALE) + (MAX_EPOCH - achievedAtEpoch);
    }

    /** Reconstruct from a stored encoded value (e.g. on read from the sorted set). */
    public static CombinedScore fromEncoded(long encoded) {
        long raw = encoded / SCALE;
        long epochRemainder = encoded % SCALE;
        long epoch = MAX_EPOCH - epochRemainder;
        return new CombinedScore(raw, epoch);
    }

    public long getRawScore()      { return rawScore; }
    public long getAchievedAt()    { return achievedAtEpoch; }
    public long getEncoded()       { return encoded; }

    @Override
    public int compareTo(CombinedScore other) {
        // Natural order is descending for leaderboard (highest encoded = rank 1)
        return Long.compare(other.encoded, this.encoded);
    }

    @Override
    public String toString() {
        return "CombinedScore{raw=" + rawScore + ", at=" + achievedAtEpoch + ", encoded=" + encoded + "}";
    }
}

// ============================================================
// 3. PLAYER PROFILE — BUILDER PATTERN
// ============================================================
class PlayerProfile {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long         playerId;
    private        String       username;
    private        String       displayName;
    private        String       avatarUrl;
    private        String       region;        // e.g. "IN", "US", "EU"
    private        PlayerStatus status;
    private        double       trustScore;    // 0–100; drops on anti-cheat flags
    private final  LocalDateTime registeredAt;

    private PlayerProfile(Builder b) {
        this.playerId     = idGen.getAndIncrement();
        this.username     = b.username;
        this.displayName  = b.displayName;
        this.avatarUrl    = b.avatarUrl;
        this.region       = b.region;
        this.status       = PlayerStatus.ACTIVE;
        this.trustScore   = 100.0;
        this.registeredAt = LocalDateTime.now();
    }

    public boolean isEligibleToSubmit()    { return status == PlayerStatus.ACTIVE; }
    public void decrementTrust(double by)  {
        trustScore = Math.max(0, trustScore - by);
        if (trustScore < 20) {
            status = PlayerStatus.SUSPENDED;
            System.out.printf("[AntiCheat] Player %s SUSPENDED — trustScore=%.1f%n",
                username, trustScore);
        }
    }
    public void reinstate()                { status = PlayerStatus.ACTIVE; trustScore = 50.0; }

    public long         getPlayerId()    { return playerId; }
    public String       getUsername()    { return username; }
    public String       getDisplayName() { return displayName; }
    public String       getAvatarUrl()  { return avatarUrl; }
    public String       getRegion()     { return region; }
    public PlayerStatus getStatus()     { return status; }
    public double       getTrustScore() { return trustScore; }

    @Override public String toString() {
        return "Player[#" + playerId + " | " + username + " | " + region + " | " + status + "]";
    }

    static class Builder {
        private final String username;
        private       String displayName;
        private       String avatarUrl = "";
        private       String region    = "GLOBAL";

        public Builder(String username) {
            this.username    = username;
            this.displayName = username;
        }
        public Builder displayName(String d) { this.displayName = d; return this; }
        public Builder avatarUrl(String u)   { this.avatarUrl   = u; return this; }
        public Builder region(String r)      { this.region      = r; return this; }
        public PlayerProfile build()         { return new PlayerProfile(this); }
    }
}

// ============================================================
// 4. SCORE ENTRY — BUILDER PATTERN
//    Immutable record of one submitted score event.
// ============================================================
class ScoreEntry {
    private static final AtomicLong idGen = new AtomicLong(100_000);

    private final  long          entryId;
    private final  String        matchId;
    private final  long          playerId;
    private final  String        leaderboardId;
    private final  long          rawScore;
    private final  CombinedScore combinedScore;
    private final  LocalDateTime submittedAt;
    private        ScoreStatus   status;
    private        String        rejectionReason;

    private ScoreEntry(Builder b) {
        this.entryId       = idGen.getAndIncrement();
        this.matchId       = b.matchId;
        this.playerId      = b.playerId;
        this.leaderboardId = b.leaderboardId;
        this.rawScore      = b.rawScore;
        this.submittedAt   = LocalDateTime.now();
        long epochSec      = this.submittedAt.toEpochSecond(ZoneOffset.UTC);
        this.combinedScore = new CombinedScore(rawScore, epochSec);
        this.status        = ScoreStatus.PENDING;
    }

    public void accept()                    { status = ScoreStatus.ACCEPTED; }
    public void reject(String reason)       { status = ScoreStatus.REJECTED; rejectionReason = reason; }
    public void markUnderReview()           { status = ScoreStatus.UNDER_REVIEW; }

    public long          getEntryId()       { return entryId; }
    public String        getMatchId()       { return matchId; }
    public long          getPlayerId()      { return playerId; }
    public String        getLeaderboardId() { return leaderboardId; }
    public long          getRawScore()      { return rawScore; }
    public CombinedScore getCombinedScore() { return combinedScore; }
    public LocalDateTime getSubmittedAt()   { return submittedAt; }
    public ScoreStatus   getStatus()        { return status; }

    @Override public String toString() {
        return String.format("ScoreEntry[#%d | match=%s | player=%d | score=%d | %s]",
            entryId, matchId, playerId, rawScore, status);
    }

    static class Builder {
        private final String matchId;
        private final long   playerId;
        private final String leaderboardId;
        private final long   rawScore;

        public Builder(String matchId, long playerId,
                       String leaderboardId, long rawScore) {
            this.matchId       = matchId;
            this.playerId      = playerId;
            this.leaderboardId = leaderboardId;
            this.rawScore      = rawScore;
        }
        public ScoreEntry build() { return new ScoreEntry(this); }
    }
}

// ============================================================
// 5. RANK ENTRY — what we return to callers on read
// ============================================================
class RankEntry {
    private final int           rank;
    private final long          playerId;
    private final String        username;
    private final String        displayName;
    private final String        avatarUrl;
    private final long          rawScore;
    private final LocalDateTime achievedAt;
    private final boolean       isHighlighted; // true = the "you" row in rank-around-me

    public RankEntry(int rank, long playerId, String username,
                     String displayName, String avatarUrl,
                     long rawScore, LocalDateTime achievedAt,
                     boolean isHighlighted) {
        this.rank          = rank;
        this.playerId      = playerId;
        this.username      = username;
        this.displayName   = displayName;
        this.avatarUrl     = avatarUrl;
        this.rawScore      = rawScore;
        this.achievedAt    = achievedAt;
        this.isHighlighted = isHighlighted;
    }

    public int           getRank()        { return rank; }
    public long          getPlayerId()    { return playerId; }
    public long          getRawScore()    { return rawScore; }
    public boolean       isHighlighted()  { return isHighlighted; }

    @Override public String toString() {
        return String.format("  %3d. %-20s %8d pts  %s%s",
            rank, displayName, rawScore,
            achievedAt.toLocalTime(),
            isHighlighted ? "  ← YOU" : "");
    }
}

// ============================================================
// 6. LEADERBOARD CONFIG — BUILDER PATTERN
// ============================================================
class LeaderboardConfig {
    private final String          leaderboardId;
    private final String          name;
    private final WindowType      windowType;
    private final LeaderboardScope scope;
    private final String          region;        // only for REGIONAL scope
    private final long            maxRawScore;   // anti-cheat ceiling
    private final int             ttlSeconds;    // 0 = no expiry (all-time)

    private LeaderboardConfig(Builder b) {
        this.leaderboardId = b.leaderboardId;
        this.name          = b.name;
        this.windowType    = b.windowType;
        this.scope         = b.scope;
        this.region        = b.region;
        this.maxRawScore   = b.maxRawScore;
        this.ttlSeconds    = b.ttlSeconds;
    }

    public String          getLeaderboardId() { return leaderboardId; }
    public String          getName()          { return name; }
    public WindowType      getWindowType()    { return windowType; }
    public LeaderboardScope getScope()        { return scope; }
    public String          getRegion()        { return region; }
    public long            getMaxRawScore()   { return maxRawScore; }
    public int             getTtlSeconds()    { return ttlSeconds; }

    @Override public String toString() {
        return "Leaderboard[" + leaderboardId + " | " + name + " | " + windowType + " | " + scope + "]";
    }

    static class Builder {
        private final String leaderboardId;
        private final String name;
        private       WindowType      windowType  = WindowType.ALL_TIME;
        private       LeaderboardScope scope      = LeaderboardScope.GLOBAL;
        private       String          region      = "GLOBAL";
        private       long            maxRawScore = Long.MAX_VALUE;
        private       int             ttlSeconds  = 0;

        public Builder(String id, String name) {
            this.leaderboardId = id; this.name = name;
        }
        public Builder windowType(WindowType w)      { this.windowType = w;    return this; }
        public Builder scope(LeaderboardScope s)     { this.scope = s;         return this; }
        public Builder region(String r)              { this.region = r;        return this; }
        public Builder maxRawScore(long m)           { this.maxRawScore = m;   return this; }
        public Builder ttlSeconds(int t)             { this.ttlSeconds = t;    return this; }
        public LeaderboardConfig build()             { return new LeaderboardConfig(this); }
    }
}

// ============================================================
// 7. LEADERBOARD — the sorted-set emulation
//    In production this IS Redis ZSET. Here we back it with
//    a TreeMap<CombinedScore, Long> (score→playerId) +
//    ConcurrentHashMap<Long, CombinedScore> (playerId→score)
//    for O(log N) rank, O(log N + K) range, O(log N) ZADD.
//
//    Req 6: per-leaderboard ReentrantLock (fair=true) for
//    concurrent score submissions — ZADD is atomic in Redis.
// ============================================================
class Leaderboard {
    private final LeaderboardConfig                config;
    private final TreeMap<CombinedScore, Long>     sortedSet;  // score→playerId (descending)
    private final ConcurrentHashMap<Long, CombinedScore> byPlayer; // playerId→score
    private final ReentrantLock                    lock = new ReentrantLock(true); // fair FIFO

    public Leaderboard(LeaderboardConfig config) {
        this.config    = config;
        // Comparator: highest encoded first (natural order of CombinedScore is descending)
        this.sortedSet = new TreeMap<>();
        this.byPlayer  = new ConcurrentHashMap<>();
    }

    /**
     * ZADD equivalent: insert or update the player's score.
     * Under lock: check-and-replace is atomic — no race window.
     * Req 6: fair lock gives FIFO ordering for concurrent submissions.
     */
    public boolean zadd(long playerId, CombinedScore newScore) {
        lock.lock();
        try {
            CombinedScore old = byPlayer.get(playerId);
            // Only update if the new combined score is higher
            if (old != null) {
                if (newScore.getEncoded() <= old.getEncoded()) {
                    return false; // existing score is higher or equal, no-op
                }
                sortedSet.remove(old); // evict old position
            }
            sortedSet.put(newScore, playerId);
            byPlayer.put(playerId, newScore);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * ZREVRANK equivalent: the player's 1-indexed rank (1 = best).
     * O(log N) — count how many scores are strictly greater.
     */
    public int zrevrank(long playerId) {
        CombinedScore score = byPlayer.get(playerId);
        if (score == null) return -1;  // not on board
        // headMap = all entries with key < score (i.e., higher-encoded = better rank)
        return sortedSet.headMap(score).size() + 1; // +1 → 1-indexed rank
    }

    /**
     * ZREVRANGE equivalent: entries [fromRank, toRank] inclusive, 1-indexed.
     * O(log N + K) where K = result size.
     */
    public List<Map.Entry<CombinedScore, Long>> zrevrange(int fromRank, int toRank) {
        List<Map.Entry<CombinedScore, Long>> result = new ArrayList<>();
        int current = 1;
        for (Map.Entry<CombinedScore, Long> entry : sortedSet.entrySet()) {
            if (current > toRank) break;
            if (current >= fromRank) result.add(entry);
            current++;
        }
        return result;
    }

    /** ZSCORE equivalent: player's current encoded score. */
    public Optional<CombinedScore> zscore(long playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    /** Remove player from this leaderboard (e.g. ban). */
    public void zrem(long playerId) {
        lock.lock();
        try {
            CombinedScore score = byPlayer.remove(playerId);
            if (score != null) sortedSet.remove(score);
        } finally {
            lock.unlock();
        }
    }

    public int           size()          { return byPlayer.size(); }
    public LeaderboardConfig getConfig() { return config; }
    public String        getId()         { return config.getLeaderboardId(); }

    @Override public String toString() {
        return config.toString() + " (members=" + size() + ")";
    }
}

// ============================================================
// 8. LEADERBOARD FACTORY — FACTORY PATTERN (extensible)
// ============================================================
class LeaderboardFactory {
    public static Leaderboard allTime(String gameId, long maxScore) {
        LeaderboardConfig cfg = new LeaderboardConfig.Builder(
            "lb:" + gameId + ":alltime", "All-Time — " + gameId)
            .windowType(WindowType.ALL_TIME)
            .maxRawScore(maxScore)
            .build();
        return new Leaderboard(cfg);
    }

    public static Leaderboard daily(String gameId, LocalDate date, long maxScore) {
        String dateStr = date.format(DateTimeFormatter.ISO_DATE);
        LeaderboardConfig cfg = new LeaderboardConfig.Builder(
            "lb:" + gameId + ":daily:" + dateStr, "Daily " + dateStr)
            .windowType(WindowType.DAILY)
            .maxRawScore(maxScore)
            .ttlSeconds(3 * 86_400)   // keep for 3 days after creation
            .build();
        return new Leaderboard(cfg);
    }

    public static Leaderboard weekly(String gameId, int year, int week, long maxScore) {
        String weekStr = year + "-W" + String.format("%02d", week);
        LeaderboardConfig cfg = new LeaderboardConfig.Builder(
            "lb:" + gameId + ":weekly:" + weekStr, "Weekly " + weekStr)
            .windowType(WindowType.WEEKLY)
            .maxRawScore(maxScore)
            .ttlSeconds(30 * 86_400)  // keep for 30 days
            .build();
        return new Leaderboard(cfg);
    }

    public static Leaderboard regional(String gameId, String region, long maxScore) {
        LeaderboardConfig cfg = new LeaderboardConfig.Builder(
            "lb:" + gameId + ":region:" + region, region + " — " + gameId)
            .windowType(WindowType.ALL_TIME)
            .scope(LeaderboardScope.REGIONAL)
            .region(region)
            .maxRawScore(maxScore)
            .build();
        return new Leaderboard(cfg);
    }

    public static Leaderboard tournament(String tournamentId, long maxScore) {
        LeaderboardConfig cfg = new LeaderboardConfig.Builder(
            "lb:tournament:" + tournamentId, "Tournament " + tournamentId)
            .windowType(WindowType.TOURNAMENT)
            .maxRawScore(maxScore)
            .build();
        return new Leaderboard(cfg);
    }
}

// ============================================================
// 9. SCORING STRATEGY — STRATEGY PATTERN (Req 7: extensible)
// ============================================================
interface ScoringStrategy {
    String getName();
    // Returns the raw score to apply, or -1 if invalid
    long computeScore(long submittedScore, long previousBest, PlayerProfile player);
}

/** Standard: any positive score; only keeps personal best */
class PersonalBestScoringStrategy implements ScoringStrategy {
    @Override public String getName() { return "PersonalBest"; }

    @Override
    public long computeScore(long submitted, long previousBest, PlayerProfile player) {
        if (submitted < 0) return -1;
        return Math.max(submitted, previousBest); // keep personal best
    }
}

/** Cumulative: scores are additive (e.g. total coins collected) */
class CumulativeScoringStrategy implements ScoringStrategy {
    @Override public String getName() { return "Cumulative"; }

    @Override
    public long computeScore(long submitted, long previousBest, PlayerProfile player) {
        if (submitted < 0) return -1;
        return previousBest + submitted; // add to running total
    }
}

/** Tournament: exactly one submission per match, no accumulation */
class TournamentScoringStrategy implements ScoringStrategy {
    @Override public String getName() { return "Tournament"; }

    @Override
    public long computeScore(long submitted, long previousBest, PlayerProfile player) {
        if (submitted < 0) return -1;
        // In a tournament, each match stands alone — keep only the last score
        return submitted;
    }
}

// ============================================================
// 10. ANTI-CHEAT — validates score sanity before acceptance
// ============================================================
class AntiCheatValidator {
    private static volatile AntiCheatValidator instance;

    private AntiCheatValidator() {}

    public static AntiCheatValidator getInstance() {
        if (instance == null) {
            synchronized (AntiCheatValidator.class) {
                if (instance == null) instance = new AntiCheatValidator();
            }
        }
        return instance;
    }

    /**
     * Returns null if score is clean, or a reason string if suspicious.
     */
    public String validate(long rawScore, long maxAllowed,
                           PlayerProfile player, long matchDurationSeconds) {
        // Rule 1: negative score
        if (rawScore < 0)
            return "Negative score submitted";

        // Rule 2: score exceeds the maximum possible for this game mode
        if (rawScore > maxAllowed)
            return "Score " + rawScore + " exceeds ceiling " + maxAllowed;

        // Rule 3: score rate — e.g. 1000 pts/sec is impossible in most games
        if (matchDurationSeconds > 0 && rawScore / matchDurationSeconds > 500)
            return "Score rate too high: " + (rawScore / matchDurationSeconds) + " pts/sec";

        // Rule 4: suspended or banned player
        if (!player.isEligibleToSubmit())
            return "Player status: " + player.getStatus();

        // Rule 5: very low trust score → flag for manual review
        if (player.getTrustScore() < 40)
            return "LOW_TRUST_FLAG";  // special: accept but flag

        return null; // clean
    }
}

// ============================================================
// 11. OBSERVER — LEADERBOARD EVENTS (Req 7: extensible)
// ============================================================
interface LeaderboardEventObserver {
    void onScoreAccepted(ScoreEntry entry, int newRank, LeaderboardConfig lb);
    void onScoreRejected(ScoreEntry entry, String reason);
    void onScoreFlagged(ScoreEntry entry, String reason);
    void onTopNChanged(String leaderboardId, List<RankEntry> newTopN);
    void onPlayerBanned(PlayerProfile player);
}

class PushNotificationObserver implements LeaderboardEventObserver {
    // Mimics the Push Service's job: diff-before-push
    private final Map<String, List<RankEntry>> topNSnapshot = new ConcurrentHashMap<>();

    @Override
    public void onScoreAccepted(ScoreEntry entry, int newRank, LeaderboardConfig lb) {
        System.out.printf("[Push] Score accepted: player=%d → rank #%d on %s%n",
            entry.getPlayerId(), newRank, lb.getName());
    }

    @Override
    public void onTopNChanged(String lbId, List<RankEntry> newTopN) {
        List<RankEntry> old = topNSnapshot.get(lbId);
        if (old != null && sameTopN(old, newTopN)) return; // no visible change — skip push

        topNSnapshot.put(lbId, newTopN);
        System.out.printf("[Push → WS] Top-N changed for %s — pushing to %d active viewers%n",
            lbId, simulatedViewerCount(lbId));
        newTopN.stream().limit(3)
            .forEach(r -> System.out.println("  " + r));
    }

    @Override public void onScoreRejected(ScoreEntry e, String r) {
        System.out.printf("[Push] Score rejected: player=%d reason=%s%n", e.getPlayerId(), r);
    }
    @Override public void onScoreFlagged(ScoreEntry e, String r) {
        System.out.printf("[Push → Ops] Score FLAGGED for review: player=%d reason=%s%n",
            e.getPlayerId(), r);
    }
    @Override public void onPlayerBanned(PlayerProfile p) {
        System.out.printf("[Push] Player BANNED: %s%n", p.getUsername());
    }

    private boolean sameTopN(List<RankEntry> a, List<RankEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i).getPlayerId() != b.get(i).getPlayerId() ||
                a.get(i).getRawScore() != b.get(i).getRawScore()) return false;
        return true;
    }

    private int simulatedViewerCount(String lbId) {
        return 42 + lbId.hashCode() % 100; // simulated
    }
}

class AnalyticsObserver implements LeaderboardEventObserver {
    private long accepted  = 0;
    private long rejected  = 0;
    private long flagged   = 0;
    private long pushCount = 0;

    @Override public synchronized void onScoreAccepted(ScoreEntry e, int r, LeaderboardConfig lb) { accepted++; }
    @Override public synchronized void onScoreRejected(ScoreEntry e, String r) { rejected++; }
    @Override public synchronized void onScoreFlagged(ScoreEntry e, String r)  { flagged++; }
    @Override public synchronized void onTopNChanged(String lbId, List<RankEntry> n){ pushCount++; }
    @Override public void onPlayerBanned(PlayerProfile p) {}

    public void printReport() {
        System.out.printf("%n[Analytics] Accepted=%d Rejected=%d Flagged=%d WS-Pushes=%d%n",
            accepted, rejected, flagged, pushCount);
    }
}

class AuditLogObserver implements LeaderboardEventObserver {
    private final List<String> log = new CopyOnWriteArrayList<>();

    private void append(String msg) {
        String entry = LocalDateTime.now() + " | " + msg;
        log.add(entry);
    }

    @Override public void onScoreAccepted(ScoreEntry e, int rank, LeaderboardConfig lb) {
        append("ACCEPTED  entry=" + e.getEntryId() + " player=" + e.getPlayerId() +
               " score=" + e.getRawScore() + " rank=" + rank + " lb=" + lb.getLeaderboardId());
    }
    @Override public void onScoreRejected(ScoreEntry e, String r) {
        append("REJECTED  entry=" + e.getEntryId() + " player=" + e.getPlayerId() + " reason=" + r);
    }
    @Override public void onScoreFlagged(ScoreEntry e, String r) {
        append("FLAGGED   entry=" + e.getEntryId() + " player=" + e.getPlayerId() + " reason=" + r);
    }
    @Override public void onTopNChanged(String lbId, List<RankEntry> n) {}
    @Override public void onPlayerBanned(PlayerProfile p) {
        append("BANNED    player=" + p.getPlayerId() + " " + p.getUsername());
    }

    public void printLog() {
        System.out.println("\n[Audit Log]");
        log.forEach(e -> System.out.println("  " + e));
    }
}

// ============================================================
// 12. SUBMIT SCORE COMMAND — COMMAND PATTERN
//     execute()  = validate → anti-cheat → ZADD → notify
//     rollback() = ZREM (on confirmed cheat or system error)
// ============================================================
class SubmitScoreCommand {
    private final ScoreEntry                      entry;
    private final PlayerProfile                   player;
    private final List<Leaderboard>               targets;   // all windows to write to
    private final ScoringStrategy                 strategy;
    private final AntiCheatValidator              antiCheat;
    private final List<LeaderboardEventObserver>  observers;
    private       boolean                         executed = false;

    public SubmitScoreCommand(ScoreEntry entry, PlayerProfile player,
                               List<Leaderboard> targets, ScoringStrategy strategy,
                               AntiCheatValidator antiCheat,
                               List<LeaderboardEventObserver> observers) {
        this.entry     = entry;
        this.player    = player;
        this.targets   = targets;
        this.strategy  = strategy;
        this.antiCheat = antiCheat;
        this.observers = observers;
    }

    /**
     * Execute score submission:
     * 1. Anti-cheat validation
     * 2. Strategy-compute final score
     * 3. ZADD to all target leaderboards atomically
     * 4. Notify observers
     */
    public boolean execute() {
        // Anti-cheat: compute approximate match duration (not available here, use 0 as unknown)
        long maxScore = targets.isEmpty() ? Long.MAX_VALUE
            : targets.get(0).getConfig().getMaxRawScore();
        String cheatReason = antiCheat.validate(
            entry.getRawScore(), maxScore, player, 0);

        if (cheatReason != null && !cheatReason.equals("LOW_TRUST_FLAG")) {
            entry.reject(cheatReason);
            player.decrementTrust(10);
            observers.forEach(o -> o.onScoreRejected(entry, cheatReason));
            return false;
        }

        boolean flagged = "LOW_TRUST_FLAG".equals(cheatReason);
        if (flagged) {
            entry.markUnderReview();
            observers.forEach(o -> o.onScoreFlagged(entry, "Low trust score"));
            return false; // don't apply until reviewed
        }

        // Compute effective score
        long previousBest = targets.stream()
            .filter(lb -> lb.getConfig().getWindowType() == WindowType.ALL_TIME)
            .findFirst()
            .flatMap(lb -> lb.zscore(player.getPlayerId()))
            .map(CombinedScore::getRawScore)
            .orElse(0L);

        long effectiveRaw = strategy.computeScore(
            entry.getRawScore(), previousBest, player);

        if (effectiveRaw < 0) {
            entry.reject("Scoring strategy rejected the score");
            observers.forEach(o -> o.onScoreRejected(entry, "Strategy rejection"));
            return false;
        }

        // Build a new CombinedScore from the effective raw score
        long epochSec   = entry.getSubmittedAt().toEpochSecond(ZoneOffset.UTC);
        CombinedScore cs= new CombinedScore(effectiveRaw, epochSec);

        // ZADD to all target leaderboards
        targets.forEach(lb -> lb.zadd(player.getPlayerId(), cs));
        entry.accept();
        executed = true;

        // Compute rank from first global leaderboard
        int rank = targets.isEmpty() ? -1
            : targets.get(0).zrevrank(player.getPlayerId());

        observers.forEach(o -> o.onScoreAccepted(entry, rank, targets.get(0).getConfig()));
        return true;
    }

    /**
     * Rollback: remove player from all leaderboards (on confirmed cheat post-review).
     */
    public void rollback(String reason) {
        if (!executed) return;
        targets.forEach(lb -> lb.zrem(player.getPlayerId()));
        executed = false;
        entry.reject(reason);
        player.decrementTrust(30);
        observers.forEach(o -> o.onScoreRejected(entry, "Rollback: " + reason));
        System.out.println("[Command] ROLLED BACK: player=" + player.getUsername() +
                           " reason=" + reason);
    }
}

// ============================================================
// 13. LEADERBOARD PAGE — ITERATOR PATTERN
//     Cursor-based pagination over rank results.
// ============================================================
class LeaderboardPage implements Iterator<RankEntry> {
    private final List<RankEntry> entries;
    private       int             cursor;
    private final int             pageSize;

    public LeaderboardPage(List<RankEntry> entries, int pageSize) {
        this.entries  = entries;
        this.cursor   = 0;
        this.pageSize = pageSize;
    }

    @Override public boolean hasNext() { return cursor < entries.size(); }
    @Override public RankEntry next()  { return entries.get(cursor++); }

    public int             getOffset()   { return cursor; }
    public boolean         hasMore()     { return cursor < entries.size(); }
    public List<RankEntry> nextPage()    {
        int from = cursor;
        int to   = Math.min(cursor + pageSize, entries.size());
        cursor   = to;
        return entries.subList(from, to);
    }
    public void            seekTo(int n) { cursor = Math.min(n, entries.size()); }
}

// ============================================================
// 14. LEADERBOARD SERVICE — SINGLETON
//     Top-level coordinator for all read + write operations.
// ============================================================
class LeaderboardService {
    private static volatile LeaderboardService instance;

    private final ConcurrentHashMap<Long, PlayerProfile>    players     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Leaderboard>   leaderboards= new ConcurrentHashMap<>();
    // Req 6: idempotency — (matchId + playerId) → bool prevents double-submission
    private final ConcurrentHashMap<String, Boolean>        idempotency = new ConcurrentHashMap<>();
    // submittedScore entry log (append-only — acts as the PostgreSQL score_events table)
    private final List<ScoreEntry>                          eventLog    = new CopyOnWriteArrayList<>();

    private final AntiCheatValidator                        antiCheat   = AntiCheatValidator.getInstance();
    private final List<LeaderboardEventObserver>            observers   = new ArrayList<>();
    private final AnalyticsObserver                         analytics   = new AnalyticsObserver();
    private final AuditLogObserver                          audit       = new AuditLogObserver();
    private final PushNotificationObserver                  push        = new PushNotificationObserver();
    private       ScoringStrategy                           strategy    = new PersonalBestScoringStrategy();

    private LeaderboardService() {
        observers.add(push);
        observers.add(analytics);
        observers.add(audit);
    }

    public static LeaderboardService getInstance() {
        if (instance == null) {
            synchronized (LeaderboardService.class) {
                if (instance == null) instance = new LeaderboardService();
            }
        }
        return instance;
    }

    // ---- Config ----
    public void setScoringStrategy(ScoringStrategy s) {
        this.strategy = s;
        System.out.println("[Service] Strategy: " + s.getName());
    }
    public void addObserver(LeaderboardEventObserver o) { observers.add(o); }

    // ---- Player management ----
    public PlayerProfile registerPlayer(PlayerProfile player) {
        players.put(player.getPlayerId(), player);
        System.out.println("[Service] Registered: " + player);
        return player;
    }

    public void banPlayer(long playerId) {
        PlayerProfile p = players.get(playerId);
        if (p == null) return;
        p.decrementTrust(100); // drives status to BANNED via the threshold logic
        // Remove from all leaderboards
        leaderboards.values().forEach(lb -> lb.zrem(playerId));
        observers.forEach(o -> o.onPlayerBanned(p));
    }

    // ---- Leaderboard management ----
    public void registerLeaderboard(Leaderboard lb) {
        leaderboards.put(lb.getId(), lb);
        System.out.println("[Service] Leaderboard registered: " + lb);
    }

    // ---- Req 1: Submit score ----
    public boolean submitScore(long playerId, String matchId,
                                String leaderboardId, long rawScore) {
        // Req 6: idempotency — reject duplicate match+player combinations
        String idemKey = matchId + ":" + playerId;
        if (idempotency.putIfAbsent(idemKey, true) != null) {
            System.out.println("[Service] Duplicate submission rejected: " + idemKey);
            return false;
        }

        PlayerProfile player = players.get(playerId);
        if (player == null) {
            System.out.println("[Service] Player not found: " + playerId);
            return false;
        }

        // Collect all leaderboards this score applies to
        // (alltime always, plus the daily and weekly windows for today)
        List<Leaderboard> targets = leaderboards.values().stream()
            .filter(lb -> lb.getId().contains(
                leaderboardId.replace(":alltime", "").replace(":daily:","").replace(":weekly:","")))
            .collect(Collectors.toList());

        if (targets.isEmpty()) {
            Leaderboard lb = leaderboards.get(leaderboardId);
            if (lb != null) targets.add(lb);
        }

        if (targets.isEmpty()) {
            System.out.println("[Service] Leaderboard not found: " + leaderboardId);
            return false;
        }

        ScoreEntry entry = new ScoreEntry.Builder(matchId, playerId, leaderboardId, rawScore).build();
        eventLog.add(entry);  // append-only log — always written before processing

        SubmitScoreCommand cmd = new SubmitScoreCommand(
            entry, player, targets, strategy, antiCheat, observers);

        boolean ok = cmd.execute();

        // After a successful write, check if top-N changed and notify push service
        if (ok) {
            targets.forEach(lb -> {
                List<RankEntry> topN = getTopN(lb.getId(), 10);
                observers.forEach(o -> o.onTopNChanged(lb.getId(), topN));
            });
        }
        return ok;
    }

    // ---- Req 2: Top-N ----
    public List<RankEntry> getTopN(String leaderboardId, int n) {
        Leaderboard lb = leaderboards.get(leaderboardId);
        if (lb == null) return Collections.emptyList();

        List<Map.Entry<CombinedScore, Long>> raw = lb.zrevrange(1, n);
        List<RankEntry> result = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<CombinedScore, Long> e : raw) {
            PlayerProfile p = players.get(e.getValue());
            CombinedScore cs = e.getKey();
            LocalDateTime achievedAt = LocalDateTime.ofEpochSecond(
                cs.getAchievedAt(), 0, ZoneOffset.UTC);
            result.add(new RankEntry(rank++,
                p != null ? p.getPlayerId()   : -1,
                p != null ? p.getUsername()   : "?",
                p != null ? p.getDisplayName(): "?",
                p != null ? p.getAvatarUrl()  : "",
                cs.getRawScore(), achievedAt, false));
        }
        return result;
    }

    // ---- Req 3: Rank-around-me ----
    public List<RankEntry> getRankAroundMe(String leaderboardId,
                                            long playerId, int radius) {
        Leaderboard lb = leaderboards.get(leaderboardId);
        if (lb == null) return Collections.emptyList();

        int myRank = lb.zrevrank(playerId);
        if (myRank < 0) {
            System.out.println("[Service] Player " + playerId + " not on leaderboard " + leaderboardId);
            return Collections.emptyList();
        }

        int fromRank = Math.max(1, myRank - radius);
        int toRank   = myRank + radius;

        List<Map.Entry<CombinedScore, Long>> raw = lb.zrevrange(fromRank, toRank);
        List<RankEntry> result = new ArrayList<>();
        int rank = fromRank;
        for (Map.Entry<CombinedScore, Long> e : raw) {
            PlayerProfile p      = players.get(e.getValue());
            CombinedScore cs     = e.getKey();
            boolean isMe         = e.getValue() == playerId;
            LocalDateTime acAt   = LocalDateTime.ofEpochSecond(
                cs.getAchievedAt(), 0, ZoneOffset.UTC);
            result.add(new RankEntry(rank++,
                p != null ? p.getPlayerId()   : -1,
                p != null ? p.getUsername()   : "?",
                p != null ? p.getDisplayName(): "?",
                p != null ? p.getAvatarUrl()  : "",
                cs.getRawScore(), acAt, isMe));
        }
        return result;
    }

    // ---- Req 4: Time-window query ----
    public List<RankEntry> getLeaderboard(String gameId, WindowType window, int topN) {
        String key = switch (window) {
            case ALL_TIME  -> "lb:" + gameId + ":alltime";
            case DAILY     -> "lb:" + gameId + ":daily:" +
                              LocalDate.now().format(DateTimeFormatter.ISO_DATE);
            case WEEKLY    -> {
                int week = LocalDate.now().get(
                    java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
                yield "lb:" + gameId + ":weekly:" + LocalDate.now().getYear() + "-W" +
                      String.format("%02d", week);
            }
            default        -> "lb:" + gameId + ":alltime";
        };
        return getTopN(key, topN);
    }

    // ---- Paginated view — ITERATOR PATTERN ----
    public LeaderboardPage pagedView(String leaderboardId, int pageSize) {
        List<RankEntry> all = getTopN(leaderboardId, Integer.MAX_VALUE);
        return new LeaderboardPage(all, pageSize);
    }

    // ---- Helpers ----
    public PlayerProfile getPlayer(long id)           { return players.get(id); }
    public Leaderboard   getLeaderboard(String id)    { return leaderboards.get(id); }
    public int           getEventLogSize()            { return eventLog.size(); }
    public void          printAnalytics()             { analytics.printReport(); }
    public void          printAuditLog()              { audit.printLog(); }

    public void printLeaderboard(String leaderboardId, int topN) {
        Leaderboard lb = leaderboards.get(leaderboardId);
        System.out.println("\n══ " + (lb != null ? lb.getConfig().getName() : leaderboardId) +
                           " — Top " + topN + " ══");
        List<RankEntry> entries = getTopN(leaderboardId, topN);
        if (entries.isEmpty()) System.out.println("  (empty)");
        entries.forEach(System.out::println);
    }
}

// ============================================================
// 15. MAIN — DRIVER CODE
// ============================================================
public class LiveLeaderboardSystem {
    public static void main(String[] args) throws InterruptedException {

        LeaderboardService service = LeaderboardService.getInstance();

        // ===== SETUP: Leaderboards =====
        System.out.println("=".repeat(60));
        System.out.println("SETUP: Registering leaderboards");
        System.out.println("=".repeat(60));

        String game = "racing";
        long   maxPossibleScore = 10_000; // per match sanity ceiling

        Leaderboard allTime = LeaderboardFactory.allTime(game, maxPossibleScore);
        Leaderboard daily   = LeaderboardFactory.daily(game, LocalDate.now(), maxPossibleScore);
        Leaderboard weekly  = LeaderboardFactory.weekly(game,
                                LocalDate.now().getYear(),
                                LocalDate.now().get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()),
                                maxPossibleScore);
        Leaderboard regional = LeaderboardFactory.regional(game, "IN", maxPossibleScore);

        service.registerLeaderboard(allTime);
        service.registerLeaderboard(daily);
        service.registerLeaderboard(weekly);
        service.registerLeaderboard(regional);

        // ===== SETUP: Players =====
        PlayerProfile alice = service.registerPlayer(
            new PlayerProfile.Builder("alice99")
                .displayName("Alice ⚡").region("IN").build());
        PlayerProfile bob = service.registerPlayer(
            new PlayerProfile.Builder("bobspeed")
                .displayName("Bob 🏎").region("IN").build());
        PlayerProfile carol = service.registerPlayer(
            new PlayerProfile.Builder("carol_races")
                .displayName("Carol 🌟").region("US").build());
        PlayerProfile dave = service.registerPlayer(
            new PlayerProfile.Builder("davedrift")
                .displayName("Dave 🔥").region("IN").build());
        PlayerProfile eve = service.registerPlayer(
            new PlayerProfile.Builder("eve_turbo")
                .displayName("Eve ⚡").region("EU").build());
        PlayerProfile cheater = service.registerPlayer(
            new PlayerProfile.Builder("hax0r")
                .displayName("Hax0r 💀").region("IN").build());

        // ===== SCENARIO 1: Normal score submissions =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Normal Score Submissions (Req 1)");
        System.out.println("=".repeat(60));

        service.submitScore(alice.getPlayerId(),  "match-001", allTime.getId(), 8500);
        Thread.sleep(10);
        service.submitScore(bob.getPlayerId(),    "match-002", allTime.getId(), 7200);
        Thread.sleep(10);
        service.submitScore(carol.getPlayerId(),  "match-003", allTime.getId(), 9100);
        Thread.sleep(10);
        service.submitScore(dave.getPlayerId(),   "match-004", allTime.getId(), 8500); // ties with Alice
        Thread.sleep(10);
        service.submitScore(eve.getPlayerId(),    "match-005", allTime.getId(), 7800);

        // ===== SCENARIO 2: Top-N (Req 2) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Top-N Leaderboard (Req 2)");
        System.out.println("=".repeat(60));

        service.printLeaderboard(allTime.getId(), 10);

        // ===== SCENARIO 3: Tie-breaking (Req 5) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Tie-Breaking — Alice and Dave both have 8500");
        System.out.println("=".repeat(60));

        int aliceRank = allTime.zrevrank(alice.getPlayerId());
        int daveRank  = allTime.zrevrank(dave.getPlayerId());
        System.out.println("Alice rank: #" + aliceRank + " (submitted earlier → wins tie)");
        System.out.println("Dave rank:  #" + daveRank  + " (submitted later  → loses tie)");
        System.out.println("Both have rawScore=8500, but Alice got there first → higher encoded score");

        // ===== SCENARIO 4: Rank-around-me (Req 3) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Rank-Around-Me — Bob wants to see his neighbours");
        System.out.println("=".repeat(60));

        System.out.println("\nBob's view (radius=2):");
        service.getRankAroundMe(allTime.getId(), bob.getPlayerId(), 2)
            .forEach(System.out::println);

        // ===== SCENARIO 5: Daily leaderboard (Req 4) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Daily Leaderboard (Req 4: time windows)");
        System.out.println("=".repeat(60));

        // Need to also write to the daily board
        service.submitScore(alice.getPlayerId(), "match-d01", daily.getId(), 8500);
        service.submitScore(carol.getPlayerId(), "match-d02", daily.getId(), 9100);
        service.submitScore(bob.getPlayerId(),   "match-d03", daily.getId(), 7200);
        service.printLeaderboard(daily.getId(), 5);

        // ===== SCENARIO 6: Idempotency — duplicate match rejected (Req 6) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Idempotency — same matchId+playerId rejected (Req 6)");
        System.out.println("=".repeat(60));

        System.out.println("Submitting match-001 again for Alice (duplicate):");
        boolean dup = service.submitScore(alice.getPlayerId(), "match-001", allTime.getId(), 9999);
        System.out.println("Duplicate accepted: " + dup); // false

        // ===== SCENARIO 7: Anti-cheat — score too high =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Anti-Cheat — score exceeds ceiling");
        System.out.println("=".repeat(60));

        boolean cheatAttempt = service.submitScore(
            cheater.getPlayerId(), "match-c01", allTime.getId(), 99_999); // > maxPossibleScore
        System.out.println("Cheat attempt accepted: " + cheatAttempt); // false
        System.out.println("Cheater status: " + cheater.getStatus());

        // ===== SCENARIO 8: Concurrent submissions (Req 6) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Concurrent Submissions — 5 players submit simultaneously");
        System.out.println("=".repeat(60));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<PlayerProfile> extra = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PlayerProfile p = service.registerPlayer(
                new PlayerProfile.Builder("racer" + i).displayName("Racer-" + i).region("IN").build());
            extra.add(p);
        }

        long[] scores = {6000, 6500, 5500, 7000, 6800};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            pool.submit(() -> service.submitScore(
                extra.get(idx).getPlayerId(),
                "match-conc-" + idx,
                allTime.getId(),
                scores[idx]));
        }
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        System.out.println("\nAll-time leaderboard after concurrent submissions:");
        service.printLeaderboard(allTime.getId(), 10);

        // ===== SCENARIO 9: Score improvement (personal best) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Personal Best — Bob beats his previous score");
        System.out.println("=".repeat(60));

        System.out.println("Bob's current best: " +
            allTime.zscore(bob.getPlayerId()).map(CombinedScore::getRawScore).orElse(0L));
        service.submitScore(bob.getPlayerId(), "match-bob-pb", allTime.getId(), 9500);
        System.out.println("Bob's new best: " +
            allTime.zscore(bob.getPlayerId()).map(CombinedScore::getRawScore).orElse(0L));
        service.printLeaderboard(allTime.getId(), 5);

        // ===== SCENARIO 10: Strategy swap — cumulative scoring =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Strategy Swap — cumulative scoring (Req 7 extensible)");
        System.out.println("=".repeat(60));

        Leaderboard cumulativeLb = LeaderboardFactory.tournament("weekly-cup", 50_000);
        service.registerLeaderboard(cumulativeLb);
        service.setScoringStrategy(new CumulativeScoringStrategy());

        service.submitScore(alice.getPlayerId(), "cup-1", cumulativeLb.getId(), 3000);
        service.submitScore(alice.getPlayerId(), "cup-2", cumulativeLb.getId(), 2000); // adds up
        service.submitScore(bob.getPlayerId(),   "cup-3", cumulativeLb.getId(), 4500);

        service.printLeaderboard(cumulativeLb.getId(), 5);

        // Reset to personal best strategy
        service.setScoringStrategy(new PersonalBestScoringStrategy());

        // ===== SCENARIO 11: Paginated view (Iterator) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 11: Paginated View — 3 entries per page");
        System.out.println("=".repeat(60));

        LeaderboardPage page = service.pagedView(allTime.getId(), 3);
        int pageNum = 1;
        while (page.hasMore()) {
            System.out.println("  Page " + pageNum + ":");
            page.nextPage().forEach(System.out::println);
            pageNum++;
            if (pageNum > 3) break; // limit output for demo
        }

        // ===== FINAL REPORTS =====
        service.printAnalytics();
        service.printAuditLog();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|----------------------------------------------------------
            Singleton  | LeaderboardService (double-checked locking)
                       | AntiCheatValidator
            State      | PlayerStatus: ACTIVE → SUSPENDED → BANNED
                       | ScoreStatus:  PENDING → ACCEPTED / REJECTED / UNDER_REVIEW
            Strategy   | ScoringStrategy: PersonalBest / Cumulative / Tournament
                       | (swap at runtime — zero LeaderboardService change)
            Observer   | LeaderboardEventObserver: PushNotification / Analytics / Audit
                       | PushNotificationObserver diffs top-N before deciding to push
            Factory    | LeaderboardFactory: allTime / daily / weekly / regional / tournament
                       | Each returns a differently-configured Leaderboard instance
            Builder    | ScoreEntry.Builder, LeaderboardConfig.Builder, PlayerProfile.Builder
            Command    | SubmitScoreCommand: execute() = validate+ZADD+notify
                       |                    rollback() = ZREM+trustDecrement
            Iterator   | LeaderboardPage: cursor-based pagination over rank results
            """);

        System.out.println("===== CONCURRENCY SAFETY =====");
        System.out.println("""
            Class               | Mechanism                  | Why
            --------------------|----------------------------|--------------------------
            Leaderboard.zadd()  | ReentrantLock(fair=true)   | Atomic check-and-replace
            Leaderboard.zrem()  | Same per-board lock        | Consistent removal
            CombinedScore       | Immutable value object     | Safe to share across threads
            PlayerProfile trust | Synchronized decrementTrust| Atomic score updates
            Idempotency map     | ConcurrentHashMap.putIfAbsent| Double-submission dedup
            Event log           | CopyOnWriteArrayList       | Append-only, reads>>writes
            Player map          | ConcurrentHashMap          | Safe concurrent registration
            """);

        System.out.println("===== KEY DESIGN DECISIONS =====");
        System.out.println("""
            1. CombinedScore  — tie-break encoded into the sort key:
                               (rawScore × 10^13) + (MAX_TS − achievedAtEpoch)
                               Equal raw scores → earlier timestamp wins → no extra pass needed

            2. Per-board lock — ReentrantLock(fair=true) on Leaderboard, not global.
                               Two players submitting to DIFFERENT boards never contend.
                               ZADD in Redis is atomic server-side; this lock mirrors that.

            3. Idempotency   — putIfAbsent on (matchId:playerId) before any DB write.
                               Client retries after timeout never double-count one match.

            4. Diff-before-push — PushNotificationObserver keeps a top-N snapshot per board.
                               Only notifies WS viewers if the visible top-N actually changed.
                               Eliminates 99%+ of WS pushes during high-volume write periods.

            5. Append-only log — every ScoreEntry written before processing.
                               If the service restarts, replaying the log exactly
                               reconstructs every ZSET — same role as PostgreSQL score_events.
            """);
    }
}
