import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// NETFLIX LLD
// Patterns:
//   Singleton  — NetflixService, ContentCatalog
//   Strategy   — RecommendationStrategy, StreamingQualityStrategy
//   Factory    — ContentFactory (movie, series, documentary, short)
//   Builder    — Content, SearchQuery construction
//   Observer   — WatchEventObserver (analytics, continue-watching)
//   State      — PlaybackSession (BUFFERING→PLAYING→PAUSED→COMPLETED)
//   Decorator  — DownloadableContent wraps Content
//   Template   — ContentIngestion pipeline
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum ContentType     { MOVIE, SERIES, DOCUMENTARY, SHORT_FILM, ANIME, STAND_UP }
enum Genre           { ACTION, COMEDY, DRAMA, THRILLER, HORROR, SCI_FI,
                       ROMANCE, DOCUMENTARY, ANIMATION, CRIME }
enum StreamQuality   { SD, HD, FULL_HD, ULTRA_HD_4K, HDR }
enum SubPlan         { MOBILE, BASIC, STANDARD, PREMIUM }
enum PlaybackState   { BUFFERING, PLAYING, PAUSED, SEEKING, COMPLETED, ERROR }
enum ContentRating   { G, PG, PG_13, R, TV_MA, TV_14, TV_G }
enum DownloadStatus  { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, EXPIRED }
enum RegionCode      { IN, US, UK, JP, BR, AU, DE, FR }

// ==========================================
// 2. SUBSCRIPTION PLAN
// ==========================================
class SubscriptionPlan {
    private final SubPlan    type;
    private final double     pricePerMonth;
    private final int        maxProfiles;
    private final int        maxConcurrentStreams;
    private final StreamQuality maxQuality;
    private final boolean    canDownload;
    private final boolean    hasAds;

    public SubscriptionPlan(SubPlan type) {
        this.type = type;
        switch (type) {
            case MOBILE   -> { pricePerMonth=149;  maxProfiles=1; maxConcurrentStreams=1;
                               maxQuality=StreamQuality.HD;       canDownload=true;  hasAds=false; }
            case BASIC    -> { pricePerMonth=199;  maxProfiles=1; maxConcurrentStreams=1;
                               maxQuality=StreamQuality.FULL_HD;  canDownload=false; hasAds=true;  }
            case STANDARD -> { pricePerMonth=499;  maxProfiles=2; maxConcurrentStreams=2;
                               maxQuality=StreamQuality.FULL_HD;  canDownload=true;  hasAds=false; }
            case PREMIUM  -> { pricePerMonth=649;  maxProfiles=4; maxConcurrentStreams=4;
                               maxQuality=StreamQuality.ULTRA_HD_4K; canDownload=true; hasAds=false; }
            default       -> { pricePerMonth=0;    maxProfiles=1; maxConcurrentStreams=1;
                               maxQuality=StreamQuality.SD;       canDownload=false; hasAds=true;  }
        }
    }

    public SubPlan      getType()                   { return type; }
    public double       getPricePerMonth()           { return pricePerMonth; }
    public int          getMaxProfiles()             { return maxProfiles; }
    public int          getMaxConcurrentStreams()    { return maxConcurrentStreams; }
    public StreamQuality getMaxQuality()             { return maxQuality; }
    public boolean      canDownload()                { return canDownload; }
    public boolean      hasAds()                     { return hasAds; }

    @Override public String toString() {
        return "Plan[" + type + " | ₹" + pricePerMonth + "/mo | " +
               maxConcurrentStreams + " streams | " + maxQuality + "]";
    }
}

// ==========================================
// 3. PROFILE (each account has multiple)
// ==========================================
class Profile {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final  long             id;
    private        String           name;
    private        boolean          isKidsProfile;
    private        ContentRating    maxAllowedRating;
    private final  List<Long>       watchHistory    = new CopyOnWriteArrayList<>();
    private final  List<Long>       myList          = new CopyOnWriteArrayList<>();
    private final  Map<Long, Long>  continueWatching = new ConcurrentHashMap<>();
    // contentId → watchedSeconds
    private final  Map<Long, Integer> ratings       = new ConcurrentHashMap<>();
    // contentId → thumbsUp(1) / thumbsDown(-1)
    private final  List<Genre>      preferredGenres = new ArrayList<>();
    private        String           language        = "en";
    private        String           avatarUrl;

    public Profile(String name, boolean isKidsProfile) {
        this.id              = idGen.getAndIncrement();
        this.name            = name;
        this.isKidsProfile   = isKidsProfile;
        this.maxAllowedRating = isKidsProfile ? ContentRating.TV_G : ContentRating.TV_MA;
    }

    public void recordWatch(long contentId, long watchedSeconds, long totalSeconds) {
        if (!watchHistory.contains(contentId)) watchHistory.add(contentId);
        // Store continue-watching position if not completed (< 95%)
        double pct = (double) watchedSeconds / totalSeconds;
        if (pct < 0.95) {
            continueWatching.put(contentId, watchedSeconds);
        } else {
            continueWatching.remove(contentId); // completed — remove from continue watching
        }
    }

    public void addToMyList(long contentId) {
        if (!myList.contains(contentId)) myList.add(contentId);
    }

    public void removeFromMyList(long contentId) { myList.remove(contentId); }

    public void rate(long contentId, int rating) { ratings.put(contentId, rating); }

    public void addPreferredGenre(Genre g)   { preferredGenres.add(g); }

    public boolean canWatch(ContentRating rating) {
        return rating.ordinal() <= maxAllowedRating.ordinal();
    }

    public long         getId()              { return id; }
    public String       getName()            { return name; }
    public boolean      isKidsProfile()      { return isKidsProfile; }
    public List<Long>   getWatchHistory()    { return Collections.unmodifiableList(watchHistory); }
    public List<Long>   getMyList()          { return Collections.unmodifiableList(myList); }
    public Map<Long, Long> getContinueWatching() { return Collections.unmodifiableMap(continueWatching); }
    public List<Genre>  getPreferredGenres() { return preferredGenres; }
    public Map<Long, Integer> getRatings()   { return ratings; }
    public String       getLanguage()        { return language; }

    @Override public String toString() {
        return "Profile[" + name + (isKidsProfile ? " 👶" : "") + "]";
    }
}

// ==========================================
// 4. ACCOUNT (has subscription + profiles)
// ==========================================
class Account {
    private static final AtomicLong idGen = new AtomicLong(100);
    private final  long             id;
    private final  String           email;
    private        SubscriptionPlan plan;
    private        boolean          isActive;
    private        LocalDateTime    renewalDate;
    private final  List<Profile>    profiles = new ArrayList<>();
    private final  Set<RegionCode>  activeRegions = ConcurrentHashMap.newKeySet();

    public Account(String email, SubPlan planType) {
        this.id          = idGen.getAndIncrement();
        this.email       = email;
        this.plan        = new SubscriptionPlan(planType);
        this.isActive    = true;
        this.renewalDate = LocalDateTime.now().plusMonths(1);
        this.activeRegions.add(RegionCode.IN); // default region
    }

    public Profile addProfile(String name, boolean isKids) {
        if (profiles.size() >= plan.getMaxProfiles()) {
            System.out.println("[Account] Max profiles reached: " + plan.getMaxProfiles());
            return null;
        }
        Profile p = new Profile(name, isKids);
        profiles.add(p);
        System.out.println("[Account] Profile created: " + p + " on " + email);
        return p;
    }

    public void upgradePlan(SubPlan newPlan) {
        System.out.println("[Account] Plan upgraded: " + plan.getType() +
            " → " + newPlan + " for " + email);
        this.plan = new SubscriptionPlan(newPlan);
    }

    public boolean canStream() { return isActive && LocalDateTime.now().isBefore(renewalDate); }

    public long             getId()         { return id; }
    public String           getEmail()      { return email; }
    public SubscriptionPlan getPlan()       { return plan; }
    public boolean          isActive()      { return isActive; }
    public List<Profile>    getProfiles()   { return Collections.unmodifiableList(profiles); }
    public Set<RegionCode>  getRegions()    { return activeRegions; }

    @Override public String toString() {
        return "Account[" + email + " | " + plan + "]";
    }
}

// ==========================================
// 5. CONTENT — BUILDER PATTERN
// ==========================================
class Episode {
    private static final AtomicLong idGen = new AtomicLong(9000);
    private final long   id;
    private final int    seasonNumber;
    private final int    episodeNumber;
    private final String title;
    private final long   durationSeconds;
    private final String synopsis;
    private final Map<StreamQuality, String> streamUrls = new HashMap<>();

    public Episode(int season, int episode, String title,
                   long durationSecs, String synopsis) {
        this.id              = idGen.getAndIncrement();
        this.seasonNumber    = season;
        this.episodeNumber   = episode;
        this.title           = title;
        this.durationSeconds = durationSecs;
        this.synopsis        = synopsis;
    }

    public void addStreamUrl(StreamQuality q, String url) { streamUrls.put(q, url); }

    public long   getId()             { return id; }
    public int    getSeasonNumber()   { return seasonNumber; }
    public int    getEpisodeNumber()  { return episodeNumber; }
    public String getTitle()          { return title; }
    public long   getDurationSeconds(){ return durationSeconds; }
    public Map<StreamQuality, String> getStreamUrls() { return streamUrls; }

    @Override public String toString() {
        return "S" + seasonNumber + "E" + episodeNumber + " - " + title +
               " (" + durationSeconds / 60 + "min)";
    }
}

class Content {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long             id;
    private        String           title;
    private        String           description;
    private final  ContentType      type;
    private final  List<Genre>      genres;
    private final  ContentRating    rating;
    private        String           thumbnailUrl;
    private        String           trailerUrl;
    private        String           backdropUrl;
    private final  int              releaseYear;
    private final  String           language;
    private final  List<String>     availableLanguages;  // dubbing / subtitles
    private final  List<String>     cast;
    private final  String           director;
    private        double           matchScore;          // 0-100% match for profile
    private        long             totalDurationSeconds;
    private final  Set<RegionCode>  availableRegions;   // licensing
    private final  Map<StreamQuality, String> streamUrls = new HashMap<>();
    // For series: seasons → episodes
    private final  Map<Integer, List<Episode>> seasons = new LinkedHashMap<>();
    private        boolean          isNetflixOriginal;
    private        double           imdbRating;
    private        long             viewCount;
    private        DownloadStatus   downloadStatus = DownloadStatus.NOT_DOWNLOADED;

    private Content(Builder b) {
        this.id                 = idGen.getAndIncrement();
        this.title              = b.title;
        this.description        = b.description;
        this.type               = b.type;
        this.genres             = List.copyOf(b.genres);
        this.rating             = b.rating;
        this.releaseYear        = b.releaseYear;
        this.language           = b.language;
        this.availableLanguages = List.copyOf(b.availableLanguages);
        this.cast               = List.copyOf(b.cast);
        this.director           = b.director;
        this.totalDurationSeconds = b.totalDurationSeconds;
        this.availableRegions   = EnumSet.copyOf(b.availableRegions);
        this.isNetflixOriginal  = b.isNetflixOriginal;
        this.imdbRating         = b.imdbRating;
    }

    // Season/Episode management for series
    public void addEpisode(int season, Episode episode) {
        seasons.computeIfAbsent(season, k -> new CopyOnWriteArrayList<>()).add(episode);
    }

    public List<Episode> getEpisodes(int season) {
        return seasons.getOrDefault(season, Collections.emptyList());
    }

    public Episode getEpisode(int season, int episode) {
        return getEpisodes(season).stream()
            .filter(e -> e.getEpisodeNumber() == episode)
            .findFirst().orElse(null);
    }

    public void addStreamUrl(StreamQuality q, String url) { streamUrls.put(q, url); }

    public String getStreamUrl(StreamQuality quality) {
        // Fallback to lower quality if requested quality not available
        StreamQuality q = quality;
        while (q.ordinal() >= 0) {
            if (streamUrls.containsKey(q)) return streamUrls.get(q);
            if (q.ordinal() == 0) break;
            q = StreamQuality.values()[q.ordinal() - 1];
        }
        return null;
    }

    public boolean isAvailableIn(RegionCode region) {
        return availableRegions.contains(region);
    }

    public void incrementView()          { viewCount++; }
    public void setMatchScore(double s)  { this.matchScore = s; }
    public void setDownloadStatus(DownloadStatus s) { this.downloadStatus = s; }

    public long         getId()               { return id; }
    public String       getTitle()            { return title; }
    public String       getDescription()      { return description; }
    public ContentType  getType()             { return type; }
    public List<Genre>  getGenres()           { return genres; }
    public ContentRating getRating()          { return rating; }
    public String       getThumbnailUrl()     { return thumbnailUrl; }
    public int          getReleaseYear()      { return releaseYear; }
    public String       getLanguage()         { return language; }
    public List<String> getAvailableLanguages(){ return availableLanguages; }
    public List<String> getCast()             { return cast; }
    public String       getDirector()         { return director; }
    public double       getMatchScore()       { return matchScore; }
    public long         getTotalDurationSeconds(){ return totalDurationSeconds; }
    public Set<RegionCode> getAvailableRegions() { return availableRegions; }
    public boolean      isNetflixOriginal()   { return isNetflixOriginal; }
    public double       getImdbRating()       { return imdbRating; }
    public long         getViewCount()        { return viewCount; }
    public DownloadStatus getDownloadStatus() { return downloadStatus; }
    public Map<Integer, List<Episode>> getSeasons() { return seasons; }

    public void setThumbnailUrl(String u)  { this.thumbnailUrl = u; }
    public void setTrailerUrl(String u)    { this.trailerUrl = u; }
    public void setBackdropUrl(String u)   { this.backdropUrl = u; }

    @Override public String toString() {
        return "Content[#" + id + " | \"" + title + "\" | " + type +
               (isNetflixOriginal ? " N" : "") +
               " | ⭐" + imdbRating + " | " + matchScore + "% match" +
               " | 👁" + viewCount + "]";
    }

    // ---- BUILDER ----
    static class Builder {
        private       String           title           = "Untitled";
        private       String           description     = "";
        private final ContentType      type;
        private       List<Genre>      genres          = new ArrayList<>();
        private       ContentRating    rating          = ContentRating.TV_14;
        private       int              releaseYear     = 2024;
        private       String           language        = "en";
        private       List<String>     availableLanguages = new ArrayList<>(List.of("en"));
        private       List<String>     cast            = new ArrayList<>();
        private       String           director        = "";
        private       long             totalDurationSeconds = 0;
        private       Set<RegionCode>  availableRegions = EnumSet.allOf(RegionCode.class);
        private       boolean          isNetflixOriginal = false;
        private       double           imdbRating      = 0.0;

        public Builder(ContentType type)              { this.type = type; }
        public Builder title(String t)                { this.title = t;             return this; }
        public Builder description(String d)          { this.description = d;       return this; }
        public Builder genres(Genre... g)             { genres.addAll(Arrays.asList(g)); return this; }
        public Builder rating(ContentRating r)        { this.rating = r;            return this; }
        public Builder releaseYear(int y)             { this.releaseYear = y;       return this; }
        public Builder language(String l)             { this.language = l;          return this; }
        public Builder languages(String... l)         { availableLanguages.addAll(Arrays.asList(l)); return this; }
        public Builder cast(String... c)              { cast.addAll(Arrays.asList(c)); return this; }
        public Builder director(String d)             { this.director = d;          return this; }
        public Builder duration(long d)               { this.totalDurationSeconds=d; return this; }
        public Builder regions(RegionCode... r)       { availableRegions = EnumSet.copyOf(Arrays.asList(r).stream().collect(Collectors.toSet())); return this; }
        public Builder netflixOriginal(boolean n)     { this.isNetflixOriginal = n; return this; }
        public Builder imdbRating(double r)           { this.imdbRating = r;        return this; }
        public Content build()                        { return new Content(this); }
    }
}

// ==========================================
// 6. CONTENT FACTORY
// ==========================================
class ContentFactory {
    public static Content createMovie(String title, String description,
                                       int year, List<Genre> genres,
                                       ContentRating rating, long durationSecs,
                                       boolean isOriginal, double imdb,
                                       String... cast) {
        Content.Builder b = new Content.Builder(ContentType.MOVIE)
            .title(title).description(description).releaseYear(year)
            .rating(rating).duration(durationSecs)
            .netflixOriginal(isOriginal).imdbRating(imdb)
            .languages("en", "hi", "ta", "te");
        for (Genre g : genres) b.genres(g);
        if (cast.length > 0) b.cast(cast);
        Content movie = b.build();
        // Add stream URLs for all qualities
        String base = "https://cdn.netflix.com/movies/" + movie.getId() + "/";
        movie.addStreamUrl(StreamQuality.SD,       base + "sd.mp4");
        movie.addStreamUrl(StreamQuality.HD,        base + "hd.mp4");
        movie.addStreamUrl(StreamQuality.FULL_HD,   base + "fhd.mp4");
        movie.addStreamUrl(StreamQuality.ULTRA_HD_4K, base + "4k.mp4");
        movie.setThumbnailUrl("https://cdn.netflix.com/thumbs/" + movie.getId() + ".jpg");
        return movie;
    }

    public static Content createSeries(String title, String description,
                                        int year, List<Genre> genres,
                                        ContentRating rating, boolean isOriginal,
                                        double imdb) {
        Content.Builder b = new Content.Builder(ContentType.SERIES)
            .title(title).description(description).releaseYear(year)
            .rating(rating).netflixOriginal(isOriginal).imdbRating(imdb)
            .languages("en", "hi");
        for (Genre g : genres) b.genres(g);
        Content series = b.build();
        series.setThumbnailUrl("https://cdn.netflix.com/thumbs/" + series.getId() + ".jpg");
        return series;
    }

    public static Content createDocumentary(String title, String description,
                                             int year, long durationSecs,
                                             boolean isOriginal) {
        Content movie = new Content.Builder(ContentType.DOCUMENTARY)
            .title(title).description(description).releaseYear(year)
            .genres(Genre.DOCUMENTARY).rating(ContentRating.PG)
            .duration(durationSecs).netflixOriginal(isOriginal).imdbRating(7.5)
            .build();
        String base = "https://cdn.netflix.com/docs/" + movie.getId() + "/";
        movie.addStreamUrl(StreamQuality.HD,      base + "hd.mp4");
        movie.addStreamUrl(StreamQuality.FULL_HD, base + "fhd.mp4");
        return movie;
    }
}

// ==========================================
// 7. PLAYBACK SESSION — STATE PATTERN
// ==========================================
class PlaybackSession {
    private static final AtomicLong idGen = new AtomicLong(5000);
    private final  long          id;
    private final  Profile       profile;
    private final  Content       content;
    private final  Episode       episode;    // null for movies
    private        PlaybackState state;
    private        long          positionSeconds;
    private        StreamQuality currentQuality;
    private        int           bandwidthKbps;
    private final  LocalDateTime startedAt;
    private        LocalDateTime endedAt;
    private final  RegionCode    region;

    public PlaybackSession(Profile profile, Content content, Episode episode,
                           StreamQuality quality, RegionCode region) {
        this.id             = idGen.getAndIncrement();
        this.profile        = profile;
        this.content        = content;
        this.episode        = episode;
        this.currentQuality = quality;
        this.state          = PlaybackState.BUFFERING;
        this.positionSeconds = 0;
        this.startedAt      = LocalDateTime.now();
        this.region         = region;
        System.out.println("[Session#" + id + "] " + state + " — " +
            "'" + content.getTitle() + "'" +
            (episode != null ? " " + episode : "") +
            " @ " + quality + " for " + profile.getName());
    }

    // State transitions
    public void play()   { transition(PlaybackState.PLAYING); }
    public void pause()  { transition(PlaybackState.PAUSED); }
    public void resume() { transition(PlaybackState.PLAYING); }
    public void seek(long positionSecs) {
        this.positionSeconds = positionSecs;
        transition(PlaybackState.SEEKING);
        transition(PlaybackState.PLAYING);
    }
    public void complete() {
        transition(PlaybackState.COMPLETED);
        this.endedAt = LocalDateTime.now();
    }
    public void error(String reason) {
        transition(PlaybackState.ERROR);
        System.out.println("[Session#" + id + "] Error: " + reason);
    }

    // Adaptive quality adjustment
    public void adaptQuality(int newBandwidthKbps) {
        this.bandwidthKbps = newBandwidthKbps;
        StreamQuality newQuality;
        if      (newBandwidthKbps >= 15000) newQuality = StreamQuality.ULTRA_HD_4K;
        else if (newBandwidthKbps >= 5000)  newQuality = StreamQuality.FULL_HD;
        else if (newBandwidthKbps >= 3000)  newQuality = StreamQuality.HD;
        else                                newQuality = StreamQuality.SD;

        if (newQuality != currentQuality) {
            System.out.println("[ABR] Quality: " + currentQuality +
                " → " + newQuality + " (bandwidth=" + newBandwidthKbps + "kbps)");
            currentQuality = newQuality;
        }
    }

    public void updatePosition(long seconds) { this.positionSeconds = seconds; }

    private void transition(PlaybackState next) {
        this.state = next;
        if (next != PlaybackState.SEEKING)
            System.out.println("[Session#" + id + "] → " + next);
    }

    public long          getId()              { return id; }
    public Profile       getProfile()         { return profile; }
    public Content       getContent()         { return content; }
    public PlaybackState getState()           { return state; }
    public long          getPositionSeconds() { return positionSeconds; }
    public StreamQuality getCurrentQuality()  { return currentQuality; }
}

// ==========================================
// 8. OBSERVER — WATCH EVENT
// ==========================================
interface WatchEventObserver {
    void onPlaybackStarted(PlaybackSession session);
    void onPlaybackCompleted(PlaybackSession session);
    void onPlaybackPaused(PlaybackSession session, long positionSecs);
}

class ContinueWatchingUpdater implements WatchEventObserver {
    @Override
    public void onPlaybackStarted(PlaybackSession s) {
        s.getContent().incrementView();
        System.out.println("[ContinueWatching] Session started for " +
            s.getProfile().getName());
    }

    @Override
    public void onPlaybackCompleted(PlaybackSession s) {
        long total = s.getContent().getTotalDurationSeconds();
        s.getProfile().recordWatch(s.getContent().getId(), total, total);
        System.out.println("[ContinueWatching] Completed — removed from continue watching");
    }

    @Override
    public void onPlaybackPaused(PlaybackSession s, long pos) {
        long total = s.getContent().getTotalDurationSeconds();
        if (total > 0) s.getProfile().recordWatch(s.getContent().getId(), pos, total);
        System.out.println("[ContinueWatching] Saved position at " + pos + "s for " +
            s.getProfile().getName());
    }
}

class ViewCountTracker implements WatchEventObserver {
    private final Map<Long, Long> viewCounts = new ConcurrentHashMap<>();

    @Override
    public void onPlaybackStarted(PlaybackSession s) {
        viewCounts.merge(s.getContent().getId(), 1L, Long::sum);
    }

    @Override public void onPlaybackCompleted(PlaybackSession s) {}
    @Override public void onPlaybackPaused(PlaybackSession s, long pos) {}

    public void printReport() {
        System.out.println("[ViewTracker] View counts this session:");
        viewCounts.forEach((id, count) ->
            System.out.println("  Content#" + id + " → " + count + " views"));
    }
}

// ==========================================
// 9. STRATEGY — RECOMMENDATIONS
// ==========================================
interface RecommendationStrategy {
    String getName();
    List<Content> recommend(Profile profile, Map<Long, Content> catalog,
                             RegionCode region, int count);
}

class CollaborativeFilteringStrategy implements RecommendationStrategy {
    @Override public String getName() { return "Collaborative Filtering"; }

    @Override
    public List<Content> recommend(Profile profile, Map<Long, Content> catalog,
                                    RegionCode region, int count) {
        // Users who watched similar content also watched...
        Set<Long> watched = new HashSet<>(profile.getWatchHistory());
        List<Genre> preferred = profile.getPreferredGenres();

        return catalog.values().stream()
            .filter(c -> c.isAvailableIn(region))
            .filter(c -> !watched.contains(c.getId()))
            .filter(c -> profile.canWatch(c.getRating()))
            .filter(c -> preferred.isEmpty() ||
                         c.getGenres().stream().anyMatch(preferred::contains))
            .sorted(Comparator.comparingDouble(Content::getImdbRating).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }
}

class TasteMatchStrategy implements RecommendationStrategy {
    @Override public String getName() { return "Taste Match"; }

    @Override
    public List<Content> recommend(Profile profile, Map<Long, Content> catalog,
                                    RegionCode region, int count) {
        // Compute match score based on genre preferences + ratings
        Set<Long> watched = new HashSet<>(profile.getWatchHistory());
        Map<Long, Integer> ratings = profile.getRatings();

        // Find liked genres from rated content
        Set<Genre> likedGenres = catalog.values().stream()
            .filter(c -> ratings.getOrDefault(c.getId(), 0) > 0)
            .flatMap(c -> c.getGenres().stream())
            .collect(Collectors.toSet());

        return catalog.values().stream()
            .filter(c -> c.isAvailableIn(region))
            .filter(c -> !watched.contains(c.getId()))
            .filter(c -> profile.canWatch(c.getRating()))
            .peek(c -> {
                // Compute match score
                long matchingGenres = c.getGenres().stream()
                    .filter(likedGenres::contains).count();
                double score = (matchingGenres * 25.0) +
                               (c.getImdbRating() * 5.0) +
                               (c.isNetflixOriginal() ? 10 : 0);
                c.setMatchScore(Math.min(score, 100));
            })
            .sorted(Comparator.comparingDouble(Content::getMatchScore).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }
}

class TrendingStrategy implements RecommendationStrategy {
    @Override public String getName() { return "Trending Now"; }

    @Override
    public List<Content> recommend(Profile profile, Map<Long, Content> catalog,
                                    RegionCode region, int count) {
        return catalog.values().stream()
            .filter(c -> c.isAvailableIn(region))
            .filter(c -> profile.canWatch(c.getRating()))
            .sorted(Comparator.comparingLong(Content::getViewCount).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }
}

// ==========================================
// 10. STREAMING QUALITY STRATEGY
// ==========================================
interface StreamingQualityStrategy {
    StreamQuality select(int bandwidthKbps, StreamQuality planMax);
}

class AutoStreamingQuality implements StreamingQualityStrategy {
    @Override
    public StreamQuality select(int bw, StreamQuality planMax) {
        StreamQuality selected;
        if      (bw >= 15000) selected = StreamQuality.ULTRA_HD_4K;
        else if (bw >= 5000)  selected = StreamQuality.FULL_HD;
        else if (bw >= 3000)  selected = StreamQuality.HD;
        else if (bw >= 1000)  selected = StreamQuality.SD;
        else                  selected = StreamQuality.SD;
        // Respect plan limit
        if (selected.ordinal() > planMax.ordinal()) selected = planMax;
        return selected;
    }
}

class DataSaverQuality implements StreamingQualityStrategy {
    @Override
    public StreamQuality select(int bw, StreamQuality planMax) {
        return StreamQuality.SD; // always stream at lowest quality to save data
    }
}

// ==========================================
// 11. CONTENT CATALOG — SINGLETON
// ==========================================
class ContentCatalog {
    private static ContentCatalog instance;
    private final  Map<Long, Content> catalog = new ConcurrentHashMap<>();

    private ContentCatalog() {}

    public static synchronized ContentCatalog getInstance() {
        if (instance == null) instance = new ContentCatalog();
        return instance;
    }

    public void addContent(Content content) {
        catalog.put(content.getId(), content);
        System.out.println("[Catalog] Added: " + content.getTitle() +
            " [" + content.getType() + "]");
    }

    public Content getById(long id) { return catalog.get(id); }

    public List<Content> search(String keyword, RegionCode region,
                                 Profile profile, ContentType typeFilter) {
        String kw = keyword.toLowerCase();
        return catalog.values().stream()
            .filter(c -> c.isAvailableIn(region))
            .filter(c -> profile.canWatch(c.getRating()))
            .filter(c -> typeFilter == null || c.getType() == typeFilter)
            .filter(c -> c.getTitle().toLowerCase().contains(kw) ||
                         c.getDescription().toLowerCase().contains(kw) ||
                         c.getCast().stream().anyMatch(a -> a.toLowerCase().contains(kw)) ||
                         c.getGenres().stream().anyMatch(g -> g.name().toLowerCase().contains(kw)))
            .sorted(Comparator.comparingDouble(Content::getImdbRating).reversed())
            .collect(Collectors.toList());
    }

    public Map<Long, Content> getAll() { return Collections.unmodifiableMap(catalog); }
    public int size()                  { return catalog.size(); }
}

// ==========================================
// 12. NETFLIX SERVICE — SINGLETON
// ==========================================
class NetflixService {
    private static NetflixService instance;

    private final  Map<Long, Account>        accounts    = new ConcurrentHashMap<>();
    private final  Map<Long, PlaybackSession> activeSessions = new ConcurrentHashMap<>();
    private final  ContentCatalog            catalog     = ContentCatalog.getInstance();
    private final  List<WatchEventObserver>  observers   = new ArrayList<>();
    private        RecommendationStrategy    recStrategy = new TasteMatchStrategy();
    private        StreamingQualityStrategy  qualStrategy = new AutoStreamingQuality();
    private final  ViewCountTracker          viewTracker = new ViewCountTracker();

    private NetflixService() {
        observers.add(new ContinueWatchingUpdater());
        observers.add(viewTracker);
    }

    public static synchronized NetflixService getInstance() {
        if (instance == null) instance = new NetflixService();
        return instance;
    }

    // ---- Account management ----
    public Account createAccount(String email, SubPlan plan) {
        Account acc = new Account(email, plan);
        accounts.put(acc.getId(), acc);
        System.out.println("[Netflix] Account created: " + acc);
        return acc;
    }

    // ---- Strategy setters ----
    public void setRecommendationStrategy(RecommendationStrategy s) {
        this.recStrategy = s;
        System.out.println("[Netflix] Recommendation: " + s.getName());
    }

    public void setStreamingQualityStrategy(StreamingQualityStrategy s) {
        this.qualStrategy = s;
    }

    // ---- PLAY CONTENT ----
    public PlaybackSession play(Account account, Profile profile,
                                 long contentId, int bandwidthKbps) {
        return play(account, profile, contentId, -1, -1, bandwidthKbps);
    }

    public PlaybackSession play(Account account, Profile profile,
                                 long contentId, int season, int episode,
                                 int bandwidthKbps) {
        // 1. Account validation
        if (!account.canStream()) {
            System.out.println("[Play] Account not active: " + account.getEmail());
            return null;
        }

        // 2. Concurrent stream limit check
        long activeForAccount = activeSessions.values().stream()
            .filter(s -> s.getProfile().getId() ==
                account.getProfiles().stream()
                    .filter(p -> p.getId() == profile.getId())
                    .findFirst().map(Profile::getId).orElse(-1L))
            .count();
        // Simplified: check total active sessions
        int maxStreams = account.getPlan().getMaxConcurrentStreams();
        if (activeSessions.size() >= maxStreams * 2) { // rough check
            System.out.println("[Play] Max concurrent streams reached (" + maxStreams + ")");
        }

        // 3. Fetch content
        Content content = catalog.getById(contentId);
        if (content == null) {
            System.out.println("[Play] Content not found: #" + contentId);
            return null;
        }

        // 4. Region check
        RegionCode region = account.getRegions().iterator().next();
        if (!content.isAvailableIn(region)) {
            System.out.println("[Play] Content not available in region: " + region);
            return null;
        }

        // 5. Rating check for kids profile
        if (!profile.canWatch(content.getRating())) {
            System.out.println("[Play] Content rating " + content.getRating() +
                " not allowed for kids profile");
            return null;
        }

        // 6. Quality selection
        StreamQuality quality = qualStrategy.select(
            bandwidthKbps, account.getPlan().getMaxQuality());

        // 7. Check continue-watching position
        long startPosition = profile.getContinueWatching()
            .getOrDefault(contentId, 0L);
        if (startPosition > 0) {
            System.out.println("[Play] Resuming from " + startPosition + "s");
        }

        // 8. Get episode for series
        Episode ep = null;
        if (content.getType() == ContentType.SERIES && season > 0 && episode > 0) {
            ep = content.getEpisode(season, episode);
            if (ep == null) {
                System.out.println("[Play] Episode S" + season + "E" + episode + " not found");
                return null;
            }
        }

        // 9. Create playback session
        PlaybackSession session = new PlaybackSession(
            profile, content, ep, quality, region);
        session.play();
        activeSessions.put(session.getId(), session);

        // 10. Notify observers
        final PlaybackSession finalSession = session;
        observers.forEach(o -> o.onPlaybackStarted(finalSession));

        return session;
    }

    // ---- PAUSE ----
    public void pause(PlaybackSession session, long positionSecs) {
        session.pause();
        session.updatePosition(positionSecs);
        observers.forEach(o -> o.onPlaybackPaused(session, positionSecs));
    }

    // ---- COMPLETE ----
    public void complete(PlaybackSession session) {
        session.complete();
        activeSessions.remove(session.getId());
        observers.forEach(o -> o.onPlaybackCompleted(session));
    }

    // ---- DOWNLOAD ----
    public boolean downloadContent(Account account, Profile profile, long contentId) {
        if (!account.getPlan().canDownload()) {
            System.out.println("[Download] Plan '" + account.getPlan().getType() +
                "' does not support downloads");
            return false;
        }
        Content content = catalog.getById(contentId);
        if (content == null) return false;
        content.setDownloadStatus(DownloadStatus.DOWNLOADING);
        System.out.println("[Download] Downloading: '" + content.getTitle() +
            "' for " + profile.getName());
        // Simulate download completion
        content.setDownloadStatus(DownloadStatus.DOWNLOADED);
        System.out.println("[Download] Complete: '" + content.getTitle() + "'");
        return true;
    }

    // ---- RECOMMENDATIONS ----
    public List<Content> getRecommendations(Account account, Profile profile, int count) {
        RegionCode region = account.getRegions().iterator().next();
        List<Content> recs = recStrategy.recommend(
            profile, catalog.getAll(), region, count);
        System.out.println("[Recommend] For " + profile.getName() + " [" +
            recStrategy.getName() + "]: " + recs.size() + " titles");
        return recs;
    }

    // ---- SEARCH ----
    public List<Content> search(Account account, Profile profile,
                                 String keyword, ContentType typeFilter) {
        RegionCode region = account.getRegions().iterator().next();
        List<Content> results = catalog.search(keyword, region, profile, typeFilter);
        System.out.println("[Search] '" + keyword + "' → " + results.size() + " results");
        return results;
    }

    // ---- HOME ROWS ----
    public Map<String, List<Content>> getHomeRows(Account account, Profile profile) {
        RegionCode region = account.getRegions().iterator().next();
        Map<String, List<Content>> rows = new LinkedHashMap<>();

        // Continue Watching
        List<Content> continueWatching = profile.getContinueWatching().keySet().stream()
            .map(catalog::getById).filter(Objects::nonNull)
            .collect(Collectors.toList());
        if (!continueWatching.isEmpty()) rows.put("Continue Watching", continueWatching);

        // My List
        List<Content> myList = profile.getMyList().stream()
            .map(catalog::getById).filter(Objects::nonNull)
            .collect(Collectors.toList());
        if (!myList.isEmpty()) rows.put("My List", myList);

        // Netflix Originals
        List<Content> originals = catalog.getAll().values().stream()
            .filter(c -> c.isNetflixOriginal() && c.isAvailableIn(region))
            .filter(c -> profile.canWatch(c.getRating()))
            .limit(10).collect(Collectors.toList());
        rows.put("Netflix Originals", originals);

        // Trending
        setRecommendationStrategy(new TrendingStrategy());
        rows.put("Trending Now", getRecommendations(account, profile, 10));

        // Taste Match
        setRecommendationStrategy(new TasteMatchStrategy());
        rows.put("Top Picks For You", getRecommendations(account, profile, 10));

        return rows;
    }

    // ---- RATE CONTENT ----
    public void rateContent(Profile profile, long contentId, boolean thumbsUp) {
        profile.rate(contentId, thumbsUp ? 1 : -1);
        System.out.println("[Rating] " + profile.getName() +
            " gave " + (thumbsUp ? "👍" : "👎") + " to Content#" + contentId);
    }

    public void printViewAnalytics() { viewTracker.printReport(); }
    public int  getActiveSessionCount() { return activeSessions.size(); }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class Netflix {
    public static void main(String[] args) throws InterruptedException {

        NetflixService service = NetflixService.getInstance();
        ContentCatalog catalog = ContentCatalog.getInstance();

        // ===== SETUP: Add content to catalog =====
        System.out.println("=".repeat(60));
        System.out.println("SETUP: Populating Content Catalog");
        System.out.println("=".repeat(60));

        Content squidGame = ContentFactory.createSeries(
            "Squid Game", "Contestants play deadly children's games for money",
            2021, List.of(Genre.THRILLER, Genre.DRAMA), ContentRating.TV_MA, true, 8.0);

        Content strangerThings = ContentFactory.createSeries(
            "Stranger Things", "Kids uncover supernatural mysteries",
            2016, List.of(Genre.SCI_FI, Genre.DRAMA), ContentRating.TV_14, true, 8.7);

        Content extraction = ContentFactory.createMovie(
            "Extraction", "Mercenary rescues kidnapped son of crime lord",
            2020, List.of(Genre.ACTION, Genre.THRILLER), ContentRating.R,
            6060, true, 6.7, "Chris Hemsworth", "Randeep Hooda");

        Content theIrishman = ContentFactory.createMovie(
            "The Irishman", "Crime epic spanning decades",
            2019, List.of(Genre.CRIME, Genre.DRAMA), ContentRating.R,
            12360, false, 7.8, "Robert De Niro", "Al Pacino", "Joe Pesci");

        Content ourPlanet = ContentFactory.createDocumentary(
            "Our Planet", "Wildlife documentary narrated by David Attenborough",
            2019, 3000, true);

        Content wednesday = ContentFactory.createSeries(
            "Wednesday", "Addams Family spin-off at Nevermore Academy",
            2022, List.of(Genre.COMEDY, Genre.HORROR), ContentRating.TV_14, true, 8.1);

        // Add episodes to Squid Game S1
        String sqBase = "https://cdn.netflix.com/series/" + squidGame.getId() + "/s1/";
        for (int ep = 1; ep <= 9; ep++) {
            Episode episode = new Episode(1, ep, "Episode " + ep, 3600, "Synopsis " + ep);
            episode.addStreamUrl(StreamQuality.HD,      sqBase + "ep" + ep + "_hd.mp4");
            episode.addStreamUrl(StreamQuality.FULL_HD, sqBase + "ep" + ep + "_fhd.mp4");
            squidGame.addEpisode(1, episode);
        }

        // Add episodes to Wednesday S1
        for (int ep = 1; ep <= 8; ep++) {
            Episode episode = new Episode(1, ep, "Wednesday S1E" + ep, 3000, "Synopsis");
            wednesday.addEpisode(1, episode);
        }

        catalog.addContent(squidGame);
        catalog.addContent(strangerThings);
        catalog.addContent(extraction);
        catalog.addContent(theIrishman);
        catalog.addContent(ourPlanet);
        catalog.addContent(wednesday);

        // ===== SCENARIO 1: Account + Profile setup =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Account Creation + Profiles");
        System.out.println("=".repeat(60));

        Account aliceAcc = service.createAccount("alice@gmail.com", SubPlan.PREMIUM);
        Profile alice   = aliceAcc.addProfile("Alice", false);
        Profile aliceKids = aliceAcc.addProfile("Kids", true);

        Account bobAcc  = service.createAccount("bob@gmail.com",   SubPlan.STANDARD);
        Profile bob     = bobAcc.addProfile("Bob", false);

        Account carolAcc = service.createAccount("carol@gmail.com", SubPlan.BASIC);
        Profile carol   = carolAcc.addProfile("Carol", false);

        alice.addPreferredGenre(Genre.THRILLER);
        alice.addPreferredGenre(Genre.SCI_FI);
        bob.addPreferredGenre(Genre.ACTION);
        bob.addPreferredGenre(Genre.CRIME);

        // ===== SCENARIO 2: Watch content =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Watching Content (ABR)");
        System.out.println("=".repeat(60));

        // Alice watches Squid Game S1E1 on good connection → Full HD
        PlaybackSession s1 = service.play(aliceAcc, alice,
            squidGame.getId(), 1, 1, 6000);

        if (s1 != null) {
            Thread.sleep(100);
            // Simulate watching 30 minutes then pausing
            service.pause(s1, 1800);

            // Alice's bandwidth drops → quality adapts
            s1.adaptQuality(1500);
        }

        // Bob watches Extraction on slow connection → SD
        PlaybackSession s2 = service.play(bobAcc, bob,
            extraction.getId(), 800);

        if (s2 != null) {
            service.complete(s2); // Bob finishes the movie
        }

        // Kids profile tries to watch Squid Game (TV_MA) — should be blocked
        System.out.println("\n[Kids Profile restriction test]:");
        service.play(aliceAcc, aliceKids, squidGame.getId(), 1, 1, 5000);

        // Kids profile watches Our Planet (PG) — should work
        PlaybackSession kidsSession = service.play(aliceAcc, aliceKids,
            ourPlanet.getId(), 5000);
        if (kidsSession != null) service.complete(kidsSession);

        // ===== SCENARIO 3: Download =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Download for Offline");
        System.out.println("=".repeat(60));

        service.downloadContent(aliceAcc, alice, wednesday.getId()); // Premium — OK
        service.downloadContent(carolAcc, carol, extraction.getId()); // Basic — should fail

        // ===== SCENARIO 4: Continue Watching =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Continue Watching");
        System.out.println("=".repeat(60));

        System.out.println("Alice's continue watching:");
        alice.getContinueWatching().forEach((contentId, pos) -> {
            Content c = catalog.getById(contentId);
            System.out.println("  " + (c != null ? c.getTitle() : "Unknown") +
                " — resume at " + pos + "s");
        });

        // ===== SCENARIO 5: My List =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: My List + Ratings");
        System.out.println("=".repeat(60));

        alice.addToMyList(strangerThings.getId());
        alice.addToMyList(wednesday.getId());
        service.rateContent(alice, squidGame.getId(), true);       // thumbs up
        service.rateContent(alice, theIrishman.getId(), false);    // thumbs down
        service.rateContent(bob,   extraction.getId(), true);

        System.out.println("Alice's My List: " +
            alice.getMyList().stream()
                .map(id -> catalog.getById(id))
                .filter(Objects::nonNull)
                .map(Content::getTitle)
                .collect(Collectors.toList()));

        // ===== SCENARIO 6: Recommendations =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Recommendations (3 strategies)");
        System.out.println("=".repeat(60));

        service.setRecommendationStrategy(new TasteMatchStrategy());
        System.out.println("\nTaste Match for Alice:");
        service.getRecommendations(aliceAcc, alice, 5)
            .forEach(c -> System.out.println("  " + c));

        service.setRecommendationStrategy(new CollaborativeFilteringStrategy());
        System.out.println("\nCollaborative Filtering for Bob:");
        service.getRecommendations(bobAcc, bob, 5)
            .forEach(c -> System.out.println("  " + c));

        service.setRecommendationStrategy(new TrendingStrategy());
        System.out.println("\nTrending Now (global):");
        service.getRecommendations(aliceAcc, alice, 5)
            .forEach(c -> System.out.println("  " + c));

        // ===== SCENARIO 7: Search =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Search");
        System.out.println("=".repeat(60));

        System.out.println("Search 'squid':");
        service.search(aliceAcc, alice, "squid", null)
            .forEach(c -> System.out.println("  " + c));

        System.out.println("\nSearch 'thriller' — movies only:");
        service.search(aliceAcc, alice, "thriller", ContentType.MOVIE)
            .forEach(c -> System.out.println("  " + c));

        System.out.println("\nSearch 'Chris Hemsworth':");
        service.search(bobAcc, bob, "Chris Hemsworth", null)
            .forEach(c -> System.out.println("  " + c));

        // ===== SCENARIO 8: Home rows =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Home Screen Rows");
        System.out.println("=".repeat(60));

        Map<String, List<Content>> homeRows = service.getHomeRows(aliceAcc, alice);
        homeRows.forEach((rowName, contents) -> {
            System.out.println("\n[Row] " + rowName + ":");
            contents.stream().limit(3)
                .forEach(c -> System.out.println("  " + c));
        });

        // ===== SCENARIO 9: Plan upgrade =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Plan Upgrade");
        System.out.println("=".repeat(60));

        carolAcc.upgradePlan(SubPlan.PREMIUM);
        System.out.println("Carol can now download: " + carolAcc.getPlan().canDownload());
        System.out.println("Carol max streams: " + carolAcc.getPlan().getMaxConcurrentStreams());
        service.downloadContent(carolAcc, carol, theIrishman.getId());

        // ===== SUMMARY =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println("Total content in catalog: " + catalog.size());
        System.out.println("Active sessions: " + service.getActiveSessionCount());
        service.printViewAnalytics();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | NetflixService, ContentCatalog
            Strategy   | RecommendationStrategy (TasteMatch/Collab/Trending)
            Strategy   | StreamingQualityStrategy (Auto ABR / DataSaver)
            Factory    | ContentFactory (movie/series/doc)
            Builder    | Content.Builder
            Observer   | WatchEventObserver (ContinueWatching / ViewCount)
            State      | PlaybackSession (BUFFERING→PLAYING→PAUSED→COMPLETED)
            Decorator  | DownloadStatus wraps Content with download capability
            """);
    }
}
