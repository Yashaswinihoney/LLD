import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// ============================================================
// TRAFFIC LIGHT CONTROL SYSTEM — LLD
// Patterns:
//   Singleton  — IntersectionController, TrafficLightSystem
//   State      — Phase (NS_GREEN→NS_YELLOW→ALL_RED→EW_GREEN→...)
//   Strategy   — TimingStrategy (fixed vs. adaptive sensor-based)
//   Observer   — SignalObserver (monitoring, emergency, logger)
//   Factory    — IntersectionFactory
//   Builder    — Intersection.Builder
//   Command    — PhaseChangeCommand (encapsulate phase transitions)
//   Template   — AbstractIntersectionController (standard cycle)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum LightColor      { RED, YELLOW, GREEN, FLASHING_RED, FLASHING_YELLOW, ALL_OFF }
enum Phase {
    NS_GREEN,           // North-South green, East-West red
    NS_YELLOW,          // North-South yellow (clearing)
    ALL_RED_CLEARANCE,  // All red safety buffer between phases
    EW_GREEN,           // East-West green, North-South red
    EW_YELLOW,          // East-West yellow (clearing)
    PEDESTRIAN_CROSSING,// All vehicle red; pedestrian walk signal active
    EMERGENCY_PREEMPTION,// All red → emergency approach direction green
    OFF_PEAK_FLASH,     // Flashing yellow (late night low-traffic mode)
    POWER_FAILURE       // All signals off
}
enum Direction       { NORTH, SOUTH, EAST, WEST }
enum ControlMode     { NORMAL, ADAPTIVE, PEDESTRIAN_REQUESTED, EMERGENCY, MAINTENANCE, OFF_PEAK }
enum SensorType      { INDUCTIVE_LOOP, PEDESTRIAN_BUTTON, EMERGENCY_BEACON, CAMERA_VISION }
enum FaultType       { BULB_FAILURE, SENSOR_FAILURE, CONTROLLER_FAILURE, POWER_FAILURE, COMM_FAILURE }

// ============================================================
// 2. PHASE CONFIG — value object for timing parameters
// ============================================================
class PhaseConfig {
    private final Phase  phase;
    private final int    greenSeconds;    // base green duration
    private final int    yellowSeconds;   // always fixed (SEBI → MUTCD rules: 3-5 sec)
    private final int    allRedSeconds;   // inter-green clearance
    private final int    minGreenSeconds; // adaptive: floor
    private final int    maxGreenSeconds; // adaptive: ceiling

    public PhaseConfig(Phase phase, int green, int yellow, int allRed,
                       int minGreen, int maxGreen) {
        this.phase          = phase;
        this.greenSeconds   = green;
        this.yellowSeconds  = yellow;
        this.allRedSeconds  = allRed;
        this.minGreenSeconds= minGreen;
        this.maxGreenSeconds= maxGreen;
    }

    // Convenience — symmetric intersections
    public PhaseConfig(Phase phase, int green, int yellow, int allRed) {
        this(phase, green, yellow, allRed, green / 2, green * 2);
    }

    public Phase getPhase()           { return phase; }
    public int   getGreenSeconds()    { return greenSeconds; }
    public int   getYellowSeconds()   { return yellowSeconds; }
    public int   getAllRedSeconds()    { return allRedSeconds; }
    public int   getMinGreenSeconds() { return minGreenSeconds; }
    public int   getMaxGreenSeconds() { return maxGreenSeconds; }

    @Override public String toString() {
        return String.format("PhaseConfig[%s | G:%ds Y:%ds AR:%ds min:%ds max:%ds]",
            phase, greenSeconds, yellowSeconds, allRedSeconds, minGreenSeconds, maxGreenSeconds);
    }
}

// ============================================================
// 3. SENSOR READING — value object
// ============================================================
class SensorReading {
    private final SensorType   type;
    private final Direction    direction;
    private final int          vehicleCount;   // queued vehicles detected
    private final boolean      pedestrianWait; // button pressed
    private final boolean      emergencyDetected;
    private final LocalDateTime timestamp;

    public SensorReading(SensorType type, Direction direction, int vehicleCount,
                         boolean pedestrianWait, boolean emergencyDetected) {
        this.type              = type;
        this.direction         = direction;
        this.vehicleCount      = vehicleCount;
        this.pedestrianWait    = pedestrianWait;
        this.emergencyDetected = emergencyDetected;
        this.timestamp         = LocalDateTime.now();
    }

    public SensorType      getType()              { return type; }
    public Direction       getDirection()          { return direction; }
    public int             getVehicleCount()       { return vehicleCount; }
    public boolean         isPedestrianWaiting()   { return pedestrianWait; }
    public boolean         isEmergencyDetected()   { return emergencyDetected; }
    public LocalDateTime   getTimestamp()          { return timestamp; }
}

// ============================================================
// 4. TRAFFIC SIGNAL — one physical signal head (per direction)
// ============================================================
class TrafficSignal {
    private static final AtomicLong idGen = new AtomicLong(100);

    private final long      signalId;
    private final Direction direction;
    private       LightColor currentColor;
    private       LightColor pedestrianColor; // separate ped signal
    private       boolean    isFlashing;
    private       boolean    faulted;

    public TrafficSignal(Direction direction) {
        this.signalId        = idGen.getAndIncrement();
        this.direction       = direction;
        this.currentColor    = LightColor.RED;
        this.pedestrianColor = LightColor.RED;
        this.isFlashing      = false;
        this.faulted         = false;
    }

    public void setColor(LightColor color) {
        if (faulted && color != LightColor.FLASHING_RED) {
            System.out.println("[Signal#" + signalId + ":" + direction +
                "] FAULTED — cannot set " + color + ", holding FLASHING_RED");
            return;
        }
        LightColor prev = this.currentColor;
        this.currentColor = color;
        this.isFlashing   = (color == LightColor.FLASHING_RED ||
                             color == LightColor.FLASHING_YELLOW);
        if (prev != color)
            System.out.printf("[Signal#%d:%s] %s → %s%n",
                signalId, direction, prev, color);
    }

    public void setPedestrianColor(LightColor color) {
        this.pedestrianColor = color;
        System.out.printf("[Signal#%d:%s] Pedestrian → %s%n",
            signalId, direction, color);
    }

    public void declareFault(FaultType fault) {
        this.faulted = true;
        setColor(LightColor.FLASHING_RED); // fail-safe: treat as stop sign
        System.out.println("[FAULT] Signal#" + signalId + ":" + direction +
            " declared " + fault + " → FLASHING_RED");
    }

    public void clearFault() {
        this.faulted = false;
        System.out.println("[Signal#" + signalId + ":" + direction + "] Fault cleared");
    }

    public long       getSignalId()        { return signalId; }
    public Direction  getDirection()       { return direction; }
    public LightColor getCurrentColor()    { return currentColor; }
    public LightColor getPedestrianColor() { return pedestrianColor; }
    public boolean    isFaulted()          { return faulted; }

    @Override public String toString() {
        return String.format("Signal[#%d %s | Vehicle:%s | Ped:%s%s]",
            signalId, direction, currentColor, pedestrianColor,
            faulted ? " FAULTED" : "");
    }
}

// ============================================================
// 5. TIMING STRATEGY — STRATEGY PATTERN
// ============================================================
interface TimingStrategy {
    String getName();
    /** Return actual green duration in seconds for this phase, given sensor input. */
    int computeGreenDuration(PhaseConfig config, List<SensorReading> readings);
}

class FixedTimingStrategy implements TimingStrategy {
    @Override public String getName() { return "Fixed Timer"; }

    @Override
    public int computeGreenDuration(PhaseConfig config, List<SensorReading> readings) {
        return config.getGreenSeconds(); // always return base configured duration
    }
}

class AdaptiveTimingStrategy implements TimingStrategy {
    private static final int VEHICLES_PER_EXTRA_SECOND = 3; // 3 vehicles = +1s green

    @Override public String getName() { return "Adaptive (Vehicle-Count Sensor)"; }

    @Override
    public int computeGreenDuration(PhaseConfig config, List<SensorReading> readings) {
        int totalVehicles = readings.stream()
            .mapToInt(SensorReading::getVehicleCount).sum();

        int base  = config.getGreenSeconds();
        int bonus = totalVehicles / VEHICLES_PER_EXTRA_SECOND;
        int computed = base + bonus;

        // Clamp between min and max
        int result = Math.max(config.getMinGreenSeconds(),
                              Math.min(config.getMaxGreenSeconds(), computed));

        System.out.printf("[Adaptive] Vehicles=%d base=%ds bonus=%ds → clamped=%ds%n",
            totalVehicles, base, bonus, result);
        return result;
    }
}

// ============================================================
// 6. SIGNAL OBSERVER — OBSERVER PATTERN
// ============================================================
interface SignalObserver {
    void onPhaseChange(Phase oldPhase, Phase newPhase, String intersectionId);
    void onFault(FaultType fault, Direction direction, String intersectionId);
    void onEmergency(Direction approachDir, String intersectionId);
    void onModeChange(ControlMode oldMode, ControlMode newMode, String intersectionId);
}

class MonitoringService implements SignalObserver {
    private final List<String> auditLog = new ArrayList<>();

    @Override
    public void onPhaseChange(Phase oldPhase, Phase newPhase, String id) {
        String entry = "[Monitor:" + id + "] Phase " + oldPhase + " → " + newPhase +
            " at " + LocalDateTime.now();
        auditLog.add(entry);
        System.out.println(entry);
    }

    @Override
    public void onFault(FaultType fault, Direction dir, String id) {
        String entry = "[Monitor:" + id + "] ⚠ FAULT " + fault + " at " + dir;
        auditLog.add(entry);
        System.out.println(entry);
        // In a real system: page on-call technician, open incident ticket
    }

    @Override
    public void onEmergency(Direction dir, String id) {
        System.out.println("[Monitor:" + id + "] 🚨 EMERGENCY from " + dir);
    }

    @Override
    public void onModeChange(ControlMode old, ControlMode nw, String id) {
        System.out.println("[Monitor:" + id + "] Mode " + old + " → " + nw);
    }

    public List<String> getAuditLog() { return Collections.unmodifiableList(auditLog); }
}

class EmergencyService implements SignalObserver {
    // Records which intersection is under emergency preemption
    private final Set<String> activeEmergencies = ConcurrentHashMap.newKeySet();

    @Override public void onPhaseChange(Phase o, Phase n, String id) {}
    @Override public void onFault(FaultType f, Direction d, String id) {}
    @Override public void onModeChange(ControlMode o, ControlMode n, String id) {}

    @Override
    public void onEmergency(Direction dir, String id) {
        activeEmergencies.add(id);
        System.out.println("[Emergency] Preemption ACTIVE on intersection " + id +
            " — clearing path from " + dir);
    }

    public void clearEmergency(String id) {
        activeEmergencies.remove(id);
        System.out.println("[Emergency] Preemption CLEARED on intersection " + id);
    }

    public boolean isUnderEmergency(String id) { return activeEmergencies.contains(id); }
}

class DataLogger implements SignalObserver {
    // Append-only log of all phase transitions for analytics / timing audit
    private final List<String> phaseLog = new CopyOnWriteArrayList<>();

    @Override
    public void onPhaseChange(Phase old, Phase nw, String id) {
        phaseLog.add(id + "|" + old + "|" + nw + "|" + System.currentTimeMillis());
    }

    @Override public void onFault(FaultType f, Direction d, String id) {
        phaseLog.add(id + "|FAULT|" + f + "|" + d + "|" + System.currentTimeMillis());
    }

    @Override public void onEmergency(Direction d, String id) {
        phaseLog.add(id + "|EMERGENCY|" + d + "|" + System.currentTimeMillis());
    }

    @Override public void onModeChange(ControlMode o, ControlMode n, String id) {
        phaseLog.add(id + "|MODE|" + o + "|" + n + "|" + System.currentTimeMillis());
    }

    public List<String> getPhaseLog() { return Collections.unmodifiableList(phaseLog); }
}

// ============================================================
// 7. FAULT DETECTOR
// ============================================================
class FaultDetector {
    private final Map<Long, FaultType> activeFaults = new ConcurrentHashMap<>();

    public boolean checkBulb(TrafficSignal signal, LightColor expected) {
        // Simulate: 5% chance of bulb failure for demo
        boolean failed = Math.random() < 0.05;
        if (failed) {
            activeFaults.put(signal.getSignalId(), FaultType.BULB_FAILURE);
            signal.declareFault(FaultType.BULB_FAILURE);
            return false;
        }
        return true;
    }

    public boolean checkSensor(SensorReading reading) {
        // If sensor reports impossible values, mark as failed
        if (reading.getVehicleCount() < 0 || reading.getVehicleCount() > 500) {
            System.out.println("[FaultDetector] Sensor reading out of range — SENSOR_FAILURE");
            return false;
        }
        return true;
    }

    public void reportFault(long signalId, FaultType fault) {
        activeFaults.put(signalId, fault);
        System.out.println("[FaultDetector] Recorded fault " + fault + " on signal#" + signalId);
    }

    public boolean hasFault(long signalId) { return activeFaults.containsKey(signalId); }
    public Map<Long, FaultType> getActiveFaults() { return Collections.unmodifiableMap(activeFaults); }
}

// ============================================================
// 8. COMMAND — PHASE CHANGE COMMAND (Command Pattern)
// ============================================================
interface TrafficCommand {
    void execute(Map<Direction, TrafficSignal> signals);
    void undo(Map<Direction, TrafficSignal> signals);
    Phase getTargetPhase();
}

class NSGreenCommand implements TrafficCommand {
    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        signals.get(Direction.NORTH).setColor(LightColor.GREEN);
        signals.get(Direction.SOUTH).setColor(LightColor.GREEN);
        signals.get(Direction.EAST).setColor(LightColor.RED);
        signals.get(Direction.WEST).setColor(LightColor.RED);
        signals.get(Direction.NORTH).setPedestrianColor(LightColor.RED);
        signals.get(Direction.EAST).setPedestrianColor(LightColor.RED);
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { new AllRedCommand().execute(s); }
    @Override public Phase getTargetPhase() { return Phase.NS_GREEN; }
}

class NSYellowCommand implements TrafficCommand {
    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        signals.get(Direction.NORTH).setColor(LightColor.YELLOW);
        signals.get(Direction.SOUTH).setColor(LightColor.YELLOW);
        // East/West already red — no change needed
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { new AllRedCommand().execute(s); }
    @Override public Phase getTargetPhase() { return Phase.NS_YELLOW; }
}

class EWGreenCommand implements TrafficCommand {
    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        signals.get(Direction.NORTH).setColor(LightColor.RED);
        signals.get(Direction.SOUTH).setColor(LightColor.RED);
        signals.get(Direction.EAST).setColor(LightColor.GREEN);
        signals.get(Direction.WEST).setColor(LightColor.GREEN);
        signals.get(Direction.EAST).setPedestrianColor(LightColor.RED);
        signals.get(Direction.NORTH).setPedestrianColor(LightColor.RED);
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { new AllRedCommand().execute(s); }
    @Override public Phase getTargetPhase() { return Phase.EW_GREEN; }
}

class EWYellowCommand implements TrafficCommand {
    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        signals.get(Direction.EAST).setColor(LightColor.YELLOW);
        signals.get(Direction.WEST).setColor(LightColor.YELLOW);
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { new AllRedCommand().execute(s); }
    @Override public Phase getTargetPhase() { return Phase.EW_YELLOW; }
}

class AllRedCommand implements TrafficCommand {
    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        signals.values().forEach(s -> s.setColor(LightColor.RED));
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { /* no-op */ }
    @Override public Phase getTargetPhase() { return Phase.ALL_RED_CLEARANCE; }
}

class PedestrianCrossCommand implements TrafficCommand {
    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        // All vehicles red; pedestrian walk on NS and EW
        signals.values().forEach(s -> s.setColor(LightColor.RED));
        signals.get(Direction.NORTH).setPedestrianColor(LightColor.GREEN); // WALK
        signals.get(Direction.EAST).setPedestrianColor(LightColor.GREEN);  // WALK
        System.out.println("[Pedestrian] WALK signal active — all vehicles RED");
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { new AllRedCommand().execute(s); }
    @Override public Phase getTargetPhase() { return Phase.PEDESTRIAN_CROSSING; }
}

class EmergencyPreemptCommand implements TrafficCommand {
    private final Direction approachDirection;

    public EmergencyPreemptCommand(Direction approachDir) {
        this.approachDirection = approachDir;
    }

    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        // All red first (safety clear), then green for emergency approach axis
        signals.values().forEach(s -> s.setColor(LightColor.RED));
        boolean isNS = approachDirection == Direction.NORTH ||
                       approachDirection == Direction.SOUTH;
        if (isNS) {
            signals.get(Direction.NORTH).setColor(LightColor.GREEN);
            signals.get(Direction.SOUTH).setColor(LightColor.GREEN);
        } else {
            signals.get(Direction.EAST).setColor(LightColor.GREEN);
            signals.get(Direction.WEST).setColor(LightColor.GREEN);
        }
        System.out.println("[EMERGENCY] 🚨 Path cleared for approach from " + approachDirection);
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { new AllRedCommand().execute(s); }
    @Override public Phase getTargetPhase() { return Phase.EMERGENCY_PREEMPTION; }
}

class OffPeakFlashCommand implements TrafficCommand {
    @Override
    public void execute(Map<Direction, TrafficSignal> signals) {
        // Main road (NS) = flashing yellow (caution); Side road (EW) = flashing red (stop)
        signals.get(Direction.NORTH).setColor(LightColor.FLASHING_YELLOW);
        signals.get(Direction.SOUTH).setColor(LightColor.FLASHING_YELLOW);
        signals.get(Direction.EAST).setColor(LightColor.FLASHING_RED);
        signals.get(Direction.WEST).setColor(LightColor.FLASHING_RED);
        System.out.println("[OffPeak] NS=FLASHING_YELLOW (caution) | EW=FLASHING_RED (stop)");
    }
    @Override public void undo(Map<Direction, TrafficSignal> s) { new AllRedCommand().execute(s); }
    @Override public Phase getTargetPhase() { return Phase.OFF_PEAK_FLASH; }
}

// ============================================================
// 9. INTERSECTION — BUILDER PATTERN
// ============================================================
class Intersection {
    private final String             id;
    private final String             location;
    private final Map<Phase, PhaseConfig> phaseConfigs;
    private final boolean            adaptiveEnabled;
    private final boolean            pedestrianEnabled;

    private Intersection(Builder b) {
        this.id                = b.id;
        this.location          = b.location;
        this.phaseConfigs      = Collections.unmodifiableMap(b.phaseConfigs);
        this.adaptiveEnabled   = b.adaptiveEnabled;
        this.pedestrianEnabled = b.pedestrianEnabled;
    }

    public String    getId()                 { return id; }
    public String    getLocation()           { return location; }
    public boolean   isAdaptiveEnabled()     { return adaptiveEnabled; }
    public boolean   isPedestrianEnabled()   { return pedestrianEnabled; }
    public PhaseConfig getPhaseConfig(Phase p) { return phaseConfigs.get(p); }

    static class Builder {
        private final String id;
        private final String location;
        private final Map<Phase, PhaseConfig> phaseConfigs = new EnumMap<>(Phase.class);
        private boolean adaptiveEnabled   = false;
        private boolean pedestrianEnabled = true;

        public Builder(String id, String location) {
            this.id = id; this.location = location;
        }

        public Builder phaseConfig(PhaseConfig cfg) {
            phaseConfigs.put(cfg.getPhase(), cfg); return this;
        }
        public Builder adaptive(boolean v)   { adaptiveEnabled = v;   return this; }
        public Builder pedestrian(boolean v) { pedestrianEnabled = v; return this; }
        public Intersection build()          { return new Intersection(this); }
    }

    @Override public String toString() {
        return "Intersection[" + id + " @ " + location +
            " | adaptive=" + adaptiveEnabled + " ped=" + pedestrianEnabled + "]";
    }
}

// ============================================================
// 10. ABSTRACT INTERSECTION CONTROLLER — TEMPLATE METHOD
// Defines the standard cycle skeleton; subclasses can override
// ============================================================
abstract class AbstractIntersectionController {
    protected final Intersection                 intersection;
    protected final Map<Direction, TrafficSignal> signals;
    protected final List<SignalObserver>          observers;
    protected final FaultDetector                faultDetector;
    protected       Phase                        currentPhase;
    protected       ControlMode                  mode;
    protected final Deque<TrafficCommand>        commandHistory = new ArrayDeque<>();

    public AbstractIntersectionController(Intersection intersection) {
        this.intersection  = intersection;
        this.observers     = new ArrayList<>();
        this.faultDetector = new FaultDetector();
        this.mode          = ControlMode.NORMAL;
        this.currentPhase  = Phase.ALL_RED_CLEARANCE;

        // Instantiate one signal per direction
        this.signals = new EnumMap<>(Direction.class);
        for (Direction d : Direction.values()) signals.put(d, new TrafficSignal(d));
    }

    // Template method — defines the standard cycle
    public final void runOneCycle(List<SensorReading> sensorReadings) {
        if (mode == ControlMode.MAINTENANCE) {
            System.out.println("[" + intersection.getId() + "] MAINTENANCE mode — manual override");
            return;
        }
        if (mode == ControlMode.OFF_PEAK) {
            applyCommand(new OffPeakFlashCommand()); return;
        }
        if (mode == ControlMode.EMERGENCY) {
            // emergency handled externally via triggerEmergency()
            return;
        }
        runNormalCycle(sensorReadings);      // subclass hook
    }

    protected abstract void runNormalCycle(List<SensorReading> readings);

    // Apply a command and record in history (Command pattern)
    protected void applyCommand(TrafficCommand cmd) {
        Phase old = currentPhase;
        cmd.execute(signals);
        commandHistory.push(cmd);
        currentPhase = cmd.getTargetPhase();
        notifyPhaseChange(old, currentPhase);
    }

    protected void undoLastCommand() {
        if (!commandHistory.isEmpty()) {
            TrafficCommand last = commandHistory.pop();
            last.undo(signals);
            System.out.println("[" + intersection.getId() + "] Undid: " + last.getTargetPhase());
        }
    }

    // Emergency preemption — highest priority, interrupts everything
    public void triggerEmergency(Direction approachDir) {
        ControlMode old = mode;
        mode = ControlMode.EMERGENCY;
        notifyModeChange(old, mode);
        notifyEmergency(approachDir);
        applyCommand(new EmergencyPreemptCommand(approachDir));
    }

    public void clearEmergency() {
        ControlMode old = mode;
        mode = ControlMode.NORMAL;
        applyCommand(new AllRedCommand());
        notifyModeChange(old, mode);
        System.out.println("[" + intersection.getId() + "] Emergency cleared — resuming NORMAL");
    }

    // Pedestrian crossing interrupt
    public void triggerPedestrianCrossing() {
        if (!intersection.isPedestrianEnabled()) return;
        ControlMode old = mode;
        mode = ControlMode.PEDESTRIAN_REQUESTED;
        notifyModeChange(old, mode);
        applyCommand(new AllRedCommand());     // flush current green safely
        applyCommand(new PedestrianCrossCommand());
    }

    public void setMode(ControlMode newMode) {
        ControlMode old = mode;
        this.mode = newMode;
        notifyModeChange(old, newMode);
    }

    public void addObserver(SignalObserver obs)    { observers.add(obs); }

    protected void notifyPhaseChange(Phase old, Phase nw) {
        observers.forEach(o -> o.onPhaseChange(old, nw, intersection.getId()));
    }
    protected void notifyFault(FaultType f, Direction d) {
        observers.forEach(o -> o.onFault(f, d, intersection.getId()));
    }
    protected void notifyEmergency(Direction d) {
        observers.forEach(o -> o.onEmergency(d, intersection.getId()));
    }
    protected void notifyModeChange(ControlMode old, ControlMode nw) {
        observers.forEach(o -> o.onModeChange(old, nw, intersection.getId()));
    }

    public Phase       getCurrentPhase() { return currentPhase; }
    public ControlMode getMode()         { return mode; }
    public Map<Direction, TrafficSignal> getSignals() { return Collections.unmodifiableMap(signals); }
    public void printSignalState() {
        System.out.println("\n--- Signal State [" + intersection.getId() +
            " | Phase:" + currentPhase + " | Mode:" + mode + "] ---");
        signals.values().forEach(s -> System.out.println("  " + s));
    }
}

// ============================================================
// 11. CONCRETE CONTROLLER — uses Strategy for timing
// ============================================================
class StandardIntersectionController extends AbstractIntersectionController {
    private TimingStrategy timingStrategy;

    public StandardIntersectionController(Intersection intersection,
                                          TimingStrategy strategy) {
        super(intersection);
        this.timingStrategy = strategy;
    }

    public void setTimingStrategy(TimingStrategy strategy) {
        System.out.println("[" + intersection.getId() + "] Switching strategy to: " +
            strategy.getName());
        this.timingStrategy = strategy;
    }

    @Override
    protected void runNormalCycle(List<SensorReading> readings) {
        String id = intersection.getId();
        System.out.println("\n[" + id + "] === Starting new cycle ===");

        // Phase 1: NS GREEN
        PhaseConfig nsCfg = intersection.getPhaseConfig(Phase.NS_GREEN);
        if (nsCfg != null) {
            int greenDur = timingStrategy.computeGreenDuration(nsCfg, readings);
            applyCommand(new NSGreenCommand());
            sleep(id, "NS_GREEN", greenDur);
        }

        // Phase 2: NS YELLOW
        PhaseConfig nsYCfg = intersection.getPhaseConfig(Phase.NS_YELLOW);
        if (nsYCfg != null) {
            applyCommand(new NSYellowCommand());
            sleep(id, "NS_YELLOW", nsYCfg.getYellowSeconds());
        }

        // Phase 3: ALL RED CLEARANCE
        applyCommand(new AllRedCommand());
        sleep(id, "ALL_RED_CLEARANCE", 2);

        // Phase 4: EW GREEN
        PhaseConfig ewCfg = intersection.getPhaseConfig(Phase.EW_GREEN);
        if (ewCfg != null) {
            int greenDur = timingStrategy.computeGreenDuration(ewCfg, readings);
            applyCommand(new EWGreenCommand());
            sleep(id, "EW_GREEN", greenDur);
        }

        // Phase 5: EW YELLOW
        PhaseConfig ewYCfg = intersection.getPhaseConfig(Phase.EW_YELLOW);
        if (ewYCfg != null) {
            applyCommand(new EWYellowCommand());
            sleep(id, "EW_YELLOW", ewYCfg.getYellowSeconds());
        }

        // Phase 6: ALL RED CLEARANCE before next cycle
        applyCommand(new AllRedCommand());
        sleep(id, "ALL_RED_CLEARANCE", 2);
    }

    private void sleep(String id, String phase, int seconds) {
        // Simulate timing (scaled to 100ms per second for demo)
        System.out.printf("[%s] Holding %s for %ds (sim: %dms)%n",
            id, phase, seconds, seconds * 100);
        try { Thread.sleep(seconds * 100L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// ============================================================
// 12. INTERSECTION FACTORY
// ============================================================
class IntersectionFactory {
    /** Standard 4-way intersection: 30s NS, 30s EW, 4s yellow, 2s all-red */
    public static Intersection standardFourWay(String id, String location) {
        return new Intersection.Builder(id, location)
            .phaseConfig(new PhaseConfig(Phase.NS_GREEN,  30, 4, 2, 15, 60))
            .phaseConfig(new PhaseConfig(Phase.NS_YELLOW,  0, 4, 2))
            .phaseConfig(new PhaseConfig(Phase.EW_GREEN,  30, 4, 2, 15, 60))
            .phaseConfig(new PhaseConfig(Phase.EW_YELLOW,  0, 4, 2))
            .adaptive(false)
            .pedestrian(true)
            .build();
    }

    /** Busy urban intersection: adaptive, shorter cycle, pedestrian */
    public static Intersection urbanAdaptive(String id, String location) {
        return new Intersection.Builder(id, location)
            .phaseConfig(new PhaseConfig(Phase.NS_GREEN,  20, 4, 2, 10, 45))
            .phaseConfig(new PhaseConfig(Phase.NS_YELLOW,  0, 4, 2))
            .phaseConfig(new PhaseConfig(Phase.EW_GREEN,  20, 4, 2, 10, 45))
            .phaseConfig(new PhaseConfig(Phase.EW_YELLOW,  0, 4, 2))
            .adaptive(true)
            .pedestrian(true)
            .build();
    }

    /** T-intersection: only NS and one side (East) */
    public static Intersection tIntersection(String id, String location) {
        return new Intersection.Builder(id, location)
            .phaseConfig(new PhaseConfig(Phase.NS_GREEN,  25, 4, 2, 12, 50))
            .phaseConfig(new PhaseConfig(Phase.NS_YELLOW,  0, 4, 2))
            .phaseConfig(new PhaseConfig(Phase.EW_GREEN,  15, 4, 2,  8, 30))
            .phaseConfig(new PhaseConfig(Phase.EW_YELLOW,  0, 4, 2))
            .adaptive(false)
            .pedestrian(false) // no ped crossing on T-junction arm
            .build();
    }
}

// ============================================================
// 13. TRAFFIC LIGHT SYSTEM — SINGLETON (top-level orchestrator)
// ============================================================
class TrafficLightSystem {
    private static TrafficLightSystem instance;

    private final Map<String, StandardIntersectionController> controllers =
        new ConcurrentHashMap<>();
    private final MonitoringService  monitoring  = new MonitoringService();
    private final EmergencyService   emergency   = new EmergencyService();
    private final DataLogger         logger      = new DataLogger();

    private TrafficLightSystem() {}

    public static synchronized TrafficLightSystem getInstance() {
        if (instance == null) instance = new TrafficLightSystem();
        return instance;
    }

    public StandardIntersectionController registerIntersection(
            Intersection intersection, TimingStrategy strategy) {
        StandardIntersectionController ctrl =
            new StandardIntersectionController(intersection, strategy);
        ctrl.addObserver(monitoring);
        ctrl.addObserver(emergency);
        ctrl.addObserver(logger);
        controllers.put(intersection.getId(), ctrl);
        System.out.println("[System] Registered: " + intersection);
        return ctrl;
    }

    public StandardIntersectionController getController(String id) {
        return controllers.get(id);
    }

    public MonitoringService getMonitoring() { return monitoring; }
    public DataLogger        getLogger()     { return logger; }
    public EmergencyService  getEmergency()  { return emergency; }
}

// ============================================================
// 14. MAIN — DRIVER + SCENARIOS
// ============================================================
public class TrafficLightSystem {
    public static void main(String[] args) throws InterruptedException {

        TrafficLightSystem system = TrafficLightSystem.getInstance();

        // ---- Setup Intersections ----
        Intersection mainAndBroad = IntersectionFactory.standardFourWay(
            "INT-01", "Main St & Broad Ave");
        Intersection mgRoadUrban  = IntersectionFactory.urbanAdaptive(
            "INT-02", "MG Road & Brigade Rd");
        Intersection tJunction    = IntersectionFactory.tIntersection(
            "INT-03", "Park Ave T-Junction");

        StandardIntersectionController ctrl01 = system.registerIntersection(
            mainAndBroad, new FixedTimingStrategy());
        StandardIntersectionController ctrl02 = system.registerIntersection(
            mgRoadUrban,  new AdaptiveTimingStrategy());
        StandardIntersectionController ctrl03 = system.registerIntersection(
            tJunction,    new FixedTimingStrategy());

        // ===== SCENARIO 1: Normal Fixed-Time Cycle =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Normal Fixed-Time Cycle (INT-01)");
        System.out.println("=".repeat(60));

        List<SensorReading> noSensors = Collections.emptyList();
        ctrl01.runOneCycle(noSensors);
        ctrl01.printSignalState();

        // ===== SCENARIO 2: Adaptive Timing (sensor-driven) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Adaptive Timing — heavy NS traffic (INT-02)");
        System.out.println("=".repeat(60));

        // Simulate heavy NS traffic (24 vehicles), light EW (6 vehicles)
        List<SensorReading> heavyNS = List.of(
            new SensorReading(SensorType.INDUCTIVE_LOOP, Direction.NORTH, 14, false, false),
            new SensorReading(SensorType.INDUCTIVE_LOOP, Direction.SOUTH, 10, false, false),
            new SensorReading(SensorType.INDUCTIVE_LOOP, Direction.EAST,   4, false, false),
            new SensorReading(SensorType.INDUCTIVE_LOOP, Direction.WEST,   2, false, false)
        );
        ctrl02.runOneCycle(heavyNS);
        ctrl02.printSignalState();

        // ===== SCENARIO 3: Pedestrian Crossing Request =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Pedestrian Crossing Interrupt (INT-01)");
        System.out.println("=".repeat(60));

        // Start a cycle, then interrupt mid-cycle with pedestrian request
        new Thread(() -> {
            try {
                Thread.sleep(800); // halfway through NS_GREEN sim time
                System.out.println("[Scenario3] Pedestrian button pressed!");
                ctrl01.triggerPedestrianCrossing();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }).start();

        ctrl01.runOneCycle(noSensors);
        Thread.sleep(500);
        ctrl01.printSignalState();

        // Resume after pedestrian phase
        ctrl01.setMode(ControlMode.NORMAL);

        // ===== SCENARIO 4: Emergency Vehicle Preemption =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Emergency Vehicle Preemption (INT-01)");
        System.out.println("=".repeat(60));

        ctrl01.triggerEmergency(Direction.NORTH); // ambulance approaching from North
        ctrl01.printSignalState();
        Thread.sleep(500);

        ctrl01.clearEmergency();                  // emergency vehicle passed
        ctrl01.printSignalState();

        // ===== SCENARIO 5: Strategy Switch — Fixed → Adaptive =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Strategy Switch Fixed → Adaptive (INT-03)");
        System.out.println("=".repeat(60));

        ctrl03.runOneCycle(noSensors); // fixed timing

        ctrl03.setTimingStrategy(new AdaptiveTimingStrategy());
        List<SensorReading> heavyEW = List.of(
            new SensorReading(SensorType.CAMERA_VISION, Direction.EAST, 18, false, false),
            new SensorReading(SensorType.CAMERA_VISION, Direction.WEST, 12, false, false),
            new SensorReading(SensorType.INDUCTIVE_LOOP, Direction.NORTH, 2, false, false)
        );
        ctrl03.runOneCycle(heavyEW); // adaptive — EW should get longer green
        ctrl03.printSignalState();

        // ===== SCENARIO 6: Off-Peak Flash Mode =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Off-Peak Flash Mode (INT-01) — 11 PM");
        System.out.println("=".repeat(60));

        ctrl01.setMode(ControlMode.OFF_PEAK);
        ctrl01.runOneCycle(noSensors);
        ctrl01.printSignalState();
        ctrl01.setMode(ControlMode.NORMAL);

        // ===== SCENARIO 7: Fault Detection — Bulb Failure =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Fault — North signal bulb failure");
        System.out.println("=".repeat(60));

        TrafficSignal northSignal = ctrl01.getSignals().get(Direction.NORTH);
        northSignal.declareFault(FaultType.BULB_FAILURE); // manual fault simulation
        system.getMonitoring().onFault(FaultType.BULB_FAILURE, Direction.NORTH, "INT-01");
        ctrl01.printSignalState();

        // Technician arrives and clears fault
        Thread.sleep(300);
        northSignal.clearFault();
        System.out.println("[Technician] Fault cleared, resuming normal operation");
        ctrl01.printSignalState();

        // ===== SCENARIO 8: Maintenance Mode =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Maintenance Mode Override (INT-02)");
        System.out.println("=".repeat(60));

        ctrl02.setMode(ControlMode.MAINTENANCE);
        ctrl02.runOneCycle(noSensors); // should print maintenance message and skip
        ctrl02.setMode(ControlMode.NORMAL);

        // ===== SCENARIO 9: Audit Log Review =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Audit Log (last 10 entries)");
        System.out.println("=".repeat(60));

        List<String> log = system.getMonitoring().getAuditLog();
        int start = Math.max(0, log.size() - 10);
        log.subList(start, log.size()).forEach(System.out::println);

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern        | Class(es)
            ---------------|--------------------------------------------------
            Singleton      | TrafficLightSystem, (IntersectionController)
            State          | Phase enum + Command per phase (NS_GREEN→YELLOW→ALL_RED→...)
            Strategy       | TimingStrategy → FixedTimingStrategy / AdaptiveTimingStrategy
            Observer       | SignalObserver → MonitoringService / EmergencyService / DataLogger
            Factory        | IntersectionFactory (standardFourWay / urbanAdaptive / tIntersection)
            Builder        | Intersection.Builder
            Command        | TrafficCommand → NSGreenCommand / EWGreenCommand / EmergencyPreemptCommand / ...
            Template Method| AbstractIntersectionController.runOneCycle() skeleton
            """);
    }
}
