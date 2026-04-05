import java.util.List;

public class Main {
    public static void main(String[] args) {
        RentalSystem system = RentalSystem.getInstance();

        Location loc = new Location(560001, "Bangalore");
        Store myStore = new Store(101, loc);
        myStore.addVehicle(new Car(1));
        myStore.addVehicle(new Car(2));
        system.addStore(myStore);

        User user = new User(1, "Alice");

        // 1. Search with real location filtering
        List<Store> nearbyStores = system.getStoresByLocation(loc);

        if (!nearbyStores.isEmpty()) {
            Store store = nearbyStores.get(0);
            List<Vehicle> availableCars = store.searchVehicles(VehicleType.CAR);

            if (!availableCars.isEmpty()) {
                // 2. Atomic Reservation
                Reservation reservation = store.createReservation(availableCars.get(0), user);
                System.out.println("Booked Vehicle: " + reservation.vehicle.vehicleId);

                // 3. Cancellation Test
                System.out.println("Cancelling reservation...");
                boolean cancelled = store.cancelReservation(reservation.id);

                if (cancelled) {
                    System.out.println("Vehicle " + reservation.vehicle.vehicleId + " is now " + reservation.vehicle.status);
                }
            }
        }
    }
}