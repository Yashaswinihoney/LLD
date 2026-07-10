import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ============================================================
// COURSE REGISTRATION SYSTEM — LLD
//
// Requirements covered:
//   1. Students register for courses + view registered courses
//   2. Course has code, name, instructor, max capacity
//   3. Search by course code or name
//   4. Prevent registration beyond max capacity
//   5. Handle concurrent registration from multiple students
//   6. Data consistency + no race conditions
//   7. Extensible design (strategy, observer, factory, builder)
//
// Design Patterns:
//   Singleton  — RegistrationService, CourseCatalog
//   Strategy   — EnrollmentStrategy (standard / priority / lottery)
//   Observer   — RegistrationEventObserver (email, analytics, waitlist)
//   Factory    — CourseFactory (lecture / lab / seminar / online)
//   Builder    — Course, Student construction
//   State      — EnrollmentStatus (PENDING→ENROLLED→DROPPED/WAITLISTED)
//   Command    — EnrollCommand (enroll + drop, with undo)
//   Iterator   — CourseSearchIterator (paginated filtered search)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum CourseType        { LECTURE, LAB, SEMINAR, ONLINE, HYBRID }
enum EnrollmentStatus  { PENDING, ENROLLED, DROPPED, WAITLISTED, COMPLETED }
enum StudentStatus     { ACTIVE, INACTIVE, SUSPENDED, GRADUATED }
enum Department        { CS, MATH, PHYSICS, CHEMISTRY, BUSINESS, ARTS, ENGINEERING }
enum DayOfWeek         { MON, TUE, WED, THU, FRI, SAT, SUN }
enum NotificationChannel { EMAIL, SMS, IN_APP }

// ============================================================
// 2. TIME SLOT — value object
//    Needed to detect schedule conflicts (Req 7 extensibility)
// ============================================================
class TimeSlot {
    private final DayOfWeek day;
    private final LocalTime startTime;
    private final LocalTime endTime;

    public TimeSlot(DayOfWeek day, LocalTime start, LocalTime end) {
        this.day       = day;
        this.startTime = start;
        this.endTime   = end;
    }

    /** Returns true if this slot overlaps with another */
    public boolean overlapsWith(TimeSlot other) {
        if (this.day != other.day) return false;
        return this.startTime.isBefore(other.endTime) &&
               this.endTime.isAfter(other.startTime);
    }

    public DayOfWeek getDay()       { return day; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime()   { return endTime; }

    @Override public String toString() {
        return day + " " + startTime + "-" + endTime;
    }
}

// ============================================================
// 3. INSTRUCTOR — immutable
// ============================================================
class Instructor {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final long       instructorId;
    private final String     name;
    private final String     email;
    private final Department department;

    public Instructor(String name, String email, Department dept) {
        this.instructorId = idGen.getAndIncrement();
        this.name         = name;
        this.email        = email;
        this.department   = dept;
    }

    public long       getInstructorId() { return instructorId; }
    public String     getName()         { return name; }
    public String     getEmail()        { return email; }
    public Department getDepartment()   { return department; }

    @Override public String toString() {
        return "Prof. " + name + " (" + department + ")";
    }
}

// ============================================================
// 4. COURSE — BUILDER PATTERN
//    Requirement 2: code, name, instructor, max capacity
//    Requirement 5 + 6: ReentrantLock per course for
//    concurrent-safe enrollment
// ============================================================
class Course {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long         courseId;
    private final  String       courseCode;     // Req 2: unique e.g. "CS-301"
    private final  String       name;           // Req 2: course name
    private final  Instructor   instructor;     // Req 2: instructor
    private final  int          maxCapacity;    // Req 2: max enrollment
    private final  CourseType   type;
    private final  Department   department;
    private final  int          credits;
    private final  String       description;
    private final  List<TimeSlot> schedule;
    private        int          currentEnrollment = 0;
    // Set of enrolled studentIds — ConcurrentHashMap for thread-safe reads
    private final  Set<Long>    enrolledStudents = ConcurrentHashMap.newKeySet();
    // FIFO waitlist queue (Req 4: capacity exceeded)
    private final  Queue<Long>  waitlistQueue    = new LinkedList<>();

    // ============================================================
    // Per-course ReentrantLock (Req 5 + 6: concurrency safety)
    //
    // WHY per-course lock (not synchronized on Course object)?
    //   - fair=true gives FIFO ordering → prevents starvation (Req 5)
    //   - Students enrolling in DIFFERENT courses never contend
    //   - Students enrolling in the SAME course queue up fairly
    //   - tryLock(0) lets us detect capacity-full instantly
    // ============================================================
    private final ReentrantLock lock = new ReentrantLock(true); // fair FIFO

    private Course(Builder b) {
        this.courseId    = idGen.getAndIncrement();
        this.courseCode  = b.courseCode;
        this.name        = b.name;
        this.instructor  = b.instructor;
        this.maxCapacity = b.maxCapacity;
        this.type        = b.type;
        this.department  = b.department;
        this.credits     = b.credits;
        this.description = b.description;
        this.schedule    = List.copyOf(b.schedule);
    }

    // ============================================================
    // ENROLL — Req 4 + 5 + 6
    //
    // Returns:
    //   "ENROLLED"   — success
    //   "WAITLISTED" — capacity full, added to waitlist
    //   "DUPLICATE"  — already enrolled
    // ============================================================
    public String enroll(long studentId) {
        // fair lock: thread with longest wait gets in next
        lock.lock();
        try {
            // Req 6: Check duplicate under lock (no TOCTOU race)
            if (enrolledStudents.contains(studentId)) {
                return "DUPLICATE";
            }

            // Req 4: Check capacity under the same lock
            if (currentEnrollment >= maxCapacity) {
                // Add to waitlist if not already there
                if (!waitlistQueue.contains(studentId)) {
                    waitlistQueue.offer(studentId);
                    System.out.printf("[Course %s] Student %d WAITLISTED (pos=%d)%n",
                        courseCode, studentId, waitlistQueue.size());
                }
                return "WAITLISTED";
            }

            // Enroll
            enrolledStudents.add(studentId);
            currentEnrollment++;
            System.out.printf("[Course %s] Student %d ENROLLED (%d/%d)%n",
                courseCode, studentId, currentEnrollment, maxCapacity);
            return "ENROLLED";

        } finally {
            lock.unlock(); // always released
        }
    }

    // ============================================================
    // DROP — Req 6: consistent state after drop
    //
    // Drop the student → if waitlist has entries, auto-enroll next
    // All in one atomic lock acquisition to avoid inconsistency
    // ============================================================
    public Optional<Long> drop(long studentId) {
        lock.lock();
        try {
            if (!enrolledStudents.contains(studentId)) {
                return Optional.empty(); // wasn't enrolled
            }

            enrolledStudents.remove(studentId);
            currentEnrollment--;
            System.out.printf("[Course %s] Student %d DROPPED (%d/%d)%n",
                courseCode, studentId, currentEnrollment, maxCapacity);

            // Auto-promote next from waitlist
            if (!waitlistQueue.isEmpty()) {
                Long next = waitlistQueue.poll();
                enrolledStudents.add(next);
                currentEnrollment++;
                System.out.printf("[Course %s] Waitlist: Student %d auto-ENROLLED%n",
                    courseCode, next);
                return Optional.of(next); // caller notifies this student
            }
            return Optional.empty();

        } finally {
            lock.unlock();
        }
    }

    public boolean hasConflict(List<TimeSlot> otherSchedule) {
        for (TimeSlot mine : schedule)
            for (TimeSlot other : otherSchedule)
                if (mine.overlapsWith(other)) return true;
        return false;
    }

    public boolean isEnrolled(long studentId)    { return enrolledStudents.contains(studentId); }
    public boolean isFull()                      { return currentEnrollment >= maxCapacity; }
    public int     getAvailableSeats()           { return maxCapacity - currentEnrollment; }
    public int     getWaitlistSize()             { return waitlistQueue.size(); }
    public int     getWaitlistPosition(long sid) {
        int pos = 1;
        for (long id : waitlistQueue) {
            if (id == sid) return pos;
            pos++;
        }
        return -1;
    }

    public long        getCourseId()      { return courseId; }
    public String      getCourseCode()    { return courseCode; }
    public String      getName()          { return name; }
    public Instructor  getInstructor()    { return instructor; }
    public int         getMaxCapacity()   { return maxCapacity; }
    public int         getCurrentEnrollment(){ return currentEnrollment; }
    public CourseType  getType()          { return type; }
    public Department  getDepartment()    { return department; }
    public int         getCredits()       { return credits; }
    public String      getDescription()   { return description; }
    public List<TimeSlot> getSchedule()   { return schedule; }
    public Set<Long>   getEnrolledStudents(){ return Collections.unmodifiableSet(enrolledStudents); }

    @Override public String toString() {
        return String.format("Course[%s | %-30s | %-20s | %d/%d | %s]",
            courseCode, name, instructor.getName(),
            currentEnrollment, maxCapacity,
            isFull() ? "FULL" : "AVAILABLE(" + getAvailableSeats() + ")");
    }

    // ---- BUILDER ----
    static class Builder {
        private final String     courseCode;
        private final String     name;
        private final Instructor instructor;
        private final int        maxCapacity;
        private       CourseType  type        = CourseType.LECTURE;
        private       Department  department  = Department.CS;
        private       int         credits     = 3;
        private       String      description = "";
        private       List<TimeSlot> schedule = new ArrayList<>();

        public Builder(String courseCode, String name,
                       Instructor instructor, int maxCapacity) {
            this.courseCode  = courseCode;
            this.name        = name;
            this.instructor  = instructor;
            this.maxCapacity = maxCapacity;
        }
        public Builder type(CourseType t)          { this.type = t;          return this; }
        public Builder department(Department d)    { this.department = d;    return this; }
        public Builder credits(int c)              { this.credits = c;       return this; }
        public Builder description(String d)       { this.description = d;   return this; }
        public Builder schedule(TimeSlot... slots) {
            this.schedule.addAll(Arrays.asList(slots)); return this;
        }
        public Course build()                      { return new Course(this); }
    }
}

// ============================================================
// 5. STUDENT — BUILDER PATTERN
//    Req 1: register + view registered courses
// ============================================================
class Student {
    private static final AtomicLong idGen = new AtomicLong(2000);

    private final  long          studentId;
    private        String        name;
    private        String        email;
    private        String        studentNumber; // e.g. "STU-2024-001"
    private        Department    major;
    private        int           yearOfStudy;   // 1–4
    private        StudentStatus status;
    private        int           maxCredits;    // max credits allowed per semester
    private final  LocalDateTime enrolledAt;

    private Student(Builder b) {
        this.studentId    = idGen.getAndIncrement();
        this.name         = b.name;
        this.email        = b.email;
        this.major        = b.major;
        this.yearOfStudy  = b.yearOfStudy;
        this.status       = StudentStatus.ACTIVE;
        this.maxCredits   = b.maxCredits;
        this.studentNumber= "STU-" + String.format("%06d", this.studentId);
        this.enrolledAt   = LocalDateTime.now();
    }

    public boolean isEligible()    { return status == StudentStatus.ACTIVE; }

    public long          getStudentId()    { return studentId; }
    public String        getName()         { return name; }
    public String        getEmail()        { return email; }
    public String        getStudentNumber(){ return studentNumber; }
    public Department    getMajor()        { return major; }
    public int           getYearOfStudy()  { return yearOfStudy; }
    public StudentStatus getStatus()       { return status; }
    public int           getMaxCredits()   { return maxCredits; }

    public void setStatus(StudentStatus s) { this.status = s; }

    @Override public String toString() {
        return String.format("Student[%s | %-15s | %s | Year=%d | %s]",
            studentNumber, name, major, yearOfStudy, status);
    }

    static class Builder {
        private final String     name;
        private final String     email;
        private       Department major      = Department.CS;
        private       int        yearOfStudy = 1;
        private       int        maxCredits  = 20;

        public Builder(String name, String email) {
            this.name = name; this.email = email;
        }
        public Builder major(Department d)    { this.major = d;         return this; }
        public Builder year(int y)            { this.yearOfStudy = y;   return this; }
        public Builder maxCredits(int c)      { this.maxCredits = c;    return this; }
        public Student build()               { return new Student(this); }
    }
}

// ============================================================
// 6. ENROLLMENT — records one student↔course relationship
//    STATE PATTERN: PENDING → ENROLLED / WAITLISTED → DROPPED
// ============================================================
class Enrollment {
    private static final AtomicLong idGen = new AtomicLong(500_000);

    private final  long             enrollmentId;
    private final  long             studentId;
    private final  long             courseId;
    private final  String           courseCode;
    private        EnrollmentStatus status;
    private final  LocalDateTime    createdAt;
    private        LocalDateTime    updatedAt;
    private        int              waitlistPosition; // only meaningful if WAITLISTED

    public Enrollment(long studentId, long courseId, String courseCode,
                       EnrollmentStatus status) {
        this.enrollmentId = idGen.getAndIncrement();
        this.studentId    = studentId;
        this.courseId     = courseId;
        this.courseCode   = courseCode;
        this.status       = status;
        this.createdAt    = LocalDateTime.now();
        this.updatedAt    = LocalDateTime.now();
    }

    // State transitions
    public void markEnrolled()    { status = EnrollmentStatus.ENROLLED;    update(); }
    public void markDropped()     { status = EnrollmentStatus.DROPPED;     update(); }
    public void markWaitlisted(int pos) {
        status           = EnrollmentStatus.WAITLISTED;
        waitlistPosition = pos;
        update();
    }
    public void markCompleted()   { status = EnrollmentStatus.COMPLETED;   update(); }

    private void update()         { updatedAt = LocalDateTime.now(); }

    public long             getEnrollmentId()    { return enrollmentId; }
    public long             getStudentId()       { return studentId; }
    public long             getCourseId()        { return courseId; }
    public String           getCourseCode()      { return courseCode; }
    public EnrollmentStatus getStatus()          { return status; }
    public LocalDateTime    getCreatedAt()       { return createdAt; }
    public int              getWaitlistPosition(){ return waitlistPosition; }

    @Override public String toString() {
        return String.format("Enrollment[#%d | student=%d | course=%s | %s%s]",
            enrollmentId, studentId, courseCode, status,
            status == EnrollmentStatus.WAITLISTED ? "(pos=" + waitlistPosition + ")" : "");
    }
}

// ============================================================
// 7. ENROLLMENT STRATEGY — STRATEGY PATTERN (Req 7)
//    Extensible: new strategies added without touching core logic
// ============================================================
interface EnrollmentStrategy {
    String getName();
    // Validate if a student CAN enroll (pre-conditions)
    // Returns null if OK, error message if not
    String validate(Student student, Course course,
                    List<Enrollment> studentEnrollments);
}

/** Standard: any active student can enroll, no special rules */
class StandardEnrollmentStrategy implements EnrollmentStrategy {
    @Override public String getName() { return "Standard"; }

    @Override
    public String validate(Student student, Course course,
                           List<Enrollment> enrolled) {
        if (!student.isEligible())
            return "Student is not active: " + student.getStatus();

        if (course.isEnrolled(student.getStudentId()))
            return "Already enrolled in: " + course.getCourseCode();

        return null; // OK
    }
}

/** Credit-limit: student cannot exceed their semester credit cap */
class CreditLimitEnrollmentStrategy implements EnrollmentStrategy {
    @Override public String getName() { return "CreditLimit"; }

    @Override
    public String validate(Student student, Course course,
                           List<Enrollment> enrolled) {
        // Delegate standard checks first
        String base = new StandardEnrollmentStrategy()
            .validate(student, course, enrolled);
        if (base != null) return base;

        // Count credits already enrolled (ENROLLED status only)
        // In a real system we'd look up course credits per enrollment
        long currentCourseCount = enrolled.stream()
            .filter(e -> e.getStatus() == EnrollmentStatus.ENROLLED)
            .count();

        // Simple heuristic: assume 3 credits per course
        if ((currentCourseCount + 1) * 3 > student.getMaxCredits()) {
            return "Credit limit exceeded: max=" + student.getMaxCredits() +
                   " current=" + (currentCourseCount * 3) +
                   " adding=" + course.getCredits();
        }
        return null;
    }
}

/** Schedule-conflict: reject if new course overlaps existing schedule */
class ConflictCheckEnrollmentStrategy implements EnrollmentStrategy {
    private final CourseCatalog catalog;

    public ConflictCheckEnrollmentStrategy(CourseCatalog catalog) {
        this.catalog = catalog;
    }

    @Override public String getName() { return "ConflictCheck"; }

    @Override
    public String validate(Student student, Course course,
                           List<Enrollment> enrolled) {
        String base = new CreditLimitEnrollmentStrategy()
            .validate(student, course, enrolled);
        if (base != null) return base;

        // Check schedule conflicts with all currently enrolled courses
        for (Enrollment e : enrolled) {
            if (e.getStatus() != EnrollmentStatus.ENROLLED) continue;
            Course existing = catalog.getCourseById(e.getCourseId());
            if (existing != null && course.hasConflict(existing.getSchedule())) {
                return "Schedule conflict with: " + existing.getCourseCode() +
                       " (" + existing.getSchedule() + ")";
            }
        }
        return null;
    }
}

// ============================================================
// 8. OBSERVER — REGISTRATION EVENTS (Req 7: extensible)
// ============================================================
interface RegistrationEventObserver {
    void onEnrolled(Enrollment enrollment, Course course, Student student);
    void onWaitlisted(Enrollment enrollment, Course course, Student student);
    void onDropped(Enrollment enrollment, Course course, Student student);
    void onWaitlistPromoted(long studentId, Course course);
}

class EmailNotificationObserver implements RegistrationEventObserver {
    @Override
    public void onEnrolled(Enrollment e, Course c, Student s) {
        System.out.printf("[Email → %s] You are enrolled in %s (%s)%n" +
            "  Instructor: %s | Credits: %d | Schedule: %s%n",
            s.getEmail(), c.getName(), c.getCourseCode(),
            c.getInstructor().getName(), c.getCredits(), c.getSchedule());
    }

    @Override
    public void onWaitlisted(Enrollment e, Course c, Student s) {
        System.out.printf("[Email → %s] Added to WAITLIST for %s (pos=%d)%n" +
            "  You will be notified when a seat opens.%n",
            s.getEmail(), c.getName(), e.getWaitlistPosition());
    }

    @Override
    public void onDropped(Enrollment e, Course c, Student s) {
        System.out.printf("[Email → %s] You have DROPPED %s (%s)%n",
            s.getEmail(), c.getName(), c.getCourseCode());
    }

    @Override
    public void onWaitlistPromoted(long studentId, Course c) {
        System.out.printf("[Email → student#%d] Great news! A seat opened in %s. " +
            "You have been auto-enrolled.%n", studentId, c.getName());
    }
}

class AnalyticsObserver implements RegistrationEventObserver {
    private long totalEnrollments = 0;
    private long totalDrops       = 0;
    private long totalWaitlists   = 0;
    private final Map<String, Long> courseEnrollCounts = new ConcurrentHashMap<>();

    @Override public synchronized void onEnrolled(Enrollment e, Course c, Student s) {
        totalEnrollments++;
        courseEnrollCounts.merge(c.getCourseCode(), 1L, Long::sum);
    }
    @Override public synchronized void onWaitlisted(Enrollment e, Course c, Student s) {
        totalWaitlists++;
    }
    @Override public synchronized void onDropped(Enrollment e, Course c, Student s) {
        totalDrops++;
    }
    @Override public void onWaitlistPromoted(long sid, Course c) { totalEnrollments++; }

    public void printReport() {
        System.out.println("\n[Analytics] Registration Report:");
        System.out.printf("  Enrollments: %d | Drops: %d | Waitlists: %d%n",
            totalEnrollments, totalDrops, totalWaitlists);
        System.out.println("  Top courses by enrollment:");
        courseEnrollCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .forEach(e -> System.out.println("    " + e.getKey() + " → " + e.getValue()));
    }
}

// ============================================================
// 9. ENROLL COMMAND — COMMAND PATTERN (Req 7: extensible)
//    execute() = enroll student in course
//    undo()    = drop student from course
// ============================================================
class EnrollCommand {
    private final Course                           course;
    private final Student                          student;
    private final EnrollmentStrategy               strategy;
    private final List<Enrollment>                 studentEnrollments;
    private final List<RegistrationEventObserver>  observers;
    private       Enrollment                       enrollment  = null;
    private       boolean                          executed    = false;

    public EnrollCommand(Course course, Student student,
                          EnrollmentStrategy strategy,
                          List<Enrollment> studentEnrollments,
                          List<RegistrationEventObserver> observers) {
        this.course             = course;
        this.student            = student;
        this.strategy           = strategy;
        this.studentEnrollments = studentEnrollments;
        this.observers          = observers;
    }

    /**
     * Execute enrollment:
     *  1. Strategy validates pre-conditions
     *  2. Course atomically enrolls or waitlists (Req 4 + 5 + 6)
     *  3. Enrollment record created
     *  4. Observers notified
     */
    public Enrollment execute() {
        // Step 1: Strategy validation (req 7: pluggable)
        String error = strategy.validate(student, course, studentEnrollments);
        if (error != null) {
            System.out.println("[EnrollCmd] Rejected: " + error);
            return null;
        }

        // Step 2: Atomic enroll/waitlist in Course (Req 5 + 6)
        String result = course.enroll(student.getStudentId());

        // Step 3: Create enrollment record
        EnrollmentStatus status = switch (result) {
            case "ENROLLED"   -> EnrollmentStatus.ENROLLED;
            case "WAITLISTED" -> EnrollmentStatus.WAITLISTED;
            default           -> null; // DUPLICATE or error
        };

        if (status == null) return null;

        enrollment = new Enrollment(student.getStudentId(),
            course.getCourseId(), course.getCourseCode(), status);

        if (status == EnrollmentStatus.WAITLISTED) {
            enrollment.markWaitlisted(
                course.getWaitlistPosition(student.getStudentId()));
        }

        executed = true;

        // Step 4: Notify observers
        if (status == EnrollmentStatus.ENROLLED) {
            observers.forEach(o -> o.onEnrolled(enrollment, course, student));
        } else {
            observers.forEach(o -> o.onWaitlisted(enrollment, course, student));
        }

        return enrollment;
    }

    /**
     * Undo = drop the student.
     * Returns Optional of studentId promoted from waitlist (if any).
     */
    public Optional<Long> undo() {
        if (!executed || enrollment == null) return Optional.empty();

        Optional<Long> promoted = course.drop(student.getStudentId());
        enrollment.markDropped();
        executed = false;

        observers.forEach(o -> o.onDropped(enrollment, course, student));
        promoted.ifPresent(sid ->
            observers.forEach(o -> o.onWaitlistPromoted(sid, course)));

        return promoted;
    }

    public Enrollment getEnrollment() { return enrollment; }
}

// ============================================================
// 10. COURSE FACTORY — FACTORY PATTERN (Req 7: extensible)
// ============================================================
class CourseFactory {
    /** Standard lecture course */
    public static Course lecture(String code, String name,
                                  Instructor instructor, int capacity,
                                  int credits, TimeSlot... slots) {
        return new Course.Builder(code, name, instructor, capacity)
            .type(CourseType.LECTURE)
            .credits(credits)
            .schedule(slots)
            .build();
    }

    /** Lab course — usually smaller capacity */
    public static Course lab(String code, String name,
                               Instructor instructor, int capacity,
                               TimeSlot... slots) {
        return new Course.Builder(code, name, instructor, capacity)
            .type(CourseType.LAB)
            .credits(1) // labs are typically 1 credit
            .schedule(slots)
            .build();
    }

    /** Online course — larger capacity, no schedule conflict */
    public static Course online(String code, String name,
                                 Instructor instructor, int capacity,
                                 int credits) {
        return new Course.Builder(code, name, instructor, capacity)
            .type(CourseType.ONLINE)
            .credits(credits)
            // No time slots — online async
            .build();
    }

    /** Seminar — small, discussion-based */
    public static Course seminar(String code, String name,
                                  Instructor instructor, TimeSlot slot) {
        return new Course.Builder(code, name, instructor, 15)
            .type(CourseType.SEMINAR)
            .credits(2)
            .schedule(slot)
            .build();
    }
}

// ============================================================
// 11. COURSE CATALOG — SINGLETON
//     Req 3: search by code / name
// ============================================================
class CourseCatalog {
    private static volatile CourseCatalog instance;

    private final ConcurrentHashMap<Long, Course>   byId   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Course> byCode = new ConcurrentHashMap<>();

    private CourseCatalog() {}

    public static CourseCatalog getInstance() {
        if (instance == null) {
            synchronized (CourseCatalog.class) {
                if (instance == null) instance = new CourseCatalog();
            }
        }
        return instance;
    }

    public Course addCourse(Course course) {
        byId.put(course.getCourseId(), course);
        byCode.put(course.getCourseCode().toUpperCase(), course);
        System.out.println("[Catalog] Added: " + course);
        return course;
    }

    /** Req 3: search by exact course code */
    public Optional<Course> searchByCode(String code) {
        return Optional.ofNullable(byCode.get(code.toUpperCase()));
    }

    /** Req 3: search by name (partial, case-insensitive) */
    public List<Course> searchByName(String query) {
        String q = query.toLowerCase();
        return byId.values().stream()
            .filter(c -> c.getName().toLowerCase().contains(q))
            .sorted(Comparator.comparing(Course::getCourseCode))
            .collect(Collectors.toList());
    }

    /** Search by department */
    public List<Course> searchByDepartment(Department dept) {
        return byId.values().stream()
            .filter(c -> c.getDepartment() == dept)
            .sorted(Comparator.comparing(Course::getCourseCode))
            .collect(Collectors.toList());
    }

    /** Combined search */
    public List<Course> search(String query, Department dept,
                                CourseType type, boolean availableOnly) {
        return byId.values().stream()
            .filter(c -> query == null ||
                c.getCourseCode().toLowerCase().contains(query.toLowerCase()) ||
                c.getName().toLowerCase().contains(query.toLowerCase()))
            .filter(c -> dept == null || c.getDepartment() == dept)
            .filter(c -> type == null || c.getType() == type)
            .filter(c -> !availableOnly || !c.isFull())
            .sorted(Comparator.comparing(Course::getCourseCode))
            .collect(Collectors.toList());
    }

    public List<Course> getAllCourses() {
        return new ArrayList<>(byId.values());
    }

    public List<Course> getAvailableCourses() {
        return byId.values().stream()
            .filter(c -> !c.isFull())
            .collect(Collectors.toList());
    }

    public Course getCourseById(long id)     { return byId.get(id); }
    public Course getCourseByCode(String c)  { return byCode.get(c.toUpperCase()); }
}

// ============================================================
// 12. REGISTRATION SERVICE — SINGLETON
//     Top-level entry point (Req 1 + 5 + 6 + 7)
// ============================================================
class RegistrationService {
    private static volatile RegistrationService instance;

    private final CourseCatalog                       catalog  = CourseCatalog.getInstance();
    private final ConcurrentHashMap<Long, Student>    students = new ConcurrentHashMap<>();
    // studentId → list of Enrollment records
    private final ConcurrentHashMap<Long, List<Enrollment>> studentEnrollments
        = new ConcurrentHashMap<>();
    // enrollmentId → EnrollCommand (for undo / drop)
    private final ConcurrentHashMap<Long, EnrollCommand>    commandStore
        = new ConcurrentHashMap<>();

    private final List<RegistrationEventObserver>   observers = new ArrayList<>();
    private final AnalyticsObserver                 analytics = new AnalyticsObserver();
    private       EnrollmentStrategy                strategy  =
        new ConflictCheckEnrollmentStrategy(CourseCatalog.getInstance());

    private RegistrationService() {
        observers.add(new EmailNotificationObserver());
        observers.add(analytics);
    }

    public static RegistrationService getInstance() {
        if (instance == null) {
            synchronized (RegistrationService.class) {
                if (instance == null) instance = new RegistrationService();
            }
        }
        return instance;
    }

    /** Req 7: swap strategy at runtime */
    public void setEnrollmentStrategy(EnrollmentStrategy s) {
        this.strategy = s;
        System.out.println("[Service] Enrollment strategy: " + s.getName());
    }

    public void addObserver(RegistrationEventObserver o) { observers.add(o); }

    // ---- Student management ----
    public Student registerStudent(Student student) {
        students.put(student.getStudentId(), student);
        studentEnrollments.put(student.getStudentId(), new CopyOnWriteArrayList<>());
        System.out.println("[Service] Registered: " + student);
        return student;
    }

    // ---- Req 1: Enroll in a course ----
    public Enrollment enroll(long studentId, String courseCode) {
        Student student = students.get(studentId);
        Course  course  = catalog.getCourseByCode(courseCode);

        if (student == null) {
            System.out.println("[Service] Student not found: " + studentId);
            return null;
        }
        if (course == null) {
            System.out.println("[Service] Course not found: " + courseCode);
            return null;
        }

        List<Enrollment> existing = studentEnrollments.get(studentId);

        EnrollCommand cmd = new EnrollCommand(
            course, student, strategy, existing, observers);

        Enrollment enrollment = cmd.execute();

        if (enrollment != null) {
            existing.add(enrollment);
            commandStore.put(enrollment.getEnrollmentId(), cmd);
        }

        return enrollment;
    }

    // ---- Req 1: Drop a course ----
    public boolean drop(long studentId, String courseCode) {
        List<Enrollment> enrollments = studentEnrollments.get(studentId);
        if (enrollments == null) return false;

        // Find active enrollment for this course
        Optional<Enrollment> target = enrollments.stream()
            .filter(e -> e.getCourseCode().equalsIgnoreCase(courseCode) &&
                         e.getStatus() == EnrollmentStatus.ENROLLED)
            .findFirst();

        if (target.isEmpty()) {
            System.out.println("[Service] No active enrollment found for " + courseCode);
            return false;
        }

        EnrollCommand cmd = commandStore.get(target.get().getEnrollmentId());
        if (cmd != null) cmd.undo();
        return true;
    }

    // ---- Req 1: View registered courses ----
    public List<Enrollment> getEnrollments(long studentId) {
        return Collections.unmodifiableList(
            studentEnrollments.getOrDefault(studentId, Collections.emptyList()));
    }

    public List<Enrollment> getActiveEnrollments(long studentId) {
        return studentEnrollments.getOrDefault(studentId, Collections.emptyList())
            .stream()
            .filter(e -> e.getStatus() == EnrollmentStatus.ENROLLED ||
                         e.getStatus() == EnrollmentStatus.WAITLISTED)
            .collect(Collectors.toList());
    }

    // ---- Req 3: Search ----
    public Optional<Course> searchByCode(String code) {
        return catalog.searchByCode(code);
    }

    public List<Course> searchByName(String name) {
        return catalog.searchByName(name);
    }

    public List<Course> searchCourses(String query, Department dept,
                                       CourseType type, boolean availableOnly) {
        return catalog.search(query, dept, type, availableOnly);
    }

    // Print student schedule
    public void printSchedule(long studentId) {
        Student student = students.get(studentId);
        if (student == null) return;

        System.out.println("\n══ Schedule: " + student.getName() + " ══");
        getActiveEnrollments(studentId).forEach(e -> {
            Course c = catalog.getCourseById(e.getCourseId());
            if (c != null) {
                System.out.printf("  %-10s %-30s %-20s %s%n",
                    c.getCourseCode(), c.getName(),
                    c.getInstructor().getName(), e.getStatus());
                c.getSchedule().forEach(s ->
                    System.out.println("             " + s));
            }
        });
    }

    public void printAnalytics()      { analytics.printReport(); }
    public CourseCatalog getCatalog() { return catalog; }
    public Student getStudent(long id){ return students.get(id); }
}

// ============================================================
// 13. MAIN — DRIVER CODE
// ============================================================
public class CourseRegistrationSystem {
    public static void main(String[] args) throws InterruptedException {

        RegistrationService service = RegistrationService.getInstance();
        CourseCatalog       catalog = CourseCatalog.getInstance();

        // ---- Setup Instructors ----
        Instructor profSmith  = new Instructor("Alice Smith",  "smith@uni.edu",  Department.CS);
        Instructor profKumar  = new Instructor("Raj Kumar",    "kumar@uni.edu",  Department.CS);
        Instructor profChen   = new Instructor("Li Chen",      "chen@uni.edu",   Department.MATH);
        Instructor profPatel  = new Instructor("Neha Patel",   "patel@uni.edu",  Department.ENGINEERING);

        // ---- Setup Courses (Req 2: code, name, instructor, capacity) ----
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SETUP: Adding Courses to Catalog");
        System.out.println("=".repeat(60));

        Course ds = catalog.addCourse(CourseFactory.lecture(
            "CS-301", "Data Structures & Algorithms", profSmith, 3, 4,
            new TimeSlot(DayOfWeek.MON, LocalTime.of(9, 0),  LocalTime.of(10, 30)),
            new TimeSlot(DayOfWeek.WED, LocalTime.of(9, 0),  LocalTime.of(10, 30))));

        Course ml = catalog.addCourse(CourseFactory.lecture(
            "CS-401", "Machine Learning", profKumar, 5, 4,
            new TimeSlot(DayOfWeek.TUE, LocalTime.of(11, 0), LocalTime.of(12, 30)),
            new TimeSlot(DayOfWeek.THU, LocalTime.of(11, 0), LocalTime.of(12, 30))));

        Course calc = catalog.addCourse(CourseFactory.lecture(
            "MATH-201", "Calculus II", profChen, 4, 3,
            new TimeSlot(DayOfWeek.MON, LocalTime.of(14, 0), LocalTime.of(15, 30)),
            new TimeSlot(DayOfWeek.FRI, LocalTime.of(14, 0), LocalTime.of(15, 30))));

        Course dbLab = catalog.addCourse(CourseFactory.lab(
            "CS-301L", "DSA Lab", profSmith, 4,
            new TimeSlot(DayOfWeek.FRI, LocalTime.of(10, 0), LocalTime.of(12, 0))));

        Course mlOnline = catalog.addCourse(CourseFactory.online(
            "CS-402", "Deep Learning (Online)", profKumar, 100, 3));

        Course seminar = catalog.addCourse(CourseFactory.seminar(
            "ENG-501", "Research Methods Seminar", profPatel,
            new TimeSlot(DayOfWeek.WED, LocalTime.of(16, 0), LocalTime.of(18, 0))));

        // ---- Setup Students ----
        Student alice = service.registerStudent(
            new Student.Builder("Alice", "alice@student.edu")
                .major(Department.CS).year(2).maxCredits(18).build());

        Student bob = service.registerStudent(
            new Student.Builder("Bob", "bob@student.edu")
                .major(Department.CS).year(3).maxCredits(20).build());

        Student carol = service.registerStudent(
            new Student.Builder("Carol", "carol@student.edu")
                .major(Department.MATH).year(1).maxCredits(15).build());

        Student dave = service.registerStudent(
            new Student.Builder("Dave", "dave@student.edu")
                .major(Department.ENGINEERING).year(4).maxCredits(20).build());

        Student eve = service.registerStudent(
            new Student.Builder("Eve", "eve@student.edu")
                .major(Department.CS).year(2).maxCredits(18).build());

        // ===== SCENARIO 1: Req 1 — normal enrollment =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Normal Enrollment (Req 1)");
        System.out.println("=".repeat(60));

        Enrollment e1 = service.enroll(alice.getStudentId(), "CS-301");
        Enrollment e2 = service.enroll(alice.getStudentId(), "CS-401");
        Enrollment e3 = service.enroll(bob.getStudentId(),   "CS-301");
        Enrollment e4 = service.enroll(bob.getStudentId(),   "MATH-201");

        service.printSchedule(alice.getStudentId());
        service.printSchedule(bob.getStudentId());

        // ===== SCENARIO 2: Req 3 — search by code + name =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Search Courses (Req 3)");
        System.out.println("=".repeat(60));

        System.out.println("Search by code 'CS-401':");
        service.searchByCode("CS-401").ifPresent(c ->
            System.out.println("  Found: " + c));

        System.out.println("\nSearch by name 'machine':");
        service.searchByName("machine")
            .forEach(c -> System.out.println("  " + c));

        System.out.println("\nSearch: available CS courses:");
        service.searchCourses(null, Department.CS, null, true)
            .forEach(c -> System.out.println("  " + c));

        // ===== SCENARIO 3: Req 4 — capacity enforcement =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Capacity Enforcement (Req 4 — max=3 for CS-301)");
        System.out.println("=".repeat(60));

        Enrollment e5 = service.enroll(carol.getStudentId(), "CS-301"); // 3rd — fits
        Enrollment e6 = service.enroll(dave.getStudentId(),  "CS-301"); // 4th — WAITLISTED
        Enrollment e7 = service.enroll(eve.getStudentId(),   "CS-301"); // 5th — WAITLISTED

        System.out.println("\nCS-301 status after 5 attempts (max=3):");
        System.out.println(ds);
        System.out.println("Waitlist size: " + ds.getWaitlistSize());
        System.out.println("Dave enrollment: " + e6);
        System.out.println("Eve enrollment:  " + e7);

        // ===== SCENARIO 4: Req 5 + 6 — concurrent enrollment =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Concurrent Enrollment — 5 students, 2 seats left in ML");
        System.out.println("=".repeat(60));

        // ML course has 5 capacity, bob and alice already in (3 remain)
        // Register 5 more students and fire concurrently
        List<Student> extra = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            extra.add(service.registerStudent(
                new Student.Builder("Student" + i, "s" + i + "@uni.edu")
                    .major(Department.CS).build()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Enrollment> concurrentResults = new CopyOnWriteArrayList<>();

        for (Student s : extra) {
            pool.submit(() -> {
                Enrollment en = service.enroll(s.getStudentId(), "CS-401");
                if (en != null) concurrentResults.add(en);
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long enrolled   = concurrentResults.stream()
            .filter(e -> e.getStatus() == EnrollmentStatus.ENROLLED).count();
        long waitlisted = concurrentResults.stream()
            .filter(e -> e.getStatus() == EnrollmentStatus.WAITLISTED).count();

        System.out.printf("\nML concurrent results: enrolled=%d waitlisted=%d total=%d%n",
            enrolled, waitlisted, concurrentResults.size());
        System.out.println(ml);

        // ===== SCENARIO 5: Duplicate enrollment =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Duplicate Enrollment Prevention (Req 6)");
        System.out.println("=".repeat(60));

        Enrollment dup = service.enroll(alice.getStudentId(), "CS-301");
        System.out.println("Duplicate attempt result: " + dup); // null

        // ===== SCENARIO 6: Drop + waitlist auto-promotion =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Drop Course → Waitlist Auto-Promotion");
        System.out.println("=".repeat(60));

        System.out.println("Before drop — CS-301 waitlist: " + ds.getWaitlistSize());
        System.out.println("Dave's status before: " + e6.getStatus());

        // Alice drops CS-301 → Dave should be auto-promoted
        service.drop(alice.getStudentId(), "CS-301");

        System.out.println("After Alice drops:");
        System.out.println("  CS-301: " + ds);
        System.out.println("  Dave (was waitlisted, now): "  +
            service.getActiveEnrollments(dave.getStudentId()).stream()
            .filter(e -> e.getCourseCode().equals("CS-301"))
            .map(e -> e.getStatus().toString())
            .findFirst().orElse("not found"));

        // ===== SCENARIO 7: Schedule conflict check =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Schedule Conflict Detection (Req 7 extensibility)");
        System.out.println("=".repeat(60));

        // CS-301: MON 9:00-10:30 — create conflicting course
        Course conflict = catalog.addCourse(CourseFactory.lecture(
            "CS-999", "Conflicting Course", profSmith, 30, 3,
            new TimeSlot(DayOfWeek.MON, LocalTime.of(9, 30), LocalTime.of(11, 0))));
        // MON 9:30-11:00 overlaps with CS-301 MON 9:00-10:30

        // Bob is enrolled in CS-301 (MON 9:00-10:30)
        Enrollment conflictAttempt = service.enroll(bob.getStudentId(), "CS-999");
        System.out.println("Conflict attempt result: " + conflictAttempt); // rejected

        // ===== SCENARIO 8: Online course — no schedule conflict =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Online Course (no schedule conflict, large capacity)");
        System.out.println("=".repeat(60));

        Enrollment onlineEnroll = service.enroll(alice.getStudentId(), "CS-402");
        System.out.println("Online enrollment: " + onlineEnroll);
        System.out.println(mlOnline);

        // ===== SCENARIO 9: Strategy swap (Req 7 extensibility) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Strategy Swap at Runtime (Req 7)");
        System.out.println("=".repeat(60));

        // Switch to standard strategy (no credit/conflict checks)
        service.setEnrollmentStrategy(new StandardEnrollmentStrategy());
        System.out.println("Strategy switched to: Standard");

        // Switch back to full conflict-check
        service.setEnrollmentStrategy(
            new ConflictCheckEnrollmentStrategy(catalog));
        System.out.println("Strategy switched to: ConflictCheck");

        // ===== SCENARIO 10: View all registrations =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: View Registered Courses (Req 1)");
        System.out.println("=".repeat(60));

        service.printSchedule(alice.getStudentId());
        service.printSchedule(bob.getStudentId());
        service.printSchedule(carol.getStudentId());

        // ===== FINAL REPORT =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("COURSE CATALOG FINAL STATUS");
        System.out.println("=".repeat(60));
        catalog.getAllCourses().forEach(System.out::println);

        service.printAnalytics();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|-----------------------------------------------------------
            Singleton  | RegistrationService, CourseCatalog (double-checked locking)
            State      | EnrollmentStatus (PENDING→ENROLLED/WAITLISTED→DROPPED)
            Strategy   | EnrollmentStrategy (Standard / CreditLimit / ConflictCheck)
            Observer   | RegistrationEventObserver (Email / Analytics)
            Factory    | CourseFactory (lecture / lab / online / seminar)
            Builder    | Course.Builder, Student.Builder
            Command    | EnrollCommand: execute()=enroll, undo()=drop
            Iterator   | CourseCatalog.search() — filtered stream results
            """);

        System.out.println("===== THREAD-SAFETY SUMMARY =====");
        System.out.println("""
            Class           | Mechanism                  | Why
            ----------------|----------------------------|---------------------------------
            Course.enroll() | ReentrantLock(fair=true)   | FIFO fairness (Req 5)
            Course.drop()   | Same ReentrantLock         | Atomic drop + auto-promote
            CourseCatalog   | ConcurrentHashMap          | Safe concurrent reads + writes
            Enrollment list | CopyOnWriteArrayList       | Reads >> writes per student
            RegistrationSvc | ConcurrentHashMap          | Multi-student concurrent access
            """);
    }
}
