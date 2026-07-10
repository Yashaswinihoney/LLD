import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

// --- 1. ENUMS AND HELPERS ---

/**
 * Representing the various states of a ride.
 * [Design Pattern: State Pattern (Conceptual)]
 */
enum RideStatus {
    IDLE, REQUESTED, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED
}

class Location {
    double latitude, longitude;
    public Location(double lat, double lon) {
        this.latitude = lat;
        this.longitude = lon;
    }
}

// --- 2. CORE ENTITIES ---

class Ride {
    private final String id;
    private RideStatus status;
    // ReentrantLock provides more flexibility than 'synchronized'
    private final ReentrantLock rideLock = new ReentrantLock();

    public Ride(String rideId) {
        this.id = rideId;
        this.status = RideStatus.REQUESTED;
    }

    public boolean acceptRide(String driverId) {
        rideLock.lock(); // Explicitly acquiring the lock
        try {
            // Check-then-act logic must be inside the lock to be atomic
            if (this.status == RideStatus.REQUESTED) {
                this.status = RideStatus.ACCEPTED;
                System.out.println("[Ride] " + id + " accepted by Driver " + driverId);
                return true;
            }
            return false;
        } finally {
            rideLock.unlock(); // Always release in finally block to prevent deadlocks
        }
    }
}

class Driver {
    private final String id;
    private boolean available = true;
    private final ReentrantLock driverLock = new ReentrantLock();

    public Driver(String dId) {
        this.id = dId;
    }

    public String getId() { return id; }

    /**
     * Atomically checks if the driver is available and books them.
     */
    public boolean tryBook() {
        driverLock.lock();
        try {
            if (available) {
                available = false;
                return true;
            }
            return false;
        } finally {
            driverLock.unlock();
        }
    }

    public void release() {
        driverLock.lock();
        try {
            available = true;
        } finally {
            driverLock.unlock();
        }
    }
}

// --- 3. THE MANAGER ---

/**
 * [Design Pattern: Singleton]
 * Ensures only one RideManager coordinates matches across the system.
 */
class RideManager {
    // Volatile prevents instruction reordering issues in multi-threaded environments
    private static volatile RideManager instance;
    private static final ReentrantLock registrationLock = new ReentrantLock();

    // Thread-safe collections for managing resources
    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();
    private final Map<String, Ride> activeRides = new ConcurrentHashMap<>();

    private RideManager() {}

    /**
     * Double-checked locking with ReentrantLock for thread-safe Singleton instantiation.
     */
    public static RideManager getInstance() {
        if (instance == null) {
            registrationLock.lock();
            try {
                if (instance == null) {
                    instance = new RideManager();
                }
            } finally {
                registrationLock.unlock();
            }
        }
        return instance;
    }

    public void addDriver(Driver driver) {
        drivers.put(driver.getId(), driver);
    }

    /**
     * [Design Pattern: Strategy Pattern (Simplified)]
     * The matching logic can be extracted into a separate Strategy interface 
     * (e.g., LeastTimeMatchingStrategy) to make it extensible.
     */
    public Ride requestRide(String riderId, Location src, Location dest) {
        System.out.println("[Request] Rider " + riderId + " searching for drivers...");

        // Iterating over the ConcurrentHashMap is thread-safe
        for (Driver driver : drivers.values()) {
            if (driver.tryBook()) {
                String rideId = "RIDE_" + UUID.randomUUID().toString().substring(0, 5);
                Ride newRide = new Ride(rideId);

                if (newRide.acceptRide(driver.getId())) {
                    activeRides.put(rideId, newRide);
                    System.out.println("[Match] Rider " + riderId + " matched with Driver " + driver.getId());
                    return newRide;
                } else {
                    // If ride acceptance fails for some reason, free the driver back up
                    driver.release();
                }
            }
        }

        System.out.println("[System] No drivers available for Rider " + riderId);
        return null;
    }
}