
import java.util.*;
import java.util.concurrent.*;

// --- Enums and Helpers ---
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

// --- Ride Class ---
class Ride {
    private final String id;
    private RideStatus status;

    public Ride(String rideId) {
        this.id = rideId;
        this.status = RideStatus.REQUESTED;
    }

    // Synchronized method to ensure only one thread can change the state at a time
    public synchronized boolean acceptRide(String driverId) {
        if (this.status == RideStatus.REQUESTED) {
            this.status = RideStatus.ACCEPTED;
            System.out.println("[Ride] " + id + " accepted by Driver " + driverId);
            return true;
        }
        return false;
    }
}

// --- Driver Class ---
class Driver {
    private final String id;
    private boolean available = true;

    public Driver(String dId) {
        this.id = dId;
    }

    public String getId() { return id; }

    // Synchronized block to atomically check and set availability
    public synchronized boolean tryBook() {
        if (available) {
            available = false;
            return true;
        }
        return false;
    }

    public synchronized void release() {
        available = true;
    }
}

// --- RideManager (Singleton) ---
class RideManager {
    // Volatile ensures visibility across threads for the Singleton instance
    private static volatile RideManager instance;

    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();
    private final Map<String, Ride> activeRides = new ConcurrentHashMap<>();

    private RideManager() {}

    // Double-checked locking for thread-safe Singleton
    public static RideManager getInstance() {
        if (instance == null) {
            synchronized (RideManager.class) {
                if (instance == null) {
                    instance = new RideManager();
                }
            }
        }
        return instance;
    }

    public void addDriver(Driver driver) {
        drivers.put(driver.getId(), driver);
    }

    public Ride requestRide(String riderId, Location src, Location dest) {
        System.out.println("[Request] Rider " + riderId + " is looking for a ride...");

        // Strategy: Iterate through drivers and try to book the first available one
        for (Driver driver : drivers.values()) {
            if (driver.tryBook()) {
                String rideId = "RIDE_" + UUID.randomUUID().toString().substring(0, 5);
                Ride newRide = new Ride(rideId);
                activeRides.put(rideId, newRide);

                System.out.println("[Match] Successfully matched Rider " + riderId + " with Driver " + driver.getId());
                return newRide;
            }
        }

        System.out.println("[System] No drivers available for Rider " + riderId);
        return null;
    }
}
