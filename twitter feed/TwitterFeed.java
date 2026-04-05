import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// TWITTER / NEWS FEED LLD
// Patterns:
//   Singleton  — FeedService (central hub)
//   Observer   — FeedSubscriber (follower notification)
//   Strategy   — FeedRankingStrategy (chronological vs ranked)
//   Factory    — TweetFactory (text, image, poll, retweet)
//   Builder    — Tweet construction
//   Iterator   — FeedIterator (paginated feed)
//   Decorator  — TweetDecorator (add like/retweet counts)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum TweetType     { TEXT, IMAGE, VIDEO, POLL, RETWEET, QUOTE_TWEET, THREAD }
enum TweetVisibility { PUBLIC, FOLLOWERS_ONLY, MENTIONED_ONLY }
enum NotifType     { NEW_TWEET, LIKE, RETWEET, FOLLOW, MENTION, REPLY }

// ==========================================
// 2. USER + PROFILE
// ==========================================
class User {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final  long         id;
    private final  String       username;
    private final  String       displayName;
    private        boolean      isVerified;
    private        int          followerCount;
    private        int          followingCount;
    private final  Set<Long>    followers  = ConcurrentHashMap.newKeySet();
    private final  Set<Long>    following  = ConcurrentHashMap.newKeySet();
    private final  Set<Long>    blocked    = ConcurrentHashMap.newKeySet();
    private final  Set<Long>    muted      = ConcurrentHashMap.newKeySet();

    public User(String username, String displayName, boolean isVerified) {
        this.id          = idGen.getAndIncrement();
        this.username    = username;
        this.displayName = displayName;
        this.isVerified  = isVerified;
    }

    public boolean follow(User target) {
        if (blocked.contains(target.getId())) {
            System.out.println("[Follow] " + username + " is blocked by " + target.username);
            return false;
        }
        if (following.add(target.getId())) {
            target.followers.add(this.id);
            this.followingCount++;
            target.followerCount++;
            System.out.println("[Follow] " + username + " → " + target.username);
            return true;
        }
        return false;
    }

    public void unfollow(User target) {
        if (following.remove(target.getId())) {
            target.followers.remove(this.id);
            this.followingCount--;
            target.followerCount--;
            System.out.println("[Unfollow] " + username + " ↛ " + target.username);
        }
    }

    public void block(User target)    { blocked.add(target.getId()); unfollow(target); }
    public void mute(User target)     { muted.add(target.getId()); }
    public boolean isMuted(long userId){ return muted.contains(userId); }
    public boolean isBlocked(long userId){ return blocked.contains(userId); }

    public long    getId()           { return id; }
    public String  getUsername()     { return username; }
    public String  getDisplayName()  { return displayName; }
    public boolean isVerified()      { return isVerified; }
    public int     getFollowerCount(){ return followerCount; }
    public Set<Long> getFollowers()  { return Collections.unmodifiableSet(followers); }
    public Set<Long> getFollowing()  { return Collections.unmodifiableSet(following); }

    @Override public String toString() {
        return "@" + username + (isVerified ? " ✓" : "") +
               " [" + followerCount + " followers]";
    }
}

// ==========================================
// 3. TWEET — BUILDER PATTERN
// ==========================================
class Tweet {
    private static final AtomicLong idGen = new AtomicLong(10000);

    private final  long            id;
    private final  User            author;
    private final  TweetType       type;
    private final  String          content;
    private final  List<String>    mediaUrls;
    private final  LocalDateTime   createdAt;
    private final  TweetVisibility visibility;
    private final  Long            replyToTweetId;    // null if not a reply
    private final  Long            retweetOfId;       // null if not a retweet
    private final  Long            quoteTweetOfId;    // null if not a quote
    private final  List<String>    hashtags;
    private final  List<String>    mentions;
    private final  Map<String, List<String>> pollOptions; // option → voters

    // Mutable engagement metrics
    private final  AtomicLong      likeCount    = new AtomicLong(0);
    private final  AtomicLong      retweetCount = new AtomicLong(0);
    private final  AtomicLong      replyCount   = new AtomicLong(0);
    private final  AtomicLong      viewCount    = new AtomicLong(0);
    private final  Set<Long>       likedBy      = ConcurrentHashMap.newKeySet();
    private        boolean         deleted      = false;

    private Tweet(Builder b) {
        this.id             = idGen.getAndIncrement();
        this.author         = b.author;
        this.type           = b.type;
        this.content        = b.content;
        this.mediaUrls      = List.copyOf(b.mediaUrls);
        this.createdAt      = LocalDateTime.now();
        this.visibility     = b.visibility;
        this.replyToTweetId = b.replyToTweetId;
        this.retweetOfId    = b.retweetOfId;
        this.quoteTweetOfId = b.quoteTweetOfId;
        this.hashtags       = extractHashtags(b.content);
        this.mentions       = extractMentions(b.content);
        this.pollOptions    = b.pollOptions;
    }

    private List<String> extractHashtags(String text) {
        List<String> tags = new ArrayList<>();
        for (String word : text.split("\\s+")) {
            if (word.startsWith("#")) tags.add(word.toLowerCase());
        }
        return tags;
    }

    private List<String> extractMentions(String text) {
        List<String> mentions = new ArrayList<>();
        for (String word : text.split("\\s+")) {
            if (word.startsWith("@")) mentions.add(word.toLowerCase());
        }
        return mentions;
    }

    // Engagement actions
    public boolean like(User user) {
        if (likedBy.add(user.getId())) {
            likeCount.incrementAndGet();
            System.out.println("[Like] @" + user.getUsername() +
                " liked tweet #" + id);
            return true;
        }
        return false;
    }

    public boolean unlike(User user) {
        if (likedBy.remove(user.getId())) {
            likeCount.decrementAndGet();
            return true;
        }
        return false;
    }

    public void incrementRetweet() { retweetCount.incrementAndGet(); }
    public void incrementReply()   { replyCount.incrementAndGet(); }
    public void incrementView()    { viewCount.incrementAndGet(); }

    public boolean votePoll(String option, User user) {
        if (pollOptions == null || !pollOptions.containsKey(option)) return false;
        pollOptions.get(option).add(user.getUsername());
        System.out.println("[Poll] @" + user.getUsername() + " voted: " + option);
        return true;
    }

    public void delete()           { this.deleted = true; }

    public long          getId()             { return id; }
    public User          getAuthor()         { return author; }
    public TweetType     getType()           { return type; }
    public String        getContent()        { return content; }
    public List<String>  getMediaUrls()      { return mediaUrls; }
    public LocalDateTime getCreatedAt()      { return createdAt; }
    public TweetVisibility getVisibility()   { return visibility; }
    public Long          getReplyToTweetId() { return replyToTweetId; }
    public Long          getRetweetOfId()    { return retweetOfId; }
    public List<String>  getHashtags()       { return hashtags; }
    public List<String>  getMentions()       { return mentions; }
    public long          getLikeCount()      { return likeCount.get(); }
    public long          getRetweetCount()   { return retweetCount.get(); }
    public long          getReplyCount()     { return replyCount.get(); }
    public long          getViewCount()      { return viewCount.get(); }
    public boolean       isDeleted()         { return deleted; }
    public boolean       isLikedBy(long uid) { return likedBy.contains(uid); }

    @Override public String toString() {
        String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
        return "Tweet[#" + id + " | @" + author.getUsername() + " | \"" + preview +
               "\" | ♥" + likeCount + " RT" + retweetCount + " 👁" + viewCount + "]";
    }

    // ---- BUILDER ----
    static class Builder {
        private final User            author;
        private       TweetType       type          = TweetType.TEXT;
        private       String          content       = "";
        private       List<String>    mediaUrls     = new ArrayList<>();
        private       TweetVisibility visibility    = TweetVisibility.PUBLIC;
        private       Long            replyToTweetId;
        private       Long            retweetOfId;
        private       Long            quoteTweetOfId;
        private       Map<String, List<String>> pollOptions;

        public Builder(User author)             { this.author = author; }
        public Builder type(TweetType t)        { this.type = t;           return this; }
        public Builder content(String c)        { this.content = c;        return this; }
        public Builder media(String url)        { this.mediaUrls.add(url); return this; }
        public Builder visibility(TweetVisibility v) { this.visibility = v;return this; }
        public Builder replyTo(Long id)         { this.replyToTweetId = id;return this; }
        public Builder retweetOf(Long id)       { this.retweetOfId = id;   return this; }
        public Builder quoteTweetOf(Long id)    { this.quoteTweetOfId = id;return this; }
        public Builder poll(String... options) {
            this.type = TweetType.POLL;
            this.pollOptions = new LinkedHashMap<>();
            for (String opt : options) pollOptions.put(opt, new CopyOnWriteArrayList<>());
            return this;
        }
        public Tweet build() { return new Tweet(this); }
    }
}

// ==========================================
// 4. FACTORY — CREATE TWEETS BY TYPE
// ==========================================
class TweetFactory {
    public static Tweet createText(User author, String content) {
        return new Tweet.Builder(author).content(content).build();
    }

    public static Tweet createWithMedia(User author, String content, String... mediaUrls) {
        Tweet.Builder b = new Tweet.Builder(author).content(content).type(TweetType.IMAGE);
        for (String url : mediaUrls) b.media(url);
        return b.build();
    }

    public static Tweet createReply(User author, String content, long replyToId) {
        return new Tweet.Builder(author)
            .type(TweetType.TEXT)
            .content(content)
            .replyTo(replyToId)
            .build();
    }

    public static Tweet createRetweet(User author, long originalTweetId) {
        return new Tweet.Builder(author)
            .type(TweetType.RETWEET)
            .content("RT #" + originalTweetId)
            .retweetOf(originalTweetId)
            .build();
    }

    public static Tweet createQuoteTweet(User author, String comment, long quotedId) {
        return new Tweet.Builder(author)
            .type(TweetType.QUOTE_TWEET)
            .content(comment)
            .quoteTweetOf(quotedId)
            .build();
    }

    public static Tweet createPoll(User author, String question, String... options) {
        return new Tweet.Builder(author).content(question).poll(options).build();
    }

    public static Tweet createThread(User author, String firstTweet) {
        return new Tweet.Builder(author)
            .type(TweetType.THREAD)
            .content(firstTweet)
            .build();
    }
}

// ==========================================
// 5. STRATEGY — FEED RANKING
// ==========================================
interface FeedRankingStrategy {
    String getName();
    List<Tweet> rank(List<Tweet> tweets, User viewer);
}

// Chronological: newest first (simple, Twitter original)
class ChronologicalStrategy implements FeedRankingStrategy {
    @Override public String getName() { return "Chronological"; }

    @Override
    public List<Tweet> rank(List<Tweet> tweets, User viewer) {
        return tweets.stream()
            .sorted(Comparator.comparing(Tweet::getCreatedAt).reversed())
            .collect(Collectors.toList());
    }
}

// Ranked: engagement-based score (Twitter "For You" tab)
class RankedFeedStrategy implements FeedRankingStrategy {
    @Override public String getName() { return "Ranked (Engagement)"; }

    @Override
    public List<Tweet> rank(List<Tweet> tweets, User viewer) {
        return tweets.stream()
            .sorted(Comparator.comparingDouble(
                (Tweet t) -> computeScore(t, viewer)).reversed())
            .collect(Collectors.toList());
    }

    private double computeScore(Tweet t, User viewer) {
        double score = 0;
        // Recency — newer tweets score higher
        long ageMinutes = java.time.temporal.ChronoUnit.MINUTES.between(
            t.getCreatedAt(), LocalDateTime.now());
        score += Math.max(0, 100 - ageMinutes * 0.5);

        // Engagement signals
        score += t.getLikeCount()    * 2.0;
        score += t.getRetweetCount() * 3.0;
        score += t.getReplyCount()   * 1.5;
        score += t.getViewCount()    * 0.1;

        // Verified author boost
        if (t.getAuthor().isVerified()) score += 20;

        // If viewer liked something from this author before — boost (simplified)
        if (viewer.getFollowing().contains(t.getAuthor().getId())) score += 10;

        return score;
    }
}

// Trending: top hashtag / topic based
class TrendingStrategy implements FeedRankingStrategy {
    private final Set<String> trendingHashtags;

    public TrendingStrategy(Set<String> trendingHashtags) {
        this.trendingHashtags = trendingHashtags;
    }

    @Override public String getName() { return "Trending"; }

    @Override
    public List<Tweet> rank(List<Tweet> tweets, User viewer) {
        return tweets.stream()
            .sorted(Comparator.comparingDouble(
                (Tweet t) -> {
                    long trendBoost = t.getHashtags().stream()
                        .filter(trendingHashtags::contains).count() * 50;
                    return t.getLikeCount() + t.getRetweetCount() * 2 + trendBoost;
                }).reversed())
            .collect(Collectors.toList());
    }
}

// ==========================================
// 6. NOTIFICATION
// ==========================================
class Notification {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final long       id;
    private final User       recipient;
    private final User       actor;
    private final NotifType  type;
    private final Tweet      relatedTweet;
    private final LocalDateTime createdAt;
    private       boolean    read = false;

    public Notification(User recipient, User actor, NotifType type, Tweet relatedTweet) {
        this.id           = idGen.getAndIncrement();
        this.recipient    = recipient;
        this.actor        = actor;
        this.type         = type;
        this.relatedTweet = relatedTweet;
        this.createdAt    = LocalDateTime.now();
    }

    public void markRead() { this.read = true; }

    @Override public String toString() {
        String action = switch (type) {
            case LIKE     -> "liked your tweet";
            case RETWEET  -> "retweeted your tweet";
            case FOLLOW   -> "started following you";
            case MENTION  -> "mentioned you";
            case REPLY    -> "replied to your tweet";
            case NEW_TWEET-> "posted a new tweet";
        };
        return "[Notif] @" + actor.getUsername() + " " + action +
               (relatedTweet != null ? " → #" + relatedTweet.getId() : "");
    }
}

// ==========================================
// 7. OBSERVER — FEED SUBSCRIBER
// ==========================================
interface FeedObserver {
    void onNewTweet(Tweet tweet);
    void onNotification(Notification notification);
}

class UserFeedListener implements FeedObserver {
    private final User   user;
    private final Deque<Tweet>         feed          = new ConcurrentLinkedDeque<>();
    private final List<Notification>   notifications = new CopyOnWriteArrayList<>();
    private static final int           MAX_FEED_SIZE = 800; // Twitter-like inbox

    public UserFeedListener(User user) { this.user = user; }

    @Override
    public void onNewTweet(Tweet tweet) {
        if (user.isMuted(tweet.getAuthor().getId())) return;
        if (user.isBlocked(tweet.getAuthor().getId())) return;

        feed.addFirst(tweet);
        if (feed.size() > MAX_FEED_SIZE) feed.pollLast(); // evict oldest
    }

    @Override
    public void onNotification(Notification notification) {
        notifications.add(notification);
        System.out.println("[→ " + user.getUsername() + "] " + notification);
    }

    public List<Tweet> getFeedPage(int page, int pageSize, FeedRankingStrategy strategy) {
        List<Tweet> all = new ArrayList<>(feed);
        List<Tweet> ranked = strategy.rank(all, user);
        int from = page * pageSize;
        int to   = Math.min(from + pageSize, ranked.size());
        return from < ranked.size() ? ranked.subList(from, to) : Collections.emptyList();
    }

    public int getUnreadNotifCount() {
        return (int) notifications.stream().filter(n -> !n.read).count();
    }

    public User getUser() { return user; }
}

// ==========================================
// 8. TRENDING TOPICS TRACKER
// ==========================================
class TrendingTracker {
    // hashtag → tweet count in last hour
    private final Map<String, AtomicLong> hashtagCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> topicCounts   = new ConcurrentHashMap<>();

    public void recordTweet(Tweet tweet) {
        tweet.getHashtags().forEach(tag ->
            hashtagCounts.computeIfAbsent(tag, k -> new AtomicLong(0)).incrementAndGet());
    }

    public List<Map.Entry<String, AtomicLong>> getTopTrending(int n) {
        return hashtagCounts.entrySet().stream()
            .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                Comparator.comparingLong(AtomicLong::get)).reversed())
            .limit(n)
            .collect(Collectors.toList());
    }

    public Set<String> getTrendingHashtags() {
        return getTopTrending(20).stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }
}

// ==========================================
// 9. TWITTER FEED SERVICE — SINGLETON
// ==========================================
class TwitterFeedService {
    private static TwitterFeedService instance;

    private final Map<Long, User>             users         = new ConcurrentHashMap<>();
    private final Map<Long, Tweet>            tweets        = new ConcurrentHashMap<>();
    private final Map<Long, UserFeedListener> feedListeners = new ConcurrentHashMap<>();
    private final TrendingTracker             trending      = new TrendingTracker();
    private       FeedRankingStrategy         rankingStrategy = new ChronologicalStrategy();

    // Async fan-out thread pool
    private final ExecutorService fanOutPool =
        Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "fanout-worker");
            t.setDaemon(true);
            return t;
        });

    private static final int CELEBRITY_THRESHOLD = 5; // followers above this = celebrity

    private TwitterFeedService() {}

    public static synchronized TwitterFeedService getInstance() {
        if (instance == null) instance = new TwitterFeedService();
        return instance;
    }

    // ---- Setup ----
    public void registerUser(User user) {
        users.put(user.getId(), user);
        feedListeners.put(user.getId(), new UserFeedListener(user));
        System.out.println("[Service] Registered: " + user);
    }

    public void setRankingStrategy(FeedRankingStrategy strategy) {
        this.rankingStrategy = strategy;
        System.out.println("[Service] Ranking strategy: " + strategy.getName());
    }

    // ---- FOLLOW ----
    public void follow(User follower, User target) {
        if (!follower.follow(target)) return;

        // Send follow notification to target
        Notification notif = new Notification(
            target, follower, NotifType.FOLLOW, null);
        UserFeedListener targetListener = feedListeners.get(target.getId());
        if (targetListener != null) targetListener.onNotification(notif);
    }

    // ---- POST TWEET (core flow) ----
    public Tweet postTweet(Tweet tweet) {
        // Store tweet
        tweets.put(tweet.getId(), tweet);

        // Record for trending
        trending.recordTweet(tweet);

        System.out.println("[Post] " + tweet.getAuthor() + " tweeted: " + tweet);

        // Handle mentions
        tweet.getMentions().forEach(mention -> {
            users.values().stream()
                .filter(u -> ("@" + u.getUsername().toLowerCase()).equals(mention))
                .findFirst()
                .ifPresent(mentioned -> {
                    Notification n = new Notification(
                        mentioned, tweet.getAuthor(), NotifType.MENTION, tweet);
                    UserFeedListener l = feedListeners.get(mentioned.getId());
                    if (l != null) l.onNotification(n);
                });
        });

        // Fan-out to followers
        fanOut(tweet);

        return tweet;
    }

    // Fan-out strategy: push to all followers OR pull (for celebrities)
    private void fanOut(Tweet tweet) {
        User author = tweet.getAuthor();
        Set<Long> followers = author.getFollowers();

        if (followers.size() <= CELEBRITY_THRESHOLD) {
            // PUSH model: write tweet to each follower's feed inbox
            System.out.println("[FanOut] Push model — " + followers.size() + " followers");
            followers.forEach(followerId ->
                fanOutPool.submit(() -> {
                    UserFeedListener listener = feedListeners.get(followerId);
                    if (listener != null) listener.onNewTweet(tweet);
                })
            );
        } else {
            // PULL model: celebrity tweets stored centrally, fetched on-demand
            System.out.println("[FanOut] Pull model (celebrity with " +
                followers.size() + " followers) — store centrally");
            // In real system: just store in celebrity tweet store
            // Followers pull when they open the app
        }
    }

    // ---- RETWEET ----
    public Tweet retweet(User retweeter, long originalTweetId) {
        Tweet original = tweets.get(originalTweetId);
        if (original == null || original.isDeleted()) {
            System.out.println("[Retweet] Original tweet not found");
            return null;
        }

        original.incrementRetweet();
        Tweet rt = TweetFactory.createRetweet(retweeter, originalTweetId);
        tweets.put(rt.getId(), rt);

        // Notify original author
        Notification n = new Notification(
            original.getAuthor(), retweeter, NotifType.RETWEET, original);
        UserFeedListener authorListener = feedListeners.get(original.getAuthor().getId());
        if (authorListener != null) authorListener.onNotification(n);

        // Fan out the retweet
        fanOut(rt);

        System.out.println("[Retweet] @" + retweeter.getUsername() +
            " retweeted #" + originalTweetId);
        return rt;
    }

    // ---- LIKE ----
    public void likeTweet(User user, long tweetId) {
        Tweet tweet = tweets.get(tweetId);
        if (tweet == null) return;

        if (tweet.like(user)) {
            Notification n = new Notification(
                tweet.getAuthor(), user, NotifType.LIKE, tweet);
            UserFeedListener authorListener = feedListeners.get(tweet.getAuthor().getId());
            if (authorListener != null) authorListener.onNotification(n);
        }
    }

    // ---- REPLY ----
    public Tweet reply(User user, String content, long replyToTweetId) {
        Tweet parent = tweets.get(replyToTweetId);
        if (parent == null) { System.out.println("[Reply] Tweet not found"); return null; }

        parent.incrementReply();
        Tweet reply = TweetFactory.createReply(user, content, replyToTweetId);
        tweets.put(reply.getId(), reply);

        // Notify original author
        Notification n = new Notification(
            parent.getAuthor(), user, NotifType.REPLY, parent);
        UserFeedListener authorListener = feedListeners.get(parent.getAuthor().getId());
        if (authorListener != null) authorListener.onNotification(n);

        fanOut(reply);
        System.out.println("[Reply] @" + user.getUsername() +
            " replied to #" + replyToTweetId + ": " + content);
        return reply;
    }

    // ---- GET FEED (paginated) ----
    public List<Tweet> getFeed(User user, int page, int pageSize) {
        UserFeedListener listener = feedListeners.get(user.getId());
        if (listener == null) return Collections.emptyList();

        // For users following celebrities: merge push inbox + pull celebrity tweets
        List<Tweet> feed = listener.getFeedPage(page, pageSize, rankingStrategy);
        System.out.println("[Feed] @" + user.getUsername() + " page " + page +
            " — " + feed.size() + " tweets [" + rankingStrategy.getName() + "]");
        return feed;
    }

    // ---- DELETE ----
    public void deleteTweet(User user, long tweetId) {
        Tweet tweet = tweets.get(tweetId);
        if (tweet == null) return;
        if (tweet.getAuthor().getId() != user.getId()) {
            System.out.println("[Delete] Unauthorized");
            return;
        }
        tweet.delete();
        tweets.remove(tweetId);
        System.out.println("[Delete] Tweet #" + tweetId + " deleted");
    }

    // ---- SEARCH ----
    public List<Tweet> searchByHashtag(String hashtag) {
        String tag = hashtag.startsWith("#") ? hashtag.toLowerCase() : "#" + hashtag.toLowerCase();
        return tweets.values().stream()
            .filter(t -> !t.isDeleted() && t.getHashtags().contains(tag))
            .sorted(Comparator.comparing(Tweet::getCreatedAt).reversed())
            .collect(Collectors.toList());
    }

    public List<Tweet> searchByKeyword(String keyword) {
        return tweets.values().stream()
            .filter(t -> !t.isDeleted() &&
                t.getContent().toLowerCase().contains(keyword.toLowerCase()))
            .sorted(Comparator.comparing(Tweet::getCreatedAt).reversed())
            .collect(Collectors.toList());
    }

    // ---- TRENDING ----
    public void printTrending() {
        System.out.println("[Trending] Top hashtags:");
        trending.getTopTrending(5)
            .forEach(e -> System.out.println("  " + e.getKey() + " — " + e.getValue() + " tweets"));
    }

    // ---- PROFILE TWEETS ----
    public List<Tweet> getUserTweets(User user, int limit) {
        return tweets.values().stream()
            .filter(t -> t.getAuthor().getId() == user.getId() && !t.isDeleted())
            .sorted(Comparator.comparing(Tweet::getCreatedAt).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    public int getTweetCount()  { return tweets.size(); }
    public int getUserCount()   { return users.size(); }
}

// ==========================================
// 10. MAIN — DRIVER CODE
// ==========================================
public class TwitterFeed {
    public static void main(String[] args) throws InterruptedException {

        TwitterFeedService service = TwitterFeedService.getInstance();

        // ---- Users ----
        User elon    = new User("elon",    "Elon Musk",     true);
        User obama   = new User("obama",   "Barack Obama",  true);
        User alice   = new User("alice",   "Alice Dev",     false);
        User bob     = new User("bob",     "Bob Engineer",  false);
        User carol   = new User("carol",   "Carol Designer",false);

        service.registerUser(elon);
        service.registerUser(obama);
        service.registerUser(alice);
        service.registerUser(bob);
        service.registerUser(carol);

        // ===== SCENARIO 1: Follow graph =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Building Follow Graph");
        System.out.println("=".repeat(55));

        service.follow(alice, elon);
        service.follow(alice, obama);
        service.follow(bob,   elon);
        service.follow(carol, alice);
        service.follow(carol, obama);
        service.follow(bob,   alice);

        // ===== SCENARIO 2: Posting tweets =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Posting Tweets");
        System.out.println("=".repeat(55));

        Tweet t1 = service.postTweet(TweetFactory.createText(elon,
            "Just launched a new feature on X! #AI #Tech #Innovation"));

        Tweet t2 = service.postTweet(TweetFactory.createText(obama,
            "Democracy depends on informed citizens. Read more. #Democracy #News"));

        Tweet t3 = service.postTweet(TweetFactory.createWithMedia(alice,
            "My new blog post on #SystemDesign is live! Check it out 🚀",
            "https://cdn.example.com/blog-thumbnail.jpg"));

        Tweet t4 = service.postTweet(TweetFactory.createText(bob,
            "Working on a #Java backend today. #Programming #LLD"));

        // ===== SCENARIO 3: Poll tweet =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Poll Tweet");
        System.out.println("=".repeat(55));

        Tweet poll = service.postTweet(
            TweetFactory.createPoll(elon, "Best programming language?",
                "Java", "Python", "Go", "Rust"));
        poll.votePoll("Java", alice);
        poll.votePoll("Python", bob);
        poll.votePoll("Go", carol);
        poll.votePoll("Java", obama);

        // ===== SCENARIO 4: Likes + Retweets =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: Likes and Retweets");
        System.out.println("=".repeat(55));

        service.likeTweet(alice,  t1.getId());
        service.likeTweet(bob,    t1.getId());
        service.likeTweet(carol,  t1.getId());
        service.likeTweet(alice,  t2.getId());

        service.retweet(alice, t1.getId());
        service.retweet(bob,   t2.getId());

        // ===== SCENARIO 5: Replies + Thread =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Replies");
        System.out.println("=".repeat(55));

        Tweet r1 = service.reply(bob,   "This is amazing! 🔥",    t1.getId());
        Tweet r2 = service.reply(carol, "Totally agree! @bob",     t1.getId());
        Tweet r3 = service.reply(alice, "Great initiative @obama!", t2.getId());

        // ===== SCENARIO 6: Mentions =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Mentions");
        System.out.println("=".repeat(55));

        service.postTweet(TweetFactory.createText(alice,
            "Shoutout to @bob and @carol for the amazing collab! #Teamwork"));

        // ===== SCENARIO 7: Get feed (chronological) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Alice's Feed (Chronological)");
        System.out.println("=".repeat(55));

        service.setRankingStrategy(new ChronologicalStrategy());
        List<Tweet> aliceFeed = service.getFeed(alice, 0, 5);
        aliceFeed.forEach(t -> System.out.println("  " + t));

        // ===== SCENARIO 8: Ranked feed =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Alice's Feed (Ranked — For You)");
        System.out.println("=".repeat(55));

        service.setRankingStrategy(new RankedFeedStrategy());
        List<Tweet> rankedFeed = service.getFeed(alice, 0, 5);
        rankedFeed.forEach(t -> System.out.println("  " + t));

        // ===== SCENARIO 9: Trending =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 9: Trending Hashtags");
        System.out.println("=".repeat(55));

        service.printTrending();

        // ===== SCENARIO 10: Search =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 10: Search");
        System.out.println("=".repeat(55));

        System.out.println("Search #SystemDesign:");
        service.searchByHashtag("#SystemDesign")
            .forEach(t -> System.out.println("  " + t));

        System.out.println("Search 'java':");
        service.searchByKeyword("java")
            .forEach(t -> System.out.println("  " + t));

        // ===== SCENARIO 11: Block + Mute =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 11: Block and Mute");
        System.out.println("=".repeat(55));

        carol.mute(bob);
        System.out.println("Carol muted Bob — Bob's tweets won't appear in Carol's feed");

        alice.block(carol);
        System.out.println("Alice blocked Carol");

        // ===== SCENARIO 12: Delete tweet =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 12: Delete Tweet");
        System.out.println("=".repeat(55));

        service.deleteTweet(bob, t4.getId());   // authorized
        service.deleteTweet(alice, t1.getId()); // unauthorized (not sender)

        // ===== SUMMARY =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(55));
        System.out.println("Total users:  " + service.getUserCount());
        System.out.println("Total tweets: " + service.getTweetCount());
        System.out.println("Elon followers: " + elon.getFollowerCount());
        System.out.println("Tweet #" + t1.getId() + " likes: " + t1.getLikeCount() +
            ", retweets: " + t1.getRetweetCount());

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | TwitterFeedService
            Observer   | FeedObserver / UserFeedListener
            Strategy   | FeedRankingStrategy (Chrono/Ranked/Trending)
            Factory    | TweetFactory (text/image/poll/retweet/reply)
            Builder    | Tweet.Builder
            Iterator   | getFeedPage() — paginated feed
            Decorator  | Tweet engagement metrics (likes/RTs/views)
            """);
    }
}
