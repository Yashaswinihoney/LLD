import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ============================================================
// BOOKMYSHOW — LLD (Thread-Safe, Pattern-Annotated)
//
// Design Patterns used:
//   1. Singleton  — BookMyShowService (one global entry point)
//   2. State      — SeatStatus (AVAILABLE → LOCKED → BOOKED)
//   3. Builder    — Booking.Builder (immutable booking object)
//   4. Observer   — BookingEventObserver (notification + analytics)
//   5. Factory    — ShowFactory (create shows with typed seat layouts)
//   6. Command    — BookingCommand (book / cancel, keeps an audit trail)
//
// Thread-Safety approach:
//   - Per-seat ReentrantLock (fine-grained) instead of
//     synchronized(this) on the whole Show object.
//     Why: synchronized(Show) means ALL seat operations across
//     the ENTIRE show block each other. With 500 seats,
//     that's unnecessary. Two users booking seat-1 and
//     seat-2 have no conflict — they should not block each other.
//   - TTL-based seat lock: seat held for 10 minutes during
//     payment; auto-released by a background scheduler.
//   - ConcurrentHashMap for the show registry.
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================

enum SeatCategory { SILVER, GOLD, PLATINUM }

/**
 * STATE PATTERN — SeatStatus is the state of a single seat.
 *
 * Valid transitions:
 *   AVAILABLE → LOCKED   (user selects seat, payment pending)
 *   LOCKED    → BOOKED   (payment successful)
 *   LOCKED    → AVAILABLE(payment timed out or user cancelled)
 *   BOOKED    → AVAILABLE(cancellation / refund)
 *
 * AVAILABLE → BOOKED directly is intentionally NOT allowed.
 * Every booking must go through LOCKED first so we hold the
 * seat during the payment window.
 */
enum SeatStatus { AVAILABLE, LOCKED, BOOKED }

enum BookingStatus { CONFIRMED, CANCELLED, PAYMENT_PENDING }

// ============================================================
// 2. SEAT — immutable value object
//
// A Seat carries only static information (id, category, price).
// Mutable state (AVAILABLE / LOCKED / BOOKED) lives in
// SeatSlot so Seat itself never changes.
// ============================================================
class Seat {
    private final int          id;
    private final SeatCategory category;
    private final double       price;
    private final String       rowLabel;   // "A", "B", "C" ...
    private final int          colNumber;  // 1, 2, 3 ...

    public Seat(int id, SeatCategory category, double price,
                String rowLabel, int colNumber) {
        this.id        = id;
        this.category  = category;
        this.price     = price;
        this.rowLabel  = rowLabel;
        this.colNumber = colNumber;
    }

    public int          getId()        { return id; }
    public SeatCategory getCategory()  { return category; }
    public double       getPrice()     { return price; }
    public String       getSeatCode()  { return rowLabel + colNumber; }

    @Override public String toString() {
        return getSeatCode() + "(" + category + ",₹" + (int)price + ")";
    }
}

// ============================================================
// 3. SEAT SLOT — mutable wrapper around Seat
//
// This is the unit of concurrency. Each slot has its OWN lock.
// Two users competing for the SAME seat → one waits.
// Two users competing for DIFFERENT seats → no waiting at all.
//
// Why ReentrantLock over synchronized?
//   - tryLock(0) lets us immediately fail without waiting when
//     a seat is already being processed by another thread.
//     With synchronized, tryLock semantics are unavailable.
//   - Better monitoring — ReentrantLock exposes isLocked(),
//     getQueueLength() useful for dashboards.
// ============================================================
class SeatSlot {
    private final Seat           seat;   // seat instance
    private       SeatStatus     status        = SeatStatus.AVAILABLE;
    private       String         lockedByUser  = null;  // userId holding the lock
    private       LocalDateTime  lockExpiresAt = null;  // TTL for the lock

    // One lock per seat — fine-grained concurrency
    private final ReentrantLock lock = new ReentrantLock(true); // fair = FIFO order

    public SeatSlot(Seat seat) { this.seat = seat; }

    /**
     * Attempt to LOCK this seat for a user (seat selection step).
     *
     * Uses tryLock(0) — if another thread already holds this seat's
     * lock we return false IMMEDIATELY rather than waiting.
     * This prevents users from blocking each other during selection.
     *
     * @return true if lock acquired (seat was AVAILABLE)
     */
    public boolean tryLock(String userId, int ttlMinutes) {
        // tryLock(0) = non-blocking attempt
        if (!lock.tryLock()) {
            // Another thread is mid-operation on this seat right now
            return false;
        }
        try {
            if (status != SeatStatus.AVAILABLE) return false;

            // STATE TRANSITION: AVAILABLE → LOCKED
            status        = SeatStatus.LOCKED;
            lockedByUser  = userId;
            lockExpiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
            System.out.printf("[SeatSlot] Seat %s LOCKED by user=%s until %s%n",
                seat.getSeatCode(), userId, lockExpiresAt);
            return true;

        } finally {
            lock.unlock(); // Always release the ReentrantLock after state update
        }
    }

    /**
     * Confirm booking after payment success.
     * STATE TRANSITION: LOCKED → BOOKED
     */
    public boolean confirm(String userId) {
        lock.lock();
        try {
            if (status != SeatStatus.LOCKED || !userId.equals(lockedByUser)) {
                return false;
            }
            // STATE TRANSITION: LOCKED → BOOKED
            status       = SeatStatus.BOOKED;
            lockedByUser = null;
            lockExpiresAt= null;
            System.out.printf("[SeatSlot] Seat %s BOOKED by user=%s%n",
                seat.getSeatCode(), userId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Release lock — called when:
     *   a) Payment timed out (TTL expired)
     *   b) User explicitly cancelled selection
     * STATE TRANSITION: LOCKED → AVAILABLE
     */
    public boolean release(String userId) {
        lock.lock();
        try {
            if (status != SeatStatus.LOCKED || !userId.equals(lockedByUser)) {
                return false;
            }
            // STATE TRANSITION: LOCKED → AVAILABLE
            status        = SeatStatus.AVAILABLE;
            lockedByUser  = null;
            lockExpiresAt = null;
            System.out.printf("[SeatSlot] Seat %s RELEASED (back to AVAILABLE)%n",
                seat.getSeatCode());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cancel a confirmed booking — refund path.
     * STATE TRANSITION: BOOKED → AVAILABLE
     */
    public boolean cancel() {
        lock.lock();
        try {
            if (status != SeatStatus.BOOKED) return false;
            // STATE TRANSITION: BOOKED → AVAILABLE
            status = SeatStatus.AVAILABLE;
            System.out.printf("[SeatSlot] Seat %s CANCELLED → AVAILABLE%n",
                seat.getSeatCode());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * TTL expiry check — called by the background scheduler.
     * If the lock has expired and seat is still LOCKED, release it.
     */
    public boolean expireIfOverdue() {
        lock.lock();
        try {
            if (status == SeatStatus.LOCKED &&
                lockExpiresAt != null &&
                LocalDateTime.now().isAfter(lockExpiresAt)) {

                System.out.printf("[SeatSlot] Seat %s lock EXPIRED for user=%s%n",
                    seat.getSeatCode(), lockedByUser);
                status        = SeatStatus.AVAILABLE;
                lockedByUser  = null;
                lockExpiresAt = null;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    // ---- Getters (non-locking reads for display — acceptable slight staleness) ----
    public Seat       getSeat()       { return seat; }
    public SeatStatus getStatus()     { return status; }
    public boolean    isAvailable()   { return status == SeatStatus.AVAILABLE; }
    public String     getLockedBy()   { return lockedByUser; }

    @Override public String toString() {
        return seat.getSeatCode() + "=" + status;
    }
}

// ============================================================
// 4. MOVIE — immutable
// ============================================================
class Movie {
    private final String title;
    private final int    durationMins;
    private final String genre;
    private final String language;
    private final String rating; // U, UA, A

    public Movie(String title, int durationMins,
                 String genre, String language, String rating) {
        this.title       = title;
        this.durationMins= durationMins;
        this.genre       = genre;
        this.language    = language;
        this.rating      = rating;
    }

    public String getTitle()       { return title; }
    public int    getDurationMins(){ return durationMins; }
    public String getLanguage()    { return language; }

    @Override public String toString() {
        return title + " (" + language + ", " + durationMins + "min, " + rating + ")";
    }
}

// ============================================================
// 5. THEATRE + SCREEN — venue entities
// ============================================================
class Screen {
    private final int    screenId;
    private final String name;
    private final int    totalSeats;

    public Screen(int screenId, String name, int totalSeats) {
        this.screenId  = screenId;
        this.name      = name;
        this.totalSeats= totalSeats;
    }

    public int    getScreenId()   { return screenId; }
    public String getName()       { return name; }
    public int    getTotalSeats() { return totalSeats; }
}

class Theatre {
    private final int         theatreId;
    private final String      name;
    private final String      city;
    private final List<Screen> screens;

    public Theatre(int theatreId, String name,
                   String city, List<Screen> screens) {
        this.theatreId = theatreId;
        this.name      = name;
        this.city      = city;
        this.screens   = screens;
    }

    public int    getTheatreId() { return theatreId; }
    public String getName()      { return name; }
    public String getCity()      { return city; }

    @Override public String toString() {
        return name + ", " + city;
    }
}

// ============================================================
// 6. BOOKING — BUILDER PATTERN
//
// WHY Builder here?
//   A Booking has many fields (bookingId, userId, showId, seats,
//   totalAmount, createdAt, paymentId...). Constructors with 8+
//   parameters are unreadable. Builder makes construction
//   step-by-step and produces an immutable Booking object.
// ============================================================
class Booking {
    private static final AtomicLong idGen = new AtomicLong(10_000);

    private final long          bookingId;
    private final String        userId;
    private final int           showId;
    private final List<Integer> seatIds;      // list of seat IDs booked
    private final double        totalAmount;
    private       BookingStatus status;
    private final LocalDateTime createdAt;
    private       String        paymentId;    // set after payment

    // Private constructor — only Builder can call this
    private Booking(Builder b) {
        this.bookingId   = idGen.getAndIncrement();
        this.userId      = b.userId;
        this.showId      = b.showId;
        this.seatIds     = List.copyOf(b.seatIds);
        this.totalAmount = b.totalAmount;
        this.status      = BookingStatus.PAYMENT_PENDING;
        this.createdAt   = LocalDateTime.now();
    }

    public void confirmPayment(String paymentId) {
        this.paymentId = paymentId;
        this.status    = BookingStatus.CONFIRMED;
    }

    public void cancel() { this.status = BookingStatus.CANCELLED; }

    // ---- Getters ----
    public long          getBookingId()  { return bookingId; }
    public String        getUserId()     { return userId; }
    public int           getShowId()     { return showId; }
    public List<Integer> getSeatIds()    { return seatIds; }
    public double        getTotalAmount(){ return totalAmount; }
    public BookingStatus getStatus()     { return status; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    @Override public String toString() {
        return String.format("Booking[#%d | user=%s | show=%d | seats=%s | ₹%.2f | %s]",
            bookingId, userId, showId, seatIds, totalAmount, status);
    }

    // ---- BUILDER ----
    static class Builder {
        private final String        userId;
        private final int           showId;
        private       List<Integer> seatIds     = new ArrayList<>();
        private       double        totalAmount = 0;

        public Builder(String userId, int showId) {
            this.userId = userId;
            this.showId = showId;
        }
        public Builder seats(List<Integer> ids)  { this.seatIds = ids;      return this; }
        public Builder totalAmount(double amt)   { this.totalAmount = amt;  return this; }
        public Booking build()                   { return new Booking(this); }
    }
}

// ============================================================
// 7. SHOW — the core booking-facing entity
//
// A Show = Movie running on a specific Screen at a specific time.
// It owns all SeatSlots for that screening.
//
// Thread-safety:
//   - SeatSlots are stored in a ConcurrentHashMap.
//   - Each SeatSlot has its own ReentrantLock.
//   - The Show object itself has NO class-level lock.
//     Two operations on different seats NEVER contend.
// ============================================================
class Show {
    private final int                           showId;
    private final Movie                         movie;
    private final Screen                        screen;
    private final Theatre                       theatre;
    private final LocalDateTime                 showTime;
    // seatId → SeatSlot (ConcurrentHashMap for safe iteration)
    private final ConcurrentHashMap<Integer, SeatSlot> seatSlots
        = new ConcurrentHashMap<>();
    // Background scheduler to auto-expire TTL locks
    private final ScheduledExecutorService ttlScheduler
        = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ttl-show-" + 0);
            t.setDaemon(true); // won't prevent JVM shutdown
            return t;
        });

    public Show(int showId, Movie movie, Screen screen,
                Theatre theatre, LocalDateTime showTime,
                List<Seat> seats) {
        this.showId  = showId;
        this.movie   = movie;
        this.screen  = screen;
        this.theatre = theatre;
        this.showTime= showTime;

        // Wrap each Seat in a SeatSlot
        for (Seat s : seats) {
            seatSlots.put(s.getId(), new SeatSlot(s));
        }

        // Background job: scan for expired TTL locks every 30 seconds.
        // This is the TTL release mechanism — prevents seats from being
        // permanently stuck in LOCKED if the user closes the browser.
        ttlScheduler.scheduleAtFixedRate(this::releaseExpiredLocks,
            30, 30, TimeUnit.SECONDS);
    }

    /**
     * LOCK seats for a user (seat selection before payment).
     *
     * Atomicity requirement: either ALL requested seats are locked
     * or NONE are. We don't want a situation where seats 1,2,3
     * were locked but seat 4 was unavailable, leaving seats 1-3
     * permanently locked.
     *
     * Strategy:
     *   1. Try to lock each seat.
     *   2. If ANY fails, rollback ALL previously locked seats.
     *
     * @param userId   the user trying to lock
     * @param seatIds  list of seat IDs to lock
     * @param ttlMins  how long to hold the lock (payment window)
     * @return list of successfully locked SeatSlots, or empty on failure
     */
    public List<SeatSlot> lockSeats(String userId, List<Integer> seatIds, int ttlMins) {
        List<SeatSlot> locked = new ArrayList<>();

        for (int seatId : seatIds) {
            SeatSlot slot = seatSlots.get(seatId);

            if (slot == null) {
                System.out.println("[Show] Seat " + seatId + " does not exist.");
                rollbackLocks(locked, userId); // release already-locked seats
                return Collections.emptyList();
            }

            if (!slot.tryLock(userId, ttlMins)) {
                System.out.println("[Show] Seat " + seatId +
                    " is not available (status=" + slot.getStatus() + ").");
                rollbackLocks(locked, userId); // all-or-nothing
                return Collections.emptyList();
            }

            locked.add(slot);
        }

        System.out.printf("[Show #%d] %d seats locked for user=%s%n",
            showId, locked.size(), userId);
        return locked;
    }

    /**
     * Rollback helper — release all slots that were locked in the
     * current transaction. Called when a multi-seat lock partially fails.
     */
    private void rollbackLocks(List<SeatSlot> locked, String userId) {
        locked.forEach(slot -> slot.release(userId));
        System.out.println("[Show] Rollback: released " + locked.size() +
            " previously locked seats for user=" + userId);
    }

    /**
     * Confirm all locked seats → BOOKED after payment success.
     */
    public boolean confirmSeats(String userId, List<Integer> seatIds) {
        for (int seatId : seatIds) {
            SeatSlot slot = seatSlots.get(seatId);
            if (slot == null || !slot.confirm(userId)) {
                System.out.println("[Show] Confirm failed for seat " + seatId);
                return false;
            }
        }
        return true;
    }

    /**
     * Release locked seats — called on payment timeout or user cancel.
     */
    public void releaseSeats(String userId, List<Integer> seatIds) {
        seatIds.forEach(seatId -> {
            SeatSlot slot = seatSlots.get(seatId);
            if (slot != null) slot.release(userId);
        });
    }

    /**
     * Cancel booked seats — called on booking cancellation.
     */
    public void cancelSeats(List<Integer> seatIds) {
        seatIds.forEach(seatId -> {
            SeatSlot slot = seatSlots.get(seatId);
            if (slot != null) slot.cancel();
        });
    }

    /**
     * Background TTL scanner.
     * Iterates all slots and expires any that are LOCKED past their TTL.
     * Safe to call concurrently — each SeatSlot handles its own lock.
     */
    private void releaseExpiredLocks() {
        long expired = seatSlots.values().stream()
            .filter(SeatSlot::expireIfOverdue)
            .count();
        if (expired > 0)
            System.out.println("[TTL-Scanner] Released " + expired +
                " expired seat locks for show #" + showId);
    }

    /**
     * Calculate total price for a list of seat IDs.
     */
    public double calculateTotal(List<Integer> seatIds) {
        return seatIds.stream()
            .mapToDouble(id -> {
                SeatSlot slot = seatSlots.get(id);
                return slot != null ? slot.getSeat().getPrice() : 0;
            }).sum();
    }

    public List<SeatSlot> getAvailableSeats() {
        return seatSlots.values().stream()
            .filter(SeatSlot::isAvailable)
            .collect(Collectors.toList());
    }

    public int  getShowId()        { return showId; }
    public Movie getMovie()        { return movie; }
    public Theatre getTheatre()    { return theatre; }
    public LocalDateTime getShowTime(){ return showTime; }

    public void displaySeats() {
        System.out.printf("Show #%d | %s | %s | %s%n",
            showId, movie.getTitle(), theatre, showTime);
        seatSlots.forEach((id, slot) ->
            System.out.print("  " + slot + " "));
        System.out.println();
    }

    public void shutdown() { ttlScheduler.shutdown(); }
}

// ============================================================
// 8. FACTORY PATTERN — ShowFactory
//
// WHY Factory?
//   Creating a Show requires assembling many objects (Seats,
//   SeatSlots, pricing by category, row/column layout).
//   The factory hides this construction complexity from the
//   caller and provides named creation methods for common
//   theatre layouts.
// ============================================================
class ShowFactory {

    /**
     * Create a standard multiplex show with SILVER, GOLD, PLATINUM rows.
     *
     * Layout:
     *   Rows A-C   → SILVER   ₹150
     *   Rows D-F   → GOLD     ₹250
     *   Rows G-H   → PLATINUM ₹400
     *   10 seats per row
     */
    public static Show createMultiplexShow(int showId, Movie movie,
                                            Screen screen, Theatre theatre,
                                            LocalDateTime showTime) {
        List<Seat> seats = new ArrayList<>();
        int seatId = 1;

        // SILVER rows A-C
        for (char row = 'A'; row <= 'C'; row++) {
            for (int col = 1; col <= 10; col++) {
                seats.add(new Seat(seatId++, SeatCategory.SILVER,
                    150.0, String.valueOf(row), col));
            }
        }
        // GOLD rows D-F
        for (char row = 'D'; row <= 'F'; row++) {
            for (int col = 1; col <= 10; col++) {
                seats.add(new Seat(seatId++, SeatCategory.GOLD,
                    250.0, String.valueOf(row), col));
            }
        }
        // PLATINUM rows G-H
        for (char row = 'G'; row <= 'H'; row++) {
            for (int col = 1; col <= 10; col++) {
                seats.add(new Seat(seatId++, SeatCategory.PLATINUM,
                    400.0, String.valueOf(row), col));
            }
        }

        return new Show(showId, movie, screen, theatre, showTime, seats);
    }

    /** Minimal show — 10 SILVER seats, useful for unit tests */
    public static Show createSimpleShow(int showId, Movie movie,
                                         Screen screen, Theatre theatre,
                                         LocalDateTime showTime) {
        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            seats.add(new Seat(i, SeatCategory.SILVER, 100.0, "A", i));
        }
        return new Show(showId, movie, screen, theatre, showTime, seats);
    }
}

// ============================================================
// 9. OBSERVER PATTERN — BookingEventObserver
//
// WHY Observer?
//   When a booking is confirmed/cancelled, multiple things must
//   happen: send confirmation email, update analytics, notify
//   payment ledger. We should NOT hardcode these in BookingService.
//   Adding a new action (e.g., SMS) should NOT require modifying
//   BookingService — just register a new observer.
// ============================================================
interface BookingEventObserver {
    void onBookingConfirmed(Booking booking, Show show);
    void onBookingCancelled(Booking booking, Show show);
}

/** Sends confirmation emails / push notifications */
class NotificationObserver implements BookingEventObserver {
    @Override
    public void onBookingConfirmed(Booking booking, Show show) {
        System.out.printf("[Email] Booking #%d confirmed for user=%s | %s | Seats: %s | ₹%.2f%n",
            booking.getBookingId(), booking.getUserId(),
            show.getMovie().getTitle(), booking.getSeatIds(),
            booking.getTotalAmount());
    }

    @Override
    public void onBookingCancelled(Booking booking, Show show) {
        System.out.printf("[Email] Booking #%d cancelled for user=%s | Refund: ₹%.2f%n",
            booking.getBookingId(), booking.getUserId(), booking.getTotalAmount());
    }
}

/** Tracks revenue and occupancy metrics */
class AnalyticsObserver implements BookingEventObserver {
    private double totalRevenue  = 0;
    private long   totalBookings = 0;
    private long   totalCancels  = 0;

    @Override
    public synchronized void onBookingConfirmed(Booking booking, Show show) {
        totalRevenue  += booking.getTotalAmount();
        totalBookings++;
    }

    @Override
    public synchronized void onBookingCancelled(Booking booking, Show show) {
        totalRevenue  -= booking.getTotalAmount();
        totalCancels++;
    }

    public void printReport() {
        System.out.printf("[Analytics] Bookings=%d Cancels=%d Revenue=₹%.2f%n",
            totalBookings, totalCancels, totalRevenue);
    }
}

// ============================================================
// 10. COMMAND PATTERN — BookingCommand
//
// WHY Command?
//   A booking involves multiple steps: lockSeats → createBooking
//   → processPayment → confirmSeats. If payment fails, we must
//   undo the lock. Wrapping this in a Command object:
//     - Keeps all booking logic in one place (Single Responsibility)
//     - Provides a clean execute() + undo() interface
//     - Enables an audit trail (store commands for replay)
// ============================================================
class BookingCommand {
    private final Show                         show;
    private final Booking                      booking;
    private final List<Integer>                seatIds;
    private final List<SeatSlot>               lockedSlots = new ArrayList<>();
    private final List<BookingEventObserver>   observers;
    private       boolean                      executed = false;

    public BookingCommand(Show show, Booking booking,
                           List<BookingEventObserver> observers) {
        this.show     = show;
        this.booking  = booking;
        this.seatIds  = booking.getSeatIds();
        this.observers= observers;
    }

    /**
     * Execute: lock seats → simulate payment → confirm.
     * @return true if booking successful
     */
    public boolean execute() {
        // Step 1: Lock seats (10-minute payment window)
        List<SeatSlot> locked = show.lockSeats(
            booking.getUserId(), seatIds, 10);

        if (locked.isEmpty()) {
            System.out.println("[BookingCommand] Failed to lock seats — aborting.");
            return false;
        }
        lockedSlots.addAll(locked);

        // Step 2: Simulate payment (in production: call Payment Gateway)
        boolean paymentSuccess = simulatePayment(booking.getTotalAmount());

        if (!paymentSuccess) {
            System.out.println("[BookingCommand] Payment failed — rolling back seat locks.");
            undo(); // release all locked seats
            return false;
        }

        // Step 3: Confirm seats → BOOKED
        boolean confirmed = show.confirmSeats(booking.getUserId(), seatIds);
        if (!confirmed) {
            undo();
            return false;
        }

        // Step 4: Mark booking confirmed
        booking.confirmPayment("PAY_" + System.currentTimeMillis());
        executed = true;

        // Step 5: Notify all observers (email, analytics, ledger)
        observers.forEach(o -> o.onBookingConfirmed(booking, show));
        return true;
    }

    /**
     * Undo: release all locked seats (rollback).
     * Called on payment failure or explicit cancellation.
     */
    public void undo() {
        if (!lockedSlots.isEmpty()) {
            show.releaseSeats(booking.getUserId(), seatIds);
            lockedSlots.clear();
        }
        if (executed) {
            booking.cancel();
            show.cancelSeats(seatIds);
            observers.forEach(o -> o.onBookingCancelled(booking, show));
            executed = false;
        }
    }

    private boolean simulatePayment(double amount) {
        // In production: call Razorpay / Stripe with idempotency key
        System.out.printf("[Payment] Processing ₹%.2f ...%n", amount);
        return true; // always succeeds in this simulation
    }
}

// ============================================================
// 11. BOOKING SERVICE — core business logic
// ============================================================
class BookingService {
    // bookingId → Booking (ConcurrentHashMap for thread safety)
    private final ConcurrentHashMap<Long, Booking>          bookings  = new ConcurrentHashMap<>();
    // bookingId → BookingCommand (keep for undo / audit trail)
    private final ConcurrentHashMap<Long, BookingCommand>   commands  = new ConcurrentHashMap<>();
    private final List<BookingEventObserver>                observers = new ArrayList<>();

    public void addObserver(BookingEventObserver obs) { observers.add(obs); }

    /**
     * Book seats end-to-end.
     * This is the main entry point for a booking request.
     */
    public Booking book(String userId, Show show, List<Integer> seatIds) {
        double total = show.calculateTotal(seatIds);

        // Build the immutable Booking using Builder pattern
        Booking booking = new Booking.Builder(userId, show.getShowId())
            .seats(seatIds)
            .totalAmount(total)
            .build();

        // Wrap in a Command (encapsulates lock → pay → confirm + undo)
        BookingCommand cmd = new BookingCommand(show, booking, observers);

        boolean success = cmd.execute();

        if (success) {
            bookings.put(booking.getBookingId(), booking);
            commands.put(booking.getBookingId(), cmd);
            System.out.println("[BookingService] " + booking);
        }

        return success ? booking : null;
    }

    /**
     * Cancel an existing booking.
     * Retrieves the original command and calls undo().
     */
    public boolean cancel(long bookingId) {
        BookingCommand cmd = commands.get(bookingId);
        if (cmd == null) {
            System.out.println("[BookingService] Booking not found: #" + bookingId);
            return false;
        }
        cmd.undo();
        System.out.println("[BookingService] Booking #" + bookingId + " cancelled.");
        return true;
    }

    public Booking getBooking(long id) { return bookings.get(id); }
}

// ============================================================
// 12. SINGLETON — BookMyShowService
//
// WHY Singleton?
//   The service holds the global registry of shows and the
//   booking service. There must be exactly ONE registry —
//   if two instances exist, show #5 in instance A is a
//   different object from show #5 in instance B, breaking
//   all concurrency guarantees.
//
// Thread-safe Singleton using double-checked locking + volatile.
//   volatile: ensures the reference is fully constructed before
//   any other thread can read it (prevents partial init).
// ============================================================
class BookMyShowService {
    // volatile ensures visibility across threads without full synchronization
    private static volatile BookMyShowService instance;

    // ConcurrentHashMap: safe for concurrent reads + writes without
    // synchronizing the entire map
    private final ConcurrentHashMap<Integer, Show>    shows          = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Theatre> theatres       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Movie>   movies         = new ConcurrentHashMap<>();
    private final BookingService                      bookingService = new BookingService();

    private BookMyShowService() {
        // Register built-in observers
        NotificationObserver notif     = new NotificationObserver();
        AnalyticsObserver    analytics = new AnalyticsObserver();
        bookingService.addObserver(notif);
        bookingService.addObserver(analytics);
    }

    /**
     * Double-checked locking — thread-safe Singleton.
     *
     * First check (without lock): fast path for already-initialised case.
     * Second check (with lock): ensures only one thread creates the instance.
     * volatile on 'instance' prevents CPU instruction reordering.
     */
    public static BookMyShowService getInstance() {
        if (instance == null) {                         // first check — no lock
            synchronized (BookMyShowService.class) {
                if (instance == null) {                 // second check — with lock
                    instance = new BookMyShowService();
                }
            }
        }
        return instance;
    }

    // ---- Registry methods ----
    public void registerTheatre(Theatre t) { theatres.put(t.getTheatreId(), t); }
    public void registerMovie(Movie m, int id){ movies.put(id, m); }

    public Show addShow(Show show) {
        shows.put(show.getShowId(), show);
        System.out.println("[BMS] Show registered: #" + show.getShowId() +
            " | " + show.getMovie().getTitle() +
            " | " + show.getTheatre());
        return show;
    }

    public Show getShow(int showId) { return shows.get(showId); }

    // ---- Booking delegation ----
    public Booking book(String userId, int showId, List<Integer> seatIds) {
        Show show = shows.get(showId);
        if (show == null) {
            System.out.println("[BMS] Show not found: #" + showId);
            return null;
        }
        return bookingService.book(userId, show, seatIds);
    }

    public boolean cancel(long bookingId) {
        return bookingService.cancel(bookingId);
    }

    // ---- Search ----
    public List<Show> searchShows(String city, String movieTitle) {
        return shows.values().stream()
            .filter(s -> s.getTheatre().getCity().equalsIgnoreCase(city))
            .filter(s -> s.getMovie().getTitle()
                .equalsIgnoreCase(movieTitle))
            .collect(Collectors.toList());
    }
}

// ============================================================
// 13. MAIN — DRIVER CODE
// ============================================================
public class BookMyShow {

    public static void main(String[] args) throws InterruptedException {

        BookMyShowService bms = BookMyShowService.getInstance();

        // ---- Setup ----
        Movie inception = new Movie("Inception", 148, "Sci-Fi", "English", "UA");
        Screen screen1  = new Screen(1, "Screen 1", 80);
        Theatre pvr     = new Theatre(1, "PVR Nexus", "Bangalore",
                                      List.of(screen1));

        bms.registerTheatre(pvr);

        Show show1 = bms.addShow(
            ShowFactory.createSimpleShow(
                101, inception, screen1, pvr,
                LocalDateTime.now().plusHours(2)));

        Show show2 = bms.addShow(
            ShowFactory.createMultiplexShow(
                102, inception, screen1, pvr,
                LocalDateTime.now().plusHours(5)));

        // ===== SCENARIO 1: Simple sequential booking =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Sequential Booking");
        System.out.println("=".repeat(55));

        show1.displaySeats();
        Booking b1 = bms.book("alice", 101, List.of(1, 2, 3));
        show1.displaySeats();

        // ===== SCENARIO 2: Duplicate booking (same seat) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Duplicate Seat Attempt");
        System.out.println("=".repeat(55));

        // Bob tries to book seat 1 which Alice already booked
        Booking b2 = bms.book("bob", 101, List.of(1, 4));
        System.out.println("Bob's booking: " + b2); // null — seat 1 unavailable

        // Bob books different seats
        Booking b3 = bms.book("bob", 101, List.of(4, 5));
        System.out.println("Bob's new booking: " + b3);

        // ===== SCENARIO 3: Concurrent booking (race condition test) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Concurrent Booking (race condition)");
        System.out.println("=".repeat(55));

        // Both carol and dave try to book seat 6 simultaneously
        // Exactly ONE should succeed — the other should get "not available"
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<Booking> results = new CopyOnWriteArrayList<>();

        pool.submit(() -> {
            Booking b = bms.book("carol", 101, List.of(6, 7));
            if (b != null) results.add(b);
        });

        pool.submit(() -> {
            Booking b = bms.book("dave", 101, List.of(6, 8));
            if (b != null) results.add(b);
        });

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("\nConcurrent booking results:");
        System.out.println("Successful bookings for seat 6: " + results.size() +
            " (must be exactly 1)");
        results.forEach(b -> System.out.println("  " + b));

        // ===== SCENARIO 4: All-or-nothing multi-seat lock =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: All-or-Nothing Multi-Seat Lock");
        System.out.println("=".repeat(55));

        // Try to book seats 4 (taken by bob) and 9 — should fail entirely
        Booking b4 = bms.book("eve", 101, List.of(4, 9));
        System.out.println("Eve's booking (seat 4 taken): " + b4); // null
        // Seat 9 must be released back to AVAILABLE
        System.out.println("Seat 9 still available: " +
            show1.getAvailableSeats().stream()
                .anyMatch(s -> s.getSeat().getId() == 9));

        // ===== SCENARIO 5: Cancellation =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Booking Cancellation");
        System.out.println("=".repeat(55));

        if (b1 != null) {
            System.out.println("Before cancel: " + b1);
            bms.cancel(b1.getBookingId());
            System.out.println("After cancel: " + b1.getStatus());
        }

        // ===== SCENARIO 6: Search shows =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Search Shows");
        System.out.println("=".repeat(55));

        List<Show> found = bms.searchShows("Bangalore", "Inception");
        System.out.println("Found " + found.size() + " shows in Bangalore:");
        found.forEach(s -> System.out.println("  Show #" + s.getShowId() +
            " | " + s.getShowTime() +
            " | " + s.getAvailableSeats().size() + " seats available"));

        // ===== SCENARIO 7: Multiplex show — seat categories =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Multiplex Show Booking");
        System.out.println("=".repeat(55));

        // Book a GOLD seat (seats 31-60 in multiplex layout)
        Booking b5 = bms.book("frank", 102, List.of(31, 32));
        System.out.println("Frank's GOLD booking: " + b5);

        // Book a PLATINUM seat (seats 61-80)
        Booking b6 = bms.book("grace", 102, List.of(71, 72));
        System.out.println("Grace's PLATINUM booking: " + b6);

        System.out.println("\nAvailable seats in Show 102: " +
            show2.getAvailableSeats().size());

        // Cleanup
        show1.shutdown();
        show2.shutdown();

        System.out.println("\n===== DESIGN PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class(es)                  | Why
            -----------|----------------------------|----------------------------------
            Singleton  | BookMyShowService          | One global registry, one booking
                       |                            | service — prevents split state
            State      | SeatStatus                 | AVAILABLE→LOCKED→BOOKED enforces
                       | SeatSlot transitions       | legal transitions only
            Builder    | Booking.Builder            | Immutable booking with 6+ fields,
                       |                            | readable construction
            Observer   | BookingEventObserver       | Email/Analytics decouple from
                       | Notification/Analytics     | booking logic — add without change
            Factory    | ShowFactory                | createMultiplexShow / createSimple
                       |                            | hides seat layout complexity
            Command    | BookingCommand             | execute() + undo() — atomic
                       |                            | multi-step booking with rollback
            """);

        System.out.println("===== THREAD-SAFETY SUMMARY =====");
        System.out.println("""
            Class              | Mechanism                | Why chosen over synchronized
            -------------------|--------------------------|-----------------------------
            SeatSlot           | ReentrantLock per seat   | Seat-level granularity; tryLock(0)
                               |                          | gives instant fail on contention
            Show               | ConcurrentHashMap        | Safe iteration + writes; no
                               |                          | class-level lock needed
            BookMyShowService  | volatile + double-check  | Safe Singleton creation
                               | + ConcurrentHashMap      |
            Booking/Seat       | Immutable fields         | No mutation = no race condition
            AnalyticsObserver  | synchronized methods     | Simple counters — low contention
            """);
    }
}
