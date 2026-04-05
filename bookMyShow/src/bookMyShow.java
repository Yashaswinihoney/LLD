import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// ==========================================
// 1. ENUMS
// ==========================================
enum SeatCategory { SILVER, GOLD, PLATINUM }
enum SeatStatus { AVAILABLE, LOCKED, BOOKED }

// ==========================================
// 2. BASIC ENTITIES
// ==========================================
class Seat {
    private final int id;
    private final SeatCategory category;
    private final double price;

    public Seat(int id, SeatCategory cat, double price) {
        this.id = id;
        this.category = cat;
        this.price = price;
    }

    public int getId() { return id; }
    public double getPrice() { return price; }
}

class Movie {
    private final String title;
    private final int durationMins;

    public Movie(String title, int durationMins) {
        this.title = title;
        this.durationMins = durationMins;
    }

    public String getTitle() { return title; }
}

// ==========================================
// 3. THE "SHOW" (Thread-Safe Class)
// ==========================================
class Show {
    private final int id;
    private final Movie movie;
    // Map SeatID -> Current Status
    private final Map<Integer, SeatStatus> seatStatusMap = new HashMap<>();
    private final List<Seat> seats = new ArrayList<>();

    public Show(int id, Movie movie) {
        this.id = id;
        this.movie = movie;
        // Initialize 10 seats for demo
        for (int i = 1; i <= 10; i++) {
            seats.add(new Seat(i, SeatCategory.SILVER, 100.0));
            seatStatusMap.put(i, SeatStatus.AVAILABLE);
        }
    }

    // Read-only method: Synchronized ensures visibility of the latest state
    public synchronized void displayAvailableSeats() {
        System.out.print("Show " + id + " (" + movie.getTitle() + "): ");
        seatStatusMap.forEach((seatId, status) -> {
            if (status == SeatStatus.AVAILABLE) System.out.print("[S" + seatId + "] ");
            else if (status == SeatStatus.LOCKED) System.out.print("[LOCKED] ");
            else System.out.print("[X] ");
        });
        System.out.println();
    }

    /**
     * Critical Section: Using 'synchronized' keyword instead of ReentrantLock.
     * This locks the current 'Show' instance.
     */
    public synchronized boolean bookSeat(int seatId) {
        // 1. Validate existence
        if (!seatStatusMap.containsKey(seatId)) {
            System.out.println("Error: Seat " + seatId + " does not exist.");
            return false;
        }

        // 2. Check status
        if (seatStatusMap.get(seatId) != SeatStatus.AVAILABLE) {
            System.out.println("Fail: Seat " + seatId + " is already taken/locked.");
            return false;
        }

        // 3. Update state
        seatStatusMap.put(seatId, SeatStatus.BOOKED);
        System.out.println("Success: Seat " + seatId + " booked!");
        return true;
    }
}

// ==========================================
// 4. THE SYSTEM (Controller)
// ==========================================
class BookMyShow {
    // Using ConcurrentHashMap for thread-safe access to the shows map itself
    private final Map<Integer, Show> shows = new ConcurrentHashMap<>();

    public void addShow(int id, Movie m) {
        shows.put(id, new Show(id, m));
    }

    public Show getShow(int id) {
        return shows.get(id);
    }
}

