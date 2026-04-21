import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// ==========================================
// TRAFFIC LIGHT SYSTEM LLD — SIMPLE VERSION
//
// Scope:
//   - Traffic lights at one or more intersections
//   - Fixed cycle: RED → GREEN → YELLOW → RED
//   - Emergency override: pause cycle, give green to one direction
//   - Fault detection: light stuck / bulb out → blink RED
//   - Multi-intersection coordination via TrafficControlCenter
//
// Patterns:
//   Singleton  — TrafficControlCenter
//   Strategy   — TimingStrategy (fixed / peak-hour)
//   Observer   — LightEventObserver (dashboard, logger)
//   Factory    — IntersectionFactory (4-way / T-junction)
//   Builder    — Intersection construction
//   State      — TrafficLight (RED → GREEN → YELLOW → RED)
//   Command    — EmergencyCommand (execute + rollback)
//   CoR        — FaultHandler (bulb → comm → power)
// ==========================================

// ==========================================
// 1. ENUMS — keep it minimal
// ==========================================
enum SignalColor    { RED, GREEN, YELLOW, FLASHING_RED }
enum Direction     { NORTH, SOUTH, EAST, WEST, PEDESTRIAN }
enum IntersectionType { FOUR_WAY, T_JUNCTION }
enum FaultSeverity { MINOR, MAJOR, CRITICAL }

// ==========================================
// 2. TRAFFIC LIGHT — STATE MACHINE
//
// The natural state cycle is:
//   RED → GREEN → YELLOW → RED → ...
//
// Transitions:
//   goGreen()  : RED   → GREEN
//   goYellow() : GREEN → YELLOW
//   goRed()    : YELLOW→ RED
//   fault()    : any   → FLASHING_RED
//   restore()  : FLASHING_RED → RED
// ==========================================
class TrafficLight {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long        id;
    private final  Direction   direction;
    private        SignalColor currentColor;
    private        boolean     isFaulty;
    private        int         cyclesCompleted;

    public TrafficLight(Direction direction) {
        this.id           = idGen.getAndIncrement();
        this.direction    = direction;
        this.currentColor = SignalColor.RED;   // always start RED (safe)
        this.isFaulty     = false;
    }

    // ---- State transitions ----
    public synchronized boolean goGreen() {
        if (isFaulty) return false;
        if (currentColor != SignalColor.RED) {
            System.out.println("[Light-" + direction + "] Can't go GREEN from " + currentColor);
            return false;
        }
        currentColor = SignalColor.GREEN;
        System.out.println("[Light-" + direction + "] 🟢 GREEN");
        return true;
    }

    public synchronized boolean goYellow() {
        if (isFaulty) return false;
        if (currentColor != SignalColor.GREEN) {
            System.out.println("[Light-" + direction + "] Can't go YELLOW from " + currentColor);
            return false;
        }
        currentColor = SignalColor.YELLOW;
        System.out.println("[Light-" + direction + "] 🟡 YELLOW");
        return true;
    }

    public synchronized boolean goRed() {
        if (isFaulty) return false;
        if (currentColor != SignalColor.YELLOW) {
            System.out.println("[Light-" + direction + "] Can't go RED from " + currentColor);
            return false;
        }
        currentColor = SignalColor.RED;
        cyclesCompleted++;
        System.out.println("[Light-" + direction + "] 🔴 RED  (cycles=" + cyclesCompleted + ")");
        return true;
    }

    // Force RED — used for emergency / all-red clearance
    public synchronized void forceRed() {
        currentColor = SignalColor.RED;
        System.out.println("[Light-" + direction + "] 🔴 FORCED RED");
    }

    // Fault handling — lights blink RED (treat as 4-way stop)
    public synchronized void markFaulty(String reason) {
        isFaulty     = true;
        currentColor = SignalColor.FLASHING_RED;
        System.out.println("[Light-" + direction + "] ⚠ FAULTY → FLASHING RED | " + reason);
    }

    public synchronized void restore() {
        isFaulty     = false;
        currentColor = SignalColor.RED;         // safe start state
        System.out.println("[Light-" + direction + "] ✓ RESTORED → RED");
    }

    public Direction   getDirection()  { return direction; }
    public SignalColor getColor()      { return currentColor; }
    public boolean     isFaulty()      { return isFaulty; }
    public int         getCycles()     { return cyclesCompleted; }

    @Override public String toString() {
        return direction + "=" + currentColor + (isFaulty ? "⚠" : "");
    }
}

// ==========================================
// 3. SIGNAL TIMING CONFIG
//    Simple value object — no sensors needed
// ==========================================
class TimingConfig {
    final int greenSeconds;
    final int yellowSeconds;
    final int allRedSeconds; // clearance between phases

    public TimingConfig(int green, int yellow, int allRed) {
        this.greenSeconds  = green;
        this.yellowSeconds = yellow;
        this.allRedSeconds = allRed;
    }

    public static TimingConfig normal() {
        return new TimingConfig(30, 3, 2);
    }

    public static TimingConfig peakHour() {
        return new TimingConfig(45, 3, 2); // longer green during peak
    }

    public static TimingConfig night() {
        return new TimingConfig(20, 3, 2); // shorter green at night
    }

    @Override public String toString() {
        return "Timing[green=" + greenSeconds + "s yellow=" + yellowSeconds +
               "s allRed=" + allRedSeconds + "s]";
    }
}

// ==========================================
// 4. STRATEGY — TIMING
//    Two concrete strategies: Fixed and PeakHour
// ==========================================
interface TimingStrategy {
    String       getName();
    TimingConfig getConfig();
}

class FixedTimingStrategy implements TimingStrategy {
    private final TimingConfig config;
    public FixedTimingStrategy(TimingConfig config) { this.config = config; }
    @Override public String       getName()    { return "Fixed(" + config + ")"; }
    @Override public TimingConfig getConfig()  { return config; }
}

class PeakHourTimingStrategy implements TimingStrategy {
    @Override public String       getName()    { return "PeakHour"; }
    @Override public TimingConfig getConfig()  { return TimingConfig.peakHour(); }
}

// ==========================================
// 5. OBSERVER — LIGHT EVENTS
// ==========================================
interface LightEventObserver {
    void onCycleStarted(String intersectionId, String phase);
    void onEmergency(String intersectionId, Direction approach);
    void onFault(String intersectionId, Direction direction, String reason);
    void onRestored(String intersectionId);
}

class DashboardObserver implements LightEventObserver {
    @Override public void onCycleStarted(String id, String phase) {
        System.out.println("[Dashboard] " + id + " → phase: " + phase);
    }
    @Override public void onEmergency(String id, Direction dir) {
        System.out.println("[Dashboard] 🚨 EMERGENCY at " + id + " | approach: " + dir);
    }
    @Override public void onFault(String id, Direction dir, String reason) {
        System.out.println("[Dashboard] ⚠ FAULT at " + id + " | " + dir + " | " + reason);
    }
    @Override public void onRestored(String id) {
        System.out.println("[Dashboard] ✅ " + id + " restored to normal");
    }
}

class LoggerObserver implements LightEventObserver {
    private final List<String> log = new ArrayList<>();

    @Override public void onCycleStarted(String id, String phase) {
        log.add(LocalDateTime.now() + " | CYCLE | " + id + " | " + phase);
    }
    @Override public void onEmergency(String id, Direction dir) {
        log.add(LocalDateTime.now() + " | EMERGENCY | " + id + " | " + dir);
    }
    @Override public void onFault(String id, Direction dir, String reason) {
        log.add(LocalDateTime.now() + " | FAULT | " + id + " | " + dir + " | " + reason);
    }
    @Override public void onRestored(String id) {
        log.add(LocalDateTime.now() + " | RESTORED | " + id);
    }

    public void printLog() {
        System.out.println("\n[Event Log]");
        log.forEach(e -> System.out.println("  " + e));
    }
}

// ==========================================
// 6. EMERGENCY COMMAND — COMMAND PATTERN
//    execute()  : pause cycle, give green to emergency direction
//    rollback() : restore cycle from saved state
// ==========================================
class EmergencyCommand {
    private final Intersection              intersection;
    private final Direction                 approachDirection;
    private final Map<Direction, SignalColor> savedColors = new LinkedHashMap<>();
    private       boolean                   executed = false;

    public EmergencyCommand(Intersection intersection, Direction approach) {
        this.intersection      = intersection;
        this.approachDirection = approach;
        // snapshot current colors before override
        intersection.getLights().forEach((dir, light) ->
            savedColors.put(dir, light.getColor()));
    }

    public void execute() {
        intersection.pauseCycle();

        // All RED except emergency approach
        intersection.getLights().forEach((dir, light) -> {
            if (dir == approachDirection) light.goGreen();
            else                          light.forceRed();
        });

        executed = true;
        System.out.println("[Emergency] ✅ " + approachDirection +
            " → GREEN | all others → RED");
    }

    public void rollback() {
        if (!executed) return;
        System.out.println("[Emergency] ↩ Restoring normal cycle at " +
            intersection.getId());
        intersection.resumeCycle();
        executed = false;
    }

    public Direction getApproach() { return approachDirection; }
}

// ==========================================
// 7. CHAIN OF RESPONSIBILITY — FAULT HANDLER
//    Bulb failure → minor, keep running on fixed
//    Comm failure → major, standalone mode
//    Power failure → critical, all blink RED
// ==========================================
abstract class FaultHandler {
    protected FaultHandler next;

    public FaultHandler setNext(FaultHandler n) { this.next = n; return n; }

    public abstract void handle(FaultSeverity severity, Intersection intersection);

    protected void passToNext(FaultSeverity severity, Intersection intersection) {
        if (next != null) next.handle(severity, intersection);
    }
}

// Bulb out → minor, switch that light to FLASHING_RED, rest continue
class BulbFaultHandler extends FaultHandler {
    private final Direction affectedDirection;
    private final String    reason;

    public BulbFaultHandler(Direction dir, String reason) {
        this.affectedDirection = dir;
        this.reason            = reason;
    }

    @Override
    public void handle(FaultSeverity severity, Intersection intersection) {
        if (severity == FaultSeverity.MINOR) {
            TrafficLight light = intersection.getLights().get(affectedDirection);
            if (light != null) light.markFaulty(reason);
        } else {
            passToNext(severity, intersection);
        }
    }
}

// Comm failure → switch to standalone fixed timing
class CommFaultHandler extends FaultHandler {
    @Override
    public void handle(FaultSeverity severity, Intersection intersection) {
        if (severity == FaultSeverity.MAJOR) {
            System.out.println("[FaultHandler] Comm failure → standalone fixed timing");
            intersection.setTimingStrategy(new FixedTimingStrategy(TimingConfig.normal()));
            intersection.setStandalone(true);
        } else {
            passToNext(severity, intersection);
        }
    }
}

// Power failure → all lights FLASHING_RED (treat as 4-way stop)
class PowerFaultHandler extends FaultHandler {
    @Override
    public void handle(FaultSeverity severity, Intersection intersection) {
        if (severity == FaultSeverity.CRITICAL) {
            System.out.println("[FaultHandler] ⚡ Power failure → ALL FLASHING RED");
            intersection.pauseCycle();
            intersection.getLights().values()
                .forEach(l -> l.markFaulty("power failure"));
        } else {
            passToNext(severity, intersection);
        }
    }
}

// ==========================================
// 8. INTERSECTION — BUILDER PATTERN
//    Holds lights + phases + cycle logic
//
//    Phase model (4-way):
//      Phase A: NS green  → NS yellow → all-red
//      Phase B: EW green  → EW yellow → all-red
//      (Pedestrian phase inserted between A and B if requested)
// ==========================================
class Intersection {
    private final  String                        id;
    private final  IntersectionType              type;
    private final  Map<Direction, TrafficLight>  lights       = new LinkedHashMap<>();
    private final  List<LightEventObserver>      observers;
    private        TimingStrategy                timingStrategy;
    private        boolean                       paused       = false;
    private        boolean                       standalone   = false;
    private        int                           cycleNumber  = 0;
    private        boolean                       running      = false;
    private final  ScheduledExecutorService      scheduler;

    private Intersection(Builder b) {
        this.id             = b.id;
        this.type           = b.type;
        this.timingStrategy = b.timingStrategy;
        this.observers      = b.observers;
        this.scheduler      = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "signal-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    // ---- Setup ----
    public void addLight(Direction dir) {
        lights.put(dir, new TrafficLight(dir));
    }

    // ---- Cycle control ----
    public void start() {
        running = true;
        runCycle();
    }

    // One full cycle: NS green-yellow-allred, EW green-yellow-allred
    private void runCycle() {
        if (!running || paused) return;

        cycleNumber++;
        TimingConfig cfg = timingStrategy.getConfig();

        System.out.println("\n--- [" + id + "] Cycle #" + cycleNumber +
            " | " + timingStrategy.getName() + " ---");

        // Determine direction pairs based on intersection type
        List<List<Direction>> pairs = getPhaseDirections();

        for (int i = 0; i < pairs.size(); i++) {
            if (paused) return;  // abort if paused mid-cycle (emergency)

            List<Direction> greenDirs = pairs.get(i);
            String phaseName = "Phase-" + (char)('A' + i) + " " + greenDirs;
            observers.forEach(o -> o.onCycleStarted(id, phaseName));

            // GREEN
            lights.forEach((dir, light) -> {
                if (greenDirs.contains(dir)) light.goGreen();
                else                         light.forceRed();
            });
            sleep(cfg.greenSeconds * 1000L);
            if (paused) return;

            // YELLOW
            greenDirs.forEach(dir -> lights.get(dir).goYellow());
            sleep(cfg.yellowSeconds * 1000L);
            if (paused) return;

            // All RED clearance
            greenDirs.forEach(dir -> lights.get(dir).goRed());
            sleep(cfg.allRedSeconds * 1000L);
        }

        // Schedule next cycle
        if (running && !paused)
            scheduler.schedule(this::runCycle, 0, TimeUnit.MILLISECONDS);
    }

    private List<List<Direction>> getPhaseDirections() {
        if (type == IntersectionType.FOUR_WAY) {
            return List.of(
                List.of(Direction.NORTH, Direction.SOUTH),
                List.of(Direction.EAST,  Direction.WEST));
        } else { // T_JUNCTION
            return List.of(
                List.of(Direction.NORTH),
                List.of(Direction.EAST, Direction.WEST));
        }
    }

    public void pauseCycle()  {
        paused = true;
        System.out.println("[" + id + "] Cycle PAUSED");
    }

    public void resumeCycle() {
        paused = false;
        // Force all to RED first (safe state), then restart
        lights.values().forEach(TrafficLight::forceRed);
        System.out.println("[" + id + "] Cycle RESUMED → restarting from RED");
        scheduler.schedule(this::runCycle, 2, TimeUnit.SECONDS); // 2s delay
    }

    public void stop() {
        running = false;
        paused  = false;
        lights.values().forEach(TrafficLight::forceRed);
        scheduler.shutdown();
    }

    // ---- Fault reporting ----
    public void reportFault(FaultSeverity severity, Direction dir, String reason) {
        observers.forEach(o -> o.onFault(id, dir, reason));

        // Build fault chain dynamically
        FaultHandler bulb  = new BulbFaultHandler(dir, reason);
        FaultHandler comm  = new CommFaultHandler();
        FaultHandler power = new PowerFaultHandler();
        bulb.setNext(comm).setNext(power);
        bulb.handle(severity, this);
    }

    // ---- Restore ----
    public void restore() {
        lights.values().forEach(TrafficLight::restore);
        standalone = false;
        paused     = false;
        observers.forEach(o -> o.onRestored(id));
        if (running) scheduler.schedule(this::runCycle, 1, TimeUnit.SECONDS);
    }

    // ---- Getters / Setters ----
    public String                       getId()        { return id; }
    public IntersectionType             getType()      { return type; }
    public Map<Direction, TrafficLight> getLights()    { return lights; }
    public int                          getCycleNumber(){ return cycleNumber; }
    public boolean                      isStandalone() { return standalone; }
    public boolean                      isPaused()     { return paused; }

    public void setTimingStrategy(TimingStrategy s)  { this.timingStrategy = s; }
    public void setStandalone(boolean b)             { this.standalone = b; }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(
            "Intersection[" + id + " | " + type +
            " | cycle#" + cycleNumber +
            (standalone ? " | STANDALONE" : "") +
            (paused     ? " | PAUSED"     : "") + " | ");
        lights.values().forEach(l -> sb.append(l).append(" "));
        return sb.append("]").toString();
    }

    // ---- BUILDER ----
    static class Builder {
        private final String                   id;
        private final IntersectionType         type;
        private       TimingStrategy           timingStrategy = new FixedTimingStrategy(TimingConfig.normal());
        private       List<LightEventObserver> observers      = new ArrayList<>();

        public Builder(String id, IntersectionType type) {
            this.id   = id;
            this.type = type;
        }
        public Builder timing(TimingStrategy s)  { this.timingStrategy = s;   return this; }
        public Builder observer(LightEventObserver o){ observers.add(o);       return this; }
        public Intersection build()              { return new Intersection(this); }
    }
}

// ==========================================
// 9. INTERSECTION FACTORY
// ==========================================
class IntersectionFactory {

    public static Intersection fourWay(String id, TimingStrategy timing,
                                        List<LightEventObserver> observers) {
        Intersection.Builder b = new Intersection.Builder(id, IntersectionType.FOUR_WAY)
            .timing(timing);
        observers.forEach(b::observer);
        Intersection inter = b.build();

        inter.addLight(Direction.NORTH);
        inter.addLight(Direction.SOUTH);
        inter.addLight(Direction.EAST);
        inter.addLight(Direction.WEST);
        return inter;
    }

    public static Intersection tJunction(String id, TimingStrategy timing,
                                          List<LightEventObserver> observers) {
        Intersection.Builder b = new Intersection.Builder(id, IntersectionType.T_JUNCTION)
            .timing(timing);
        observers.forEach(b::observer);
        Intersection inter = b.build();

        inter.addLight(Direction.NORTH);
        inter.addLight(Direction.EAST);
        inter.addLight(Direction.WEST);
        return inter;
    }
}

// ==========================================
// 10. TRAFFIC CONTROL CENTER — SINGLETON
//     Manages all intersections, issues global commands
// ==========================================
class TrafficControlCenter {
    private static TrafficControlCenter instance;

    private final Map<String, Intersection>  intersections = new LinkedHashMap<>();
    private final List<LightEventObserver>   globalObservers = new ArrayList<>();

    private TrafficControlCenter() {}

    public static synchronized TrafficControlCenter getInstance() {
        if (instance == null) instance = new TrafficControlCenter();
        return instance;
    }

    public void addObserver(LightEventObserver o) { globalObservers.add(o); }

    public Intersection register(Intersection intersection) {
        intersections.put(intersection.getId(), intersection);
        System.out.println("[TCC] Registered: " + intersection.getId());
        return intersection;
    }

    public void startAll() {
        System.out.println("[TCC] Starting " + intersections.size() + " intersections");
        intersections.values().forEach(Intersection::start);
    }

    public void stopAll() {
        intersections.values().forEach(Intersection::stop);
        System.out.println("[TCC] All intersections stopped");
    }

    // Emergency at a single intersection
    public EmergencyCommand triggerEmergency(String intersectionId,
                                             Direction approach) {
        Intersection inter = intersections.get(intersectionId);
        if (inter == null) {
            System.out.println("[TCC] Intersection not found: " + intersectionId);
            return null;
        }
        EmergencyCommand cmd = new EmergencyCommand(inter, approach);
        cmd.execute();
        globalObservers.forEach(o -> o.onEmergency(intersectionId, approach));
        return cmd;
    }

    // Green wave: clear a corridor of intersections in one direction
    public List<EmergencyCommand> greenWave(List<String> corridor, Direction dir) {
        System.out.println("\n[TCC] 🚨 Green wave corridor: " + corridor + " → " + dir);
        List<EmergencyCommand> cmds = new ArrayList<>();
        for (String id : corridor) {
            EmergencyCommand cmd = triggerEmergency(id, dir);
            if (cmd != null) cmds.add(cmd);
        }
        return cmds;
    }

    public void clearAllEmergencies(List<EmergencyCommand> cmds) {
        System.out.println("[TCC] Clearing all emergency overrides");
        cmds.forEach(EmergencyCommand::rollback);
    }

    // Switch timing strategy across all intersections
    public void setGlobalTiming(TimingStrategy strategy) {
        System.out.println("[TCC] Global timing → " + strategy.getName());
        intersections.values().forEach(i -> i.setTimingStrategy(strategy));
    }

    public void printStatus() {
        System.out.println("\n[TCC] === System Status ===");
        intersections.values().forEach(i -> System.out.println("  " + i));
    }

    public Intersection get(String id) { return intersections.get(id); }
    public int          count()        { return intersections.size(); }
}

// ==========================================
// 11. MAIN — DRIVER CODE
// ==========================================
public class TrafficLightSystem {
    public static void main(String[] args) throws InterruptedException {

        TrafficControlCenter tcc       = TrafficControlCenter.getInstance();
        DashboardObserver    dashboard = new DashboardObserver();
        LoggerObserver       logger    = new LoggerObserver();
        List<LightEventObserver> observers = List.of(dashboard, logger);

        tcc.addObserver(dashboard);
        tcc.addObserver(logger);

        // ---- Create intersections ----
        Intersection mgRoad = IntersectionFactory.fourWay(
            "MG-ROAD",    new FixedTimingStrategy(TimingConfig.normal()),  observers);

        Intersection brigade = IntersectionFactory.tJunction(
            "BRIGADE",    new FixedTimingStrategy(TimingConfig.normal()),  observers);

        Intersection koramangala = IntersectionFactory.fourWay(
            "KORAMANGALA", new PeakHourTimingStrategy(),                   observers);

        tcc.register(mgRoad);
        tcc.register(brigade);
        tcc.register(koramangala);

        // ===== SCENARIO 1: Normal single-cycle run =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Run one full cycle on MG-ROAD");
        System.out.println("=".repeat(55));

        // Run directly (blocking) to see output clearly
        // In real life the scheduler drives this asynchronously
        System.out.println("TimingConfig: " + TimingConfig.normal());

        // Simulate Phase A (NS) manually to show state machine
        TrafficLight northLight = new TrafficLight(Direction.NORTH);
        TrafficLight southLight = new TrafficLight(Direction.SOUTH);
        TrafficLight eastLight  = new TrafficLight(Direction.EAST);
        TrafficLight westLight  = new TrafficLight(Direction.WEST);

        System.out.println("\n-- Phase A: North-South GREEN --");
        northLight.goGreen(); southLight.goGreen();
        System.out.println("East:  " + eastLight.getColor());
        System.out.println("West:  " + westLight.getColor());

        System.out.println("\n-- Phase A: North-South YELLOW (warning) --");
        northLight.goYellow(); southLight.goYellow();

        System.out.println("\n-- Phase A: All RED (clearance) --");
        northLight.goRed(); southLight.goRed();

        System.out.println("\n-- Phase B: East-West GREEN --");
        eastLight.goGreen(); westLight.goGreen();

        System.out.println("\n-- Phase B: East-West YELLOW --");
        eastLight.goYellow(); westLight.goYellow();

        System.out.println("\n-- Phase B: All RED (clearance) --");
        eastLight.goRed(); westLight.goRed();

        System.out.println("\nOne full cycle complete ✓");

        // ===== SCENARIO 2: Invalid state transitions =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Invalid State Transitions (guard checks)");
        System.out.println("=".repeat(55));

        TrafficLight t = new TrafficLight(Direction.NORTH);
        System.out.println("Current: " + t.getColor());
        t.goYellow();   // invalid — can't skip GREEN
        t.goRed();      // invalid — still RED
        t.goGreen();    // valid
        t.goRed();      // invalid — must go YELLOW first
        t.goYellow();   // valid
        t.goGreen();    // invalid — must go RED first
        t.goRed();      // valid
        System.out.println("Final: " + t.getColor() + " | cycles=" + t.getCycles());

        // ===== SCENARIO 3: Emergency override + rollback =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Emergency Override (Ambulance from EAST)");
        System.out.println("=".repeat(55));

        // Create a fresh intersection for this demo
        Intersection demo = IntersectionFactory.fourWay(
            "DEMO-INT", new FixedTimingStrategy(TimingConfig.normal()), observers);

        // Force all to known state
        demo.getLights().forEach((dir, light) -> {
            light.goGreen(); light.goYellow(); light.goRed();
        });

        System.out.println("Before emergency:");
        demo.getLights().forEach((dir, light) ->
            System.out.println("  " + dir + " = " + light.getColor()));

        EmergencyCommand cmd = new EmergencyCommand(demo, Direction.EAST);
        cmd.execute();

        System.out.println("\nDuring emergency:");
        demo.getLights().forEach((dir, light) ->
            System.out.println("  " + dir + " = " + light.getColor()));

        System.out.println("\nRolling back (ambulance passed):");
        cmd.rollback();

        // ===== SCENARIO 4: Fault handling chain =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: Fault Handling Chain");
        System.out.println("=".repeat(55));

        Intersection faultDemo = IntersectionFactory.fourWay(
            "FAULT-INT", new FixedTimingStrategy(TimingConfig.normal()), observers);

        // Minor fault — bulb failure on NORTH
        System.out.println("--- MINOR fault: bulb failure on NORTH ---");
        faultDemo.reportFault(FaultSeverity.MINOR, Direction.NORTH, "bulb out");
        System.out.println("NORTH light: " + faultDemo.getLights().get(Direction.NORTH));
        System.out.println("SOUTH light: " + faultDemo.getLights().get(Direction.SOUTH) +
            " (unaffected)");

        // Restore
        faultDemo.restore();
        System.out.println("After restore: " +
            faultDemo.getLights().get(Direction.NORTH));

        // Major fault — communication failure
        System.out.println("\n--- MAJOR fault: communication failure ---");
        faultDemo.reportFault(FaultSeverity.MAJOR, Direction.SOUTH, "comm down");
        System.out.println("Standalone mode: " + faultDemo.isStandalone());

        // Critical fault — power failure
        System.out.println("\n--- CRITICAL fault: power failure ---");
        Intersection powerDemo = IntersectionFactory.fourWay(
            "POWER-INT", new FixedTimingStrategy(TimingConfig.normal()), observers);
        powerDemo.reportFault(FaultSeverity.CRITICAL, Direction.NORTH, "power cut");
        System.out.println("All lights after power failure:");
        powerDemo.getLights().forEach((dir, light) ->
            System.out.println("  " + dir + " = " + light.getColor()));

        // ===== SCENARIO 5: Peak-hour timing switch =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Timing Strategy Switch");
        System.out.println("=".repeat(55));

        System.out.println("Normal:    " + TimingConfig.normal());
        System.out.println("Peak hour: " + TimingConfig.peakHour());
        System.out.println("Night:     " + TimingConfig.night());

        tcc.setGlobalTiming(new PeakHourTimingStrategy());
        System.out.println("Global timing updated to: " +
            new PeakHourTimingStrategy().getName());

        // Switch back to normal at night
        tcc.setGlobalTiming(new FixedTimingStrategy(TimingConfig.night()));

        // ===== SCENARIO 6: Green wave (emergency corridor) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Green Wave (Fire Engine Corridor)");
        System.out.println("=".repeat(55));

        // Setup all intersections with known state
        for (Intersection i : List.of(mgRoad, brigade, koramangala)) {
            i.getLights().values().forEach(l -> {
                try {
                    l.goGreen(); l.goYellow(); l.goRed();
                } catch (Exception ignored) {}
            });
        }

        List<EmergencyCommand> waveCmds = tcc.greenWave(
            List.of("MG-ROAD", "BRIGADE", "KORAMANGALA"),
            Direction.NORTH);

        Thread.sleep(100);

        System.out.println("\nDuring green wave — MG-ROAD lights:");
        mgRoad.getLights().forEach((dir, light) ->
            System.out.println("  " + dir + " = " + light.getColor()));

        // Clear the wave
        Thread.sleep(200);
        tcc.clearAllEmergencies(waveCmds);

        // ===== SCENARIO 7: System status =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: System Status");
        System.out.println("=".repeat(55));

        tcc.printStatus();

        // ===== SCENARIO 8: Event log =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Event Log (last events)");
        System.out.println("=".repeat(55));

        logger.printLog();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern                  | Class
            -------------------------|-----------------------------------------
            Singleton                | TrafficControlCenter
            Strategy                 | TimingStrategy (Fixed / PeakHour)
            Observer                 | LightEventObserver (Dashboard / Logger)
            Factory                  | IntersectionFactory (fourWay / tJunction)
            Builder                  | Intersection.Builder
            State                    | TrafficLight (RED→GREEN→YELLOW→RED)
            Command                  | EmergencyCommand (execute + rollback)
            Chain of Responsibility  | FaultHandler (Bulb→Comm→Power)
            """);
    }
}
