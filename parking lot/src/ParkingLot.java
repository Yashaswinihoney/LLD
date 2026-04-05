
import java.util.ArrayList;
import java.util.List;

public class ParkingLot {
    private static ParkingLot instance;
    private final List<ParkingFloor> floors;

    private ParkingLot() {
        floors = new ArrayList<>();
    }

    // Thread-safe Singleton
    public static synchronized ParkingLot getInstance() {
        if (instance == null) {
            instance = new ParkingLot();
        }
        return instance;
    }

    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    public synchronized boolean parkVehicle(Vehicle vehicle) {
        for (ParkingFloor floor : floors) {
            ParkingSpot spot = floor.findAndOccupySpot(vehicle);
            if (spot != null) {
                System.out.println(vehicle.getType() + " [" + vehicle.getLicensePlate() +
                        "] parked at spot " + spot.getId());
                return true;
            }
        }
        System.out.println("Parking full for: " + vehicle.getLicensePlate());
        return false;
    }
}