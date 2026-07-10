import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

// ==========================================
// 1. ENUMS & CORE DATA STRUCTURES
// ==========================================

enum Direction { UP, DOWN, IDLE }
enum DoorState { OPEN, CLOSED }

/**
 * Command/Request object encapsulating the user intent.
 * Replaces primitive integers to handle metadata cleanly.
 */
class Request {
    private final int sourceFloor;
    private final int destinationFloor;
    private final Direction direction;

    public Request(int sourceFloor, int destinationFloor) {
        this.sourceFloor = sourceFloor;
        this.destinationFloor = destinationFloor;
        this.direction = destinationFloor > sourceFloor ? Direction.UP :
                (destinationFloor < sourceFloor ? Direction.DOWN : Direction.IDLE);
    }

    public int getSourceFloor() { return sourceFloor; }
    public int getDestinationFloor() { return destinationFloor; }
    public Direction getDirection() { return direction; }
}


// ==========================================
// 2. STATE PATTERN IMPLEMENTATION
// ==========================================

/**
 * Interface representing the state of an Elevator Car.
 * Eliminates complex nested if/else logic for state transitions.
 */
interface ElevatorState {
    void handleRequest(Elevator elevator, Request request);
    void updateTelemetry(Elevator elevator);
}

class IdleState implements ElevatorState {
    @Override
    public void handleRequest(Elevator elevator, Request request) {
        if (elevator.getCurrentFloor() == request.getSourceFloor()) {
            elevator.changeState(new DoorsOpenState());
            elevator.processFloorArrival(request.getDestinationFloor());
        } else {
            elevator.changeState(new MovingState());
            elevator.moveToFloor(request.getSourceFloor());
            elevator.changeState(new DoorsOpenState());
            elevator.processFloorArrival(request.getDestinationFloor());
        }
    }

    @Override
    public void updateTelemetry(Elevator elevator) {
        System.out.println("[Telemetry Sensor Log] Elevator " + elevator.getId() + " is currently IDLE on floor " + elevator.getCurrentFloor());
    }
}

class MovingState implements ElevatorState {
    @Override
    public void handleRequest(Elevator elevator, Request request) {
        // Logically queue intermediate stops en-route if applicable
        elevator.addFloorToQueue(request.getSourceFloor());
    }

    @Override
    public void updateTelemetry(Elevator elevator) {
        System.out.println("[Telemetry Sensor Log] Elevator " + elevator.getId() + " is actively MOVING.");
    }
}

class DoorsOpenState implements ElevatorState {
    @Override
    public void handleRequest(Elevator elevator, Request request) {
        elevator.addFloorToQueue(request.getSourceFloor());
    }

    @Override
    public void updateTelemetry(Elevator elevator) {
        System.out.println("[Telemetry Sensor Log] Elevator " + elevator.getId() + " DOORS ARE OPEN.");
    }
}


// ==========================================
// 3. COMMAND PATTERN IMPLEMENTATION
// ==========================================

/**
 * Encapsulates the executive dispatching instruction.
 * Maps conceptually to event-driven commands sent over message queues.
 */
interface Command {
    void execute();
}

class ElevatorDispatchCommand implements Command {
    private final Elevator elevator;
    private final Request request;

    public ElevatorDispatchCommand(Elevator elevator, Request request) {
        this.elevator = elevator;
        this.request = request;
    }

    @Override
    public void execute() {
        elevator.processRequest(request);
    }
}


// ==========================================
// 4. STRATEGY PATTERN IMPLEMENTATION
// ==========================================

/**
 * Interface enabling pluggable elevator routing algorithms.
 */
interface DispatchStrategy {
    Elevator selectElevator(List<Elevator> elevators, Request request);
}

/**
 * Concrete strategy picking the closest available car.
 */
class ClosestIdleStrategy implements DispatchStrategy {
    @Override
    public Elevator selectElevator(List<Elevator> elevators, Request request) {
        Elevator bestMatch = null;
        int minDistance = Integer.MAX_VALUE;

        for (Elevator elevator : elevators) {
            int distance = Math.abs(elevator.getCurrentFloor() - request.getSourceFloor());
            if (distance < minDistance) {
                minDistance = distance;
                bestMatch = elevator;
            }
        }
        return bestMatch != null ? bestMatch : elevators.get(0);
    }
}


// ==========================================
// 5. OBSERVER PATTERN IMPLEMENTATION
// ==========================================

/**
 * Observer interface to stream tracking data out of elevator entities.
 */
interface ElevatorObserver {
    void onStateChange(int elevatorId, int currentFloor, Direction direction);
}


// ==========================================
// 6. THE ELEVATOR CAR MODEL (OBSERVABLE)
// ==========================================

class Elevator {
    private final int id;
    private int currentFloor = 0;
    private Direction direction = Direction.IDLE;
    private DoorState doorState = DoorState.CLOSED;
    private ElevatorState currentState;

    private final TreeSet<Integer> floorQueue = new TreeSet<>();
    private final ReentrantLock elevatorLock = new ReentrantLock();

    // CopyOnWriteArrayList handles thread-safe iteration during notifications
    private final List<ElevatorObserver> observers = new CopyOnWriteArrayList<>();

    public Elevator(int id) {
        this.id = id;
        this.currentState = new IdleState();
    }

    public int getId() { return id; }
    public int getCurrentFloor() { return currentFloor; }

    public void registerObserver(ElevatorObserver observer) {
        observers.add(observer);
    }

    private void notifyObservers() {
        for (ElevatorObserver observer : observers) {
            observer.onStateChange(this.id, this.currentFloor, this.direction);
        }
    }

    public void changeState(ElevatorState state) {
        elevatorLock.lock();
        try {
            this.currentState = state;
        } finally {
            elevatorLock.unlock();
        }
    }

    public void addFloorToQueue(int floor) {
        elevatorLock.lock();
        try {
            floorQueue.add(floor);
        } finally {
            elevatorLock.unlock();
        }
    }

    public void processRequest(Request request) {
        elevatorLock.lock();
        try {
            currentState.handleRequest(this, request);
        } finally {
            elevatorLock.unlock();
        }
    }

    public void moveToFloor(int targetFloor) {
        elevatorLock.lock();
        try {
            System.out.println("[Elevator " + id + "] Transitioning from floor " + currentFloor + " to floor " + targetFloor);
            this.direction = targetFloor > currentFloor ? Direction.UP : Direction.DOWN;

            // Simulating travel velocity safely
            Thread.sleep(800);
            this.currentFloor = targetFloor;
            this.direction = Direction.IDLE;
            notifyObservers();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            elevatorLock.unlock();
        }
    }

    public void processFloorArrival(int targetFloor) {
        elevatorLock.lock();
        try {
            System.out.println("[Elevator " + id + "] Opened doors at pickup floor: " + currentFloor);
            this.doorState = DoorState.OPEN;
            Thread.sleep(1000); // Passenger entry delay

            moveToFloor(targetFloor);

            System.out.println("[Elevator " + id + "] Destination reached. Closed doors.");
            this.doorState = DoorState.CLOSED;
            this.currentState = new IdleState();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            elevatorLock.unlock();
        }
    }
}


// ==========================================
// 7. CENTRAL ELEVATOR SYSTEM CONTROLLER (SINGLETON)
// ==========================================

class ElevatorSystem implements ElevatorObserver {
    private static volatile ElevatorSystem instance;
    private static final Object lockObject = new Object();

    private final List<Elevator> elevators;
    private final DispatchStrategy dispatchStrategy;
    private final ExecutorService commandExecutor;

    private ElevatorSystem(int numElevators) {
        this.elevators = new ArrayList<>();
        this.dispatchStrategy = new ClosestIdleStrategy();

        // Decoupled task pool to process operations concurrently without freezing input threads
        this.commandExecutor = Executors.newFixedThreadPool(4);

        for (int i = 1; i <= numElevators; i++) {
            Elevator e = new Elevator(i);
            e.registerObserver(this); // Attachment step for Observer Pattern
            elevators.add(e);
        }
    }

    // Thread-safe Double-Checked Locking Singleton Configuration
    public static ElevatorSystem getInstance(int numElevators) {
        if (instance == null) {
            synchronized (lockObject) {
                if (instance == null) {
                    instance = new ElevatorSystem(numElevators);
                }
            }
        }
        return instance;
    }

    public void handleIncomingRideRequest(Request request) {
        System.out.println("\n[Central Routing Center] Received passenger request: From floor "
                + request.getSourceFloor() + " to floor " + request.getDestinationFloor());

        // Strategy computes optimal target assignment
        Elevator assignedElevator = dispatchStrategy.selectElevator(elevators, request);

        // Command structural wrapping encapsulation 
        Command dispatchCommand = new ElevatorDispatchCommand(assignedElevator, request);

        // Pass off to asynchronously execute inside Worker Pool
        commandExecutor.submit(dispatchCommand::execute);
    }

    // Callback event listener receiving operational metrics from specific shaft variables
    @Override
    public void onStateChange(int elevatorId, int currentFloor, Direction direction) {
        System.out.println("  >>> [Console Dashboard Log Update] Car Node " + elevatorId
                + " is verified standing on floor " + currentFloor + " with Vector state: " + direction);
    }

    public void shutdown() {
        commandExecutor.shutdown();
    }
}


// ==========================================
// 8. SIMULATION RUNNER ENTRYPOINT
// ==========================================

public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Initializing Building Systems Architecture...");

        // Instantiate a central management entity with a bank of 3 active elevator cars
        ElevatorSystem buildingController = ElevatorSystem.getInstance(3);

        // Emulate independent asynchronous customer button interactions hitting the system at once
        Thread passengerA = new Thread(() -> buildingController.handleIncomingRideRequest(new Request(1, 8)));
        Thread passengerB = new Thread(() -> buildingController.handleIncomingRideRequest(new Request(4, 0)));
        Thread passengerC = new Thread(() -> buildingController.handleIncomingRideRequest(new Request(9, 2)));

        passengerA.start();
        passengerB.start();
        passengerC.start();

        // Let the asynchronous pool threads finish processing simulation workloads
        Thread.sleep(7000);

        buildingController.shutdown();
        System.out.println("\nAll concurrency operations cleared cleanly. Execution terminated safely.");
    }
}