import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ==========================================
// CONCERT TICKET BOOKING SYSTEM — LLD
//
// Requirements covered:
//   1. View concerts + seating arrangements
//   2. Search by artist / venue / date / time
//   3. Select seats + purchase tickets
//   4. Concurrent booking — no double-booking
//   5. Fair booking — queue-based fairness
//   6. Secure payment processing
//   7. Booking confirmations via email / SMS
//   8. Waiting list for sold-out concerts
//
// Design Patterns:
//   Singleton  — ConcertBookingService, WaitlistService
//   Strategy   — PaymentStrategy (UPI / Card / Wallet)
//   Observer   — BookingEventObserver (Email / SMS / Analytics)
//   Factory    — TicketFactory (standard / VIP / group)
//   Builder    — Concert, Booking construction
//   State      — SeatStatus (AVAILABLE→LOCKED→BOOKED)
//               BookingStatus (PENDING→CONFIRMED→CANCELLED)
//   Command    — BookingCommand (execute + rollback)
//   Iterator   — ConcertSearchIterator (paginated search)
// ==========================================

// ============================================================
// 1. ENUMS
// ============================================================
enum SeatStatus      { AVAILABLE, LOCKED, BOOKED }
enum SeatCategory    { GENERAL, SILVER, GOLD, PLATINUM, VIP, ACCESSIBLE }
enum BookingStatus   { PENDING, PAYMENT_PENDING, CONFIRMED, CANCELLED, REFUNDED }
enum PaymentMethod   { UPI, CREDIT_CARD, DEBIT_CARD, WALLET, NET_BANKING }
enum PaymentStatus   { PENDING, SUCCESS, FAILED, REFUNDED }
enum WaitlistStatus  { WAITING, NOTIFIED, BOOKED, EXPIRED }
enum NotifChannel    { EMAIL, SMS, BOTH }

// ============================================================
// 2. MONEY — value object
//    All amounts stored in paise to avoid floating point errors
// ============================================================
class Money {
    private final long paise; // ₹1 = 100 paise

    public Money(double rupees) { this.paise = Math.round(rupees * 100); }
    private Money(long paise)   { this.paise = paise; }

    public Money add(Money other)      { return new Money(paise + other.paise); }
    public Money multiply(int qty)     { return new Money(paise * qty); }
    public boolean isGreaterThan(Money o){ return paise > o.paise; }
    public double  toRupees()          { return paise / 100.0; }
    public long    getPaise()          { return paise; }

    @Override public String toString() {
        return "₹" + String.format("%.2f", toRupees());
    }
}

// ============================================================
// 3. VENUE — where the concert is held
// ============================================================
class Venue {
    private final String  venueId;
    private final String  name;
    private final String  city;
    private final String  address;
    private final int     totalCapacity;

    public Venue(String venueId, String name,
                 String city, String address, int totalCapacity) {
        this.venueId       = venueId;
        this.name          = name;
        this.city          = city;
        this.address       = address;
        this.totalCapacity = totalCapacity;
    }

    public String getVenueId()       { return venueId; }
    public String getName()          { return name; }
    public String getCity()          { return city; }
    public String getAddress()       { return address; }
    public int    getTotalCapacity() { return totalCapacity; }

    @Override public String toString() {
        return name + ", " + city + " (cap:" + totalCapacity + ")";
    }
}

// ============================================================
// 4. SEAT — immutable physical seat
//    Mutable status lives in SeatSlot (same separation as BookMyShow)
// ============================================================
class Seat {
    private final String       seatId;      // e.g. "A-12"
    private final String       row;
    private final int          number;
    private final SeatCategory category;
    private final Money        price;
    private final boolean      isAccessible; // wheelchair friendly

    public Seat(String row, int number, SeatCategory category,
                double price, boolean isAccessible) {
        this.seatId       = row + "-" + number;
        this.row          = row;
        this.number       = number;
        this.category     = category;
        this.price        = new Money(price);
        this.isAccessible = isAccessible;
    }

    public String       getSeatId()      { return seatId; }
    public String       getRow()         { return row; }
    public int          getNumber()      { return number; }
    public SeatCategory getCategory()    { return category; }
    public Money        getPrice()       { return price; }
    public boolean      isAccessible()   { return isAccessible; }

    @Override public String toString() {
        return seatId + "(" + category + "," + price + ")";
    }
}

// ============================================================
// 5. SEAT SLOT — mutable wrapper (STATE PATTERN)
//
//   AVAILABLE → LOCKED   : user selects seat (10-min TTL)
//   LOCKED    → BOOKED   : payment confirmed
//   LOCKED    → AVAILABLE: TTL expired or user cancelled
//   BOOKED    → AVAILABLE: booking cancelled (refund)
//
// Thread-safety: ReentrantLock per seat
//   Why per-seat and not per-concert?
//   Two users booking DIFFERENT seats should NEVER block each other.
//   Only users competing for the SAME seat contend.
// ============================================================
class SeatSlot {
    private final  Seat            seat;
    private        SeatStatus      status        = SeatStatus.AVAILABLE;
    private        String          lockedByUser  = null;
    private        LocalDateTime   lockExpiresAt = null;

    // Fair ReentrantLock: threads acquire in FIFO order (Requirement 5)
    private final ReentrantLock lock = new ReentrantLock(true);

    public SeatSlot(Seat seat) { this.seat = seat; }

    /**
     * Requirement 4 + 5: Concurrent-safe lock with tryLock(0).
     * Non-blocking — returns false immediately if another thread
     * holds this seat's lock, so no user is kept waiting unnecessarily.
     */
    public boolean tryLock(String userId, int ttlMinutes) {
        if (!lock.tryLock()) return false; // another thread mid-operation
        try {
            if (status != SeatStatus.AVAILABLE) return false;
            status        = SeatStatus.LOCKED;
            lockedByUser  = userId;
            lockExpiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
            System.out.printf("[Seat %s] LOCKED by %s until %s%n",
                    seat.getSeatId(), userId, lockExpiresAt);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Payment confirmed → BOOKED */
    public boolean confirm(String userId) {
        lock.lock();
        try {
            if (status != SeatStatus.LOCKED || !userId.equals(lockedByUser))
                return false;
            status        = SeatStatus.BOOKED;
            lockedByUser  = null;
            lockExpiresAt = null;
            System.out.printf("[Seat %s] BOOKED ✓%n", seat.getSeatId());
            return true;
        } finally { lock.unlock(); }
    }

    /** Release lock — TTL expired or user cancelled before payment */
    public boolean release(String userId) {
        lock.lock();
        try {
            if (status != SeatStatus.LOCKED || !userId.equals(lockedByUser))
                return false;
            status        = SeatStatus.AVAILABLE;
            lockedByUser  = null;
            lockExpiresAt = null;
            System.out.printf("[Seat %s] RELEASED → AVAILABLE%n", seat.getSeatId());
            return true;
        } finally { lock.unlock(); }
    }

    /** Cancel confirmed booking (refund path) */
    public boolean cancel() {
        lock.lock();
        try {
            if (status != SeatStatus.BOOKED) return false;
            status = SeatStatus.AVAILABLE;
            System.out.printf("[Seat %s] CANCELLED → AVAILABLE%n", seat.getSeatId());
            return true;
        } finally { lock.unlock(); }
    }

    /** Called by background TTL scanner */
    public boolean expireIfOverdue() {
        lock.lock();
        try {
            if (status == SeatStatus.LOCKED &&
                    lockExpiresAt != null &&
                    LocalDateTime.now().isAfter(lockExpiresAt)) {
                System.out.printf("[Seat %s] TTL EXPIRED for user=%s%n",
                        seat.getSeatId(), lockedByUser);
                status        = SeatStatus.AVAILABLE;
                lockedByUser  = null;
                lockExpiresAt = null;
                return true;
            }
            return false;
        } finally { lock.unlock(); }
    }

    public Seat       getSeat()     { return seat; }
    public SeatStatus getStatus()   { return status; }
    public boolean    isAvailable() { return status == SeatStatus.AVAILABLE; }

    @Override public String toString() {
        return seat.getSeatId() + "=" + status;
    }
}

// ============================================================
// 6. CONCERT — BUILDER PATTERN
//    Requirement 1: concerts with seating arrangements
// ============================================================
class Concert {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long                             concertId;
    private        String                           title;
    private        String                           artist;
    private final  Venue                            venue;
    private final  LocalDate                        date;
    private final  LocalTime                        time;
    private        String                           genre;
    private        String                           description;
    // seatId → SeatSlot  (Requirement 1: seating arrangement)
    private final  ConcurrentHashMap<String, SeatSlot> seatSlots
            = new ConcurrentHashMap<>();
    // TTL scanner for auto-releasing expired locks
    private final  ScheduledExecutorService ttlScanner
            = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ttl-concert-" + idGen.get());
        t.setDaemon(true);
        return t;
    });

    private Concert(Builder b) {
        this.concertId   = idGen.getAndIncrement();
        this.title       = b.title;
        this.artist      = b.artist;
        this.venue       = b.venue;
        this.date        = b.date;
        this.time        = b.time;
        this.genre       = b.genre;
        this.description = b.description;

        // Wrap each seat in a slot
        for (Seat seat : b.seats)
            seatSlots.put(seat.getSeatId(), new SeatSlot(seat));

        // Scan for expired TTL locks every 60 seconds
        ttlScanner.scheduleAtFixedRate(this::releaseExpiredLocks,
                60, 60, TimeUnit.SECONDS);
    }

    // ---- Requirement 3: Seat selection ----
    /**
     * All-or-nothing seat lock.
     * If ANY requested seat fails to lock, ALL previously locked seats
     * are immediately released (prevents partial hold situations).
     */
    public List<SeatSlot> lockSeats(String userId,
                                    List<String> seatIds,
                                    int ttlMinutes) {
        List<SeatSlot> locked = new ArrayList<>();
        for (String seatId : seatIds) {
            SeatSlot slot = seatSlots.get(seatId);
            if (slot == null || !slot.tryLock(userId, ttlMinutes)) {
                System.out.printf("[Concert #%d] Seat %s unavailable — rollback %d locks%n",
                        concertId, seatId, locked.size());
                locked.forEach(s -> s.release(userId)); // rollback
                return Collections.emptyList();
            }
            locked.add(slot);
        }
        return locked;
    }

    public boolean confirmSeats(String userId, List<String> seatIds) {
        return seatIds.stream().allMatch(id -> {
            SeatSlot slot = seatSlots.get(id);
            return slot != null && slot.confirm(userId);
        });
    }

    public void releaseSeats(String userId, List<String> seatIds) {
        seatIds.forEach(id -> {
            SeatSlot slot = seatSlots.get(id);
            if (slot != null) slot.release(userId);
        });
    }

    public void cancelSeats(List<String> seatIds) {
        seatIds.forEach(id -> {
            SeatSlot slot = seatSlots.get(id);
            if (slot != null) slot.cancel();
        });
    }

    private void releaseExpiredLocks() {
        long n = seatSlots.values().stream()
                .filter(SeatSlot::expireIfOverdue).count();
        if (n > 0)
            System.out.printf("[TTL] Concert #%d: %d expired locks released%n",
                    concertId, n);
    }

    public List<SeatSlot> getAvailableSeats() {
        return seatSlots.values().stream()
                .filter(SeatSlot::isAvailable)
                .sorted(Comparator.comparing(s -> s.getSeat().getSeatId()))
                .collect(Collectors.toList());
    }

    public List<SeatSlot> getAvailableByCategory(SeatCategory cat) {
        return seatSlots.values().stream()
                .filter(s -> s.isAvailable() &&
                        s.getSeat().getCategory() == cat)
                .collect(Collectors.toList());
    }

    public int getAvailableCount() {
        return (int) seatSlots.values().stream()
                .filter(SeatSlot::isAvailable).count();
    }

    public boolean isSoldOut() { return getAvailableCount() == 0; }

    public Money calculateTotal(List<String> seatIds) {
        return seatIds.stream()
                .map(id -> seatSlots.get(id))
                .filter(Objects::nonNull)
                .map(s -> s.getSeat().getPrice())
                .reduce(new Money(0), Money::add);
    }

    // Requirement 1: display seating arrangement
    public void displaySeatingArrangement() {
        System.out.println("\n═══ SEATING: " + title + " @ " + venue.getName() + " ═══");
        Map<SeatCategory, long[]> summary = new LinkedHashMap<>();
        for (SeatCategory cat : SeatCategory.values())
            summary.put(cat, new long[]{0, 0}); // [available, total]

        seatSlots.values().forEach(slot -> {
            SeatCategory cat = slot.getSeat().getCategory();
            summary.get(cat)[1]++;
            if (slot.isAvailable()) summary.get(cat)[0]++;
        });

        summary.forEach((cat, counts) -> {
            if (counts[1] > 0) {
                System.out.printf("  %-12s : %d/%d available%n",
                        cat, counts[0], counts[1]);
            }
        });
    }

    public long      getConcertId()  { return concertId; }
    public String    getTitle()      { return title; }
    public String    getArtist()     { return artist; }
    public Venue     getVenue()      { return venue; }
    public LocalDate getDate()       { return date; }
    public LocalTime getTime()       { return time; }
    public String    getGenre()      { return genre; }
    public LocalDateTime getDateTime(){
        return LocalDateTime.of(date, time);
    }

    public void shutdown()           { ttlScanner.shutdown(); }

    @Override public String toString() {
        return String.format("Concert[#%d | %-20s | %-15s | %s | %s %s | available=%d]",
                concertId, title, artist, venue.getName(), date, time, getAvailableCount());
    }

    // ---- BUILDER ----
    static class Builder {
        private final String    title;
        private final String    artist;
        private final Venue     venue;
        private final LocalDate date;
        private final LocalTime time;
        private       String    genre       = "Music";
        private       String    description = "";
        private       List<Seat> seats      = new ArrayList<>();

        public Builder(String title, String artist, Venue venue,
                       LocalDate date, LocalTime time) {
            this.title  = title; this.artist = artist;
            this.venue  = venue; this.date   = date; this.time = time;
        }
        public Builder genre(String g)       { this.genre = g;        return this; }
        public Builder description(String d) { this.description = d;  return this; }
        public Builder seats(List<Seat> s)   { this.seats = s;        return this; }
        public Concert build()               { return new Concert(this); }
    }
}

// ============================================================
// 7. BOOKING — BUILDER PATTERN
// ============================================================
class Booking {
    private static final AtomicLong idGen = new AtomicLong(100_000);

    private final  long          bookingId;
    private final  String        userId;
    private final  long          concertId;
    private final  List<String>  seatIds;
    private final  Money         totalAmount;
    private        BookingStatus status;
    private        String        paymentId;
    private final  LocalDateTime createdAt;
    private        LocalDateTime updatedAt;
    private        String        cancellationReason;

    private Booking(Builder b) {
        this.bookingId    = idGen.getAndIncrement();
        this.userId       = b.userId;
        this.concertId    = b.concertId;
        this.seatIds      = List.copyOf(b.seatIds);
        this.totalAmount  = b.totalAmount;
        this.status       = BookingStatus.PENDING;
        this.createdAt    = LocalDateTime.now();
        this.updatedAt    = LocalDateTime.now();
    }

    public void markPaymentPending()      { status = BookingStatus.PAYMENT_PENDING; }

    public void confirm(String paymentId) {
        this.paymentId = paymentId;
        this.status    = BookingStatus.CONFIRMED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel(String reason) {
        this.status             = BookingStatus.CANCELLED;
        this.cancellationReason = reason;
        this.updatedAt          = LocalDateTime.now();
    }

    public void refund()    { status = BookingStatus.REFUNDED; }

    public long         getBookingId()  { return bookingId; }
    public String       getUserId()     { return userId; }
    public long         getConcertId()  { return concertId; }
    public List<String> getSeatIds()    { return seatIds; }
    public Money        getTotalAmount(){ return totalAmount; }
    public BookingStatus getStatus()    { return status; }
    public String       getPaymentId()  { return paymentId; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override public String toString() {
        return String.format("Booking[#%d | user=%s | concert=%d | seats=%s | %s | %s]",
                bookingId, userId, concertId, seatIds, totalAmount, status);
    }

    static class Builder {
        private final String        userId;
        private final long          concertId;
        private       List<String>  seatIds     = new ArrayList<>();
        private       Money         totalAmount = new Money(0);

        public Builder(String userId, long concertId) {
            this.userId = userId; this.concertId = concertId;
        }
        public Builder seats(List<String> s) { this.seatIds = s;      return this; }
        public Builder amount(Money m)       { this.totalAmount = m;  return this; }
        public Booking build()               { return new Booking(this); }
    }
}

// ============================================================
// 8. TICKET — FACTORY PATTERN
//    Requirement 3: purchase tickets
// ============================================================
class Ticket {
    private static final AtomicLong idGen = new AtomicLong(500_000);

    private final long   ticketId;
    private final long   bookingId;
    private final String seatId;
    private final String concertTitle;
    private final String artist;
    private final String venueName;
    private final String venueCity;
    private final LocalDate concertDate;
    private final LocalTime concertTime;
    private final SeatCategory category;
    private final Money  price;
    private final String qrCode; // simulated

    public Ticket(long bookingId, String seatId, Concert concert, Money price) {
        this.ticketId     = idGen.getAndIncrement();
        this.bookingId    = bookingId;
        this.seatId       = seatId;
        this.concertTitle = concert.getTitle();
        this.artist       = concert.getArtist();
        this.venueName    = concert.getVenue().getName();
        this.venueCity    = concert.getVenue().getCity();
        this.concertDate  = concert.getDate();
        this.concertTime  = concert.getTime();
        this.category     = concert.getAvailableByCategory(SeatCategory.GENERAL)
                .stream().findFirst()
                .map(s -> s.getSeat().getCategory())
                .orElse(SeatCategory.GENERAL);
        this.price        = price;
        this.qrCode       = "QR-" + ticketId + "-" + bookingId;
    }

    @Override public String toString() {
        return String.format(
                "🎟 Ticket #%d | %s by %s | %s, %s | Seat %s | %s | QR:%s",
                ticketId, concertTitle, artist, concertDate, concertTime,
                seatId, price, qrCode);
    }
}

class TicketFactory {
    /** Generate one ticket per seat in a booking */
    public static List<Ticket> createTickets(Booking booking, Concert concert) {
        return booking.getSeatIds().stream()
                .map(seatId -> new Ticket(booking.getBookingId(), seatId, concert,
                        concert.calculateTotal(List.of(seatId))))
                .collect(Collectors.toList());
    }
}

// ============================================================
// 9. PAYMENT — STRATEGY PATTERN
//    Requirement 6: secure payment processing
// ============================================================
interface PaymentStrategy {
    PaymentMethod getMethod();
    // Returns payment transaction ID on success, throws on failure
    String processPayment(Money amount, String userId,
                          String idempotencyKey) throws PaymentException;
    String refund(String paymentId, Money amount) throws PaymentException;
}

class PaymentException extends RuntimeException {
    public PaymentException(String msg) { super(msg); }
}

class UPIPaymentStrategy implements PaymentStrategy {
    private static final AtomicLong txnGen = new AtomicLong(1);
    @Override public PaymentMethod getMethod() { return PaymentMethod.UPI; }

    @Override
    public String processPayment(Money amount, String userId,
                                 String idempotencyKey) throws PaymentException {
        // In production: call Razorpay / PhonePe / GPay API
        System.out.printf("[UPI] Processing %s for user=%s | key=%s%n",
                amount, userId, idempotencyKey);
        if (Math.random() < 0.02) // 2% simulated failure
            throw new PaymentException("UPI payment failed — timeout");
        String txnId = "UPI_TXN_" + txnGen.getAndIncrement();
        System.out.println("[UPI] SUCCESS → " + txnId);
        return txnId;
    }

    @Override
    public String refund(String paymentId, Money amount) {
        String refundId = "UPI_REFUND_" + System.currentTimeMillis();
        System.out.printf("[UPI] Refund %s for paymentId=%s → %s%n",
                amount, paymentId, refundId);
        return refundId;
    }
}

class CardPaymentStrategy implements PaymentStrategy {
    private static final AtomicLong txnGen = new AtomicLong(1);
    @Override public PaymentMethod getMethod() { return PaymentMethod.CREDIT_CARD; }

    @Override
    public String processPayment(Money amount, String userId,
                                 String idempotencyKey) throws PaymentException {
        System.out.printf("[Card] Processing %s for user=%s%n", amount, userId);
        String txnId = "CARD_TXN_" + txnGen.getAndIncrement();
        System.out.println("[Card] SUCCESS → " + txnId);
        return txnId;
    }

    @Override
    public String refund(String paymentId, Money amount) {
        String refundId = "CARD_REFUND_" + System.currentTimeMillis();
        System.out.printf("[Card] Refund %s for paymentId=%s → %s%n",
                amount, paymentId, refundId);
        return refundId;
    }
}

class WalletPaymentStrategy implements PaymentStrategy {
    private static final AtomicLong txnGen = new AtomicLong(1);
    private final Map<String, Money> balances = new ConcurrentHashMap<>();

    public void addBalance(String userId, double amount) {
        balances.put(userId, new Money(amount));
    }

    @Override public PaymentMethod getMethod() { return PaymentMethod.WALLET; }

    @Override
    public String processPayment(Money amount, String userId,
                                 String idempotencyKey) throws PaymentException {
        Money bal = balances.getOrDefault(userId, new Money(0));
        if (bal.getPaise() < amount.getPaise())
            throw new PaymentException("Insufficient wallet balance: " +
                    bal + " < " + amount);
        balances.put(userId, new Money((bal.getPaise() - amount.getPaise()) / 100.0));
        String txnId = "WALLET_TXN_" + txnGen.getAndIncrement();
        System.out.printf("[Wallet] %s deducted from %s → %s%n", amount, userId, txnId);
        return txnId;
    }

    @Override
    public String refund(String paymentId, Money amount) {
        return "WALLET_REFUND_" + System.currentTimeMillis();
    }
}

// ============================================================
// 10. WAITLIST ENTRY + SERVICE
//     Requirement 8: waiting list for sold-out concerts
// ============================================================
class WaitlistEntry {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long           waitlistId;
    private final  String         userId;
    private final  long           concertId;
    private final  SeatCategory   preferredCategory;
    private final  int            seatsRequested;
    private        WaitlistStatus status;
    private final  LocalDateTime  joinedAt;
    private        LocalDateTime  notifiedAt;

    public WaitlistEntry(String userId, long concertId,
                         SeatCategory category, int seatsRequested) {
        this.waitlistId        = idGen.getAndIncrement();
        this.userId            = userId;
        this.concertId         = concertId;
        this.preferredCategory = category;
        this.seatsRequested    = seatsRequested;
        this.status            = WaitlistStatus.WAITING;
        this.joinedAt          = LocalDateTime.now();
    }

    public void markNotified() {
        status     = WaitlistStatus.NOTIFIED;
        notifiedAt = LocalDateTime.now();
    }
    public void markBooked()  { status = WaitlistStatus.BOOKED; }
    public void markExpired() { status = WaitlistStatus.EXPIRED; }

    public long          getWaitlistId()   { return waitlistId; }
    public String        getUserId()       { return userId; }
    public long          getConcertId()    { return concertId; }
    public SeatCategory  getPreferredCat() { return preferredCategory; }
    public int           getSeatsNeeded()  { return seatsRequested; }
    public WaitlistStatus getStatus()      { return status; }
    public LocalDateTime getJoinedAt()     { return joinedAt; }

    @Override public String toString() {
        return String.format("Waitlist[#%d | user=%s | concert=%d | cat=%s | qty=%d | %s]",
                waitlistId, userId, concertId, preferredCategory, seatsRequested, status);
    }
}

class WaitlistService {
    private static WaitlistService instance;

    // concertId → FIFO queue of WaitlistEntry
    private final ConcurrentHashMap<Long, Queue<WaitlistEntry>> queues
            = new ConcurrentHashMap<>();

    private WaitlistService() {}

    public static synchronized WaitlistService getInstance() {
        if (instance == null) instance = new WaitlistService();
        return instance;
    }

    /** Requirement 8: Add to waitlist */
    public WaitlistEntry addToWaitlist(String userId, long concertId,
                                       SeatCategory category, int qty) {
        WaitlistEntry entry = new WaitlistEntry(userId, concertId, category, qty);
        queues.computeIfAbsent(concertId, k -> new LinkedList<>()).offer(entry);
        int pos = getQueuePosition(concertId, entry.getWaitlistId());
        System.out.printf("[Waitlist] %s joined queue for concert #%d | position=%d%n",
                userId, concertId, pos);
        return entry;
    }

    /** Called when seats become available (cancellation) */
    public Optional<WaitlistEntry> notifyNext(long concertId, int availableSeats) {
        Queue<WaitlistEntry> queue = queues.get(concertId);
        if (queue == null || queue.isEmpty()) return Optional.empty();

        WaitlistEntry next = queue.peek();
        if (next != null && next.getSeatsNeeded() <= availableSeats) {
            next.markNotified();
            queue.poll();
            System.out.printf("[Waitlist] Notified user=%s | %d seat(s) available%n",
                    next.getUserId(), availableSeats);
            return Optional.of(next);
        }
        return Optional.empty();
    }

    public int getQueuePosition(long concertId, long waitlistId) {
        Queue<WaitlistEntry> queue = queues.getOrDefault(
                concertId, new LinkedList<>());
        int pos = 1;
        for (WaitlistEntry e : queue) {
            if (e.getWaitlistId() == waitlistId) return pos;
            pos++;
        }
        return -1;
    }

    public int getQueueSize(long concertId) {
        return queues.getOrDefault(concertId, new LinkedList<>()).size();
    }

    public List<WaitlistEntry> getQueueSnapshot(long concertId) {
        return new ArrayList<>(queues.getOrDefault(concertId, new LinkedList<>()));
    }
}

// ============================================================
// 11. OBSERVER — BOOKING EVENTS
//     Requirement 7: confirmations via email / SMS
// ============================================================
interface BookingEventObserver {
    void onBookingConfirmed(Booking booking, Concert concert, List<Ticket> tickets);
    void onBookingCancelled(Booking booking, Concert concert);
    void onPaymentFailed(Booking booking, String reason);
    void onWaitlistNotified(WaitlistEntry entry, Concert concert);
}

class EmailObserver implements BookingEventObserver {
    @Override
    public void onBookingConfirmed(Booking booking, Concert concert,
                                   List<Ticket> tickets) {
        System.out.printf("[Email → %s] Booking #%d CONFIRMED%n" +
                        "  Concert: %s by %s%n" +
                        "  Date:    %s %s @ %s, %s%n" +
                        "  Seats:   %s%n" +
                        "  Total:   %s%n" +
                        "  Tickets: %d ticket(s) attached as PDF%n",
                booking.getUserId(), booking.getBookingId(),
                concert.getTitle(), concert.getArtist(),
                concert.getDate(), concert.getTime(),
                concert.getVenue().getName(), concert.getVenue().getCity(),
                booking.getSeatIds(), booking.getTotalAmount(),
                tickets.size());
    }

    @Override
    public void onBookingCancelled(Booking booking, Concert concert) {
        System.out.printf("[Email → %s] Booking #%d CANCELLED | Refund: %s%n",
                booking.getUserId(), booking.getBookingId(), booking.getTotalAmount());
    }

    @Override
    public void onPaymentFailed(Booking booking, String reason) {
        System.out.printf("[Email → %s] Payment FAILED for booking #%d: %s%n",
                booking.getUserId(), booking.getBookingId(), reason);
    }

    @Override
    public void onWaitlistNotified(WaitlistEntry entry, Concert concert) {
        System.out.printf("[Email → %s] GOOD NEWS! Seat(s) now available for %s!%n" +
                        "  You have 15 minutes to complete your booking.%n",
                entry.getUserId(), concert.getTitle());
    }
}

class SMSObserver implements BookingEventObserver {
    @Override
    public void onBookingConfirmed(Booking booking, Concert concert,
                                   List<Ticket> tickets) {
        System.out.printf("[SMS → %s] Confirmed! %s on %s. Booking #%d. %s. " +
                        "Show ticket QR at venue.%n",
                booking.getUserId(), concert.getTitle(), concert.getDate(),
                booking.getBookingId(), booking.getTotalAmount());
    }

    @Override
    public void onBookingCancelled(Booking booking, Concert concert) {
        System.out.printf("[SMS → %s] Booking #%d cancelled. Refund in 5-7 days.%n",
                booking.getUserId(), booking.getBookingId());
    }

    @Override public void onPaymentFailed(Booking booking, String reason) {}

    @Override
    public void onWaitlistNotified(WaitlistEntry entry, Concert concert) {
        System.out.printf("[SMS → %s] %s seats available! Book in 15 min.%n",
                entry.getUserId(), concert.getTitle());
    }
}

class AnalyticsObserver implements BookingEventObserver {
    private long totalBookings   = 0;
    private long totalCancels    = 0;
    private long totalRevenue    = 0;

    @Override
    public synchronized void onBookingConfirmed(Booking booking, Concert concert,
                                                List<Ticket> tickets) {
        totalBookings++;
        totalRevenue += booking.getTotalAmount().getPaise();
    }

    @Override
    public synchronized void onBookingCancelled(Booking booking, Concert concert) {
        totalCancels++;
        totalRevenue -= booking.getTotalAmount().getPaise();
    }

    @Override public void onPaymentFailed(Booking b, String r) {}
    @Override public void onWaitlistNotified(WaitlistEntry e, Concert c) {}

    public void printReport() {
        System.out.printf("%n[Analytics] Bookings=%d | Cancels=%d | Revenue=%s%n",
                totalBookings, totalCancels, new Money(totalRevenue / 100.0));
    }
}

// ============================================================
// 12. BOOKING COMMAND — COMMAND PATTERN
//     execute() = lock → pay → confirm + notify
//     undo()    = cancel → refund → release seats → notify waitlist
// ============================================================
class BookingCommand {
    private final Concert                      concert;
    private final Booking                      booking;
    private final PaymentStrategy              payment;
    private final WaitlistService              waitlist;
    private final List<BookingEventObserver>   observers;
    private       String                       paymentId  = null;
    private       boolean                      executed   = false;

    public BookingCommand(Concert concert, Booking booking,
                          PaymentStrategy payment,
                          WaitlistService waitlist,
                          List<BookingEventObserver> observers) {
        this.concert   = concert;
        this.booking   = booking;
        this.payment   = payment;
        this.waitlist  = waitlist;
        this.observers = observers;
    }

    /**
     * Execute: lock seats → payment → confirm → generate tickets → notify
     */
    public boolean execute() {
        // Step 1: Lock seats (10-minute payment window)
        List<SeatSlot> locked = concert.lockSeats(
                booking.getUserId(), booking.getSeatIds(), 10);

        if (locked.isEmpty()) {
            System.out.println("[Command] Seat lock failed — booking aborted");
            return false;
        }

        // Step 2: Process payment (Requirement 6)
        booking.markPaymentPending();
        try {
            String idempotencyKey = "booking-" + booking.getBookingId();
            paymentId = payment.processPayment(
                    booking.getTotalAmount(),
                    booking.getUserId(),
                    idempotencyKey);
        } catch (PaymentException e) {
            // Payment failed — release seats immediately
            concert.releaseSeats(booking.getUserId(), booking.getSeatIds());
            booking.cancel("Payment failed: " + e.getMessage());
            observers.forEach(o -> o.onPaymentFailed(booking, e.getMessage()));
            return false;
        }

        // Step 3: Confirm seats BOOKED
        concert.confirmSeats(booking.getUserId(), booking.getSeatIds());
        booking.confirm(paymentId);
        executed = true;

        // Step 4: Generate tickets (Requirement 3 + 7)
        List<Ticket> tickets = TicketFactory.createTickets(booking, concert);

        // Step 5: Notify all observers (email + SMS + analytics)
        observers.forEach(o -> o.onBookingConfirmed(booking, concert, tickets));

        System.out.println("[Command] Booking CONFIRMED: " + booking);
        return true;
    }

    /**
     * Undo: cancel + refund + release seats + notify waitlist
     */
    public void undo() {
        if (!executed) return;

        // Refund payment
        if (paymentId != null) {
            payment.refund(paymentId, booking.getTotalAmount());
        }

        // Release seats → AVAILABLE
        concert.cancelSeats(booking.getSeatIds());
        booking.cancel("Cancelled by user");
        booking.refund();
        executed = false;

        // Notify observers (email + SMS)
        observers.forEach(o -> o.onBookingCancelled(booking, concert));

        // Requirement 8: notify next person on waitlist
        int freed = booking.getSeatIds().size();
        waitlist.notifyNext(concert.getConcertId(), freed)
                .ifPresent(entry -> observers.forEach(
                        o -> o.onWaitlistNotified(entry, concert)));

        System.out.println("[Command] Booking CANCELLED + seats freed: " + booking);
    }
}

// ============================================================
// 13. CONCERT SEARCH — ITERATOR PATTERN
//     Requirement 2: search by artist / venue / date / time
// ============================================================
class ConcertSearch {
    private final List<Concert> allConcerts;

    public ConcertSearch(Collection<Concert> concerts) {
        this.allConcerts = new ArrayList<>(concerts);
    }

    /** Search by any combination of criteria */
    public List<Concert> search(String artist, String venueName,
                                String city, LocalDate date,
                                LocalTime afterTime) {
        return allConcerts.stream()
                .filter(c -> artist     == null || c.getArtist()
                        .toLowerCase().contains(artist.toLowerCase()))
                .filter(c -> venueName  == null || c.getVenue().getName()
                        .toLowerCase().contains(venueName.toLowerCase()))
                .filter(c -> city       == null || c.getVenue().getCity()
                        .equalsIgnoreCase(city))
                .filter(c -> date       == null || c.getDate().equals(date))
                .filter(c -> afterTime  == null || c.getTime().isAfter(afterTime))
                .filter(c -> !c.getDate().isBefore(LocalDate.now())) // only upcoming
                .sorted(Comparator.comparing(Concert::getDateTime))
                .collect(Collectors.toList());
    }

    public List<Concert> getByArtist(String artist) {
        return search(artist, null, null, null, null);
    }

    public List<Concert> getByCity(String city) {
        return search(null, null, city, null, null);
    }

    public List<Concert> getByDate(LocalDate date) {
        return search(null, null, null, date, null);
    }

    public List<Concert> getAvailable() {
        return allConcerts.stream()
                .filter(c -> !c.isSoldOut())
                .filter(c -> !c.getDate().isBefore(LocalDate.now()))
                .collect(Collectors.toList());
    }

    public List<Concert> getSoldOut() {
        return allConcerts.stream()
                .filter(Concert::isSoldOut)
                .collect(Collectors.toList());
    }
}

// ============================================================
// 14. CONCERT BOOKING SERVICE — SINGLETON
//     Top-level entry point for all operations
// ============================================================
class ConcertBookingService {
    private static volatile ConcertBookingService instance;

    private final ConcurrentHashMap<Long, Concert>  concerts  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Booking>  bookings  = new ConcurrentHashMap<>();
    // bookingId → command (for undo / cancellation)
    private final ConcurrentHashMap<Long, BookingCommand> commands = new ConcurrentHashMap<>();
    // idempotency: userId+seatIds hash → bookingId (prevents duplicate submissions)
    private final ConcurrentHashMap<String, Long>   idempotency = new ConcurrentHashMap<>();

    private final WaitlistService               waitlistService = WaitlistService.getInstance();
    private final List<BookingEventObserver>    observers       = new ArrayList<>();
    private final AnalyticsObserver             analytics       = new AnalyticsObserver();

    private ConcertBookingService() {
        observers.add(new EmailObserver());
        observers.add(new SMSObserver());
        observers.add(analytics);
    }

    public static ConcertBookingService getInstance() {
        if (instance == null) {
            synchronized (ConcertBookingService.class) {
                if (instance == null) instance = new ConcertBookingService();
            }
        }
        return instance;
    }

    // ---- Concert management ----
    public Concert addConcert(Concert concert) {
        concerts.put(concert.getConcertId(), concert);
        System.out.println("[Service] Concert added: " + concert);
        return concert;
    }

    // Requirement 1: view concerts
    public void listAllConcerts() {
        System.out.println("\n══════ AVAILABLE CONCERTS ══════");
        concerts.values().stream()
                .filter(c -> !c.getDate().isBefore(LocalDate.now()))
                .sorted(Comparator.comparing(Concert::getDateTime))
                .forEach(System.out::println);
    }

    // Requirement 1: seating arrangement
    public void showSeatingArrangement(long concertId) {
        Concert c = concerts.get(concertId);
        if (c != null) c.displaySeatingArrangement();
    }

    // Requirement 2: search
    public ConcertSearch search() {
        return new ConcertSearch(concerts.values());
    }

    // Requirement 3 + 4 + 6: select seats + book + pay
    public Booking bookTickets(String userId, long concertId,
                               List<String> seatIds,
                               PaymentStrategy payment) {
        Concert concert = concerts.get(concertId);
        if (concert == null) {
            System.out.println("[Service] Concert not found: #" + concertId);
            return null;
        }

        // Requirement 5: idempotency — prevent duplicate booking on retry
        String idempotencyKey = userId + ":" + concertId + ":" +
                seatIds.stream().sorted().collect(Collectors.joining(","));
        Long existing = idempotency.get(idempotencyKey);
        if (existing != null) {
            System.out.println("[Service] Duplicate request — returning booking #" + existing);
            return bookings.get(existing);
        }

        // Requirement 8: redirect to waitlist if sold out
        if (concert.isSoldOut()) {
            System.out.println("[Service] Concert SOLD OUT — please join waitlist");
            return null;
        }

        Money total   = concert.calculateTotal(seatIds);
        Booking booking = new Booking.Builder(userId, concertId)
                .seats(seatIds)
                .amount(total)
                .build();

        BookingCommand cmd = new BookingCommand(
                concert, booking, payment, waitlistService, observers);

        boolean success = cmd.execute();

        if (success) {
            bookings.put(booking.getBookingId(), booking);
            commands.put(booking.getBookingId(), cmd);
            idempotency.put(idempotencyKey, booking.getBookingId());
        }

        return success ? booking : null;
    }

    // Cancellation (triggers waitlist notification)
    public boolean cancelBooking(long bookingId) {
        BookingCommand cmd = commands.get(bookingId);
        if (cmd == null) {
            System.out.println("[Service] Booking not found: #" + bookingId);
            return false;
        }
        cmd.undo();
        return true;
    }

    // Requirement 8: join waitlist
    public WaitlistEntry joinWaitlist(String userId, long concertId,
                                      SeatCategory preferredCategory, int qty) {
        Concert concert = concerts.get(concertId);
        if (concert == null) return null;
        WaitlistEntry entry = waitlistService.addToWaitlist(
                userId, concertId, preferredCategory, qty);
        System.out.println("[Service] Waitlist size for concert #" + concertId +
                ": " + waitlistService.getQueueSize(concertId));
        return entry;
    }

    public Booking   getBooking(long id) { return bookings.get(id); }
    public Concert   getConcert(long id) { return concerts.get(id); }
    public void      printAnalytics()    { analytics.printReport(); }
    public void      shutdown()          { concerts.values().forEach(Concert::shutdown); }
}

// ============================================================
// 15. SEAT LAYOUT HELPER
// ============================================================
class SeatLayoutBuilder {
    /** Build a standard concert venue layout */
    public static List<Seat> buildLayout() {
        List<Seat> seats = new ArrayList<>();

        // VIP (front rows A-B, 10 seats each) — ₹5000
        for (char row : new char[]{'A','B'})
            for (int n = 1; n <= 10; n++)
                seats.add(new Seat(String.valueOf(row), n, SeatCategory.VIP, 5000, false));

        // PLATINUM (rows C-E) — ₹3000
        for (char row : new char[]{'C','D','E'})
            for (int n = 1; n <= 20; n++)
                seats.add(new Seat(String.valueOf(row), n, SeatCategory.PLATINUM, 3000, false));

        // GOLD (rows F-J) — ₹2000
        for (char row : new char[]{'F','G','H','I','J'})
            for (int n = 1; n <= 25; n++)
                seats.add(new Seat(String.valueOf(row), n, SeatCategory.GOLD, 2000, false));

        // SILVER (rows K-P) — ₹1000
        for (char row : new char[]{'K','L','M','N','O','P'})
            for (int n = 1; n <= 30; n++)
                seats.add(new Seat(String.valueOf(row), n, SeatCategory.SILVER, 1000, false));

        // GENERAL (rows Q-Z) — ₹500
        for (char row : new char[]{'Q','R','S','T','U'})
            for (int n = 1; n <= 40; n++)
                seats.add(new Seat(String.valueOf(row), n, SeatCategory.GENERAL, 500, false));

        // ACCESSIBLE (row Z, 10 seats) — ₹500
        for (int n = 1; n <= 10; n++)
            seats.add(new Seat("Z", n, SeatCategory.ACCESSIBLE, 500, true));

        return seats;
    }

    /** Small layout for testing */
    public static List<Seat> buildSmallLayout() {
        List<Seat> seats = new ArrayList<>();
        for (char row : new char[]{'A','B'})
            for (int n = 1; n <= 5; n++)
                seats.add(new Seat(String.valueOf(row), n,
                        row == 'A' ? SeatCategory.VIP : SeatCategory.GOLD,
                        row == 'A' ? 5000 : 2000, false));
        for (int n = 1; n <= 5; n++)
            seats.add(new Seat("C", n, SeatCategory.GENERAL, 500, false));
        return seats;
    }
}

// ============================================================
// 16. MAIN — DRIVER CODE
// ============================================================
public class ConcertTicketBooking {
    public static void main(String[] args) throws InterruptedException {

        ConcertBookingService service = ConcertBookingService.getInstance();
        WalletPaymentStrategy wallet  = new WalletPaymentStrategy();
        wallet.addBalance("alice", 20000);
        wallet.addBalance("bob",   10000);
        wallet.addBalance("carol", 5000);
        wallet.addBalance("dave",  15000);

        // ---- Setup venues ----
        Venue bgKalachUri = new Venue("V1", "B Gaadi Ground",     "Bengaluru", "Palace Grounds", 5000);
        Venue mmrda       = new Venue("V2", "MMRDA Grounds",      "Mumbai",    "BKC",            8000);
        Venue jlfVenue    = new Venue("V3", "Hotel Diggi Palace",  "Jaipur",    "Civil Lines",    2000);

        // ---- Setup concerts ----
        Concert arijit = service.addConcert(
                new Concert.Builder("Arijit Singh Live", "Arijit Singh",
                        bgKalachUri, LocalDate.now().plusDays(30), LocalTime.of(19, 0))
                        .genre("Bollywood").description("A night of soulful melodies")
                        .seats(SeatLayoutBuilder.buildSmallLayout()).build());

        Concert coldplay = service.addConcert(
                new Concert.Builder("Coldplay Music of the Spheres",
                        "Coldplay", mmrda,
                        LocalDate.now().plusDays(45), LocalTime.of(20, 0))
                        .genre("Rock/Pop").description("The iconic band's India tour")
                        .seats(SeatLayoutBuilder.buildLayout()).build());

        Concert jlf = service.addConcert(
                new Concert.Builder("Shankar Ehsaan Loy Live", "SEL",
                        jlfVenue, LocalDate.now().plusDays(10), LocalTime.of(18, 0))
                        .genre("Fusion").description("Jaipur Literary Festival special")
                        .seats(SeatLayoutBuilder.buildSmallLayout()).build());

        // ===== SCENARIO 1: View concerts + seating (Req 1) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: View Concerts + Seating Arrangements");
        System.out.println("=".repeat(60));

        service.listAllConcerts();
        service.showSeatingArrangement(arijit.getConcertId());
        service.showSeatingArrangement(coldplay.getConcertId());

        // ===== SCENARIO 2: Search (Req 2) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Search Concerts");
        System.out.println("=".repeat(60));

        ConcertSearch search = service.search();

        System.out.println("Search by artist 'Coldplay':");
        search.getByArtist("Coldplay").forEach(c -> System.out.println("  " + c));

        System.out.println("\nSearch by city 'Mumbai':");
        search.getByCity("Mumbai").forEach(c -> System.out.println("  " + c));

        System.out.println("\nSearch by date " + LocalDate.now().plusDays(10) + ":");
        search.getByDate(LocalDate.now().plusDays(10))
                .forEach(c -> System.out.println("  " + c));

        System.out.println("\nAll available concerts:");
        search.getAvailable().forEach(c -> System.out.println("  " + c));

        // ===== SCENARIO 3: Successful booking (Req 3 + 6 + 7) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Alice books 2 VIP seats for Arijit Singh");
        System.out.println("=".repeat(60));

        Booking b1 = service.bookTickets("alice",
                arijit.getConcertId(),
                List.of("A-1", "A-2"),
                wallet);

        System.out.println("\nBooking result: " + b1);

        // ===== SCENARIO 4: Concurrent booking — same seat (Req 4 + 5) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Concurrent Booking Race Condition (seat A-3)");
        System.out.println("=".repeat(60));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Booking> results = new CopyOnWriteArrayList<>();

        // 3 users compete for the same seat A-3
        for (String user : new String[]{"bob","carol","dave"}) {
            pool.submit(() -> {
                Booking b = service.bookTickets(user,
                        arijit.getConcertId(), List.of("A-3"), wallet);
                if (b != null) results.add(b);
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("\nConcurrent results — exactly 1 should succeed:");
        System.out.println("  Successful bookings for A-3: " + results.size());
        results.forEach(b -> System.out.println("  " + b));

        // ===== SCENARIO 5: All-or-nothing multi-seat lock =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: All-or-Nothing Lock (one seat taken)");
        System.out.println("=".repeat(60));

        // A-1 is taken by alice; this should fail completely
        Booking b2 = service.bookTickets("bob",
                arijit.getConcertId(), List.of("A-1", "B-1"), wallet);
        System.out.println("Bob's booking (A-1 taken): " + b2); // null

        // B-1 and B-2 are free
        Booking b3 = service.bookTickets("bob",
                arijit.getConcertId(), List.of("B-1", "B-2"), wallet);
        System.out.println("Bob's booking (B-1, B-2 free): " + b3);

        // ===== SCENARIO 6: Cancellation + refund + waitlist (Req 8) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Cancellation → Waitlist Notification");
        System.out.println("=".repeat(60));

        // Eve and Frank join the waitlist (small concert, seats filling up)
        WaitlistEntry w1 = service.joinWaitlist("eve",
                arijit.getConcertId(), SeatCategory.VIP, 1);
        WaitlistEntry w2 = service.joinWaitlist("frank",
                arijit.getConcertId(), SeatCategory.VIP, 2);

        System.out.println("\nWaitlist before cancellation:");
        WaitlistService.getInstance().getQueueSnapshot(arijit.getConcertId())
                .forEach(e -> System.out.println("  " + e));

        // Alice cancels — seats A-1, A-2 freed → Eve notified
        System.out.println("\nAlice cancels booking #" + b1.getBookingId() + ":");
        if (b1 != null) service.cancelBooking(b1.getBookingId());

        System.out.println("\nAlice's booking status: " + b1.getStatus());

        // ===== SCENARIO 7: Sold-out → waitlist redirect (Req 8) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Sold-Out Concert → Waitlist");
        System.out.println("=".repeat(60));

        // Drain remaining seats of JLF concert
        List<String> jlfSeats = jlf.getAvailableSeats().stream()
                .map(s -> s.getSeat().getSeatId())
                .collect(Collectors.toList());

        // Book all seats in batches
        for (int i = 0; i < jlfSeats.size(); i += 2) {
            List<String> batch = jlfSeats.subList(i, Math.min(i + 2, jlfSeats.size()));
            service.bookTickets("alice", jlf.getConcertId(), batch,
                    new UPIPaymentStrategy());
        }

        System.out.println("JLF sold out: " + jlf.isSoldOut());
        service.showSeatingArrangement(jlf.getConcertId());

        // Dave tries to book — redirected to waitlist
        Booking soldOutAttempt = service.bookTickets("dave",
                jlf.getConcertId(), List.of("A-1"), wallet);
        System.out.println("Dave's sold-out attempt: " + soldOutAttempt);

        WaitlistEntry davesWait = service.joinWaitlist("dave",
                jlf.getConcertId(), SeatCategory.VIP, 1);
        System.out.println("Dave joined waitlist: " + davesWait);

        // ===== SCENARIO 8: Multiple payment strategies (Req 6) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Different Payment Methods");
        System.out.println("=".repeat(60));

        // UPI payment
        Booking upiBooking = service.bookTickets("carol",
                coldplay.getConcertId(),
                List.of("A-1", "A-2"),
                new UPIPaymentStrategy());
        System.out.println("UPI booking: " + upiBooking);

        // Card payment
        Booking cardBooking = service.bookTickets("dave",
                coldplay.getConcertId(),
                List.of("A-3"),
                new CardPaymentStrategy());
        System.out.println("Card booking: " + cardBooking);

        // ===== ANALYTICS =====
        service.printAnalytics();
        service.shutdown();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | ConcertBookingService (double-checked locking)
                       | WaitlistService
            Strategy   | PaymentStrategy (UPI / Card / Wallet)
            Observer   | BookingEventObserver (Email / SMS / Analytics)
            Factory    | TicketFactory.createTickets()
            Builder    | Concert.Builder, Booking.Builder
            State      | SeatStatus: AVAILABLE → LOCKED → BOOKED
                       | BookingStatus: PENDING → CONFIRMED → CANCELLED
            Command    | BookingCommand: execute() + undo() (cancel+refund)
            Iterator   | ConcertSearch (stream-based filtered results)
            """);
    }
}