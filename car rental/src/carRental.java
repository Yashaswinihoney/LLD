import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// --- Enums ---
enum VehicleType { CAR, BIKE }
enum VehicleStatus { AVAILABLE, BUSY }
enum ReservationStatus { SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED }

// --- Strategy Pattern for Pricing ---
interface PricingStrategy {
    double calculateCost(int days, int dailyRate);
}

class DefaultPricing implements PricingStrategy {
    @Override
    public double calculateCost(int days, int dailyRate) {
        return (days > 0 ? days : 1) * dailyRate;
    }
}

// --- Core Entities ---
class Location {
    int pincode;
    String city;

    public Location(int pincode, String city) {
        this.pincode = pincode;
        this.city = city;
    }
}

class User {
    int userId;
    String name;

    public User(int id, String n) {
        this.userId = id;
        this.name = n;
    }
}

abstract class Vehicle {
    int vehicleId;
    VehicleType type;
    VehicleStatus status = VehicleStatus.AVAILABLE;
    int dailyRate;

    public Vehicle(int id, VehicleType t, int rate) {
        this.vehicleId = id;
        this.type = t;
        this.dailyRate = rate;
    }

    public synchronized void setStatus(VehicleStatus status) {
        this.status = status;
    }
}

class Car extends Vehicle {
    public Car(int id) {
        super(id, VehicleType.CAR, 1000);
    }
}

// --- Inventory & Reservation ---
class Reservation {
    private static final AtomicInteger idGenerator = new AtomicInteger(100);
    int id;
    User user;
    Vehicle vehicle;
    ReservationStatus status;

    public Reservation(User u, Vehicle v) {
        this.id = idGenerator.incrementAndGet();
        this.user = u;
        this.vehicle = v;
        this.status = ReservationStatus.SCHEDULED;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }
}

class VehicleInventory {
    private final List<Vehicle> vehicles = new ArrayList<>();

    public void addVehicle(Vehicle v) {
        vehicles.add(v);
    }

    public List<Vehicle> getVehicles() {
        return vehicles;
    }
}

// --- Store Management ---
class Store {
    private int storeId;
    private Location location;
    private VehicleInventory inventory = new VehicleInventory();
    private List<Reservation> reservations = new ArrayList<>();

    public Store(int id, Location loc) {
        this.storeId = id;
        this.location = loc;
    }

    public Location getLocation() { return location; }

    public void addVehicle(Vehicle v) {
        inventory.addVehicle(v);
    }

    // FIX 1: Search and Reservation now share the "Store" lock scope.
    // Making this synchronized ensures that no one can change vehicle statuses 
    // while someone else is searching.
    public synchronized List<Vehicle> searchVehicles(VehicleType type) {
        return inventory.getVehicles().stream()
                .filter(v -> v.type == type && v.status == VehicleStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public synchronized Reservation createReservation(Vehicle v, User u) {
        if (v.status != VehicleStatus.AVAILABLE) {
            return null; // Double-check safety
        }

        v.setStatus(VehicleStatus.BUSY);
        Reservation res = new Reservation(u, v);
        reservations.add(res);
        return res;
    }

    // FIX 2: Added cancelReservation logic to free up the vehicle
    public synchronized boolean cancelReservation(int reservationId) {
        Optional<Reservation> resOpt = reservations.stream()
                .filter(r -> r.id == reservationId && r.status != ReservationStatus.CANCELLED)
                .findFirst();

        if (resOpt.isPresent()) {
            Reservation res = resOpt.get();
            res.setStatus(ReservationStatus.CANCELLED);
            res.vehicle.setStatus(VehicleStatus.AVAILABLE); // Back to inventory
            return true;
        }
        return false;
    }
}

// --- Singleton System Controller ---
class RentalSystem {
    private static RentalSystem instance;
    private List<Store> stores = new ArrayList<>();

    private RentalSystem() {}

    public static synchronized RentalSystem getInstance() {
        if (instance == null) {
            instance = new RentalSystem();
        }
        return instance;
    }

    public synchronized void addStore(Store s) {
        stores.add(s);
    }

    // FIX 3: Actually filters by Pincode now
    public synchronized List<Store> getStoresByLocation(Location loc) {
        return stores.stream()
                .filter(s -> s.getLocation().pincode == loc.pincode)
                .collect(Collectors.toList());
    }
}
