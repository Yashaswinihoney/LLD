import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ============================================================
// AIRLINE BOOKING SYSTEM — IXIGO / MAKEMYTRIP STYLE LLD
// Patterns:
//   Singleton  — BookingSystem, InventoryService, PaymentGateway
//   State      — BookingStatus (INITIATED→SEAT_HOLD→PAYMENT_PENDING→CONFIRMED→CANCELLED)
//   Strategy   — PricingStrategy (Base, Dynamic, Surge, Discount)
//   Observer   — BookingEventObserver (Notification, Inventory, Audit, Loyalty)
//   Factory    — FlightFactory (domestic, international, connecting)
//   Builder    — SearchQuery.Builder, Booking.Builder
//   Command    — BookingCommand (HoldSeats, ConfirmBooking, CancelBooking)
//   Template   — AbstractBookingProcessor (processBooking skeleton)
//   Decorator  — FareDecorator (base fare + taxes + convenience fee + insurance)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum CabinClass    { ECONOMY, PREMIUM_ECONOMY, BUSINESS, FIRST }
enum BookingStatus {
    INITIATED,         // search done, user selecting
    SEAT_HOLD,         // seats temporarily blocked (10-min TTL)
    PAYMENT_PENDING,   // payment initiated
    CONFIRMED,         // payment success, PNR issued
    TICKETED,          // actual e-ticket issued by airline
    CANCELLED,         // user or system cancelled
    REFUND_INITIATED,  // cancellation approved, refund queued
    REFUND_COMPLETED,  // money back to source
    NO_SHOW            // flight departed, passenger absent
}
enum SeatStatus    { AVAILABLE, HELD, BOOKED, BLOCKED, UNAVAILABLE }
enum SeatType      { WINDOW, MIDDLE, AISLE }
enum PaymentMethod { CREDIT_CARD, DEBIT_CARD, NET_BANKING, UPI, WALLET, EMI }
enum PaymentStatus { PENDING, AUTHORIZED, CAPTURED, FAILED, REFUNDED, PARTIALLY_REFUNDED }
enum FlightStatus  { SCHEDULED, BOARDING, DEPARTED, LANDED, DELAYED, CANCELLED }
enum TripType      { ONE_WAY, ROUND_TRIP, MULTI_CITY }
enum CancellationReason {
    USER_REQUESTED, FLIGHT_CANCELLED, SCHEDULE_CHANGE,
    PAYMENT_FAILURE, SEAT_HOLD_EXPIRED, DUPLICATE_BOOKING
}
enum FareType      { SAVER, FLEXI, BUSINESS_LITE, FULL_FLEX }

// ============================================================
// 2. MONEY — value object (paise precision, same as stock exchange)
// ============================================================
class Money implements Comparable<Money> {
    private final long   paise;
    private final String currency;

    public Money(double amount, String currency) {
        this.paise    = Math.round(amount * 100);
        this.currency = currency;
    }
    private Money(long paise, String currency) {
        this.paise = paise; this.currency = currency;
    }

    public Money add(Money o)           { return new Money(paise + o.paise, currency); }
    public Money subtract(Money o)      { return new Money(paise - o.paise, currency); }
    public Money multiply(int factor)   { return new Money(paise * factor, currency); }
    public Money percentage(double pct) { return new Money((long)(paise * pct / 100.0), currency); }
    public boolean isGreaterThan(Money o){ return paise > o.paise; }
    public boolean isZero()             { return paise == 0; }
    public double  toDouble()           { return paise / 100.0; }
    public long    getPaise()           { return paise; }
    public String  getCurrency()        { return currency; }

    @Override public int compareTo(Money o) { return Long.compare(paise, o.paise); }
    @Override public String toString()  {
        return currency + " " + String.format("%.2f", toDouble());
    }
    public static Money inr(double amount) { return new Money(amount, "INR"); }
    public static Money zero()             { return new Money(0, "INR"); }
}

// ============================================================
// 3. AIRPORT & ROUTE
// ============================================================
class Airport {
    private final String iataCode;   // DEL, BOM, BLR, CCU
    private final String name;
    private final String city;
    private final String country;
    private final String timezone;

    public Airport(String iataCode, String name, String city, String country, String tz) {
        this.iataCode = iataCode; this.name = name;
        this.city = city; this.country = country; this.timezone = tz;
    }

    public String getIataCode() { return iataCode; }
    public String getCity()     { return city; }
    public String getName()     { return name; }
    @Override public String toString() { return iataCode + " (" + city + ")"; }
}

// ============================================================
// 4. SEAT — individual physical seat
// ============================================================
class Seat {
    private final String     seatNumber;   // 12A, 14C, etc.
    private final CabinClass cabin;
    private final SeatType   type;
    private final boolean    extraLegroom;
    private final boolean    exitRow;
    private       SeatStatus status;
    private       String     heldByBookingId;
    private       LocalDateTime holdExpiry;

    public Seat(String seatNumber, CabinClass cabin, SeatType type,
                boolean extraLegroom, boolean exitRow) {
        this.seatNumber   = seatNumber;
        this.cabin        = cabin;
        this.type         = type;
        this.extraLegroom = extraLegroom;
        this.exitRow      = exitRow;
        this.status       = SeatStatus.AVAILABLE;
    }

    public synchronized boolean hold(String bookingId, int holdMinutes) {
        if (status != SeatStatus.AVAILABLE) return false;
        status           = SeatStatus.HELD;
        heldByBookingId  = bookingId;
        holdExpiry       = LocalDateTime.now().plusMinutes(holdMinutes);
        System.out.printf("[Seat %s] HELD by booking %s (expires %s)%n",
            seatNumber, bookingId, holdExpiry.toLocalTime());
        return true;
    }

    public synchronized boolean confirm(String bookingId) {
        if (status == SeatStatus.HELD && bookingId.equals(heldByBookingId)) {
            status = SeatStatus.BOOKED;
            System.out.printf("[Seat %s] BOOKED by %s%n", seatNumber, bookingId);
            return true;
        }
        return false;
    }

    public synchronized void release() {
        if (status == SeatStatus.HELD || status == SeatStatus.BOOKED) {
            System.out.printf("[Seat %s] Released → AVAILABLE%n", seatNumber);
            status = SeatStatus.AVAILABLE;
            heldByBookingId = null;
            holdExpiry = null;
        }
    }

    public synchronized boolean isHoldExpired() {
        return status == SeatStatus.HELD &&
               holdExpiry != null &&
               LocalDateTime.now().isAfter(holdExpiry);
    }

    public String     getSeatNumber()   { return seatNumber; }
    public CabinClass getCabin()        { return cabin; }
    public SeatType   getType()         { return type; }
    public SeatStatus getStatus()       { return status; }
    public boolean    hasExtraLegroom() { return extraLegroom; }
    public boolean    isExitRow()       { return exitRow; }

    @Override public String toString() {
        return String.format("Seat[%s | %s | %s | %s%s%s]",
            seatNumber, cabin, type, status,
            extraLegroom ? " LEGROOM" : "",
            exitRow      ? " EXIT"    : "");
    }
}

// ============================================================
// 5. FLIGHT — with its own seat inventory
// ============================================================
class Flight {
    private static final AtomicLong idGen = new AtomicLong(100);

    private final long          flightId;
    private final String        flightNumber;  // AI-101, 6E-204
    private final String        airline;
    private final Airport       origin;
    private final Airport       destination;
    private final LocalDateTime departure;
    private final LocalDateTime arrival;
    private final Duration      duration;
    private final Map<CabinClass, List<Seat>> seatMap;
    private final Map<CabinClass, Integer>    totalSeats;
    private       FlightStatus  status;

    public Flight(String flightNumber, String airline,
                  Airport origin, Airport destination,
                  LocalDateTime departure, LocalDateTime arrival) {
        this.flightId     = idGen.getAndIncrement();
        this.flightNumber = flightNumber;
        this.airline      = airline;
        this.origin       = origin;
        this.destination  = destination;
        this.departure    = departure;
        this.arrival      = arrival;
        this.duration     = Duration.between(departure, arrival);
        this.status       = FlightStatus.SCHEDULED;
        this.seatMap      = new EnumMap<>(CabinClass.class);
        this.totalSeats   = new EnumMap<>(CabinClass.class);
        initializeSeats();
    }

    private void initializeSeats() {
        // Economy: rows 10-35 (26 rows × 6 seats = 156 seats)
        List<Seat> economy = new ArrayList<>();
        for (int row = 10; row <= 35; row++) {
            for (char col : new char[]{'A','B','C','D','E','F'}) {
                SeatType t = (col == 'A' || col == 'F') ? SeatType.WINDOW
                           : (col == 'C' || col == 'D') ? SeatType.AISLE
                           : SeatType.MIDDLE;
                boolean exit = (row == 15 || row == 25);
                economy.add(new Seat(row + "" + col, CabinClass.ECONOMY, t, exit, exit));
            }
        }
        seatMap.put(CabinClass.ECONOMY, economy);
        totalSeats.put(CabinClass.ECONOMY, economy.size());

        // Business: rows 1-6 (6 rows × 4 seats = 24 seats)
        List<Seat> business = new ArrayList<>();
        for (int row = 1; row <= 6; row++) {
            for (char col : new char[]{'A','C','D','F'}) {
                SeatType t = (col == 'A' || col == 'F') ? SeatType.WINDOW : SeatType.AISLE;
                business.add(new Seat(row + "" + col, CabinClass.BUSINESS, t, true, false));
            }
        }
        seatMap.put(CabinClass.BUSINESS, business);
        totalSeats.put(CabinClass.BUSINESS, business.size());
    }

    public int getAvailableSeats(CabinClass cabin) {
        return (int) seatMap.getOrDefault(cabin, List.of()).stream()
            .filter(s -> s.getStatus() == SeatStatus.AVAILABLE).count();
    }

    public Optional<Seat> findAvailableSeat(CabinClass cabin) {
        return seatMap.getOrDefault(cabin, List.of()).stream()
            .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
            .findFirst();
    }

    public Optional<Seat> findSeatByNumber(String seatNumber, CabinClass cabin) {
        return seatMap.getOrDefault(cabin, List.of()).stream()
            .filter(s -> s.getSeatNumber().equals(seatNumber)).findFirst();
    }

    // Release all expired holds (called by a background scheduler)
    public void releaseExpiredHolds() {
        seatMap.values().stream().flatMap(List::stream)
            .filter(Seat::isHoldExpired)
            .forEach(s -> { s.release();
                System.out.println("[Flight " + flightNumber + "] Expired hold released: " + s.getSeatNumber()); });
    }

    public long          getFlightId()     { return flightId; }
    public String        getFlightNumber() { return flightNumber; }
    public String        getAirline()      { return airline; }
    public Airport       getOrigin()       { return origin; }
    public Airport       getDestination()  { return destination; }
    public LocalDateTime getDeparture()    { return departure; }
    public LocalDateTime getArrival()      { return arrival; }
    public Duration      getDuration()     { return duration; }
    public FlightStatus  getStatus()       { return status; }
    public void          setStatus(FlightStatus s) { this.status = s; }

    @Override public String toString() {
        return String.format("Flight[%s | %s→%s | %s→%s | %dh%dm | Eco:%d avail]",
            flightNumber, origin.getIataCode(), destination.getIataCode(),
            departure.toLocalTime(), arrival.toLocalTime(),
            duration.toHours(), duration.toMinutesPart(),
            getAvailableSeats(CabinClass.ECONOMY));
    }
}

// ============================================================
// 6. PASSENGER
// ============================================================
class Passenger {
    private static final AtomicLong idGen = new AtomicLong(50_000);

    private final long   passengerId;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final String phone;
    private final String passportNumber; // for international
    private final LocalDate dateOfBirth;
    private       int    loyaltyPoints;

    public Passenger(String firstName, String lastName, String email,
                     String phone, LocalDate dob) {
        this.passengerId    = idGen.getAndIncrement();
        this.firstName      = firstName;
        this.lastName       = lastName;
        this.email          = email;
        this.phone          = phone;
        this.passportNumber = null;
        this.dateOfBirth    = dob;
        this.loyaltyPoints  = 0;
    }

    public void addLoyaltyPoints(int pts) {
        loyaltyPoints += pts;
        System.out.printf("[Loyalty] %s earned %d pts → total: %d%n",
            getFullName(), pts, loyaltyPoints);
    }

    public long   getPassengerId() { return passengerId; }
    public String getFullName()    { return firstName + " " + lastName; }
    public String getEmail()       { return email; }
    public String getPhone()       { return phone; }
    public int    getLoyaltyPoints(){ return loyaltyPoints; }

    @Override public String toString() {
        return String.format("Passenger[#%d | %s | %s | %s]",
            passengerId, getFullName(), email, phone);
    }
}

// ============================================================
// 7. SEARCH QUERY — BUILDER PATTERN
// ============================================================
class SearchQuery {
    private final Airport       origin;
    private final Airport       destination;
    private final LocalDate     travelDate;
    private final LocalDate     returnDate;       // null for one-way
    private final CabinClass    cabin;
    private final int           adults;
    private final int           children;
    private final int           infants;
    private final TripType      tripType;
    private final boolean       flexibleDates;

    private SearchQuery(Builder b) {
        this.origin        = b.origin;
        this.destination   = b.destination;
        this.travelDate    = b.travelDate;
        this.returnDate    = b.returnDate;
        this.cabin         = b.cabin;
        this.adults        = b.adults;
        this.children      = b.children;
        this.infants       = b.infants;
        this.tripType      = b.returnDate != null ? TripType.ROUND_TRIP : TripType.ONE_WAY;
        this.flexibleDates = b.flexibleDates;
    }

    public Airport    getOrigin()      { return origin; }
    public Airport    getDestination() { return destination; }
    public LocalDate  getTravelDate()  { return travelDate; }
    public CabinClass getCabin()       { return cabin; }
    public int        getTotalPax()    { return adults + children; }
    public int        getAdults()      { return adults; }
    public TripType   getTripType()    { return tripType; }

    static class Builder {
        private final Airport    origin;
        private final Airport    destination;
        private final LocalDate  travelDate;
        private       LocalDate  returnDate;
        private       CabinClass cabin         = CabinClass.ECONOMY;
        private       int        adults        = 1;
        private       int        children      = 0;
        private       int        infants       = 0;
        private       boolean    flexibleDates = false;

        public Builder(Airport origin, Airport destination, LocalDate travelDate) {
            this.origin = origin; this.destination = destination;
            this.travelDate = travelDate;
        }
        public Builder returnDate(LocalDate d)   { returnDate = d;     return this; }
        public Builder cabin(CabinClass c)        { cabin = c;          return this; }
        public Builder adults(int n)              { adults = n;         return this; }
        public Builder children(int n)            { children = n;       return this; }
        public Builder infants(int n)             { infants = n;        return this; }
        public Builder flexibleDates(boolean f)  { flexibleDates = f;  return this; }
        public SearchQuery build()               { return new SearchQuery(this); }
    }

    @Override public String toString() {
        return String.format("Search[%s→%s | %s | %s | %d pax]",
            origin, destination, travelDate, cabin, getTotalPax());
    }
}

// ============================================================
// 8. FARE DECORATOR — DECORATOR PATTERN
//    Layered fare construction: base + taxes + convenience + insurance
// ============================================================
interface FareComponent {
    Money calculate(int passengers);
    String description();
}

class BaseFare implements FareComponent {
    private final Money baseFarePerPax;
    private final FareType fareType;

    public BaseFare(Money baseFarePerPax, FareType fareType) {
        this.baseFarePerPax = baseFarePerPax;
        this.fareType       = fareType;
    }
    @Override public Money  calculate(int passengers) { return baseFarePerPax.multiply(passengers); }
    @Override public String description()             { return "Base fare (" + fareType + ")"; }
}

abstract class FareDecorator implements FareComponent {
    protected final FareComponent wrapped;
    public FareDecorator(FareComponent wrapped) { this.wrapped = wrapped; }
}

class AirportTaxDecorator extends FareDecorator {
    private static final double TAX_RATE = 18.0; // GST 18%
    public AirportTaxDecorator(FareComponent wrapped) { super(wrapped); }

    @Override public Money calculate(int pax) {
        Money base = wrapped.calculate(pax);
        return base.add(base.percentage(TAX_RATE));
    }
    @Override public String description() {
        return wrapped.description() + " + Airport Tax (18% GST)";
    }
}

class ConvenienceFeeDecorator extends FareDecorator {
    private final Money feePerPax;
    public ConvenienceFeeDecorator(FareComponent wrapped, Money feePerPax) {
        super(wrapped); this.feePerPax = feePerPax;
    }
    @Override public Money calculate(int pax) {
        return wrapped.calculate(pax).add(feePerPax.multiply(pax));
    }
    @Override public String description() {
        return wrapped.description() + " + Convenience Fee (" + feePerPax + "/pax)";
    }
}

class TravelInsuranceDecorator extends FareDecorator {
    private final Money premiumPerPax;
    public TravelInsuranceDecorator(FareComponent wrapped, Money premiumPerPax) {
        super(wrapped); this.premiumPerPax = premiumPerPax;
    }
    @Override public Money calculate(int pax) {
        return wrapped.calculate(pax).add(premiumPerPax.multiply(pax));
    }
    @Override public String description() {
        return wrapped.description() + " + Travel Insurance (" + premiumPerPax + "/pax)";
    }
}

// ============================================================
// 9. PRICING STRATEGY — STRATEGY PATTERN
// ============================================================
interface PricingStrategy {
    String  getName();
    /** Return adjusted fare per passenger for this flight+cabin on a given date */
    Money computeFare(Flight flight, CabinClass cabin, LocalDate travelDate, int seatsLeft);
}

class BasePricingStrategy implements PricingStrategy {
    private final Map<CabinClass, Money> baseFares;

    public BasePricingStrategy() {
        baseFares = new EnumMap<>(CabinClass.class);
        baseFares.put(CabinClass.ECONOMY,         Money.inr(3500));
        baseFares.put(CabinClass.PREMIUM_ECONOMY, Money.inr(6500));
        baseFares.put(CabinClass.BUSINESS,        Money.inr(14000));
        baseFares.put(CabinClass.FIRST,           Money.inr(28000));
    }
    @Override public String getName() { return "Base Pricing"; }
    @Override public Money computeFare(Flight f, CabinClass c, LocalDate d, int seatsLeft) {
        return baseFares.getOrDefault(c, Money.inr(3500));
    }
}

class DynamicPricingStrategy implements PricingStrategy {
    private final PricingStrategy base;
    public DynamicPricingStrategy(PricingStrategy base) { this.base = base; }

    @Override public String getName() { return "Dynamic Pricing (Demand-Based)"; }

    @Override
    public Money computeFare(Flight flight, CabinClass cabin,
                             LocalDate travelDate, int seatsLeft) {
        Money baseFare = base.computeFare(flight, cabin, travelDate, seatsLeft);
        double multiplier = 1.0;

        // Scarcity: fewer seats = higher price
        if      (seatsLeft <= 2)  multiplier += 0.80;  // <3 seats → +80%
        else if (seatsLeft <= 5)  multiplier += 0.50;  // <6 seats → +50%
        else if (seatsLeft <= 10) multiplier += 0.25;  // <11 seats → +25%

        // Urgency: days to departure
        long daysAhead = LocalDate.now().until(travelDate).getDays();
        if      (daysAhead <= 1)  multiplier += 0.60;  // last-minute +60%
        else if (daysAhead <= 3)  multiplier += 0.30;
        else if (daysAhead <= 7)  multiplier += 0.10;
        else if (daysAhead >= 60) multiplier -= 0.15;  // early bird -15%

        Money computed = baseFare.multiply((int)(multiplier * 100)).percentage(1.0);
        // Simpler: just scale
        long adjustedPaise = (long)(baseFare.getPaise() * multiplier);
        Money adjusted = Money.inr(adjustedPaise / 100.0);

        System.out.printf("[Dynamic] %s | seats=%d days=%d → multiplier=%.2f → %s%n",
            cabin, seatsLeft, daysAhead, multiplier, adjusted);
        return adjusted;
    }
}

class SurgePricingStrategy implements PricingStrategy {
    private final PricingStrategy base;
    private final double surgeMultiplier;
    private final String reason; // "Holiday Season", "Peak Hours"

    public SurgePricingStrategy(PricingStrategy base, double surge, String reason) {
        this.base = base; this.surgeMultiplier = surge; this.reason = reason;
    }

    @Override public String getName() { return "Surge Pricing (" + reason + ")"; }
    @Override public Money computeFare(Flight f, CabinClass c, LocalDate d, int sl) {
        Money base_fare = base.computeFare(f, c, d, sl);
        Money surged    = Money.inr(base_fare.getPaise() * surgeMultiplier / 100.0);
        System.out.printf("[Surge] %s reason='%s' → %.1fx → %s%n",
            c, reason, surgeMultiplier, surged);
        return surged;
    }
}

class DiscountPricingStrategy implements PricingStrategy {
    private final PricingStrategy base;
    private final double discountPct;
    private final String promoCode;

    public DiscountPricingStrategy(PricingStrategy base, double discountPct, String promoCode) {
        this.base = base; this.discountPct = discountPct; this.promoCode = promoCode;
    }

    @Override public String getName() { return "Discount (" + promoCode + " -" + discountPct + "%)"; }
    @Override public Money computeFare(Flight f, CabinClass c, LocalDate d, int sl) {
        Money baseFare = base.computeFare(f, c, d, sl);
        Money discount = baseFare.percentage(discountPct);
        Money discounted = baseFare.subtract(discount);
        System.out.printf("[Discount] %s code=%s -%.0f%% → %s%n", c, promoCode, discountPct, discounted);
        return discounted;
    }
}

// ============================================================
// 10. FLIGHT SEARCH RESULT
// ============================================================
class FlightSearchResult {
    private final Flight        flight;
    private final CabinClass    cabin;
    private final Money         farePerPax;
    private final Money         totalFare;      // after Decorator chain
    private final int           seatsAvailable;
    private final FareComponent fareBreakdown;  // full Decorator chain for display
    private final FareType      fareType;
    private final boolean       refundable;
    private final int           freeBaggage;    // kg

    public FlightSearchResult(Flight flight, CabinClass cabin,
                               Money farePerPax, int seatsAvailable,
                               FareComponent fareBreakdown, FareType fareType,
                               boolean refundable, int freeBaggage, int passengers) {
        this.flight          = flight;
        this.cabin           = cabin;
        this.farePerPax      = farePerPax;
        this.seatsAvailable  = seatsAvailable;
        this.fareBreakdown   = fareBreakdown;
        this.totalFare       = fareBreakdown.calculate(passengers);
        this.fareType        = fareType;
        this.refundable      = refundable;
        this.freeBaggage     = freeBaggage;
    }

    public Flight   getFlight()          { return flight; }
    public CabinClass getCabin()         { return cabin; }
    public Money    getFarePerPax()      { return farePerPax; }
    public Money    getTotalFare()       { return totalFare; }
    public int      getSeatsAvailable()  { return seatsAvailable; }
    public FareType getFareType()        { return fareType; }
    public boolean  isRefundable()       { return refundable; }
    public int      getFreeBaggage()     { return freeBaggage; }

    public void printFareBreakdown(int passengers) {
        System.out.printf(
            "  Flight : %s%n  Cabin  : %s%n  Fare   : %s%n  Total  : %s (for %d pax)%n  Baggage: %dkg free | Refundable: %s%n",
            flight.getFlightNumber(), cabin, farePerPax,
            fareBreakdown.calculate(passengers), passengers,
            freeBaggage, refundable ? "Yes" : "No");
        System.out.println("  Breakdown: " + fareBreakdown.description());
    }
}

// ============================================================
// 11. BOOKING — STATE-BEARING entity with BUILDER
// ============================================================
class Booking {
    private static final AtomicLong idGen = new AtomicLong(1_000_000);

    private final long              bookingId;
    private final String            pnr;           // 6-char alphanumeric
    private final Passenger         primaryPassenger;
    private final List<Passenger>   allPassengers;
    private final Flight            outboundFlight;
    private final Flight            returnFlight;  // null for one-way
    private final CabinClass        cabin;
    private final Map<Long, Seat>   passengerSeats; // passengerId → seat
    private final Money             totalFare;
    private       BookingStatus     status;
    private       PaymentMethod     paymentMethod;
    private       String            paymentRef;
    private       Money             refundAmount;
    private final LocalDateTime     createdAt;
    private       LocalDateTime     updatedAt;
    private final FareType          fareType;
    private       CancellationReason cancellationReason;

    private Booking(Builder b) {
        this.bookingId        = idGen.getAndIncrement();
        this.pnr              = generatePNR();
        this.primaryPassenger = b.primaryPassenger;
        this.allPassengers    = Collections.unmodifiableList(b.allPassengers);
        this.outboundFlight   = b.outboundFlight;
        this.returnFlight     = b.returnFlight;
        this.cabin            = b.cabin;
        this.passengerSeats   = new ConcurrentHashMap<>();
        this.totalFare        = b.totalFare;
        this.status           = BookingStatus.INITIATED;
        this.fareType         = b.fareType;
        this.createdAt        = LocalDateTime.now();
        this.updatedAt        = LocalDateTime.now();
    }

    private static String generatePNR() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rng = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }

    // State transitions
    public void holdSeats()       { status = BookingStatus.SEAT_HOLD;        updatedAt = LocalDateTime.now(); }
    public void initPayment()     { status = BookingStatus.PAYMENT_PENDING;  updatedAt = LocalDateTime.now(); }
    public void confirm(String payRef, PaymentMethod method) {
        status = BookingStatus.CONFIRMED;
        paymentRef = payRef; paymentMethod = method;
        updatedAt = LocalDateTime.now();
    }
    public void ticket()          { status = BookingStatus.TICKETED;         updatedAt = LocalDateTime.now(); }
    public void cancel(CancellationReason reason) {
        status = BookingStatus.CANCELLED;
        cancellationReason = reason;
        updatedAt = LocalDateTime.now();
    }
    public void initiateRefund(Money amount) {
        status = BookingStatus.REFUND_INITIATED;
        refundAmount = amount;
        updatedAt = LocalDateTime.now();
    }
    public void completeRefund() { status = BookingStatus.REFUND_COMPLETED; updatedAt = LocalDateTime.now(); }
    public void markNoShow()     { status = BookingStatus.NO_SHOW;          updatedAt = LocalDateTime.now(); }

    public void assignSeat(long passengerId, Seat seat) { passengerSeats.put(passengerId, seat); }

    public long          getBookingId()           { return bookingId; }
    public String        getPnr()                 { return pnr; }
    public Passenger     getPrimaryPassenger()     { return primaryPassenger; }
    public List<Passenger> getAllPassengers()      { return allPassengers; }
    public Flight        getOutboundFlight()       { return outboundFlight; }
    public Flight        getReturnFlight()         { return returnFlight; }
    public CabinClass    getCabin()               { return cabin; }
    public BookingStatus getStatus()              { return status; }
    public Money         getTotalFare()           { return totalFare; }
    public String        getPaymentRef()          { return paymentRef; }
    public FareType      getFareType()            { return fareType; }
    public Money         getRefundAmount()        { return refundAmount; }
    public CancellationReason getCancellationReason() { return cancellationReason; }
    public Map<Long, Seat> getPassengerSeats()    { return Collections.unmodifiableMap(passengerSeats); }

    static class Builder {
        private final Passenger      primaryPassenger;
        private final List<Passenger> allPassengers;
        private final Flight         outboundFlight;
        private final CabinClass     cabin;
        private final Money          totalFare;
        private       Flight         returnFlight;
        private       FareType       fareType = FareType.SAVER;

        public Builder(Passenger primary, List<Passenger> all,
                       Flight flight, CabinClass cabin, Money totalFare) {
            this.primaryPassenger = primary;
            this.allPassengers    = all;
            this.outboundFlight   = flight;
            this.cabin            = cabin;
            this.totalFare        = totalFare;
        }
        public Builder returnFlight(Flight f) { returnFlight = f; return this; }
        public Builder fareType(FareType t)   { fareType = t;     return this; }
        public Booking build()               { return new Booking(this); }
    }

    @Override public String toString() {
        return String.format("Booking[#%d | PNR:%s | %s | %s | %s | %s]",
            bookingId, pnr, primaryPassenger.getFullName(),
            outboundFlight.getFlightNumber(), totalFare, status);
    }
}

// ============================================================
// 12. PAYMENT GATEWAY — SINGLETON
// ============================================================
class PaymentGateway {
    private static PaymentGateway instance;
    private final Map<String, PaymentStatus> transactions = new ConcurrentHashMap<>();
    private final AtomicLong txnIdGen = new AtomicLong(9_000_000);

    private PaymentGateway() {}
    public static synchronized PaymentGateway getInstance() {
        if (instance == null) instance = new PaymentGateway();
        return instance;
    }

    public String initiatePayment(Money amount, PaymentMethod method, long bookingId) {
        String txnId = "TXN" + txnIdGen.getAndIncrement();
        transactions.put(txnId, PaymentStatus.PENDING);
        System.out.printf("[Payment] Initiated %s via %s | TXN: %s%n", amount, method, txnId);
        return txnId;
    }

    public PaymentStatus processPayment(String txnId) {
        // Simulate: 90% success, 10% failure
        boolean success = Math.random() > 0.10;
        PaymentStatus status = success ? PaymentStatus.CAPTURED : PaymentStatus.FAILED;
        transactions.put(txnId, status);
        System.out.printf("[Payment] TXN %s → %s%n", txnId, status);
        return status;
    }

    public PaymentStatus initiateRefund(String txnId, Money amount) {
        transactions.put(txnId + "_REFUND", PaymentStatus.REFUNDED);
        System.out.printf("[Payment] Refund initiated %s for TXN %s%n", amount, txnId);
        return PaymentStatus.REFUNDED;
    }

    public PaymentStatus getStatus(String txnId) {
        return transactions.getOrDefault(txnId, PaymentStatus.PENDING);
    }
}

// ============================================================
// 13. BOOKING EVENT OBSERVER — OBSERVER PATTERN
// ============================================================
interface BookingEventObserver {
    void onBookingCreated(Booking booking);
    void onSeatsHeld(Booking booking);
    void onBookingConfirmed(Booking booking);
    void onBookingCancelled(Booking booking, CancellationReason reason);
    void onRefundInitiated(Booking booking, Money refundAmount);
    void onPaymentFailed(Booking booking, String txnId);
    void onFlightStatusChanged(Flight flight, FlightStatus newStatus);
}

class NotificationObserver implements BookingEventObserver {
    @Override public void onBookingCreated(Booking b) {
        System.out.printf("[Notif → %s] Booking initiated for %s (PNR: %s)%n",
            b.getPrimaryPassenger().getEmail(),
            b.getOutboundFlight().getFlightNumber(), b.getPnr());
    }
    @Override public void onSeatsHeld(Booking b) {
        System.out.printf("[Notif → %s] Seats held for 10 min. Complete payment to confirm PNR %s%n",
            b.getPrimaryPassenger().getEmail(), b.getPnr());
    }
    @Override public void onBookingConfirmed(Booking b) {
        System.out.printf("[Notif → %s] 🎉 Booking CONFIRMED! PNR: %s | %s%n",
            b.getPrimaryPassenger().getEmail(), b.getPnr(),
            b.getOutboundFlight().getFlightNumber());
        System.out.printf("[Notif → %s] SMS sent to %s%n",
            b.getPrimaryPassenger().getPhone(), b.getPrimaryPassenger().getPhone());
    }
    @Override public void onBookingCancelled(Booking b, CancellationReason r) {
        System.out.printf("[Notif → %s] Booking PNR %s CANCELLED — reason: %s%n",
            b.getPrimaryPassenger().getEmail(), b.getPnr(), r);
    }
    @Override public void onRefundInitiated(Booking b, Money amt) {
        System.out.printf("[Notif → %s] Refund of %s initiated for PNR %s (5-7 business days)%n",
            b.getPrimaryPassenger().getEmail(), amt, b.getPnr());
    }
    @Override public void onPaymentFailed(Booking b, String txnId) {
        System.out.printf("[Notif → %s] Payment failed for PNR %s (TXN: %s). Please retry.%n",
            b.getPrimaryPassenger().getEmail(), b.getPnr(), txnId);
    }
    @Override public void onFlightStatusChanged(Flight f, FlightStatus s) {
        System.out.printf("[Notif] Flight %s status → %s%n", f.getFlightNumber(), s);
    }
}

class InventoryObserver implements BookingEventObserver {
    @Override public void onSeatsHeld(Booking b) {
        System.out.printf("[Inventory] %d seats HELD on %s (%s)%n",
            b.getAllPassengers().size(), b.getOutboundFlight().getFlightNumber(), b.getCabin());
    }
    @Override public void onBookingConfirmed(Booking b) {
        System.out.printf("[Inventory] %d seats BOOKED on %s — Available: %d%n",
            b.getAllPassengers().size(), b.getOutboundFlight().getFlightNumber(),
            b.getOutboundFlight().getAvailableSeats(b.getCabin()));
    }
    @Override public void onBookingCancelled(Booking b, CancellationReason r) {
        System.out.printf("[Inventory] Seats released back to pool for %s%n",
            b.getOutboundFlight().getFlightNumber());
    }
    @Override public void onBookingCreated(Booking b) {}
    @Override public void onRefundInitiated(Booking b, Money a) {}
    @Override public void onPaymentFailed(Booking b, String t) {}
    @Override public void onFlightStatusChanged(Flight f, FlightStatus s) {
        if (s == FlightStatus.CANCELLED) {
            System.out.printf("[Inventory] Flight %s CANCELLED — all seats returned to pool%n",
                f.getFlightNumber());
        }
    }
}

class LoyaltyObserver implements BookingEventObserver {
    // 1 point per ₹100 spent
    private static final int POINTS_PER_HUNDRED = 1;

    @Override public void onBookingConfirmed(Booking b) {
        long points = b.getTotalFare().getPaise() / 10_000 * POINTS_PER_HUNDRED;
        b.getPrimaryPassenger().addLoyaltyPoints((int) points);
    }
    @Override public void onBookingCancelled(Booking b, CancellationReason r) {
        // Deduct points on cancellation if not full-flex
        if (b.getFareType() != FareType.FULL_FLEX) {
            System.out.printf("[Loyalty] Points may be partially forfeited for %s (fare=%s)%n",
                b.getPnr(), b.getFareType());
        }
    }
    @Override public void onBookingCreated(Booking b)     {}
    @Override public void onSeatsHeld(Booking b)          {}
    @Override public void onRefundInitiated(Booking b, Money a) {}
    @Override public void onPaymentFailed(Booking b, String t) {}
    @Override public void onFlightStatusChanged(Flight f, FlightStatus s) {}
}

class AuditObserver implements BookingEventObserver {
    private final List<String> log = new CopyOnWriteArrayList<>();

    private void record(String msg) {
        String entry = LocalDateTime.now().toLocalTime() + " | " + msg;
        log.add(entry);
        System.out.println("[Audit] " + entry);
    }

    @Override public void onBookingCreated(Booking b)  { record("CREATED PNR=" + b.getPnr()); }
    @Override public void onSeatsHeld(Booking b)       { record("SEATS_HELD PNR=" + b.getPnr()); }
    @Override public void onBookingConfirmed(Booking b){ record("CONFIRMED PNR=" + b.getPnr() + " fare=" + b.getTotalFare()); }
    @Override public void onBookingCancelled(Booking b, CancellationReason r) {
        record("CANCELLED PNR=" + b.getPnr() + " reason=" + r);
    }
    @Override public void onRefundInitiated(Booking b, Money a) { record("REFUND PNR=" + b.getPnr() + " amount=" + a); }
    @Override public void onPaymentFailed(Booking b, String t)  { record("PAYMENT_FAILED PNR=" + b.getPnr() + " txn=" + t); }
    @Override public void onFlightStatusChanged(Flight f, FlightStatus s) { record("FLIGHT_STATUS " + f.getFlightNumber() + "→" + s); }

    public List<String> getLog() { return Collections.unmodifiableList(log); }
}

// ============================================================
// 14. BOOKING COMMAND — COMMAND PATTERN
// ============================================================
interface BookingCommand {
    Booking execute();
    void undo();
}

class HoldSeatsCommand implements BookingCommand {
    private final Booking booking;
    private final List<Seat> allocatedSeats = new ArrayList<>();
    private static final int HOLD_MINUTES = 10;

    public HoldSeatsCommand(Booking booking) { this.booking = booking; }

    @Override
    public Booking execute() {
        Flight f = booking.getOutboundFlight();
        CabinClass cabin = booking.getCabin();
        int needed = booking.getAllPassengers().size();

        for (Passenger p : booking.getAllPassengers()) {
            Optional<Seat> seat = f.findAvailableSeat(cabin);
            if (seat.isEmpty()) {
                System.out.println("[HoldSeatsCmd] Not enough seats — rolling back holds");
                undo(); return booking;
            }
            if (seat.get().hold(booking.getPnr(), HOLD_MINUTES)) {
                allocatedSeats.add(seat.get());
                booking.assignSeat(p.getPassengerId(), seat.get());
            }
        }

        if (allocatedSeats.size() == needed) {
            booking.holdSeats();
            System.out.printf("[HoldSeatsCmd] %d seats held for PNR %s (10-min TTL)%n",
                needed, booking.getPnr());
        } else {
            undo();
        }
        return booking;
    }

    @Override
    public void undo() {
        allocatedSeats.forEach(Seat::release);
        allocatedSeats.clear();
        System.out.println("[HoldSeatsCmd] All holds released for PNR " + booking.getPnr());
    }
}

class ConfirmBookingCommand implements BookingCommand {
    private final Booking        booking;
    private final PaymentGateway gateway;
    private final PaymentMethod  paymentMethod;
    private       String         txnId;

    public ConfirmBookingCommand(Booking booking, PaymentGateway gateway,
                                  PaymentMethod paymentMethod) {
        this.booking       = booking;
        this.gateway       = gateway;
        this.paymentMethod = paymentMethod;
    }

    @Override
    public Booking execute() {
        booking.initPayment();
        txnId = gateway.initiatePayment(booking.getTotalFare(), paymentMethod, booking.getBookingId());
        PaymentStatus result = gateway.processPayment(txnId);

        if (result == PaymentStatus.CAPTURED) {
            // Confirm all held seats
            booking.getAllPassengers().forEach(p -> {
                Seat s = booking.getPassengerSeats().get(p.getPassengerId());
                if (s != null) s.confirm(booking.getPnr());
            });
            booking.confirm(txnId, paymentMethod);
            booking.ticket();
            System.out.printf("[ConfirmCmd] PNR %s TICKETED → TXN %s%n",
                booking.getPnr(), txnId);
        } else {
            // Payment failed — release all holds
            booking.getAllPassengers().forEach(p -> {
                Seat s = booking.getPassengerSeats().get(p.getPassengerId());
                if (s != null) s.release();
            });
            booking.cancel(CancellationReason.PAYMENT_FAILURE);
        }
        return booking;
    }

    @Override
    public void undo() {
        // Cancellation / refund path
        if (booking.getStatus() == BookingStatus.TICKETED ||
            booking.getStatus() == BookingStatus.CONFIRMED) {
            Money refund = computeRefund();
            booking.cancel(CancellationReason.USER_REQUESTED);
            booking.initiateRefund(refund);
            gateway.initiateRefund(txnId, refund);
            // Release seats
            booking.getAllPassengers().forEach(p -> {
                Seat s = booking.getPassengerSeats().get(p.getPassengerId());
                if (s != null) s.release();
            });
            System.out.printf("[ConfirmCmd UNDO] Booking %s cancelled | Refund: %s%n",
                booking.getPnr(), refund);
        }
    }

    private Money computeRefund() {
        // Refund rules: FULL_FLEX = 100%, FLEXI = 75%, SAVER = 50%, BUSINESS_LITE = 25%
        double pct = switch (booking.getFareType()) {
            case FULL_FLEX      -> 100.0;
            case FLEXI          -> 75.0;
            case SAVER          -> 50.0;
            case BUSINESS_LITE  -> 25.0;
        };
        return booking.getTotalFare().percentage(pct);
    }

    public String getTxnId() { return txnId; }
}

// ============================================================
// 15. ABSTRACT BOOKING PROCESSOR — TEMPLATE METHOD
// ============================================================
abstract class AbstractBookingProcessor {
    protected final List<BookingEventObserver> observers = new ArrayList<>();
    protected final Map<Long, Booking>         allBookings = new ConcurrentHashMap<>();

    public void addObserver(BookingEventObserver o) { observers.add(o); }

    // Template method — fixed booking skeleton
    public final Booking processBooking(SearchQuery query,
                                         FlightSearchResult result,
                                         List<Passenger> passengers,
                                         PaymentMethod paymentMethod,
                                         boolean addInsurance) {
        // 1. Validate availability
        if (!validateAvailability(result, passengers.size())) {
            System.out.println("[Processor] Not enough seats available");
            return null;
        }

        // 2. Build fare with decorators
        FareComponent fare = buildFare(result, addInsurance);

        // 3. Create booking
        Booking booking = createBooking(passengers, result, fare, addInsurance);
        allBookings.put(booking.getBookingId(), booking);
        notifyAll(o -> o.onBookingCreated(booking));

        // 4. Hold seats
        HoldSeatsCommand holdCmd = new HoldSeatsCommand(booking);
        holdCmd.execute();
        if (booking.getStatus() != BookingStatus.SEAT_HOLD) {
            System.out.println("[Processor] Seat hold failed — aborting booking");
            return booking;
        }
        notifyAll(o -> o.onSeatsHeld(booking));

        // 5. Process payment
        ConfirmBookingCommand confirmCmd =
            new ConfirmBookingCommand(booking, PaymentGateway.getInstance(), paymentMethod);
        confirmCmd.execute();

        // 6. Post-process based on payment outcome
        if (booking.getStatus() == BookingStatus.TICKETED) {
            postConfirmation(booking);
            notifyAll(o -> o.onBookingConfirmed(booking));
        } else {
            notifyAll(o -> o.onPaymentFailed(booking, confirmCmd.getTxnId()));
        }

        return booking;
    }

    // Hooks — subclasses override
    protected abstract boolean       validateAvailability(FlightSearchResult result, int paxCount);
    protected abstract FareComponent buildFare(FlightSearchResult result, boolean addInsurance);
    protected abstract Booking       createBooking(List<Passenger> passengers,
                                                   FlightSearchResult result,
                                                   FareComponent fare,
                                                   boolean addInsurance);
    protected abstract void          postConfirmation(Booking booking);

    public Booking cancelBooking(long bookingId, CancellationReason reason) {
        Booking booking = allBookings.get(bookingId);
        if (booking == null) { System.out.println("[Processor] Booking not found"); return null; }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            System.out.println("[Processor] Already cancelled"); return booking;
        }
        ConfirmBookingCommand cancelCmd =
            new ConfirmBookingCommand(booking, PaymentGateway.getInstance(), null);
        cancelCmd.undo();
        Money refund = booking.getRefundAmount();
        notifyAll(o -> o.onBookingCancelled(booking, reason));
        if (refund != null && !refund.isZero())
            notifyAll(o -> o.onRefundInitiated(booking, refund));
        return booking;
    }

    protected void notifyAll(java.util.function.Consumer<BookingEventObserver> action) {
        observers.forEach(action);
    }

    public Map<Long, Booking> getAllBookings() { return Collections.unmodifiableMap(allBookings); }
}

// ============================================================
// 16. STANDARD BOOKING PROCESSOR (concrete)
// ============================================================
class StandardBookingProcessor extends AbstractBookingProcessor {
    private final PricingStrategy pricingStrategy;

    public StandardBookingProcessor(PricingStrategy strategy) {
        this.pricingStrategy = strategy;
    }

    @Override
    protected boolean validateAvailability(FlightSearchResult result, int paxCount) {
        int available = result.getFlight().getAvailableSeats(result.getCabin());
        if (available < paxCount) {
            System.out.printf("[Validate] Only %d seats available, need %d%n", available, paxCount);
            return false;
        }
        return true;
    }

    @Override
    protected FareComponent buildFare(FlightSearchResult result, boolean addInsurance) {
        FareComponent fare = new BaseFare(result.getFarePerPax(), result.getFareType());
        fare = new AirportTaxDecorator(fare);
        fare = new ConvenienceFeeDecorator(fare, Money.inr(299));
        if (addInsurance) fare = new TravelInsuranceDecorator(fare, Money.inr(199));
        return fare;
    }

    @Override
    protected Booking createBooking(List<Passenger> passengers, FlightSearchResult result,
                                     FareComponent fare, boolean addInsurance) {
        Money total = fare.calculate(passengers.size());
        return new Booking.Builder(passengers.get(0), passengers,
                result.getFlight(), result.getCabin(), total)
            .fareType(result.getFareType())
            .build();
    }

    @Override
    protected void postConfirmation(Booking booking) {
        System.out.printf("[Processor] Post-confirm: issuing e-ticket for PNR %s%n",
            booking.getPnr());
        // In production: generate PDF e-ticket, sync with airline GDS, update FFP
    }
}

// ============================================================
// 17. FLIGHT FACTORY
// ============================================================
class FlightFactory {
    public static Flight domestic(String flightNumber, String airline,
                                   Airport origin, Airport destination,
                                   LocalDateTime dep, int durationMinutes) {
        return new Flight(flightNumber, airline, origin, destination,
            dep, dep.plusMinutes(durationMinutes));
    }

    public static Flight international(String flightNumber, String airline,
                                        Airport origin, Airport destination,
                                        LocalDateTime dep, int durationMinutes) {
        return new Flight(flightNumber, airline, origin, destination,
            dep, dep.plusMinutes(durationMinutes));
    }

    public static List<Flight> connectingItinerary(String[] flightNumbers, String airline,
                                                    Airport[] airports, LocalDateTime firstDep,
                                                    int[] durations, int[] layovers) {
        List<Flight> legs = new ArrayList<>();
        LocalDateTime current = firstDep;
        for (int i = 0; i < flightNumbers.length; i++) {
            LocalDateTime arr = current.plusMinutes(durations[i]);
            legs.add(new Flight(flightNumbers[i], airline,
                airports[i], airports[i + 1], current, arr));
            if (i < layovers.length) current = arr.plusMinutes(layovers[i]);
        }
        return legs;
    }
}

// ============================================================
// 18. BOOKING SYSTEM — SINGLETON
// ============================================================
class BookingSystem {
    private static BookingSystem instance;

    private final Map<Long, Flight>    flightInventory = new ConcurrentHashMap<>();
    private final List<BookingEventObserver> globalObservers = new ArrayList<>();
    private final AuditObserver        auditObserver   = new AuditObserver();
    private final InventoryObserver    inventoryObs    = new InventoryObserver();
    private final NotificationObserver notifObs        = new NotificationObserver();
    private final LoyaltyObserver      loyaltyObs      = new LoyaltyObserver();

    private BookingSystem() {
        globalObservers.addAll(List.of(auditObserver, inventoryObs, notifObs, loyaltyObs));
    }

    public static synchronized BookingSystem getInstance() {
        if (instance == null) instance = new BookingSystem();
        return instance;
    }

    public void registerFlight(Flight f) {
        flightInventory.put(f.getFlightId(), f);
        System.out.println("[System] Registered: " + f);
    }

    public StandardBookingProcessor createProcessor(PricingStrategy strategy) {
        StandardBookingProcessor proc = new StandardBookingProcessor(strategy);
        globalObservers.forEach(proc::addObserver);
        return proc;
    }

    /** Search available flights for a given query */
    public List<FlightSearchResult> searchFlights(SearchQuery query,
                                                   PricingStrategy pricing) {
        List<FlightSearchResult> results = new ArrayList<>();

        for (Flight f : flightInventory.values()) {
            if (!f.getOrigin().getIataCode().equals(query.getOrigin().getIataCode())) continue;
            if (!f.getDestination().getIataCode().equals(query.getDestination().getIataCode())) continue;
            if (!f.getDeparture().toLocalDate().equals(query.getTravelDate())) continue;
            if (f.getStatus() == FlightStatus.CANCELLED) continue;

            int seatsLeft = f.getAvailableSeats(query.getCabin());
            if (seatsLeft < query.getTotalPax()) continue;

            Money farePerPax = pricing.computeFare(f, query.getCabin(),
                query.getTravelDate(), seatsLeft);

            // Build fare with standard decorators for display
            FareComponent fare = new AirportTaxDecorator(
                new ConvenienceFeeDecorator(
                    new BaseFare(farePerPax, FareType.SAVER),
                    Money.inr(299)));

            results.add(new FlightSearchResult(f, query.getCabin(), farePerPax,
                seatsLeft, fare, FareType.SAVER, false, 15, query.getTotalPax()));
        }

        // Sort cheapest first
        results.sort(Comparator.comparing(FlightSearchResult::getTotalFare));
        System.out.printf("[Search] Found %d flights for %s%n", results.size(), query);
        return results;
    }

    public void updateFlightStatus(long flightId, FlightStatus newStatus) {
        Flight f = flightInventory.get(flightId);
        if (f == null) return;
        FlightStatus old = f.getStatus();
        f.setStatus(newStatus);
        System.out.printf("[System] Flight %s: %s → %s%n", f.getFlightNumber(), old, newStatus);
        globalObservers.forEach(o -> o.onFlightStatusChanged(f, newStatus));
    }

    public AuditObserver getAuditObserver() { return auditObserver; }
}

// ============================================================
// 19. MAIN — DRIVER + SCENARIOS
// ============================================================
public class AirlineBookingSystem {
    public static void main(String[] args) throws InterruptedException {

        BookingSystem system = BookingSystem.getInstance();

        // ---- Airports ----
        Airport del = new Airport("DEL", "Indira Gandhi International", "Delhi",   "India", "IST+5:30");
        Airport bom = new Airport("BOM", "Chhatrapati Shivaji Maharaj","Mumbai",   "India", "IST+5:30");
        Airport blr = new Airport("BLR", "Kempegowda International",   "Bangalore","India", "IST+5:30");
        Airport ccu = new Airport("CCU", "Netaji Subhas Chandra Bose", "Kolkata",  "India", "IST+5:30");

        // ---- Flights ----
        LocalDate today = LocalDate.now();
        Flight f1 = FlightFactory.domestic("6E-204", "IndiGo", del, bom,
            today.atTime(6, 0), 130);
        Flight f2 = FlightFactory.domestic("AI-101", "Air India", del, bom,
            today.atTime(10, 30), 125);
        Flight f3 = FlightFactory.domestic("SG-108", "SpiceJet", del, blr,
            today.atTime(7, 45), 165);
        Flight f4 = FlightFactory.domestic("6E-990", "IndiGo", del, ccu,
            today.atTime(14, 0), 120);

        system.registerFlight(f1);
        system.registerFlight(f2);
        system.registerFlight(f3);
        system.registerFlight(f4);

        // ===== SCENARIO 1: Search and book — Fixed pricing =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 1: Search Flights DEL→BOM (Fixed Pricing)");
        System.out.println("=".repeat(65));

        PricingStrategy basePricing = new BasePricingStrategy();
        SearchQuery q1 = new SearchQuery.Builder(del, bom, today)
            .adults(2).cabin(CabinClass.ECONOMY).build();

        List<FlightSearchResult> results1 = system.searchFlights(q1, basePricing);
        results1.forEach(r -> { System.out.println(); r.printFareBreakdown(2); });

        // ===== SCENARIO 2: Book first result =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 2: Book DEL→BOM (2 passengers, Economy)");
        System.out.println("=".repeat(65));

        Passenger p1 = new Passenger("Rahul", "Sharma", "rahul@email.com", "9876543210",
            LocalDate.of(1990, 4, 15));
        Passenger p2 = new Passenger("Priya", "Sharma", "priya@email.com", "9876543211",
            LocalDate.of(1992, 8, 22));

        StandardBookingProcessor proc = system.createProcessor(basePricing);
        Booking b1 = proc.processBooking(q1, results1.get(0),
            List.of(p1, p2), PaymentMethod.UPI, false);

        if (b1 != null) {
            System.out.println("\nFinal booking: " + b1);
            System.out.println("Seats assigned: " + b1.getPassengerSeats());
        }

        // ===== SCENARIO 3: Dynamic pricing — low seat count =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 3: Dynamic Pricing — Low Availability");
        System.out.println("=".repeat(65));

        PricingStrategy dynamic = new DynamicPricingStrategy(basePricing);
        SearchQuery q2 = new SearchQuery.Builder(del, blr, today)
            .adults(1).cabin(CabinClass.ECONOMY).build();

        List<FlightSearchResult> results2 = system.searchFlights(q2, dynamic);
        if (!results2.isEmpty()) results2.get(0).printFareBreakdown(1);

        // ===== SCENARIO 4: Promo code discount =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 4: Promo Code IXIGO25 → 25% Off");
        System.out.println("=".repeat(65));

        PricingStrategy discounted = new DiscountPricingStrategy(basePricing, 25.0, "IXIGO25");
        SearchQuery q3 = new SearchQuery.Builder(del, bom, today)
            .adults(1).cabin(CabinClass.ECONOMY).build();
        List<FlightSearchResult> promoResults = system.searchFlights(q3, discounted);
        if (!promoResults.isEmpty()) promoResults.get(0).printFareBreakdown(1);

        // ===== SCENARIO 5: Book with insurance =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 5: Single Passenger + Travel Insurance");
        System.out.println("=".repeat(65));

        Passenger p3 = new Passenger("Anita", "Gupta", "anita@email.com", "9000000001",
            LocalDate.of(1985, 1, 10));
        List<FlightSearchResult> r3 = system.searchFlights(q3, basePricing);
        if (!r3.isEmpty()) {
            Booking b3 = proc.processBooking(q3, r3.get(0), List.of(p3),
                PaymentMethod.CREDIT_CARD, true); // add insurance
            System.out.println("\nBooking with insurance: " + b3);
        }

        // ===== SCENARIO 6: Cancellation and refund =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 6: Cancel Booking → Refund (SAVER — 50% back)");
        System.out.println("=".repeat(65));

        if (b1 != null && b1.getStatus() == BookingStatus.TICKETED) {
            Booking cancelled = proc.cancelBooking(b1.getBookingId(),
                CancellationReason.USER_REQUESTED);
            System.out.println("After cancel: " + cancelled);
        }

        // ===== SCENARIO 7: Surge pricing — holiday season =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 7: Surge Pricing — Diwali Season 2.0x");
        System.out.println("=".repeat(65));

        PricingStrategy surge = new SurgePricingStrategy(basePricing, 200.0, "Diwali Season");
        SearchQuery q4 = new SearchQuery.Builder(del, ccu, today)
            .adults(1).cabin(CabinClass.ECONOMY).build();
        List<FlightSearchResult> surgeResults = system.searchFlights(q4, surge);
        if (!surgeResults.isEmpty()) surgeResults.get(0).printFareBreakdown(1);

        // ===== SCENARIO 8: Flight cancellation by airline =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 8: Airline Cancels Flight → Inventory Released");
        System.out.println("=".repeat(65));

        system.updateFlightStatus(f4.getFlightId(), FlightStatus.CANCELLED);

        // ===== SCENARIO 9: Business class booking =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 9: Business Class Booking with Loyalty Points");
        System.out.println("=".repeat(65));

        Passenger p4 = new Passenger("Vikram", "Nair", "vikram@corp.com", "9900000001",
            LocalDate.of(1978, 6, 5));
        SearchQuery bizQ = new SearchQuery.Builder(del, bom, today)
            .adults(1).cabin(CabinClass.BUSINESS).build();

        PricingStrategy bizPricing = new BasePricingStrategy();
        List<FlightSearchResult> bizResults = system.searchFlights(bizQ, bizPricing);
        if (!bizResults.isEmpty()) {
            Booking bizBooking = proc.processBooking(bizQ, bizResults.get(0),
                List.of(p4), PaymentMethod.CREDIT_CARD, false);
            System.out.println("\nBusiness booking: " + bizBooking);
            System.out.printf("Loyalty points balance: %d%n", p4.getLoyaltyPoints());
        }

        // ===== SCENARIO 10: Audit log =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 10: Audit Log (last 10 entries)");
        System.out.println("=".repeat(65));

        List<String> log = system.getAuditObserver().getLog();
        log.stream().skip(Math.max(0, log.size() - 10)).forEach(System.out::println);

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern        | Class(es)
            ---------------|--------------------------------------------------
            Singleton      | BookingSystem, PaymentGateway
            State          | BookingStatus — INITIATED→SEAT_HOLD→PAYMENT_PENDING→CONFIRMED→TICKETED→CANCELLED→REFUND
            Strategy       | PricingStrategy → Base / Dynamic / Surge / Discount
            Observer       | BookingEventObserver → Notification / Inventory / Loyalty / Audit
            Factory        | FlightFactory (domestic / international / connecting)
            Builder        | SearchQuery.Builder, Booking.Builder
            Command        | HoldSeatsCommand, ConfirmBookingCommand (execute + undo = cancel)
            Template Method| AbstractBookingProcessor.processBooking() — 6-step skeleton
            Decorator      | FareDecorator → AirportTax + ConvenienceFee + TravelInsurance
            """);
    }
}
