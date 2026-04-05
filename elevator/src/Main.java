public class Main {
    public static void main(String[] args) {
        ElevatorSystem system = ElevatorSystem.getInstance(3);

        // Start the controller logic in a background thread
        Thread controllerThread = new Thread(system::run);
        controllerThread.start();

        // Simulate concurrent requests
        int[] requests = {5, 2, 9, 1};
        for (int floor : requests) {
            new Thread(() -> system.sendRequest(floor)).start();
        }

        // Keep main alive to watch output
        try { Thread.sleep(15000); } catch (InterruptedException e) {}
        System.out.println("Simulation complete.");
        System.exit(0);
    }
}