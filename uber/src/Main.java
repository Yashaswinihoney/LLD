import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// --- Execution ---
public class Main {
    public static void main(String[] args) {
        RideManager uber = RideManager.getInstance();

        // Adding 2 drivers
        uber.addDriver(new Driver("D1"));
        uber.addDriver(new Driver("D2"));

        // Simulating 3 concurrent riders for 2 drivers
        ExecutorService executor = Executors.newFixedThreadPool(3);

        Runnable riderA = () -> uber.requestRide("User_A", new Location(0, 0), new Location(1, 1));
        Runnable riderB = () -> uber.requestRide("User_B", new Location(2, 2), new Location(3, 3));
        Runnable riderC = () -> uber.requestRide("User_C", new Location(4, 4), new Location(5, 5));

        executor.execute(riderA);
        executor.execute(riderB);
        executor.execute(riderC);

        executor.shutdown();
    }
}