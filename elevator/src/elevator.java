import java.util.*;
import java.util.concurrent.*;

enum Direction { UP, DOWN, IDLE }
enum DoorState { OPEN, CLOSED }

// Individual Elevator Car
class Elevator {
    private final int id;
    private int currentFloor = 0;
    private Direction direction = Direction.IDLE;
    private DoorState doorState = DoorState.CLOSED;

    public Elevator(int id) {
        this.id = id;
    }

    // Java 'synchronized' methods act like a mutex on the 'this' object
    public synchronized void openDoor() {
        if (doorState == DoorState.CLOSED) {
            System.out.println("[Elevator " + id + "] Opening doors at floor " + currentFloor);
            sleep(1000); // Simulate opening time
            doorState = DoorState.OPEN;
        }
    }

    public synchronized void closeDoor() {
        if (doorState == DoorState.OPEN) {
            System.out.println("[Elevator " + id + "] Closing doors at floor " + currentFloor);
            sleep(1000); // Simulate closing time
            doorState = DoorState.CLOSED;
        }
    }

    public void moveToFloor(int targetFloor) {
        closeDoor();

        System.out.println("[Elevator " + id + "] Moving from " + currentFloor + " to " + targetFloor);

        synchronized (this) {
            if (targetFloor > currentFloor) direction = Direction.UP;
            else if (targetFloor < currentFloor) direction = Direction.DOWN;
            else direction = Direction.IDLE;
        }

        sleep(500); // Simulate travel time
        this.currentFloor = targetFloor;

        System.out.println("[Elevator " + id + "] Arrived at floor " + targetFloor);
        openDoor();
        sleep(2000); // Passenger dwell time
        closeDoor();

        synchronized (this) {
            direction = Direction.IDLE;
        }
    }

    public synchronized DoorState getDoorState() {
        return doorState;
    }

    public synchronized int getCurrentFloor() {
        return currentFloor;
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

// Singleton Controller
class ElevatorSystem {
    private static ElevatorSystem instance;
    private final List<Elevator> elevators = new ArrayList<>();
    private final Queue<Integer> pendingRequests = new LinkedList<>();
    private boolean stopSystem = false;

    private ElevatorSystem(int numElevators) {
        for (int i = 1; i <= numElevators; i++) {
            elevators.add(new Elevator(i));
        }
    }

    // Thread-safe Singleton Initialization
    public static synchronized ElevatorSystem getInstance(int numElevators) {
        if (instance == null) {
            instance = new ElevatorSystem(numElevators);
        }
        return instance;
    }

    public void sendRequest(int floor) {
        synchronized (pendingRequests) {
            pendingRequests.add(floor);
            System.out.println("Request submitted for floor: " + floor);
            pendingRequests.notify(); // Wake up the worker thread
        }
    }

    private Elevator findBestElevator(int targetFloor) {
        Elevator best = null;
        int minDistance = Integer.MAX_VALUE;
        boolean bestDoorsClosed = false;

        for (Elevator e : elevators) {
            int distance = Math.abs(e.getCurrentFloor() - targetFloor);
            boolean doorsClosed = (e.getDoorState() == DoorState.CLOSED);

            // Logic: Prioritize closed doors, then minimal distance
            if (doorsClosed && (!bestDoorsClosed || distance < minDistance)) {
                minDistance = distance;
                best = e;
                bestDoorsClosed = true;
            }
        }
        return (best != null) ? best : elevators.get(0);
    }

    public void run() {
        while (true) {
            int floor;
            synchronized (pendingRequests) {
                while (pendingRequests.isEmpty() && !stopSystem) {
                    try {
                        pendingRequests.wait(); // Equivalent to cv.wait()
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (stopSystem && pendingRequests.isEmpty()) break;
                floor = pendingRequests.poll();
            }

            Elevator elevator = findBestElevator(floor);
            // In a real system, we'd spawn a thread per elevator task, 
            // but keeping it simple like the C++ version:
            elevator.moveToFloor(floor);
        }
    }
}

