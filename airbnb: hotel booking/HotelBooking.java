import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// ==========================================
// 1. ENUMS
// ==========================================
enum RoomType { SINGLE, DOUBLE, SUITE, DELUXE }
enum RoomStatus { AVAILABLE, BOOKED, UNDER_MAINTENANCE }
enum BookingStatus { CONFIRMED, CANCELLED, COMPLETED, PENDING }
enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
enum AmenityType { WIFI, POOL, GYM, SPA, PARKING, BREAKFAST }

// ==========================================
// 2. CORE ENTITIES
// ==========================================
class User {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;
    private final String email;

    public User(String name, String email) {
        this.id = idGen.getAndIncrement();
        this.name = name;
        this.email = email;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }

    @Override public String toString() { return "User[" + name + "]"; }
}

class Address {
    private final String street, city, country;
    private final int pincode;

    public Address(String street, String city, String country, int pincode) {
        this.street = street; this.city = city;
        this.country = country; this.pincode = pincode;
    }

    public String getCity() { return city; }

    @Override public String toString() { return street + ", " + city + ", " + country; }
}

class Room {
    private static final AtomicInteger idGen = new AtomicInteger(100);
    private final int id;
    private final int roomNumber;
    private final RoomType type;
    private final double pricePerNight;
    private RoomStatus status;
    private final List<AmenityType> amenities;

    public Room(int roomNumber, RoomType type, double pricePerNight, List<AmenityType> amenities) {
        this.id = idGen.getAndIncrement();
        this.roomNumber = roomNumber;
        this.type = type;
        this.pricePerNight = pricePerNight;
        this.status = RoomStatus.AVAILABLE;
        this.amenities = amenities;
    }

    public int getId() { return id; }
    public int getRoomNumber() { return roomNumber; }
    public RoomType getType() { return type; }
    public double getPricePerNight() { return pricePerNight; }
    public RoomStatus getStatus() { return status; }
    public void setStatus(RoomStatus s) { this.status = s; }

    @Override public String toString() {
        return "Room[" + roomNumber + ", " + type + ", Rs" + pricePerNight + "/night]";
    }
}

// ==========================================
// 3. STRATEGY PATTERN — PRICING
// ==========================================
interface PricingStrategy {
    double calculate(Room room, LocalDate checkIn, LocalDate checkOut);
}

class StandardPricing implements PricingStrategy {
    @Override
    public double calculate(Room room, LocalDate checkIn, LocalDate checkOut) {
        long nights = Math.max(1, ChronoUnit.DAYS.between(checkIn, checkOut));
        return nights * room.getPricePerNight();
    }
}

class WeekendSurgePricing implements PricingStrategy {
    @Override
    public double calculate(Room room, LocalDate checkIn, LocalDate checkOut) {
        long nights = Math.max(1, ChronoUnit.DAYS.between(checkIn, checkOut));
        return nights * room.getPricePerNight() * 1.30; // 30% surge
    }
}

class LongStayDiscountPricing implements PricingStrategy {
    @Override
    public double calculate(Room room, LocalDate checkIn, LocalDate checkOut) {
        long nights = Math.max(1, ChronoUnit.DAYS.between(checkIn, checkOut));
        double base = nights * room.getPricePerNight();
        return nights > 7 ? base * 0.85 : base; // 15% off for 7+ nights
    }
}

// ==========================================
// 4. PAYMENT
// ==========================================
class Payment {
    private static final AtomicInteger idGen = new AtomicInteger(5000);
    private final int id;
    private final double amount;
    private PaymentStatus status;
    private final String method;

    public Payment(double amount, String method) {
        this.id = idGen.getAndIncrement();
        this.amount = amount;
        this.method = method;
        this.status = PaymentStatus.PENDING;
    }

    public boolean process() {
        System.out.println("[Payment] Processing Rs" + amount + " via " + method + "...");
        this.status = PaymentStatus.SUCCESS;
        System.out.println("[Payment] SUCCESS — Payment ID: " + id);
        return true;
    }

    public void refund() {
        this.status = PaymentStatus.REFUNDED;
        System.out.println("[Payment] Refunded Rs" + amount + " — Payment ID: " + id);
    }

    public int getId() { return id; }
    public double getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
}

// ==========================================
// 5. BOOKING
// ==========================================
class Booking {
    private static final AtomicInteger idGen = new AtomicInteger(9000);
    private final int id;
    private final User user;
    private final Room room;
    private final LocalDate checkIn;
    private final LocalDate checkOut;
    private BookingStatus status;
    private Payment payment;
    private final double totalAmount;

    public Booking(User user, Room room, LocalDate checkIn, LocalDate checkOut, double totalAmount) {
        this.id = idGen.getAndIncrement();
        this.user = user;
        this.room = room;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.totalAmount = totalAmount;
        this.status = BookingStatus.PENDING;
    }

    public void setPayment(Payment p) { this.payment = p; }
    public void confirm() { this.status = BookingStatus.CONFIRMED; }
    public void cancel() {
        this.status = BookingStatus.CANCELLED;
        if (payment != null) payment.refund();
    }
    public void complete() { this.status = BookingStatus.COMPLETED; }

    public int getId() { return id; }
    public User getUser() { return user; }
    public Room getRoom() { return room; }
    public LocalDate getCheckIn() { return checkIn; }
    public LocalDate getCheckOut() { return checkOut; }
    public BookingStatus getStatus() { return status; }
    public double getTotalAmount() { return totalAmount; }

    @Override public String toString() {
        return "Booking[ID:" + id + " | " + user.getName() + " | " + room +
               " | " + checkIn + " to " + checkOut +
               " | Rs" + totalAmount + " | " + status + "]";
    }
}

// ==========================================
// 6. HOTEL
// ==========================================
class Hotel {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;
    private final Address address;
    private final int starRating;
    private final List<Room> rooms = new ArrayList<>();
    // Maps roomId -> set of booked dates (for availability check)
    private final Map<Integer, Set<LocalDate>> bookedDates = new ConcurrentHashMap<>();

    public Hotel(String name, Address address, int starRating) {
        this.id = idGen.getAndIncrement();
        this.name = name;
        this.address = address;
        this.starRating = starRating;
    }

    public void addRoom(Room room) {
        rooms.add(room);
        bookedDates.put(room.getId(), new HashSet<>());
    }

    // Thread-safe availability search
    public synchronized List<Room> searchAvailableRooms(
            RoomType type, LocalDate checkIn, LocalDate checkOut) {
        return rooms.stream()
            .filter(r -> r.getType() == type)
            .filter(r -> r.getStatus() != RoomStatus.UNDER_MAINTENANCE)
            .filter(r -> isAvailable(r, checkIn, checkOut))
            .collect(Collectors.toList());
    }

    private boolean isAvailable(Room room, LocalDate checkIn, LocalDate checkOut) {
        Set<LocalDate> booked = bookedDates.getOrDefault(room.getId(), Collections.emptySet());
        LocalDate d = checkIn;
        while (d.isBefore(checkOut)) {
            if (booked.contains(d)) return false;
            d = d.plusDays(1);
        }
        return true;
    }

    public synchronized void markDatesBooked(Room room, LocalDate checkIn, LocalDate checkOut) {
        Set<LocalDate> booked = bookedDates.get(room.getId());
        LocalDate d = checkIn;
        while (d.isBefore(checkOut)) { booked.add(d); d = d.plusDays(1); }
    }

    public synchronized void releaseDates(Room room, LocalDate checkIn, LocalDate checkOut) {
        Set<LocalDate> booked = bookedDates.get(room.getId());
        LocalDate d = checkIn;
        while (d.isBefore(checkOut)) { booked.remove(d); d = d.plusDays(1); }
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public Address getAddress() { return address; }
    public int getStarRating() { return starRating; }
    public List<Room> getRooms() { return rooms; }

    @Override public String toString() {
        return "Hotel[" + name + ", " + starRating + " star, " + address.getCity() + "]";
    }
}

// ==========================================
// 7. OBSERVER PATTERN — NOTIFICATIONS
// ==========================================
interface BookingObserver {
    void onBookingConfirmed(Booking booking);
    void onBookingCancelled(Booking booking);
}

class EmailNotifier implements BookingObserver {
    @Override
    public void onBookingConfirmed(Booking b) {
        System.out.println("[Email] Confirmation sent to " + b.getUser().getEmail() +
            " | Booking #" + b.getId() + " | Check-in: " + b.getCheckIn());
    }
    @Override
    public void onBookingCancelled(Booking b) {
        System.out.println("[Email] Cancellation sent to " + b.getUser().getEmail() +
            " | Refund initiated for Booking #" + b.getId());
    }
}

class SMSNotifier implements BookingObserver {
    @Override
    public void onBookingConfirmed(Booking b) {
        System.out.println("[SMS] Booking #" + b.getId() + " confirmed. Amount: Rs" + b.getTotalAmount());
    }
    @Override
    public void onBookingCancelled(Booking b) {
        System.out.println("[SMS] Booking #" + b.getId() + " cancelled. Refund in 3-5 days.");
    }
}

// ==========================================
// 8. HOTEL BOOKING SYSTEM (SINGLETON + CORE CONTROLLER)
// ==========================================
class HotelBookingSystem {
    private static HotelBookingSystem instance;
    private final Map<Integer, Hotel> hotels = new ConcurrentHashMap<>();
    private final Map<Integer, Booking> bookings = new ConcurrentHashMap<>();
    private final List<BookingObserver> observers = new ArrayList<>();
    private PricingStrategy pricingStrategy = new StandardPricing();

    private HotelBookingSystem() {
        observers.add(new EmailNotifier());
        observers.add(new SMSNotifier());
    }

    public static synchronized HotelBookingSystem getInstance() {
        if (instance == null) instance = new HotelBookingSystem();
        return instance;
    }

    public void addHotel(Hotel hotel) {
        hotels.put(hotel.getId(), hotel);
        System.out.println("[System] Registered: " + hotel);
    }

    public void setPricingStrategy(PricingStrategy strategy) {
        this.pricingStrategy = strategy;
    }

    // Search hotels by city + minimum stars
    public List<Hotel> searchHotels(String city, int minStars) {
        return hotels.values().stream()
            .filter(h -> h.getAddress().getCity().equalsIgnoreCase(city))
            .filter(h -> h.getStarRating() >= minStars)
            .collect(Collectors.toList());
    }

    // Search available rooms in a hotel
    public List<Room> searchAvailableRooms(Hotel hotel, RoomType type,
                                            LocalDate checkIn, LocalDate checkOut) {
        return hotel.searchAvailableRooms(type, checkIn, checkOut);
    }

    // Core booking flow
    public Booking bookRoom(User user, Hotel hotel, Room room,
                             LocalDate checkIn, LocalDate checkOut, String paymentMethod) {
        synchronized (hotel) {
            // 1. Re-check availability inside lock (prevent race condition)
            boolean available = hotel.searchAvailableRooms(room.getType(), checkIn, checkOut)
                .stream().anyMatch(r -> r.getId() == room.getId());

            if (!available) {
                System.out.println("[Booking] Room " + room.getRoomNumber() + " no longer available!");
                return null;
            }

            // 2. Calculate total price using strategy
            double total = pricingStrategy.calculate(room, checkIn, checkOut);

            // 3. Create booking in PENDING state
            Booking booking = new Booking(user, room, checkIn, checkOut, total);

            // 4. Process payment (idempotent in real system)
            Payment payment = new Payment(total, paymentMethod);
            booking.setPayment(payment);

            if (!payment.process()) {
                System.out.println("[Booking] Payment failed. Aborting.");
                return null;
            }

            // 5. Confirm — mark dates as booked
            hotel.markDatesBooked(room, checkIn, checkOut);
            booking.confirm();
            bookings.put(booking.getId(), booking);

            // 6. Notify via Observer pattern (email + SMS)
            observers.forEach(o -> o.onBookingConfirmed(booking));

            System.out.println("[Booking] " + booking);
            return booking;
        }
    }

    // Cancel booking + release dates + refund
    public boolean cancelBooking(int bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null || booking.getStatus() == BookingStatus.CANCELLED) {
            System.out.println("[Cancel] Invalid or already cancelled: " + bookingId);
            return false;
        }

        // Find hotel to release dates
        hotels.values().stream()
            .filter(h -> h.getRooms().contains(booking.getRoom()))
            .findFirst()
            .ifPresent(h -> h.releaseDates(booking.getRoom(), booking.getCheckIn(), booking.getCheckOut()));

        booking.cancel();
        observers.forEach(o -> o.onBookingCancelled(booking));
        System.out.println("[Cancel] Booking " + bookingId + " cancelled.");
        return true;
    }

    public void addObserver(BookingObserver o) { observers.add(o); }
    public Map<Integer, Booking> getAllBookings() { return bookings; }
}

// ==========================================
// 9. MAIN — DRIVER CODE
// ==========================================
public class HotelBooking {
    public static void main(String[] args) {
        HotelBookingSystem system = HotelBookingSystem.getInstance();

        // --- Setup Hotels ---
        Hotel taj = new Hotel("Taj Mahal Palace",
            new Address("Apollo Bunder", "Mumbai", "India", 400001), 5);
        taj.addRoom(new Room(101, RoomType.SINGLE,  8000,  List.of(AmenityType.WIFI, AmenityType.BREAKFAST)));
        taj.addRoom(new Room(201, RoomType.SUITE,   25000, List.of(AmenityType.WIFI, AmenityType.SPA, AmenityType.POOL)));
        taj.addRoom(new Room(301, RoomType.DOUBLE,  12000, List.of(AmenityType.WIFI, AmenityType.GYM)));
        system.addHotel(taj);

        Hotel meridien = new Hotel("Le Meridien",
            new Address("MG Road", "Bangalore", "India", 560001), 4);
        meridien.addRoom(new Room(101, RoomType.DOUBLE, 7000, List.of(AmenityType.WIFI, AmenityType.POOL)));
        meridien.addRoom(new Room(102, RoomType.DOUBLE, 7000, List.of(AmenityType.WIFI, AmenityType.GYM)));
        system.addHotel(meridien);

        User alice = new User("Alice", "alice@gmail.com");
        User bob   = new User("Bob",   "bob@gmail.com");

        // --- Scenario 1: Standard Booking ---
        System.out.println("\n===== Scenario 1: Standard Booking =====");
        system.setPricingStrategy(new StandardPricing());
        List<Hotel> mumbai = system.searchHotels("Mumbai", 5);
        System.out.println("Hotels in Mumbai (5 star+): " + mumbai);

        LocalDate in1  = LocalDate.of(2025, 12, 20);
        LocalDate out1 = LocalDate.of(2025, 12, 23); // 3 nights

        List<Room> suites = system.searchAvailableRooms(taj, RoomType.SUITE, in1, out1);
        Booking b1 = system.bookRoom(alice, taj, suites.get(0), in1, out1, "UPI");

        // --- Scenario 2: Double Booking Prevention ---
        System.out.println("\n===== Scenario 2: Double Booking Prevention =====");
        List<Room> sameSuites = system.searchAvailableRooms(taj, RoomType.SUITE, in1, out1);
        System.out.println("Available suites (should be empty): " + sameSuites);
        // Even if someone sneaks through search, bookRoom re-checks inside lock
        if (!sameSuites.isEmpty()) {
            system.bookRoom(bob, taj, sameSuites.get(0), in1, out1, "CARD");
        }

        // --- Scenario 3: Long Stay Discount ---
        System.out.println("\n===== Scenario 3: Long Stay Discount (11 nights = 15% off) =====");
        system.setPricingStrategy(new LongStayDiscountPricing());
        LocalDate in2  = LocalDate.of(2025, 12, 1);
        LocalDate out2 = LocalDate.of(2025, 12, 12);
        List<Room> doubles = system.searchAvailableRooms(meridien, RoomType.DOUBLE, in2, out2);
        Booking b2 = system.bookRoom(bob, meridien, doubles.get(0), in2, out2, "CARD");

        // --- Scenario 4: Weekend Surge Pricing ---
        System.out.println("\n===== Scenario 4: Weekend Surge Pricing =====");
        system.setPricingStrategy(new WeekendSurgePricing());
        LocalDate in3  = LocalDate.of(2025, 12, 26);
        LocalDate out3 = LocalDate.of(2025, 12, 28);
        List<Room> singles = system.searchAvailableRooms(taj, RoomType.SINGLE, in3, out3);
        Booking b3 = system.bookRoom(alice, taj, singles.get(0), in3, out3, "NETBANKING");

        // --- Scenario 5: Cancel + Refund ---
        System.out.println("\n===== Scenario 5: Cancel Booking =====");
        if (b1 != null) system.cancelBooking(b1.getId());

        // --- Scenario 6: Room Available After Cancel ---
        System.out.println("\n===== Scenario 6: Room Available After Cancel =====");
        system.setPricingStrategy(new StandardPricing());
        List<Room> afterCancel = system.searchAvailableRooms(taj, RoomType.SUITE, in1, out1);
        System.out.println("Available suites after cancel: " + afterCancel);
        if (!afterCancel.isEmpty()) {
            system.bookRoom(bob, taj, afterCancel.get(0), in1, out1, "UPI");
        }
    }
}
