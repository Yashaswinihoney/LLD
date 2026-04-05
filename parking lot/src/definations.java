import java.util.ArrayList;
import java.util.List;

enum VehicleType {
    MOTORBIKE, CAR, TRUCK
}

enum ParkingSpotType {
    SMALL, MEDIUM, LARGE
}


abstract class Vehicle {
    protected String licensePlate;
    protected VehicleType type;

    public Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = licensePlate;
        this.type = type;
    }

    public VehicleType getType() { return type; }
    public String getLicensePlate() { return licensePlate; }
}

class Motorbike extends Vehicle {
    public Motorbike(String licensePlate) {
        super(licensePlate, VehicleType.MOTORBIKE);
    }
}

class Car extends Vehicle {
    public Car(String licensePlate) {
        super(licensePlate, VehicleType.CAR);
    }
}

class Truck extends Vehicle {
    public Truck(String licensePlate) {
        super(licensePlate, VehicleType.TRUCK);
    }
}


class ParkingSpot {
    private final int id;
    private final ParkingSpotType type;
    private Vehicle parkedVehicle;

    public ParkingSpot(int id, ParkingSpotType type) {
        this.id = id;
        this.type = type;
    }

    public synchronized boolean isAvailable() {
        return parkedVehicle == null;
    }

    // Synchronized to prevent two threads from taking the same spot
    public synchronized boolean park(Vehicle v) {
        if (isAvailable()) {
            this.parkedVehicle = v;
            return true;
        }
        return false;
    }

    public synchronized void unpark() {
        this.parkedVehicle = null;
    }

    public ParkingSpotType getType() { return type; }
    public int getId() { return id; }
    public Vehicle getVehicle() { return parkedVehicle; }
}


class ParkingFloor {
    private final int floorLevel;
    private final List<ParkingSpot> spots;

    public ParkingFloor(int level) {
        this.floorLevel = level;
        this.spots = new ArrayList<>();
    }

    public void addSpot(ParkingSpot spot) {
        spots.add(spot);
    }

    // Improvement: Logic to allow smaller vehicles in larger spots if preferred
    public synchronized ParkingSpot findAndOccupySpot(Vehicle vehicle) {
        for (ParkingSpot spot : spots) {
            if (spot.isAvailable() && canFit(vehicle.getType(), spot.getType())) {
                if (spot.park(vehicle)) {
                    return spot;
                }
            }
        }
        return null;
    }

    private boolean canFit(VehicleType vType, ParkingSpotType sType) {
        if (vType == VehicleType.MOTORBIKE) return true; // Fits everywhere
        if (vType == VehicleType.CAR) return sType != ParkingSpotType.SMALL;
        if (vType == VehicleType.TRUCK) return sType == ParkingSpotType.LARGE;
        return false;
    }
}
