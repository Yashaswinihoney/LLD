import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// YOUTUBE LLD
// Patterns:
//   Singleton  — VideoService, ProcessingPipeline
//   Observer   — VideoEventObserver (likes, comments, subs)
//   Strategy   — RecommendationStrategy, VideoQualityStrategy (ABR)
//   Factory    — VideoFactory, ThumbnailFactory
//   Builder    — Video, SearchQuery construction
//   State      — VideoStatus (UPLOADING→PROCESSING→PUBLISHED)
//   Pipeline   — VideoProcessingJob (upload→transcode→index→thumbnail)
//   Iterator   — PaginatedFeed (home/search results)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum VideoStatus    { UPLOADING, PROCESSING, PUBLISHED, PRIVATE,
                      UNLISTED, DELETED, FAILED }
enum VideoQuality   { Q_144P, Q_360P, Q_480P, Q_720P, Q_1080P, Q_4K }
enum VideoCategory  { EDUCATION, ENTERTAINMENT, GAMING, MUSIC,
                      SPORTS, NEWS, TECH, VLOGS, SHORTS }
enum NotifType      { NEW_VIDEO, COMMENT, LIKE, MILESTONE,
                      REPLY, MENTION, SUBSCRIPTION }
enum ReactionType   { LIKE, DISLIKE }

// ==========================================
// 2. USER / CHANNEL
// ==========================================
class Channel {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final  long         id;
    private final  String       name;
    private final  String       handle;        // @channelname
    private        long         subscriberCount;
    private        boolean      isVerified;
    private final  Set<Long>    subscribers    = ConcurrentHashMap.newKeySet();
    private final  List<Long>   uploadedVideoIds = new CopyOnWriteArrayList<>();

    public Channel(String name, String handle) {
        this.id           = idGen.getAndIncrement();
        this.name         = name;
        this.handle       = handle;
        this.isVerified   = false;
    }

    public void addVideo(long videoId) { uploadedVideoIds.add(videoId); }

    public boolean subscribe(long userId) {
        if (subscribers.add(userId)) {
            subscriberCount++;
            System.out.println("[Sub] User#" + userId + " subscribed to " + handle);
            return true;
        }
        return false;
    }

    public boolean unsubscribe(long userId) {
        if (subscribers.remove(userId)) {
            subscriberCount--;
            return true;
        }
        return false;
    }

    public long         getId()             { return id; }
    public String       getName()           { return name; }
    public String       getHandle()         { return handle; }
    public long         getSubscriberCount(){ return subscriberCount; }
    public boolean      isVerified()        { return isVerified; }
    public Set<Long>    getSubscribers()    { return Collections.unmodifiableSet(subscribers); }
    public List<Long>   getUploadedVideos() { return Collections.unmodifiableList(uploadedVideoIds); }
    public void         setVerified(boolean v) { this.isVerified = v; }

    @Override public String toString() {
        return "Channel[" + handle + (isVerified ? " ✓" : "") +
               " | " + subscriberCount + " subs]";
    }
}

class User {
    private static final AtomicLong idGen = new AtomicLong(100);
    private final  long         id;
    private final  String       username;
    private final  String       email;
    private        Channel      channel;          // every user has a channel
    private final  Set<Long>    watchHistory      = new LinkedHashSet<>();
    private final  Set<Long>    likedVideos       = ConcurrentHashMap.newKeySet();
    private final  Set<Long>    savedVideos       = ConcurrentHashMap.newKeySet();
    private final  Map<String, String> preferences = new ConcurrentHashMap<>();

    public User(String username, String email) {
        this.id       = idGen.getAndIncrement();
        this.username = username;
        this.email    = email;
        this.channel  = new Channel(username + "'s Channel", "@" + username);
    }

    public void addToWatchHistory(long videoId) {
        watchHistory.add(videoId);
        if (watchHistory.size() > 200) {
            watchHistory.remove(watchHistory.iterator().next()); // evict oldest
        }
    }

    public boolean likeVideo(long videoId)    { return likedVideos.add(videoId); }
    public boolean unlikeVideo(long videoId)  { return likedVideos.remove(videoId); }
    public boolean saveVideo(long videoId)    { return savedVideos.add(videoId); }

    public long         getId()           { return id; }
    public String       getUsername()     { return username; }
    public String       getEmail()        { return email; }
    public Channel      getChannel()      { return channel; }
    public Set<Long>    getWatchHistory() { return Collections.unmodifiableSet(watchHistory); }
    public Set<Long>    getLikedVideos()  { return Collections.unmodifiableSet(likedVideos); }
    public boolean      hasLiked(long id) { return likedVideos.contains(id); }

    @Override public String toString() { return "User[@" + username + "]"; }
}

// ==========================================
// 3. VIDEO QUALITY / ABR STRATEGY
// Adaptive Bitrate — pick quality based on bandwidth
// ==========================================
interface VideoQualityStrategy {
    String getName();
    VideoQuality selectQuality(int bandwidthKbps, VideoQuality maxAvailable);
}

class AutoABRStrategy implements VideoQualityStrategy {
    @Override public String getName() { return "Auto ABR"; }

    @Override
    public VideoQuality selectQuality(int bandwidthKbps, VideoQuality maxAvailable) {
        VideoQuality selected;
        if      (bandwidthKbps >= 20000) selected = VideoQuality.Q_4K;
        else if (bandwidthKbps >= 8000)  selected = VideoQuality.Q_1080P;
        else if (bandwidthKbps >= 3000)  selected = VideoQuality.Q_720P;
        else if (bandwidthKbps >= 1500)  selected = VideoQuality.Q_480P;
        else if (bandwidthKbps >= 500)   selected = VideoQuality.Q_360P;
        else                             selected = VideoQuality.Q_144P;

        // Can't exceed what's available
        if (selected.ordinal() > maxAvailable.ordinal()) selected = maxAvailable;
        System.out.println("[ABR] Bandwidth=" + bandwidthKbps +
            "kbps → selected quality: " + selected);
        return selected;
    }
}

class ManualQualityStrategy implements VideoQualityStrategy {
    private final VideoQuality preferred;
    public ManualQualityStrategy(VideoQuality q) { this.preferred = q; }

    @Override public String getName() { return "Manual:" + preferred; }

    @Override
    public VideoQuality selectQuality(int bandwidthKbps, VideoQuality maxAvailable) {
        VideoQuality selected = preferred.ordinal() > maxAvailable.ordinal()
            ? maxAvailable : preferred;
        System.out.println("[Manual] User selected: " + selected);
        return selected;
    }
}

// ==========================================
// 4. VIDEO COMMENT
// ==========================================
class Comment {
    private static final AtomicLong idGen = new AtomicLong(5000);
    private final  long          id;
    private final  long          videoId;
    private final  User          author;
    private final  String        text;
    private final  LocalDateTime createdAt;
    private final  Long          replyToCommentId;
    private        long          likeCount;
    private final  List<Comment> replies = new CopyOnWriteArrayList<>();
    private        boolean       pinned;
    private        boolean       deleted;

    public Comment(long videoId, User author, String text, Long replyToCommentId) {
        this.id               = idGen.getAndIncrement();
        this.videoId          = videoId;
        this.author           = author;
        this.text             = text;
        this.replyToCommentId = replyToCommentId;
        this.createdAt        = LocalDateTime.now();
    }

    public void like()            { likeCount++; }
    public void addReply(Comment c){ replies.add(c); }
    public void pin()             { this.pinned = true; }
    public void delete()          { this.deleted = true; }

    public long         getId()          { return id; }
    public User         getAuthor()      { return author; }
    public String       getText()        { return text; }
    public long         getLikeCount()   { return likeCount; }
    public boolean      isPinned()       { return pinned; }
    public List<Comment> getReplies()    { return replies; }

    @Override public String toString() {
        return "Comment[#" + id + " | @" + author.getUsername() +
               (pinned ? " 📌" : "") + " | \"" + text + "\" | ♥" + likeCount + "]";
    }
}

// ==========================================
// 5. VIDEO — BUILDER + STATE PATTERN
// ==========================================
class Video {
    private static final AtomicLong idGen = new AtomicLong(10000);

    private final  long               id;
    private final  Channel            channel;
    private        String             title;
    private        String             description;
    private final  VideoCategory      category;
    private        VideoStatus        status;
    private final  long               durationSeconds;
    private final  List<String>       tags;
    private final  String             rawFileUrl;       // original upload
    private final  Map<VideoQuality, String> streamUrls; // quality → CDN URL
    private        String             thumbnailUrl;
    private        String             customThumbnailUrl;
    private final  LocalDateTime      uploadedAt;
    private        LocalDateTime      publishedAt;
    private        long               viewCount;
    private        long               likeCount;
    private        long               dislikeCount;
    private        long               commentCount;
    private final  Set<Long>          likedByUsers   = ConcurrentHashMap.newKeySet();
    private final  List<Comment>      comments       = new CopyOnWriteArrayList<>();
    private final  List<String>       chapters;      // timestamp → chapter name
    private        boolean            hasSubtitles;
    private        String             language;
    private        ThumbnailMetadata  thumbnail;

    private Video(Builder b) {
        this.id              = idGen.getAndIncrement();
        this.channel         = b.channel;
        this.title           = b.title;
        this.description     = b.description;
        this.category        = b.category;
        this.durationSeconds = b.durationSeconds;
        this.tags            = List.copyOf(b.tags);
        this.rawFileUrl      = b.rawFileUrl;
        this.streamUrls      = new ConcurrentHashMap<>();
        this.uploadedAt      = LocalDateTime.now();
        this.status          = VideoStatus.UPLOADING;
        this.chapters        = new ArrayList<>(b.chapters);
        this.language        = b.language;
    }

    // State transitions
    public void markProcessing()  { transition(VideoStatus.PROCESSING); }
    public void markPublished()   {
        transition(VideoStatus.PUBLISHED);
        this.publishedAt = LocalDateTime.now();
    }
    public void markPrivate()     { transition(VideoStatus.PRIVATE); }
    public void markUnlisted()    { transition(VideoStatus.UNLISTED); }
    public void markFailed()      { transition(VideoStatus.FAILED); }
    public void delete()          { transition(VideoStatus.DELETED); }

    private void transition(VideoStatus next) {
        System.out.println("[Video #" + id + "] " + status + " → " + next);
        this.status = next;
    }

    // CDN URL registration (after transcoding)
    public void addStreamUrl(VideoQuality q, String url) {
        streamUrls.put(q, url);
    }

    public VideoQuality getMaxAvailableQuality() {
        return streamUrls.keySet().stream()
            .max(Comparator.comparingInt(VideoQuality::ordinal))
            .orElse(VideoQuality.Q_360P);
    }

    public String getStreamUrl(VideoQuality q) {
        // Fallback to nearest lower quality if exact not available
        VideoQuality selected = q;
        while (selected.ordinal() >= 0 && !streamUrls.containsKey(selected)) {
            if (selected.ordinal() == 0) return null;
            selected = VideoQuality.values()[selected.ordinal() - 1];
        }
        return streamUrls.get(selected);
    }

    // Engagement
    public synchronized boolean like(long userId) {
        if (likedByUsers.add(userId)) { likeCount++; return true; }
        return false;
    }

    public synchronized boolean unlike(long userId) {
        if (likedByUsers.remove(userId)) { likeCount--; return true; }
        return false;
    }

    public synchronized void incrementView() { viewCount++; }

    public Comment addComment(User author, String text) {
        Comment c = new Comment(id, author, text, null);
        comments.add(c);
        commentCount++;
        return c;
    }

    public Comment addReply(User author, String text, long parentCommentId) {
        Comment parent = comments.stream()
            .filter(c -> c.getId() == parentCommentId)
            .findFirst().orElse(null);
        if (parent == null) return null;
        Comment reply = new Comment(id, author, text, parentCommentId);
        parent.addReply(reply);
        commentCount++;
        return reply;
    }

    public long          getId()             { return id; }
    public Channel       getChannel()        { return channel; }
    public String        getTitle()          { return title; }
    public String        getDescription()    { return description; }
    public VideoCategory getCategory()       { return category; }
    public VideoStatus   getStatus()         { return status; }
    public long          getDurationSeconds(){ return durationSeconds; }
    public List<String>  getTags()           { return tags; }
    public String        getThumbnailUrl()   { return thumbnailUrl; }
    public LocalDateTime getUploadedAt()     { return uploadedAt; }
    public LocalDateTime getPublishedAt()    { return publishedAt; }
    public long          getViewCount()      { return viewCount; }
    public long          getLikeCount()      { return likeCount; }
    public long          getCommentCount()   { return commentCount; }
    public List<Comment> getComments()       { return comments; }
    public Map<VideoQuality, String> getStreamUrls() { return streamUrls; }

    public void setThumbnailUrl(String url)       { this.thumbnailUrl = url; }
    public void setCustomThumbnail(String url)    { this.customThumbnailUrl = url; }
    public void setTitle(String t)                { this.title = t; }
    public void setDescription(String d)          { this.description = d; }
    public void setHasSubtitles(boolean b)        { this.hasSubtitles = b; }

    @Override public String toString() {
        return "Video[#" + id + " | \"" + title + "\" | " +
               channel.getHandle() + " | " + status + " | 👁" + viewCount +
               " ♥" + likeCount + "]";
    }

    // ---- BUILDER ----
    static class Builder {
        private final Channel       channel;
        private       String        title           = "Untitled";
        private       String        description     = "";
        private       VideoCategory category        = VideoCategory.EDUCATION;
        private       long          durationSeconds = 0;
        private       List<String>  tags            = new ArrayList<>();
        private       String        rawFileUrl      = "";
        private       List<String>  chapters        = new ArrayList<>();
        private       String        language        = "en";

        public Builder(Channel channel)           { this.channel = channel; }
        public Builder title(String t)            { this.title = t;           return this; }
        public Builder description(String d)      { this.description = d;     return this; }
        public Builder category(VideoCategory c)  { this.category = c;        return this; }
        public Builder duration(long d)           { this.durationSeconds = d; return this; }
        public Builder tags(String... t)          { tags.addAll(Arrays.asList(t)); return this; }
        public Builder rawFile(String url)        { this.rawFileUrl = url;    return this; }
        public Builder chapter(String c)          { this.chapters.add(c);     return this; }
        public Builder language(String l)         { this.language = l;        return this; }
        public Video   build()                    { return new Video(this); }
    }
}

// ==========================================
// 6. THUMBNAIL METADATA
// ==========================================
class ThumbnailMetadata {
    private final long   videoId;
    private final String autoUrl;        // AI-generated from frame extraction
    private       String customUrl;      // uploader-provided

    public ThumbnailMetadata(long videoId, String autoUrl) {
        this.videoId = videoId;
        this.autoUrl = autoUrl;
    }

    public String getEffectiveUrl() {
        return customUrl != null ? customUrl : autoUrl;
    }

    public void setCustomUrl(String url) { this.customUrl = url; }
}

// ==========================================
// 7. VIDEO FACTORY
// ==========================================
class VideoFactory {
    public static Video createStandardVideo(Channel channel, String title,
                                             String description, VideoCategory cat,
                                             long durationSecs, String rawUrl,
                                             String... tags) {
        return new Video.Builder(channel)
            .title(title)
            .description(description)
            .category(cat)
            .duration(durationSecs)
            .rawFile(rawUrl)
            .tags(tags)
            .build();
    }

    public static Video createShort(Channel channel, String title, String rawUrl) {
        // YouTube Shorts: max 60 seconds
        return new Video.Builder(channel)
            .title(title)
            .category(VideoCategory.ENTERTAINMENT)
            .duration(30)
            .rawFile(rawUrl)
            .build();
    }

    public static Video createLiveStream(Channel channel, String title) {
        return new Video.Builder(channel)
            .title("[LIVE] " + title)
            .category(VideoCategory.ENTERTAINMENT)
            .duration(0) // live — no fixed duration
            .build();
    }

    public static Video createPodcast(Channel channel, String title,
                                       String description, long durationSecs) {
        return new Video.Builder(channel)
            .title(title)
            .description(description)
            .category(VideoCategory.EDUCATION)
            .duration(durationSecs)
            .build();
    }
}

// ==========================================
// 8. VIDEO PROCESSING PIPELINE — CHAIN OF RESPONSIBILITY
// Each step processes the video and passes to next
// ==========================================
abstract class ProcessingStep {
    protected ProcessingStep next;

    public ProcessingStep setNext(ProcessingStep next) {
        this.next = next;
        return next;
    }

    public abstract void process(Video video);

    protected void passToNext(Video video) {
        if (next != null) next.process(video);
    }
}

class UploadValidationStep extends ProcessingStep {
    @Override
    public void process(Video video) {
        System.out.println("[Pipeline] Step 1: Validate upload — " + video.getTitle());
        // Check file format, size limits, content policy
        video.markProcessing();
        passToNext(video);
    }
}

class TranscodingStep extends ProcessingStep {
    private static final VideoQuality[] QUALITIES = {
        VideoQuality.Q_144P, VideoQuality.Q_360P,
        VideoQuality.Q_720P, VideoQuality.Q_1080P
    };

    @Override
    public void process(Video video) {
        System.out.println("[Pipeline] Step 2: Transcode to multiple qualities");
        // Simulate: generate CDN URLs for each quality
        String base = "https://cdn.youtube.com/v/" + video.getId() + "/";
        for (VideoQuality q : QUALITIES) {
            video.addStreamUrl(q, base + q.name().toLowerCase() + ".mp4");
            System.out.println("  Transcoded: " + q +
                " → " + base + q.name().toLowerCase() + ".mp4");
        }
        passToNext(video);
    }
}

class ThumbnailGenerationStep extends ProcessingStep {
    @Override
    public void process(Video video) {
        System.out.println("[Pipeline] Step 3: Generate thumbnails (3 options)");
        String thumbUrl = "https://i.ytimg.com/vi/" + video.getId() + "/maxresdefault.jpg";
        video.setThumbnailUrl(thumbUrl);
        System.out.println("  Thumbnail: " + thumbUrl);
        passToNext(video);
    }
}

class SubtitleGenerationStep extends ProcessingStep {
    @Override
    public void process(Video video) {
        System.out.println("[Pipeline] Step 4: Auto-generate subtitles (Whisper AI)");
        video.setHasSubtitles(true);
        passToNext(video);
    }
}

class ContentModerationStep extends ProcessingStep {
    @Override
    public void process(Video video) {
        System.out.println("[Pipeline] Step 5: Content moderation (AI safety check)");
        // Simulate: all videos pass moderation
        System.out.println("  Content cleared for publication");
        passToNext(video);
    }
}

class SearchIndexingStep extends ProcessingStep {
    @Override
    public void process(Video video) {
        System.out.println("[Pipeline] Step 6: Index in search (Elasticsearch)");
        System.out.println("  Indexed: title='" + video.getTitle() +
            "' tags=" + video.getTags());
        video.markPublished();
        passToNext(video);
    }
}

class VideoProcessingPipeline {
    private static VideoProcessingPipeline instance;
    private final  ProcessingStep          head;
    private final  ExecutorService         pool =
        Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "transcoder");
            t.setDaemon(true);
            return t;
        });

    private VideoProcessingPipeline() {
        // Build chain: validate → transcode → thumbnail → subtitle → moderate → index
        ProcessingStep validate  = new UploadValidationStep();
        ProcessingStep transcode = new TranscodingStep();
        ProcessingStep thumb     = new ThumbnailGenerationStep();
        ProcessingStep subtitle  = new SubtitleGenerationStep();
        ProcessingStep moderate  = new ContentModerationStep();
        ProcessingStep index     = new SearchIndexingStep();

        validate.setNext(transcode).setNext(thumb)
                .setNext(subtitle).setNext(moderate).setNext(index);
        this.head = validate;
    }

    public static synchronized VideoProcessingPipeline getInstance() {
        if (instance == null) instance = new VideoProcessingPipeline();
        return instance;
    }

    public void processAsync(Video video, Runnable onComplete) {
        pool.submit(() -> {
            head.process(video);
            if (onComplete != null) onComplete.run();
        });
    }

    public void processSync(Video video) {
        head.process(video);
    }
}

// ==========================================
// 9. RECOMMENDATION STRATEGY
// ==========================================
interface RecommendationStrategy {
    String getName();
    List<Video> recommend(User user, Video currentVideo,
                          Map<Long, Video> allVideos, int count);
}

class CollaborativeFilteringStrategy implements RecommendationStrategy {
    @Override public String getName() { return "Collaborative Filtering"; }

    @Override
    public List<Video> recommend(User user, Video currentVideo,
                                  Map<Long, Video> allVideos, int count) {
        // Users who watched this also watched...
        // Simplified: return videos from same category by view count
        return allVideos.values().stream()
            .filter(v -> v.getStatus() == VideoStatus.PUBLISHED)
            .filter(v -> v.getId() != (currentVideo != null ? currentVideo.getId() : -1))
            .filter(v -> !user.getWatchHistory().contains(v.getId()))
            .filter(v -> currentVideo == null ||
                         v.getCategory() == currentVideo.getCategory())
            .sorted(Comparator.comparingLong(Video::getViewCount).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }
}

class ContentBasedStrategy implements RecommendationStrategy {
    @Override public String getName() { return "Content Based"; }

    @Override
    public List<Video> recommend(User user, Video currentVideo,
                                  Map<Long, Video> allVideos, int count) {
        // Match by tags similarity
        Set<String> seedTags = currentVideo != null
            ? new HashSet<>(currentVideo.getTags()) : new HashSet<>();

        return allVideos.values().stream()
            .filter(v -> v.getStatus() == VideoStatus.PUBLISHED)
            .filter(v -> v.getId() != (currentVideo != null ? currentVideo.getId() : -1))
            .sorted(Comparator.comparingLong((Video v) ->
                v.getTags().stream().filter(seedTags::contains).count()).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }
}

class TrendingRecommendationStrategy implements RecommendationStrategy {
    @Override public String getName() { return "Trending"; }

    @Override
    public List<Video> recommend(User user, Video currentVideo,
                                  Map<Long, Video> allVideos, int count) {
        // Top videos by views in last 24hrs (simplified: by total view count)
        return allVideos.values().stream()
            .filter(v -> v.getStatus() == VideoStatus.PUBLISHED)
            .sorted(Comparator.comparingLong(Video::getViewCount).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }
}

// ==========================================
// 10. OBSERVER — VIDEO EVENT SYSTEM
// ==========================================
interface VideoEventObserver {
    void onVideoPublished(Video video);
    void onVideoLiked(Video video, User liker);
    void onCommentAdded(Video video, Comment comment);
    void onMilestone(Channel channel, long milestone);
}

class SubscriberNotifier implements VideoEventObserver {
    private final Map<Long, User> users;

    public SubscriberNotifier(Map<Long, User> users) { this.users = users; }

    @Override
    public void onVideoPublished(Video video) {
        // Notify all channel subscribers
        video.getChannel().getSubscribers().stream()
            .limit(10) // in HLD: async via Kafka fan-out
            .forEach(userId -> {
                User sub = users.get(userId);
                if (sub != null)
                    System.out.println("[Notif → " + sub.getUsername() + "] New video: " +
                        video.getTitle() + " by " + video.getChannel().getHandle());
            });
    }

    @Override
    public void onVideoLiked(Video video, User liker) {
        System.out.println("[Notif → " + video.getChannel().getName() + "] " +
            liker.getUsername() + " liked your video: " + video.getTitle());
    }

    @Override
    public void onCommentAdded(Video video, Comment comment) {
        System.out.println("[Notif → " + video.getChannel().getName() + "] " +
            comment.getAuthor().getUsername() + " commented on: " + video.getTitle());
    }

    @Override
    public void onMilestone(Channel channel, long milestone) {
        System.out.println("[🎉 Milestone] " + channel.getHandle() +
            " reached " + milestone + " subscribers!");
    }
}

class AnalyticsCollector implements VideoEventObserver {
    private final Map<Long, Long> viewsByVideo    = new ConcurrentHashMap<>();
    private final Map<Long, Long> likesByVideo    = new ConcurrentHashMap<>();
    private final Map<Long, Long> commentsByVideo = new ConcurrentHashMap<>();

    @Override public void onVideoPublished(Video v) {}

    @Override
    public void onVideoLiked(Video video, User liker) {
        likesByVideo.merge(video.getId(), 1L, Long::sum);
    }

    @Override
    public void onCommentAdded(Video video, Comment comment) {
        commentsByVideo.merge(video.getId(), 1L, Long::sum);
    }

    @Override
    public void onMilestone(Channel channel, long milestone) {}

    public void recordView(long videoId) {
        viewsByVideo.merge(videoId, 1L, Long::sum);
    }

    public void printReport() {
        System.out.println("\n[Analytics] Video engagement report:");
        viewsByVideo.forEach((id, views) ->
            System.out.printf("  Video#%-6d | views=%-6d | likes=%-5d | comments=%d%n",
                id, views,
                likesByVideo.getOrDefault(id, 0L),
                commentsByVideo.getOrDefault(id, 0L)));
    }
}

// ==========================================
// 11. SEARCH QUERY BUILDER
// ==========================================
class VideoSearchQuery {
    final String       keyword;
    final VideoCategory category;
    final String       channelHandle;
    final int          minDurationSeconds;
    final int          maxDurationSeconds;
    final String       sortBy;   // "relevance", "views", "date", "rating"
    final int          page;
    final int          pageSize;

    private VideoSearchQuery(Builder b) {
        this.keyword            = b.keyword;
        this.category           = b.category;
        this.channelHandle      = b.channelHandle;
        this.minDurationSeconds = b.minDurationSeconds;
        this.maxDurationSeconds = b.maxDurationSeconds;
        this.sortBy             = b.sortBy;
        this.page               = b.page;
        this.pageSize           = b.pageSize;
    }

    static class Builder {
        private final String       keyword;
        private       VideoCategory category;
        private       String       channelHandle;
        private       int          minDurationSeconds = 0;
        private       int          maxDurationSeconds = Integer.MAX_VALUE;
        private       String       sortBy             = "relevance";
        private       int          page               = 0;
        private       int          pageSize           = 20;

        public Builder(String keyword)                { this.keyword = keyword; }
        public Builder category(VideoCategory c)     { this.category = c;    return this; }
        public Builder channel(String h)             { this.channelHandle = h; return this; }
        public Builder minDuration(int s)            { this.minDurationSeconds = s; return this; }
        public Builder maxDuration(int s)            { this.maxDurationSeconds = s; return this; }
        public Builder sortBy(String s)              { this.sortBy = s;      return this; }
        public Builder page(int p)                   { this.page = p;        return this; }
        public Builder pageSize(int s)               { this.pageSize = s;    return this; }
        public VideoSearchQuery build()              { return new VideoSearchQuery(this); }
    }
}

// ==========================================
// 12. VIDEO SERVICE — SINGLETON (core hub)
// ==========================================
class VideoService {
    private static VideoService instance;

    private final Map<Long, Video>    videos     = new ConcurrentHashMap<>();
    private final Map<Long, User>     users      = new ConcurrentHashMap<>();
    private final Map<Long, Channel>  channels   = new ConcurrentHashMap<>();
    private final List<VideoEventObserver> observers = new ArrayList<>();
    private final AnalyticsCollector  analytics;
    private       RecommendationStrategy recommendationStrategy;
    private       VideoQualityStrategy   qualityStrategy;
    private final VideoProcessingPipeline pipeline =
        VideoProcessingPipeline.getInstance();

    private VideoService() {
        this.analytics              = new AnalyticsCollector();
        this.recommendationStrategy = new CollaborativeFilteringStrategy();
        this.qualityStrategy        = new AutoABRStrategy();
        observers.add(new SubscriberNotifier(users));
        observers.add(analytics);
    }

    public static synchronized VideoService getInstance() {
        if (instance == null) instance = new VideoService();
        return instance;
    }

    // ---- User management ----
    public void registerUser(User user) {
        users.put(user.getId(), user);
        channels.put(user.getChannel().getId(), user.getChannel());
        System.out.println("[Service] Registered: " + user);
    }

    // ---- Strategy setters ----
    public void setRecommendationStrategy(RecommendationStrategy s) {
        this.recommendationStrategy = s;
        System.out.println("[Service] Recommendation: " + s.getName());
    }

    public void setQualityStrategy(VideoQualityStrategy s) {
        this.qualityStrategy = s;
    }

    // ---- UPLOAD VIDEO ----
    public Video uploadVideo(User uploader, Video video) {
        videos.put(video.getId(), video);
        uploader.getChannel().addVideo(video.getId());

        System.out.println("\n[Upload] Starting: '" + video.getTitle() +
            "' by " + uploader.getChannel().getHandle());

        // Run processing pipeline synchronously for demo
        pipeline.processSync(video);

        // Notify subscribers on publish
        if (video.getStatus() == VideoStatus.PUBLISHED) {
            observers.forEach(o -> o.onVideoPublished(video));
        }

        System.out.println("[Upload] Complete: " + video);
        return video;
    }

    // ---- STREAM VIDEO (watch) ----
    public String streamVideo(User viewer, long videoId, int bandwidthKbps) {
        Video video = videos.get(videoId);
        if (video == null || video.getStatus() != VideoStatus.PUBLISHED) {
            System.out.println("[Stream] Video not available: #" + videoId);
            return null;
        }

        // Select quality using strategy
        VideoQuality selected = qualityStrategy.selectQuality(
            bandwidthKbps, video.getMaxAvailableQuality());
        String streamUrl = video.getStreamUrl(selected);

        // Record view + watch history
        video.incrementView();
        viewer.addToWatchHistory(videoId);
        analytics.recordView(videoId);

        System.out.println("[Stream] " + viewer.getUsername() +
            " watching: '" + video.getTitle() + "' @ " + selected);
        System.out.println("[Stream] CDN URL: " + streamUrl);

        // Check milestone
        checkMilestone(video.getChannel());

        return streamUrl;
    }

    // ---- LIKE VIDEO ----
    public boolean likeVideo(User user, long videoId) {
        Video video = videos.get(videoId);
        if (video == null) return false;

        if (user.hasLiked(videoId)) {
            // Unlike
            video.unlike(user.getId());
            user.unlikeVideo(videoId);
            System.out.println("[Like] " + user.getUsername() +
                " unliked: '" + video.getTitle() + "'");
            return false;
        }

        video.like(user.getId());
        user.likeVideo(videoId);
        observers.forEach(o -> o.onVideoLiked(video, user));
        return true;
    }

    // ---- COMMENT ----
    public Comment addComment(User user, long videoId, String text) {
        Video video = videos.get(videoId);
        if (video == null) return null;
        Comment comment = video.addComment(user, text);
        observers.forEach(o -> o.onCommentAdded(video, comment));
        System.out.println("[Comment] @" + user.getUsername() +
            " on '" + video.getTitle() + "': \"" + text + "\"");
        return comment;
    }

    public Comment replyToComment(User user, long videoId,
                                   long commentId, String text) {
        Video video = videos.get(videoId);
        if (video == null) return null;
        Comment reply = video.addReply(user, text, commentId);
        System.out.println("[Reply] @" + user.getUsername() + ": \"" + text + "\"");
        return reply;
    }

    public void pinComment(Channel channel, long videoId, long commentId) {
        Video video = videos.get(videoId);
        if (video == null) return;
        video.getComments().stream()
            .filter(c -> c.getId() == commentId)
            .findFirst()
            .ifPresent(c -> { c.pin(); System.out.println("[Pin] Comment pinned"); });
    }

    // ---- SUBSCRIBE ----
    public void subscribe(User subscriber, Channel target) {
        target.subscribe(subscriber.getId());
        checkMilestone(target);
    }

    private void checkMilestone(Channel channel) {
        long subs = channel.getSubscriberCount();
        long[] milestones = {100, 1000, 10000, 100000, 1000000, 10000000};
        for (long m : milestones) {
            if (subs == m) {
                observers.forEach(o -> o.onMilestone(channel, m));
            }
        }
    }

    // ---- RECOMMENDATIONS ----
    public List<Video> getRecommendations(User user, long currentVideoId, int count) {
        Video current = videos.get(currentVideoId);
        List<Video> recs = recommendationStrategy.recommend(
            user, current, videos, count);
        System.out.println("[Recommend] " + user.getUsername() +
            " → " + recs.size() + " videos [" +
            recommendationStrategy.getName() + "]");
        return recs;
    }

    // ---- SEARCH ----
    public List<Video> search(VideoSearchQuery query) {
        String kw = query.keyword.toLowerCase();
        return videos.values().stream()
            .filter(v -> v.getStatus() == VideoStatus.PUBLISHED)
            .filter(v -> v.getTitle().toLowerCase().contains(kw) ||
                         v.getDescription().toLowerCase().contains(kw) ||
                         v.getTags().stream().anyMatch(t -> t.toLowerCase().contains(kw)))
            .filter(v -> query.category == null || v.getCategory() == query.category)
            .filter(v -> query.channelHandle == null ||
                         v.getChannel().getHandle().equalsIgnoreCase(query.channelHandle))
            .filter(v -> v.getDurationSeconds() >= query.minDurationSeconds &&
                         v.getDurationSeconds() <= query.maxDurationSeconds)
            .sorted(getSearchComparator(query.sortBy))
            .skip((long) query.page * query.pageSize)
            .limit(query.pageSize)
            .collect(Collectors.toList());
    }

    private Comparator<Video> getSearchComparator(String sortBy) {
        return switch (sortBy) {
            case "views"   -> Comparator.comparingLong(Video::getViewCount).reversed();
            case "date"    -> Comparator.comparing(Video::getPublishedAt,
                               Comparator.nullsLast(Comparator.reverseOrder()));
            case "rating"  -> Comparator.comparingLong(Video::getLikeCount).reversed();
            default        -> Comparator.comparingLong(Video::getViewCount).reversed();
        };
    }

    // ---- DELETE VIDEO ----
    public boolean deleteVideo(User user, long videoId) {
        Video video = videos.get(videoId);
        if (video == null) return false;
        if (!video.getChannel().getId().equals(user.getChannel().getId())) {
            System.out.println("[Delete] Unauthorized");
            return false;
        }
        video.delete();
        System.out.println("[Delete] Video #" + videoId + " deleted");
        return true;
    }

    // ---- HOME FEED ----
    public List<Video> getHomeFeed(User user, int count) {
        return getRecommendations(user, -1, count);
    }

    public void printAnalytics() { analytics.printReport(); }

    public int  getVideoCount() { return videos.size(); }
    public int  getUserCount()  { return users.size(); }
    public Map<Long, Video> getAllVideos() { return videos; }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class YouTube {
    public static void main(String[] args) throws InterruptedException {

        VideoService service = VideoService.getInstance();

        // ---- Register Users ----
        User mrBeast    = new User("mrbeast",      "jimmy@mrbeast.com");
        User veritasium = new User("veritasium",   "derek@veritasium.com");
        User techLead   = new User("techlead",     "tech@lead.com");
        User alice      = new User("alice_viewer", "alice@gmail.com");
        User bob        = new User("bob_viewer",   "bob@gmail.com");

        mrBeast.getChannel().setVerified(true);
        veritasium.getChannel().setVerified(true);

        service.registerUser(mrBeast);
        service.registerUser(veritasium);
        service.registerUser(techLead);
        service.registerUser(alice);
        service.registerUser(bob);

        // ===== SCENARIO 1: Upload + Processing Pipeline =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Video Upload + Processing Pipeline");
        System.out.println("=".repeat(60));

        Video v1 = service.uploadVideo(mrBeast,
            VideoFactory.createStandardVideo(
                mrBeast.getChannel(),
                "$1 vs $1,000,000 Hotel Room!",
                "We stayed in the cheapest and most expensive hotels",
                VideoCategory.ENTERTAINMENT, 720,
                "s3://raw/v1.mp4",
                "hotel", "challenge", "mrbeast", "viral"));

        Video v2 = service.uploadVideo(veritasium,
            VideoFactory.createStandardVideo(
                veritasium.getChannel(),
                "The Illusion Only Some People Can See",
                "This optical illusion reveals how your brain predicts",
                VideoCategory.EDUCATION, 900,
                "s3://raw/v2.mp4",
                "science", "brain", "illusion", "psychology"));

        Video v3 = service.uploadVideo(techLead,
            VideoFactory.createStandardVideo(
                techLead.getChannel(),
                "System Design Interview — How to ace it",
                "Complete guide to system design interviews",
                VideoCategory.TECH, 3600,
                "s3://raw/v3.mp4",
                "system design", "interview", "coding", "tech"));

        Video v4 = service.uploadVideo(mrBeast,
            VideoFactory.createShort(
                mrBeast.getChannel(),
                "Wait for the ending 😱 #Shorts",
                "s3://raw/v4_short.mp4"));

        // ===== SCENARIO 2: Subscribe =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Subscriptions");
        System.out.println("=".repeat(60));

        service.subscribe(alice, mrBeast.getChannel());
        service.subscribe(alice, veritasium.getChannel());
        service.subscribe(bob,   mrBeast.getChannel());
        service.subscribe(bob,   techLead.getChannel());

        // ===== SCENARIO 3: Watch videos (ABR quality selection) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Watch Videos (Adaptive Bitrate)");
        System.out.println("=".repeat(60));

        // Alice has good bandwidth → 1080p
        service.streamVideo(alice, v1.getId(), 10000);
        // Bob has weak bandwidth → 360p
        service.streamVideo(bob,   v1.getId(), 600);
        // Alice switches to manual quality
        service.setQualityStrategy(new ManualQualityStrategy(VideoQuality.Q_4K));
        service.streamVideo(alice, v2.getId(), 20000);
        service.setQualityStrategy(new AutoABRStrategy()); // reset

        // ===== SCENARIO 4: Likes + Comments =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Likes, Comments, Replies, Pins");
        System.out.println("=".repeat(60));

        service.likeVideo(alice, v1.getId());
        service.likeVideo(bob,   v1.getId());
        service.likeVideo(alice, v2.getId());
        service.likeVideo(alice, v1.getId()); // unlike

        Comment c1 = service.addComment(alice, v1.getId(),
            "This is insane!! 🔥🔥 How much did this cost?");
        Comment c2 = service.addComment(bob,   v1.getId(),
            "MrBeast always going above and beyond!");
        service.replyToComment(mrBeast, v1.getId(), c1.getId(),
            "More than you'd think lol 😅");
        service.pinComment(mrBeast.getChannel(), v1.getId(), c1.getId());
        c1.like(); c1.like(); c1.like();

        Comment c3 = service.addComment(alice, v2.getId(),
            "Mind = blown 🤯 Never thought of it this way");

        // ===== SCENARIO 5: Search =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Search");
        System.out.println("=".repeat(60));

        System.out.println("Search 'system design':");
        service.search(new VideoSearchQuery.Builder("system design")
            .sortBy("views").pageSize(5).build())
            .forEach(v -> System.out.println("  " + v));

        System.out.println("\nSearch 'science' — Education only:");
        service.search(new VideoSearchQuery.Builder("science")
            .category(VideoCategory.EDUCATION).build())
            .forEach(v -> System.out.println("  " + v));

        System.out.println("\nSearch with duration filter (<10 min):");
        service.search(new VideoSearchQuery.Builder("mrbeast")
            .maxDuration(600).build())
            .forEach(v -> System.out.println("  " + v));

        // ===== SCENARIO 6: Recommendations =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Recommendations (3 strategies)");
        System.out.println("=".repeat(60));

        System.out.println("Collaborative Filtering for Alice (after watching v1):");
        service.setRecommendationStrategy(new CollaborativeFilteringStrategy());
        service.getRecommendations(alice, v1.getId(), 3)
            .forEach(v -> System.out.println("  " + v));

        System.out.println("\nContent-Based for Alice (based on tags):");
        service.setRecommendationStrategy(new ContentBasedStrategy());
        service.getRecommendations(alice, v1.getId(), 3)
            .forEach(v -> System.out.println("  " + v));

        System.out.println("\nTrending feed for Bob:");
        service.setRecommendationStrategy(new TrendingRecommendationStrategy());
        service.getHomeFeed(bob, 5)
            .forEach(v -> System.out.println("  " + v));

        // ===== SCENARIO 7: Delete video =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Delete Video");
        System.out.println("=".repeat(60));

        service.deleteVideo(mrBeast, v4.getId()); // authorized
        service.deleteVideo(alice,   v1.getId()); // unauthorized

        // ===== ANALYTICS =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("ANALYTICS + SUMMARY");
        System.out.println("=".repeat(60));
        service.printAnalytics();
        System.out.println("Total videos: " + service.getVideoCount());
        System.out.println("Total users:  " + service.getUserCount());
        System.out.println("MrBeast subs: " + mrBeast.getChannel().getSubscriberCount());

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern              | Class
            ---------------------|-----------------------------------------------------
            Singleton            | VideoService, VideoProcessingPipeline
            Observer             | VideoEventObserver (SubscriberNotifier, Analytics)
            Strategy             | RecommendationStrategy (Collab/Content/Trending)
            Strategy (ABR)       | VideoQualityStrategy (Auto/Manual)
            Factory              | VideoFactory (standard/short/live/podcast)
            Builder              | Video.Builder, VideoSearchQuery.Builder
            State                | VideoStatus (UPLOADING→PROCESSING→PUBLISHED)
            Chain of Responsibility | ProcessingStep (validate→transcode→thumb→index)
            Iterator             | Paginated search results (page + pageSize)
            """);
    }
}
