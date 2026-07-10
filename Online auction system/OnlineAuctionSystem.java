import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ============================================================
// ONLINE AUCTION SYSTEM — LLD
//
// Requirements covered:
//   1. User registration + login
//   2. Create auction listings (name, desc, start price, duration)
//   3. Browse + search listings (name, category, price range)
//   4. Place bids on active auctions
//   5. Auto-update highest bid + notify bidders
//   6. Auction ends at expiry → declare winner
//   7. Concurrent access + data consistency
//   8. Extensible design
//
// Design Patterns:
//   Singleton  — AuctionService, UserRegistry
//   Strategy   — BidValidationStrategy (standard / reserve / auto-bid)
//   Observer   — AuctionEventObserver (outbid alerts, win notify, analytics)
//   Factory    — AuctionFactory (standard / reserve / dutch / sealed)
//   Builder    — AuctionListing, Bid construction
//   State      — AuctionStatus (DRAFT→ACTIVE→ENDED/CANCELLED)
//               BidStatus (PENDING→LEADING→OUTBID→WON/LOST)
//   Command    — PlaceBidCommand (place + retract)
//   Iterator   — AuctionSearchIterator (filtered paginated results)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum AuctionStatus  { DRAFT, SCHEDULED, ACTIVE, ENDED, CANCELLED }
enum BidStatus      { PENDING, LEADING, OUTBID, WON, LOST, RETRACTED }
enum AuctionType    { STANDARD, RESERVE, DUTCH, SEALED_BID }
enum Category       { ELECTRONICS, COLLECTIBLES, ART, FASHION, VEHICLES,
                      REAL_ESTATE, SPORTS, BOOKS, JEWELRY, OTHER }
enum UserStatus     { ACTIVE, SUSPENDED, BANNED }
enum PaymentStatus  { PENDING, PAID, FAILED, REFUNDED }

// ============================================================
// 2. MONEY — value object (paise precision, avoids float errors)
// ============================================================
class Money implements Comparable<Money> {
    private final long   paise;
    private final String currency;

    public Money(double amount, String currency) {
        this.paise    = Math.round(amount * 100);
        this.currency = currency;
    }

    public Money(double amount)    { this(amount, "INR"); }
    private Money(long p, String c){ this.paise = p; this.currency = c; }

    public Money add(Money o)      { return new Money(paise + o.paise, currency); }
    public Money subtract(Money o) { return new Money(Math.max(0, paise - o.paise), currency); }

    public boolean isGreaterThan(Money o) { return paise > o.paise; }
    public boolean isZero()               { return paise == 0; }
    public double  toAmount()             { return paise / 100.0; }
    public long    getPaise()             { return paise; }

    @Override public int compareTo(Money o) { return Long.compare(paise, o.paise); }
    @Override public boolean equals(Object o){
        return o instanceof Money m && paise == m.paise;
    }
    @Override public String toString() {
        return currency + " " + String.format("%.2f", toAmount());
    }
}

// ============================================================
// 3. USER — BUILDER PATTERN
//    Req 1: user registration
// ============================================================
class User {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long        userId;
    private        String      username;
    private        String      email;
    private        String      passwordHash;   // never store plain text
    private        UserStatus  status;
    private        double      trustScore;     // 0-100, affects bid eligibility
    private        int         totalBidsPlaced;
    private        int         totalAuctionsWon;
    private        Money       walletBalance;  // pre-loaded for bidding
    private final  LocalDateTime registeredAt;

    private User(Builder b) {
        this.userId       = idGen.getAndIncrement();
        this.username     = b.username;
        this.email        = b.email;
        this.passwordHash = hashPassword(b.password);
        this.status       = UserStatus.ACTIVE;
        this.trustScore   = 100.0;
        this.walletBalance= new Money(b.initialBalance);
        this.registeredAt = LocalDateTime.now();
    }

    private String hashPassword(String plain) {
        // In production: BCrypt / Argon2
        return "HASH:" + plain.hashCode();
    }

    public boolean authenticate(String password) {
        return passwordHash.equals(hashPassword(password));
    }

    public boolean isEligibleToBid() {
        return status == UserStatus.ACTIVE && trustScore >= 20.0;
    }

    public synchronized boolean reserveFunds(Money amount) {
        if (walletBalance.getPaise() < amount.getPaise()) return false;
        walletBalance = walletBalance.subtract(amount);
        return true;
    }

    public synchronized void releaseFunds(Money amount) {
        walletBalance = walletBalance.add(amount);
    }

    public void incrementBids()      { totalBidsPlaced++; }
    public void incrementWins()      { totalAuctionsWon++; }
    public void decreaseTrustScore(double by) {
        trustScore = Math.max(0, trustScore - by);
    }

    public long        getUserId()         { return userId; }
    public String      getUsername()       { return username; }
    public String      getEmail()          { return email; }
    public UserStatus  getStatus()         { return status; }
    public double      getTrustScore()     { return trustScore; }
    public Money       getWalletBalance()  { return walletBalance; }
    public int         getTotalBids()      { return totalBidsPlaced; }
    public int         getAuctionsWon()    { return totalAuctionsWon; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }

    public void setStatus(UserStatus s)    { this.status = s; }

    @Override public String toString() {
        return String.format("User[#%d | %-15s | %s | trust=%.0f | balance=%s]",
            userId, username, status, trustScore, walletBalance);
    }

    static class Builder {
        private final String username;
        private final String email;
        private final String password;
        private       double initialBalance = 0;

        public Builder(String username, String email, String password) {
            this.username = username;
            this.email    = email;
            this.password = password;
        }
        public Builder balance(double b)   { this.initialBalance = b; return this; }
        public User build()                { return new User(this); }
    }
}

// ============================================================
// 4. BID — BUILDER PATTERN
//    State machine: PENDING → LEADING / OUTBID → WON / LOST
// ============================================================
class Bid {
    private static final AtomicLong idGen = new AtomicLong(100_000);

    private final  long           bidId;
    private final  long           auctionId;
    private final  long           bidderId;
    private final  Money          amount;
    private        BidStatus      status;
    private final  LocalDateTime  placedAt;
    private        LocalDateTime  updatedAt;

    private Bid(Builder b) {
        this.bidId     = idGen.getAndIncrement();
        this.auctionId = b.auctionId;
        this.bidderId  = b.bidderId;
        this.amount    = b.amount;
        this.status    = BidStatus.PENDING;
        this.placedAt  = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // ---- State transitions ----
    public void markLeading()  { status = BidStatus.LEADING;  updatedAt = LocalDateTime.now(); }
    public void markOutbid()   { status = BidStatus.OUTBID;   updatedAt = LocalDateTime.now(); }
    public void markWon()      { status = BidStatus.WON;      updatedAt = LocalDateTime.now(); }
    public void markLost()     { status = BidStatus.LOST;     updatedAt = LocalDateTime.now(); }
    public void markRetracted(){ status = BidStatus.RETRACTED;updatedAt = LocalDateTime.now(); }

    public long          getBidId()    { return bidId; }
    public long          getAuctionId(){ return auctionId; }
    public long          getBidderId() { return bidderId; }
    public Money         getAmount()   { return amount; }
    public BidStatus     getStatus()   { return status; }
    public LocalDateTime getPlacedAt() { return placedAt; }

    @Override public String toString() {
        return String.format("Bid[#%d | auction=%d | bidder=%d | %s | %s]",
            bidId, auctionId, bidderId, amount, status);
    }

    static class Builder {
        private final long  auctionId;
        private final long  bidderId;
        private final Money amount;

        public Builder(long auctionId, long bidderId, Money amount) {
            this.auctionId = auctionId;
            this.bidderId  = bidderId;
            this.amount    = amount;
        }
        public Bid build() { return new Bid(this); }
    }
}

// ============================================================
// 5. AUCTION LISTING — BUILDER PATTERN
//    Req 2: item name, description, starting price, duration
//    Req 7: per-auction ReentrantLock for concurrency safety
// ============================================================
class AuctionListing {
    private static final AtomicLong idGen = new AtomicLong(500);

    private final  long            auctionId;
    private final  String          itemName;
    private final  String          description;
    private final  Category        category;
    private final  long            sellerId;
    private final  Money           startingPrice;
    private        Money           reservePrice;   // null = no reserve
    private        Money           currentHighBid; // always >= startingPrice
    private        long            currentHighBidder; // userId
    private        Bid             leadingBid;
    private final  int             durationMinutes;
    private        LocalDateTime   startTime;
    private        LocalDateTime   endTime;
    private        AuctionStatus   status;
    private final  AuctionType     type;
    private final  List<Bid>       bidHistory = new CopyOnWriteArrayList<>();
    private        String          imageUrl;
    private        String          condition;   // NEW, LIKE_NEW, GOOD, FAIR

    // ============================================================
    // Per-auction fair ReentrantLock (Req 7)
    //   fair=true: threads queue in FIFO — prevents bid starvation
    //   Why per-auction and not global?
    //   Two users bidding on DIFFERENT auctions never contend.
    //   Only bidders on the SAME auction need to serialize.
    // ============================================================
    private final ReentrantLock lock = new ReentrantLock(true);

    // Background timer for auto-close
    private final ScheduledExecutorService timer =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-timer-" + idGen.get());
            t.setDaemon(true);
            return t;
        });

    private AuctionListing(Builder b) {
        this.auctionId      = idGen.getAndIncrement();
        this.itemName       = b.itemName;
        this.description    = b.description;
        this.category       = b.category;
        this.sellerId       = b.sellerId;
        this.startingPrice  = b.startingPrice;
        this.reservePrice   = b.reservePrice;
        this.currentHighBid = b.startingPrice;
        this.durationMinutes= b.durationMinutes;
        this.type           = b.type;
        this.status         = AuctionStatus.DRAFT;
        this.imageUrl       = b.imageUrl;
        this.condition      = b.condition;
    }

    // ---- STATE MACHINE: activate auction ----
    public synchronized void activate() {
        if (status != AuctionStatus.DRAFT && status != AuctionStatus.SCHEDULED) return;
        status    = AuctionStatus.ACTIVE;
        startTime = LocalDateTime.now();
        endTime   = startTime.plusMinutes(durationMinutes);
        System.out.printf("[Auction #%d] ACTIVE: '%s' | ends at %s%n",
            auctionId, itemName, endTime);

        // Schedule auto-close
        timer.schedule(this::closeAuction, durationMinutes, TimeUnit.MINUTES);
    }

    // ---- PLACE BID (Req 4 + 7) ----
    /**
     * Atomically places a bid under the per-auction lock.
     * Returns the placed Bid if successful, null otherwise.
     *
     * Req 7: fair lock ensures FIFO — first bid in, first served
     * at the same price level. No starvation.
     */
    public Bid placeBid(Bid newBid) {
        lock.lock();
        try {
            // Guard: auction must be active
            if (status != AuctionStatus.ACTIVE) {
                System.out.println("[Auction #" + auctionId + "] Not active — bid rejected");
                return null;
            }

            // Guard: check time window (Req 6)
            if (LocalDateTime.now().isAfter(endTime)) {
                System.out.println("[Auction #" + auctionId + "] Time expired — bid rejected");
                closeAuction();
                return null;
            }

            // Guard: bid must exceed current high bid
            if (!newBid.getAmount().isGreaterThan(currentHighBid)) {
                System.out.printf("[Auction #%d] Bid %s ≤ current high %s — rejected%n",
                    auctionId, newBid.getAmount(), currentHighBid);
                return null;
            }

            // Guard: bidder cannot bid on own auction
            if (newBid.getBidderId() == sellerId) {
                System.out.println("[Auction #" + auctionId + "] Seller cannot bid");
                return null;
            }

            // Mark previous leader as OUTBID (Req 5)
            if (leadingBid != null) {
                leadingBid.markOutbid();
                System.out.printf("[Auction #%d] Previous leader (bidder=%d) OUTBID%n",
                    auctionId, currentHighBidder);
            }

            // Accept new bid
            newBid.markLeading();
            currentHighBid     = newBid.getAmount();
            currentHighBidder  = newBid.getBidderId();
            leadingBid         = newBid;
            bidHistory.add(newBid);

            System.out.printf("[Auction #%d] New high bid: %s by bidder=%d%n",
                auctionId, currentHighBid, currentHighBidder);
            return newBid;

        } finally {
            lock.unlock(); // always released, even on exception
        }
    }

    // ---- Req 6: close auction + declare winner ----
    public synchronized void closeAuction() {
        if (status == AuctionStatus.ENDED || status == AuctionStatus.CANCELLED) return;
        status = AuctionStatus.ENDED;

        if (leadingBid != null) {
            // Check reserve price (for RESERVE auctions)
            if (reservePrice != null &&
                !currentHighBid.isGreaterThan(reservePrice) &&
                !currentHighBid.equals(reservePrice)) {
                System.out.printf("[Auction #%d] ENDED — reserve not met. No winner.%n",
                    auctionId);
                // Mark all bids as LOST
                bidHistory.forEach(Bid::markLost);
            } else {
                leadingBid.markWon();
                // Mark all others as LOST
                bidHistory.stream()
                    .filter(b -> b.getBidId() != leadingBid.getBidId())
                    .forEach(Bid::markLost);
                System.out.printf("[Auction #%d] ENDED — WINNER: bidder=%d with %s%n",
                    auctionId, currentHighBidder, currentHighBid);
            }
        } else {
            System.out.printf("[Auction #%d] ENDED — no bids placed%n", auctionId);
        }
        timer.shutdown();
    }

    public void cancel(String reason) {
        if (status == AuctionStatus.ACTIVE || status == AuctionStatus.DRAFT) {
            status = AuctionStatus.CANCELLED;
            bidHistory.forEach(Bid::markRetracted);
            System.out.printf("[Auction #%d] CANCELLED: %s%n", auctionId, reason);
            timer.shutdown();
        }
    }

    public boolean isActive()  { return status == AuctionStatus.ACTIVE; }
    public boolean hasEnded()  { return status == AuctionStatus.ENDED; }
    public boolean isExpired() {
        return endTime != null && LocalDateTime.now().isAfter(endTime);
    }

    public long          getAuctionId()       { return auctionId; }
    public String        getItemName()        { return itemName; }
    public String        getDescription()     { return description; }
    public Category      getCategory()        { return category; }
    public long          getSellerId()        { return sellerId; }
    public Money         getStartingPrice()   { return startingPrice; }
    public Money         getCurrentHighBid()  { return currentHighBid; }
    public long          getCurrentHighBidder(){ return currentHighBidder; }
    public Bid           getLeadingBid()      { return leadingBid; }
    public AuctionStatus getStatus()          { return status; }
    public AuctionType   getType()            { return type; }
    public LocalDateTime getEndTime()         { return endTime; }
    public List<Bid>     getBidHistory()      { return Collections.unmodifiableList(bidHistory); }
    public int           getBidCount()        { return bidHistory.size(); }
    public Money         getMinNextBid()      {
        // Minimum increment: 1% of current high bid or ₹10, whichever is greater
        long increment = Math.max(1000L, currentHighBid.getPaise() / 100);
        return new Money((currentHighBid.getPaise() + increment) / 100.0);
    }

    @Override public String toString() {
        return String.format("Auction[#%d | %-25s | %s | high=%s | bids=%d | %s | ends=%s]",
            auctionId, itemName, category, currentHighBid,
            bidHistory.size(), status, endTime);
    }

    static class Builder {
        private final String   itemName;
        private final long     sellerId;
        private final Money    startingPrice;
        private final int      durationMinutes;
        private       String   description = "";
        private       Category category    = Category.OTHER;
        private       Money    reservePrice = null;
        private       AuctionType type     = AuctionType.STANDARD;
        private       String   imageUrl    = "";
        private       String   condition   = "GOOD";

        public Builder(String itemName, long sellerId,
                       double startingPrice, int durationMinutes) {
            this.itemName        = itemName;
            this.sellerId        = sellerId;
            this.startingPrice   = new Money(startingPrice);
            this.durationMinutes = durationMinutes;
        }
        public Builder description(String d)  { this.description = d;   return this; }
        public Builder category(Category c)   { this.category = c;      return this; }
        public Builder reservePrice(double r) { this.reservePrice = new Money(r); return this; }
        public Builder type(AuctionType t)    { this.type = t;          return this; }
        public Builder imageUrl(String u)     { this.imageUrl = u;      return this; }
        public Builder condition(String c)    { this.condition = c;     return this; }
        public AuctionListing build()         { return new AuctionListing(this); }
    }
}

// ============================================================
// 6. AUCTION FACTORY — FACTORY PATTERN (Req 8: extensible)
// ============================================================
class AuctionFactory {
    /** Standard English auction — highest bid wins */
    public static AuctionListing standard(long sellerId, String item,
                                           String desc, Category cat,
                                           double startPrice, int durationMins) {
        return new AuctionListing.Builder(item, sellerId, startPrice, durationMins)
            .description(desc).category(cat).type(AuctionType.STANDARD).build();
    }

    /** Reserve auction — must meet reserve price to sell */
    public static AuctionListing withReserve(long sellerId, String item,
                                              String desc, Category cat,
                                              double startPrice, double reserve,
                                              int durationMins) {
        return new AuctionListing.Builder(item, sellerId, startPrice, durationMins)
            .description(desc).category(cat)
            .type(AuctionType.RESERVE).reservePrice(reserve).build();
    }

    /** Quick auction — short duration (1-15 mins, flash sale style) */
    public static AuctionListing flash(long sellerId, String item,
                                        Category cat, double startPrice) {
        return new AuctionListing.Builder(item, sellerId, startPrice, 5)
            .category(cat).type(AuctionType.STANDARD).build();
    }
}

// ============================================================
// 7. BID VALIDATION STRATEGY — STRATEGY PATTERN (Req 8)
// ============================================================
interface BidValidationStrategy {
    String getName();
    // Returns null if valid, error string if invalid
    String validate(Bid bid, AuctionListing auction, User bidder);
}

/** Standard: bid > current high, user is active, has funds */
class StandardBidValidation implements BidValidationStrategy {
    @Override public String getName() { return "Standard"; }

    @Override
    public String validate(Bid bid, AuctionListing auction, User bidder) {
        if (!bidder.isEligibleToBid())
            return "User not eligible to bid: " + bidder.getStatus();
        if (bid.getBidderId() == auction.getSellerId())
            return "Seller cannot bid on own auction";
        if (!auction.isActive())
            return "Auction is not active: " + auction.getStatus();
        if (!bid.getAmount().isGreaterThan(auction.getCurrentHighBid()))
            return "Bid " + bid.getAmount() + " must exceed current high " +
                   auction.getCurrentHighBid();
        return null;
    }
}

/** Fund-check: user must have enough wallet balance */
class FundCheckBidValidation implements BidValidationStrategy {
    @Override public String getName() { return "FundCheck"; }

    @Override
    public String validate(Bid bid, AuctionListing auction, User bidder) {
        String base = new StandardBidValidation().validate(bid, auction, bidder);
        if (base != null) return base;

        if (bidder.getWalletBalance().getPaise() < bid.getAmount().getPaise())
            return "Insufficient wallet balance: " + bidder.getWalletBalance() +
                   " < " + bid.getAmount();
        return null;
    }
}

/** Minimum increment: bid must be at least minNextBid */
class MinIncrementBidValidation implements BidValidationStrategy {
    @Override public String getName() { return "MinIncrement"; }

    @Override
    public String validate(Bid bid, AuctionListing auction, User bidder) {
        String base = new FundCheckBidValidation().validate(bid, auction, bidder);
        if (base != null) return base;

        if (bid.getAmount().getPaise() < auction.getMinNextBid().getPaise())
            return "Bid must be at least " + auction.getMinNextBid() +
                   " (current min increment)";
        return null;
    }
}

// ============================================================
// 8. OBSERVER — AUCTION EVENTS (Req 5 + 8)
// ============================================================
interface AuctionEventObserver {
    void onBidPlaced(Bid bid, AuctionListing auction);
    void onBidOutbid(Bid outbidBid, Bid newLeadingBid, AuctionListing auction);
    void onAuctionEnded(AuctionListing auction, Bid winningBid);
    void onAuctionCreated(AuctionListing auction);
    void onAuctionCancelled(AuctionListing auction);
}

class NotificationObserver implements AuctionEventObserver {
    private final UserRegistry userRegistry;

    public NotificationObserver(UserRegistry reg) { this.userRegistry = reg; }

    @Override
    public void onBidPlaced(Bid bid, AuctionListing auction) {
        User bidder = userRegistry.getUser(bid.getBidderId());
        if (bidder != null)
            System.out.printf("[Notif → %s] You are now the highest bidder on '%s' with %s%n",
                bidder.getEmail(), auction.getItemName(), bid.getAmount());
    }

    // Req 5: notify outbid users automatically
    @Override
    public void onBidOutbid(Bid outbidBid, Bid newLead, AuctionListing auction) {
        User outbid = userRegistry.getUser(outbidBid.getBidderId());
        if (outbid != null)
            System.out.printf("[Notif → %s] ⚠ You have been OUTBID on '%s'! " +
                "New high: %s. Bid again to stay in the lead.%n",
                outbid.getEmail(), auction.getItemName(), newLead.getAmount());
    }

    // Req 6: winner notification
    @Override
    public void onAuctionEnded(AuctionListing auction, Bid winningBid) {
        if (winningBid != null) {
            User winner = userRegistry.getUser(winningBid.getBidderId());
            if (winner != null) {
                System.out.printf("[Notif → %s] 🏆 Congratulations! You WON '%s' for %s!%n",
                    winner.getEmail(), auction.getItemName(), winningBid.getAmount());
                System.out.printf("[Notif → %s] Please complete payment within 48 hours.%n",
                    winner.getEmail());
            }
            // Notify losers
            auction.getBidHistory().stream()
                .filter(b -> b.getStatus() == BidStatus.LOST)
                .map(b -> userRegistry.getUser(b.getBidderId()))
                .filter(Objects::nonNull)
                .distinct()
                .forEach(u -> System.out.printf(
                    "[Notif → %s] '%s' ended. Winner bid %s. Better luck next time!%n",
                    u.getEmail(), auction.getItemName(), winningBid.getAmount()));
        } else {
            System.out.printf("[Notif] Auction '%s' ended with no winner.%n",
                auction.getItemName());
        }
    }

    @Override
    public void onAuctionCreated(AuctionListing a) {
        System.out.printf("[Notif] New auction listed: '%s' (starts at %s)%n",
            a.getItemName(), a.getStartingPrice());
    }

    @Override
    public void onAuctionCancelled(AuctionListing a) {
        System.out.printf("[Notif] Auction '%s' has been cancelled.%n", a.getItemName());
    }
}

class AnalyticsObserver implements AuctionEventObserver {
    private long totalBids      = 0;
    private long activeAuctions = 0;
    private long endedAuctions  = 0;
    private long totalRevenue   = 0;
    private final Map<Category, Long> categoryBids = new ConcurrentHashMap<>();

    @Override public synchronized void onBidPlaced(Bid b, AuctionListing a) {
        totalBids++;
        categoryBids.merge(a.getCategory(), 1L, Long::sum);
    }
    @Override public void onBidOutbid(Bid ob, Bid nb, AuctionListing a) {}
    @Override public synchronized void onAuctionEnded(AuctionListing a, Bid w) {
        endedAuctions++;
        if (w != null) totalRevenue += w.getAmount().getPaise();
    }
    @Override public synchronized void onAuctionCreated(AuctionListing a) {
        activeAuctions++;
    }
    @Override public void onAuctionCancelled(AuctionListing a) { activeAuctions--; }

    public void printReport() {
        System.out.println("\n[Analytics]");
        System.out.printf("  Total bids:    %d%n", totalBids);
        System.out.printf("  Active:        %d%n", activeAuctions);
        System.out.printf("  Ended:         %d%n", endedAuctions);
        System.out.printf("  Total revenue: INR %.2f%n", totalRevenue / 100.0);
        System.out.println("  Bids by category: " + categoryBids);
    }
}

// ============================================================
// 9. PLACE BID COMMAND — COMMAND PATTERN (Req 8: extensible)
//    execute() = validate + place bid + notify
//    undo()    = retract bid (within allowed window)
// ============================================================
class PlaceBidCommand {
    private final AuctionListing               auction;
    private final Bid                          bid;
    private final User                         bidder;
    private final BidValidationStrategy        validator;
    private final List<AuctionEventObserver>   observers;
    private       Bid                          previousLeader;
    private       boolean                      executed = false;

    public PlaceBidCommand(AuctionListing auction, Bid bid,
                            User bidder, BidValidationStrategy validator,
                            List<AuctionEventObserver> observers) {
        this.auction    = auction;
        this.bid        = bid;
        this.bidder     = bidder;
        this.validator  = validator;
        this.observers  = observers;
    }

    /**
     * Execute bid placement:
     * 1. Strategy validation
     * 2. Reserve wallet funds (hold)
     * 3. Place bid atomically in auction
     * 4. Release previous leader's held funds (Req 5)
     * 5. Notify observers
     */
    public boolean execute() {
        // Step 1: Validate
        String error = validator.validate(bid, auction, bidder);
        if (error != null) {
            System.out.println("[BidCmd] Invalid: " + error);
            return false;
        }

        // Step 2: Reserve bidder's funds
        if (!bidder.reserveFunds(bid.getAmount())) {
            System.out.println("[BidCmd] Could not reserve funds");
            return false;
        }

        // Track previous leader to release their funds after
        previousLeader = auction.getLeadingBid();
        User prevUser  = previousLeader != null
            ? AuctionService.getInstance().getUser(previousLeader.getBidderId())
            : null;

        // Step 3: Atomically place bid (per-auction lock inside)
        Bid result = auction.placeBid(bid);
        if (result == null) {
            // Bid rejected by auction — release our reserved funds
            bidder.releaseFunds(bid.getAmount());
            return false;
        }

        // Step 4: Release previous leader's held funds (they've been outbid)
        if (prevUser != null) {
            prevUser.releaseFunds(previousLeader.getAmount());
        }

        bidder.incrementBids();
        executed = true;

        // Step 5: Notify observers
        observers.forEach(o -> o.onBidPlaced(bid, auction));
        if (previousLeader != null) {
            observers.forEach(o -> o.onBidOutbid(previousLeader, bid, auction));
        }

        return true;
    }

    /** Retract bid — only allowed if not the current leader */
    public boolean undo() {
        if (!executed) return false;
        if (bid.getStatus() == BidStatus.LEADING) {
            System.out.println("[BidCmd] Cannot retract leading bid");
            return false;
        }
        bid.markRetracted();
        bidder.releaseFunds(bid.getAmount()); // refund held amount
        executed = false;
        System.out.println("[BidCmd] Bid retracted: " + bid);
        return true;
    }
}

// ============================================================
// 10. AUCTION SEARCH — ITERATOR PATTERN (Req 3)
// ============================================================
class AuctionSearch {
    private final Collection<AuctionListing> allAuctions;

    public AuctionSearch(Collection<AuctionListing> auctions) {
        this.allAuctions = auctions;
    }

    // Req 3: search by name (partial, case-insensitive)
    public List<AuctionListing> searchByName(String query) {
        String q = query.toLowerCase();
        return allAuctions.stream()
            .filter(a -> a.getItemName().toLowerCase().contains(q))
            .filter(a -> a.getStatus() == AuctionStatus.ACTIVE)
            .sorted(Comparator.comparing(AuctionListing::getEndTime))
            .collect(Collectors.toList());
    }

    // Req 3: search by category
    public List<AuctionListing> searchByCategory(Category cat) {
        return allAuctions.stream()
            .filter(a -> a.getCategory() == cat)
            .filter(a -> a.getStatus() == AuctionStatus.ACTIVE)
            .collect(Collectors.toList());
    }

    // Req 3: search by price range
    public List<AuctionListing> searchByPriceRange(double minPrice, double maxPrice) {
        return allAuctions.stream()
            .filter(a -> a.getStatus() == AuctionStatus.ACTIVE)
            .filter(a -> a.getCurrentHighBid().toAmount() >= minPrice &&
                         a.getCurrentHighBid().toAmount() <= maxPrice)
            .sorted(Comparator.comparing(a -> a.getCurrentHighBid().getPaise()))
            .collect(Collectors.toList());
    }

    // Combined search
    public List<AuctionListing> search(String query, Category category,
                                        Double minPrice, Double maxPrice,
                                        boolean activeOnly) {
        return allAuctions.stream()
            .filter(a -> !activeOnly || a.getStatus() == AuctionStatus.ACTIVE)
            .filter(a -> query == null ||
                a.getItemName().toLowerCase().contains(query.toLowerCase()) ||
                a.getDescription().toLowerCase().contains(query.toLowerCase()))
            .filter(a -> category == null || a.getCategory() == category)
            .filter(a -> minPrice == null ||
                a.getCurrentHighBid().toAmount() >= minPrice)
            .filter(a -> maxPrice == null ||
                a.getCurrentHighBid().toAmount() <= maxPrice)
            .sorted(Comparator.comparing(AuctionListing::getEndTime))
            .collect(Collectors.toList());
    }

    public List<AuctionListing> getEndingSoon(int withinMinutes) {
        LocalDateTime threshold = LocalDateTime.now().plusMinutes(withinMinutes);
        return allAuctions.stream()
            .filter(a -> a.getStatus() == AuctionStatus.ACTIVE)
            .filter(a -> a.getEndTime() != null &&
                         a.getEndTime().isBefore(threshold))
            .sorted(Comparator.comparing(AuctionListing::getEndTime))
            .collect(Collectors.toList());
    }
}

// ============================================================
// 11. USER REGISTRY — SINGLETON
// ============================================================
class UserRegistry {
    private static volatile UserRegistry instance;
    private final ConcurrentHashMap<Long, User>     usersById    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, User>   usersByEmail = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, User>   usersByName  = new ConcurrentHashMap<>();

    private UserRegistry() {}

    public static UserRegistry getInstance() {
        if (instance == null) {
            synchronized (UserRegistry.class) {
                if (instance == null) instance = new UserRegistry();
            }
        }
        return instance;
    }

    // Req 1: register
    public User register(User user) {
        if (usersByEmail.containsKey(user.getEmail())) {
            System.out.println("[UserRegistry] Email already registered: " + user.getEmail());
            return null;
        }
        usersById.put(user.getUserId(), user);
        usersByEmail.put(user.getEmail(), user);
        usersByName.put(user.getUsername(), user);
        System.out.println("[UserRegistry] Registered: " + user);
        return user;
    }

    // Req 1: login
    public User login(String email, String password) {
        User user = usersByEmail.get(email);
        if (user == null) {
            System.out.println("[Login] Email not found: " + email);
            return null;
        }
        if (!user.authenticate(password)) {
            System.out.println("[Login] Wrong password for: " + email);
            return null;
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            System.out.println("[Login] Account suspended: " + email);
            return null;
        }
        System.out.println("[Login] Success: " + user.getUsername());
        return user;
    }

    public User getUser(long id)        { return usersById.get(id); }
    public User getUserByEmail(String e){ return usersByEmail.get(e); }
}

// ============================================================
// 12. AUCTION SERVICE — SINGLETON (Req 8: top-level entry point)
// ============================================================
class AuctionService {
    private static volatile AuctionService instance;

    private final ConcurrentHashMap<Long, AuctionListing>  auctions   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PlaceBidCommand> bidCommands= new ConcurrentHashMap<>();
    private final UserRegistry                             userReg    = UserRegistry.getInstance();
    private final List<AuctionEventObserver>               observers  = new ArrayList<>();
    private final AnalyticsObserver                        analytics  = new AnalyticsObserver();
    private       BidValidationStrategy                    validator  =
        new MinIncrementBidValidation();

    private AuctionService() {
        observers.add(new NotificationObserver(userReg));
        observers.add(analytics);
    }

    public static AuctionService getInstance() {
        if (instance == null) {
            synchronized (AuctionService.class) {
                if (instance == null) instance = new AuctionService();
            }
        }
        return instance;
    }

    public void setValidator(BidValidationStrategy v) {
        this.validator = v;
        System.out.println("[Service] Validator: " + v.getName());
    }

    public void addObserver(AuctionEventObserver o) { observers.add(o); }

    // Req 2: create auction
    public AuctionListing createAuction(AuctionListing listing) {
        auctions.put(listing.getAuctionId(), listing);
        listing.activate();
        observers.forEach(o -> o.onAuctionCreated(listing));
        System.out.println("[Service] Created: " + listing);
        return listing;
    }

    // Req 4: place bid
    public boolean placeBid(long bidderId, long auctionId, double amount) {
        User           bidder  = userReg.getUser(bidderId);
        AuctionListing auction = auctions.get(auctionId);

        if (bidder == null || auction == null) {
            System.out.println("[Service] User or auction not found");
            return false;
        }

        Bid bid = new Bid.Builder(auctionId, bidderId, new Money(amount)).build();

        PlaceBidCommand cmd = new PlaceBidCommand(
            auction, bid, bidder, validator, observers);

        boolean success = cmd.execute();
        if (success) bidCommands.put(bid.getBidId(), cmd);
        return success;
    }

    // Retract a non-leading bid
    public boolean retractBid(long bidId) {
        PlaceBidCommand cmd = bidCommands.get(bidId);
        return cmd != null && cmd.undo();
    }

    // Manually close an auction
    public void closeAuction(long auctionId) {
        AuctionListing auction = auctions.get(auctionId);
        if (auction == null) return;
        auction.closeAuction();
        Bid winner = auction.getLeadingBid();
        observers.forEach(o -> o.onAuctionEnded(auction, winner));

        if (winner != null) {
            User winnerUser = userReg.getUser(winner.getBidderId());
            if (winnerUser != null) winnerUser.incrementWins();
        }
    }

    // Req 3: search
    public AuctionSearch search() { return new AuctionSearch(auctions.values()); }

    // Req 1: register + login delegated to UserRegistry
    public User registerUser(User user)                        { return userReg.register(user); }
    public User login(String email, String password)           { return userReg.login(email, password); }

    public AuctionListing getAuction(long id)   { return auctions.get(id); }
    public User           getUser(long id)      { return userReg.getUser(id); }
    public void           printAnalytics()      { analytics.printReport(); }
}

// ============================================================
// 13. MAIN — DRIVER CODE
// ============================================================
public class OnlineAuctionSystem {
    public static void main(String[] args) throws InterruptedException {

        AuctionService service = AuctionService.getInstance();
        UserRegistry   users   = UserRegistry.getInstance();

        // ===== SCENARIO 1: Req 1 — User registration + login =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: User Registration + Login (Req 1)");
        System.out.println("=".repeat(60));

        User alice = service.registerUser(
            new User.Builder("alice99", "alice@email.com", "pass123")
                .balance(50000).build());

        User bob = service.registerUser(
            new User.Builder("bidmaster_bob", "bob@email.com", "secure456")
                .balance(75000).build());

        User carol = service.registerUser(
            new User.Builder("carol_c", "carol@email.com", "mypass789")
                .balance(30000).build());

        User dave = service.registerUser(
            new User.Builder("davethebidder", "dave@email.com", "dave001")
                .balance(100000).build());

        // Login test
        User loggedIn = service.login("alice@email.com", "pass123");
        User badLogin = service.login("alice@email.com", "wrongpass");
        System.out.println("Valid login: " + (loggedIn != null));
        System.out.println("Bad login:   " + (badLogin != null));

        // ===== SCENARIO 2: Req 2 — Create auction listings =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Create Auction Listings (Req 2)");
        System.out.println("=".repeat(60));

        AuctionListing laptopAuction = service.createAuction(
            AuctionFactory.standard(
                alice.getUserId(),
                "MacBook Pro 16\" M3",
                "Barely used, 9 months old, excellent condition",
                Category.ELECTRONICS, 80000, 60));

        AuctionListing watchAuction = service.createAuction(
            AuctionFactory.withReserve(
                bob.getUserId(),
                "Rolex Submariner 2023",
                "Authentic, box + papers included",
                Category.JEWELRY, 500000, 600000, 120));

        AuctionListing artAuction = service.createAuction(
            AuctionFactory.standard(
                carol.getUserId(),
                "Original Oil Painting — Sunset",
                "Handmade, 24x36 inches, signed",
                Category.ART, 5000, 30));

        AuctionListing flashAuction = service.createAuction(
            AuctionFactory.flash(
                alice.getUserId(), "iPhone 15 Pro 256GB",
                Category.ELECTRONICS, 60000));

        // ===== SCENARIO 3: Req 3 — Search listings =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Search Listings (Req 3)");
        System.out.println("=".repeat(60));

        AuctionSearch searchService = service.search();

        System.out.println("Search by name 'macbook':");
        searchService.searchByName("macbook")
            .forEach(a -> System.out.println("  " + a));

        System.out.println("\nSearch by category ELECTRONICS:");
        searchService.searchByCategory(Category.ELECTRONICS)
            .forEach(a -> System.out.println("  " + a));

        System.out.println("\nSearch by price range 50k-100k:");
        searchService.searchByPriceRange(50000, 100000)
            .forEach(a -> System.out.println("  " + a));

        System.out.println("\nCombined search: electronics, max 90k:");
        searchService.search("", Category.ELECTRONICS, null, 90000.0, true)
            .forEach(a -> System.out.println("  " + a));

        // ===== SCENARIO 4: Req 4 — Place bids =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Bid Placement (Req 4)");
        System.out.println("=".repeat(60));

        long laptopId = laptopAuction.getAuctionId();

        // Normal bidding sequence
        service.placeBid(bob.getUserId(),   laptopId, 82000);   // Bob bids
        service.placeBid(carol.getUserId(), laptopId, 85000);   // Carol outbids
        service.placeBid(bob.getUserId(),   laptopId, 88000);   // Bob reclaims lead
        service.placeBid(dave.getUserId(),  laptopId, 90000);   // Dave jumps in

        System.out.println("\nLaptop auction after 4 bids:");
        System.out.println("  " + laptopAuction);

        // ===== SCENARIO 5: Req 5 — Automatic outbid notifications =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Auto-Notify on Outbid (Req 5)");
        System.out.println("=".repeat(60));

        // Bob retakes the lead — carol and previous bidders get notified automatically
        service.placeBid(bob.getUserId(), laptopId, 92000);

        // ===== SCENARIO 6: Req 7 — Concurrent bidding race condition test =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Concurrent Bids — 5 threads same auction (Req 7)");
        System.out.println("=".repeat(60));

        long artId = artAuction.getAuctionId();

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Boolean> results = new CopyOnWriteArrayList<>();

        double[] bidAmounts = {5200, 5400, 5300, 5600, 5500};
        User[]   bidders    = {alice, bob, carol, dave, alice};

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            pool.submit(() -> {
                boolean ok = service.placeBid(
                    bidders[idx].getUserId(), artId, bidAmounts[idx]);
                results.add(ok);
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("\nArt auction after concurrent bids:");
        System.out.println("  " + artAuction);
        System.out.println("  Bid history (" + artAuction.getBidCount() + " bids):");
        artAuction.getBidHistory().forEach(b -> System.out.println("    " + b));

        // ===== SCENARIO 7: Invalid bids =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Invalid Bid Rejection");
        System.out.println("=".repeat(60));

        // Bid lower than current high
        boolean lowBid = service.placeBid(carol.getUserId(), laptopId, 80000);
        System.out.println("Bid lower than current high: " + lowBid); // false

        // Seller bids on own auction
        boolean sellerBid = service.placeBid(alice.getUserId(), laptopId, 99999);
        System.out.println("Seller bid on own auction:    " + sellerBid); // false

        // Insufficient wallet balance (carol has 30k, laptop is at 92k)
        boolean insufficientFunds = service.placeBid(carol.getUserId(), laptopId, 95000);
        System.out.println("Insufficient funds bid:       " + insufficientFunds); // false

        // ===== SCENARIO 8: Req 6 — Close auction + declare winner =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Close Auction + Declare Winner (Req 6)");
        System.out.println("=".repeat(60));

        service.closeAuction(laptopId);

        System.out.println("\nLaptop auction final state:");
        System.out.println("  Status: " + laptopAuction.getStatus());
        System.out.println("  Winner: bidder #" + laptopAuction.getCurrentHighBidder() +
            " with " + laptopAuction.getCurrentHighBid());

        // ===== SCENARIO 9: Reserve not met =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Reserve Price Not Met (Rolex)");
        System.out.println("=".repeat(60));

        // Watch reserve = 600,000 but bids below it
        service.placeBid(dave.getUserId(), watchAuction.getAuctionId(), 520000);
        service.placeBid(alice.getUserId(), watchAuction.getAuctionId(), 550000);
        service.closeAuction(watchAuction.getAuctionId());
        // Should say "reserve not met"

        // ===== SCENARIO 10: Req 8 — strategy swap =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Strategy Swap at Runtime (Req 8)");
        System.out.println("=".repeat(60));

        service.setValidator(new StandardBidValidation());
        System.out.println("Switched to StandardBidValidation (no min increment)");

        service.setValidator(new MinIncrementBidValidation());
        System.out.println("Switched back to MinIncrementBidValidation");

        // ===== ANALYTICS =====
        service.printAnalytics();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | AuctionService, UserRegistry (double-checked lock)
            State      | AuctionStatus: DRAFT→ACTIVE→ENDED/CANCELLED
                       | BidStatus: PENDING→LEADING→OUTBID→WON/LOST
            Strategy   | BidValidationStrategy (Standard/FundCheck/MinIncrement)
            Observer   | AuctionEventObserver (Notification/Analytics) — Req 5
            Factory    | AuctionFactory (standard/reserve/flash)
            Builder    | AuctionListing.Builder, Bid.Builder, User.Builder
            Command    | PlaceBidCommand: execute()=place, undo()=retract
            Iterator   | AuctionSearch: filtered + sorted stream results
            """);

        System.out.println("===== THREAD-SAFETY (Req 7) =====");
        System.out.println("""
            Class                 | Mechanism                 | Why
            ----------------------|---------------------------|----------------------------
            AuctionListing.placeBid| ReentrantLock(fair=true) | FIFO bid ordering, no race
            User.reserveFunds     | synchronized method       | Atomic wallet deduction
            AuctionService        | ConcurrentHashMap         | Safe concurrent map access
            Bid history           | CopyOnWriteArrayList      | Safe reads >> writes
            """);
    }
}
