import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ============================================================
// TASK MANAGEMENT SYSTEM — LLD
//
// Requirements covered:
//   1. Create, update, delete tasks
//   2. Title, description, due date, priority, status
//   3. Assign tasks to users + set reminders
//   4. Search + filter (priority, due date, assigned user)
//   5. Mark complete + view task history
//   6. Concurrent access + data consistency
//   7. Extensible design
//
// Design Patterns:
//   Singleton  — TaskManagementService, UserService
//   Strategy   — TaskSortStrategy (by due date / priority / assignee)
//   Observer   — TaskEventObserver (notification, audit, analytics)
//   Factory    — TaskFactory (personal / team / sprint / recurring)
//   Builder    — Task, Reminder construction
//   State      — TaskStatus (PENDING→IN_PROGRESS→COMPLETED/CANCELLED)
//   Command    — TaskCommand (create/update/delete with full undo)
//   Iterator   — TaskFilter (composable, chainable filter pipeline)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum TaskStatus    { PENDING, IN_PROGRESS, REVIEW, COMPLETED, CANCELLED, BLOCKED }
enum TaskPriority  { CRITICAL, HIGH, MEDIUM, LOW }
enum TaskType      { PERSONAL, TEAM, SPRINT, BUG, FEATURE, RECURRING }
enum ReminderType  { EMAIL, SMS, IN_APP, PUSH }
enum UserRole      { ADMIN, MANAGER, MEMBER, VIEWER }
enum CommentType   { NOTE, STATUS_CHANGE, ASSIGNMENT, SYSTEM }

// ============================================================
// 2. USER — BUILDER PATTERN (Req 3: assign tasks to users)
// ============================================================
class User {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long       userId;
    private        String     name;
    private        String     email;
    private        UserRole   role;
    private        boolean    notificationsEnabled;
    private final  LocalDateTime registeredAt;

    private User(Builder b) {
        this.userId               = idGen.getAndIncrement();
        this.name                 = b.name;
        this.email                = b.email;
        this.role                 = b.role;
        this.notificationsEnabled = b.notificationsEnabled;
        this.registeredAt         = LocalDateTime.now();
    }

    public long     getUserId()    { return userId; }
    public String   getName()      { return name; }
    public String   getEmail()     { return email; }
    public UserRole getRole()      { return role; }
    public boolean  notificationsEnabled() { return notificationsEnabled; }

    public void setName(String n)  { this.name = n; }
    public void setRole(UserRole r){ this.role = r; }

    @Override public String toString() {
        return "User[#" + userId + " | " + name + " | " + role + "]";
    }

    static class Builder {
        private final String   name;
        private final String   email;
        private       UserRole role                 = UserRole.MEMBER;
        private       boolean  notificationsEnabled = true;

        public Builder(String name, String email) {
            this.name = name; this.email = email;
        }
        public Builder role(UserRole r)         { this.role = r;                    return this; }
        public Builder notifications(boolean n) { this.notificationsEnabled = n;    return this; }
        public User build()                     { return new User(this); }
    }
}

// ============================================================
// 3. REMINDER — BUILDER PATTERN (Req 3)
// ============================================================
class Reminder {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long          reminderId;
    private final  long          taskId;
    private final  long          userId;
    private final  LocalDateTime remindAt;
    private final  ReminderType  type;
    private        boolean       triggered;
    private        String        message;

    private Reminder(Builder b) {
        this.reminderId = idGen.getAndIncrement();
        this.taskId     = b.taskId;
        this.userId     = b.userId;
        this.remindAt   = b.remindAt;
        this.type       = b.type;
        this.triggered  = false;
        this.message    = b.message;
    }

    public void trigger()         { this.triggered = true; }
    public boolean isTriggered()  { return triggered; }
    public boolean isDue()        { return !triggered && LocalDateTime.now().isAfter(remindAt); }

    public long          getReminderId() { return reminderId; }
    public long          getTaskId()     { return taskId; }
    public long          getUserId()     { return userId; }
    public LocalDateTime getRemindAt()  { return remindAt; }
    public ReminderType  getType()       { return type; }
    public String        getMessage()    { return message; }

    @Override public String toString() {
        return "Reminder[#" + reminderId + " | task=" + taskId +
               " | " + type + " | at=" + remindAt + "]";
    }

    static class Builder {
        private final long          taskId;
        private final long          userId;
        private final LocalDateTime remindAt;
        private       ReminderType  type    = ReminderType.IN_APP;
        private       String        message = "Task reminder";

        public Builder(long taskId, long userId, LocalDateTime remindAt) {
            this.taskId = taskId; this.userId = userId; this.remindAt = remindAt;
        }
        public Builder type(ReminderType t)    { this.type = t;      return this; }
        public Builder message(String m)       { this.message = m;   return this; }
        public Reminder build()                { return new Reminder(this); }
    }
}

// ============================================================
// 4. TASK COMMENT — for activity history (Req 5)
// ============================================================
class TaskComment {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final long          commentId;
    private final long          taskId;
    private final long          authorId;
    private final String        content;
    private final CommentType   type;
    private final LocalDateTime createdAt;

    public TaskComment(long taskId, long authorId,
                       String content, CommentType type) {
        this.commentId = idGen.getAndIncrement();
        this.taskId    = taskId;
        this.authorId  = authorId;
        this.content   = content;
        this.type      = type;
        this.createdAt = LocalDateTime.now();
    }

    public long          getCommentId() { return commentId; }
    public long          getTaskId()    { return taskId; }
    public long          getAuthorId()  { return authorId; }
    public String        getContent()   { return content; }
    public CommentType   getType()      { return type; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override public String toString() {
        return "[" + createdAt.toLocalTime() + " | " + type + "] " + content;
    }
}

// ============================================================
// 5. TASK — BUILDER PATTERN
//    Req 2: title, description, due date, priority, status
//    Req 6: per-task ReentrantLock for concurrent-safe updates
// ============================================================
class Task {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long          taskId;
    private        String        title;
    private        String        description;
    private        LocalDate     dueDate;
    private        TaskPriority  priority;
    private        TaskStatus    status;
    private        TaskType      type;
    private final  long          createdByUserId;
    private        long          assignedToUserId;   // Req 3: assignee
    private        long          projectId;
    private        Set<String>   tags;
    private final  LocalDateTime createdAt;
    private        LocalDateTime updatedAt;
    private        LocalDateTime completedAt;

    // Req 5: full activity history
    private final  List<TaskComment> comments = new CopyOnWriteArrayList<>();
    // Req 3: reminders
    private final  List<Reminder>    reminders = new CopyOnWriteArrayList<>();

    // Req 6: per-task fair lock
    // fair=true: FIFO — if two users update the same task,
    // the one who waited longer gets access first
    private final ReentrantLock lock = new ReentrantLock(true);

    private Task(Builder b) {
        this.taskId           = idGen.getAndIncrement();
        this.title            = b.title;
        this.description      = b.description;
        this.dueDate          = b.dueDate;
        this.priority         = b.priority;
        this.status           = TaskStatus.PENDING;
        this.type             = b.type;
        this.createdByUserId  = b.createdByUserId;
        this.assignedToUserId = b.assignedToUserId;
        this.projectId        = b.projectId;
        this.tags             = new HashSet<>(b.tags);
        this.createdAt        = LocalDateTime.now();
        this.updatedAt        = LocalDateTime.now();
    }

    // ============================================================
    // STATE MACHINE (Req 2):
    //   PENDING → IN_PROGRESS → REVIEW → COMPLETED
    //                        ↘ BLOCKED
    //   Any → CANCELLED
    // ============================================================
    public boolean transition(TaskStatus newStatus, long actorId) {
        lock.lock();
        try {
            if (!isValidTransition(status, newStatus)) {
                System.out.printf("[Task #%d] Invalid transition: %s → %s%n",
                    taskId, status, newStatus);
                return false;
            }
            TaskStatus oldStatus = this.status;
            this.status    = newStatus;
            this.updatedAt = LocalDateTime.now();

            if (newStatus == TaskStatus.COMPLETED) {
                this.completedAt = LocalDateTime.now();
            }

            addComment(actorId,
                "Status changed: " + oldStatus + " → " + newStatus,
                CommentType.STATUS_CHANGE);

            System.out.printf("[Task #%d] '%s' → %s%n", taskId, title, newStatus);
            return true;

        } finally {
            lock.unlock();
        }
    }

    private boolean isValidTransition(TaskStatus from, TaskStatus to) {
        return switch (from) {
            case PENDING     -> to == TaskStatus.IN_PROGRESS  || to == TaskStatus.CANCELLED;
            case IN_PROGRESS -> to == TaskStatus.REVIEW       || to == TaskStatus.BLOCKED
                             || to == TaskStatus.COMPLETED    || to == TaskStatus.CANCELLED;
            case REVIEW      -> to == TaskStatus.IN_PROGRESS  || to == TaskStatus.COMPLETED
                             || to == TaskStatus.CANCELLED;
            case BLOCKED     -> to == TaskStatus.IN_PROGRESS  || to == TaskStatus.CANCELLED;
            case COMPLETED   -> false; // terminal — use Req 5 history to see it
            case CANCELLED   -> false; // terminal
        };
    }

    // Req 6: concurrent-safe update
    public void update(String newTitle, String newDesc,
                       LocalDate newDue, TaskPriority newPriority,
                       long actorId) {
        lock.lock();
        try {
            StringBuilder change = new StringBuilder("Updated: ");
            if (newTitle != null && !newTitle.equals(title)) {
                change.append("title ");
                this.title = newTitle;
            }
            if (newDesc != null && !newDesc.equals(description)) {
                change.append("description ");
                this.description = newDesc;
            }
            if (newDue != null && !newDue.equals(dueDate)) {
                change.append("dueDate→" + newDue + " ");
                this.dueDate = newDue;
            }
            if (newPriority != null && newPriority != priority) {
                change.append("priority→" + newPriority + " ");
                this.priority = newPriority;
            }
            this.updatedAt = LocalDateTime.now();
            addComment(actorId, change.toString().trim(), CommentType.NOTE);
        } finally {
            lock.unlock();
        }
    }

    // Req 3: assign to user
    public void assignTo(long newUserId, long actorId) {
        lock.lock();
        try {
            long old = this.assignedToUserId;
            this.assignedToUserId = newUserId;
            this.updatedAt = LocalDateTime.now();
            addComment(actorId,
                "Reassigned from user#" + old + " to user#" + newUserId,
                CommentType.ASSIGNMENT);
            System.out.printf("[Task #%d] Assigned to user#%d%n", taskId, newUserId);
        } finally {
            lock.unlock();
        }
    }

    public void addTag(String tag) {
        lock.lock();
        try { tags.add(tag.toLowerCase()); }
        finally { lock.unlock(); }
    }

    public void addComment(long authorId, String content, CommentType type) {
        comments.add(new TaskComment(taskId, authorId, content, type));
    }

    public void addReminder(Reminder r) { reminders.add(r); }

    // Req 5: check if overdue
    public boolean isOverdue() {
        return dueDate != null &&
               LocalDate.now().isAfter(dueDate) &&
               status != TaskStatus.COMPLETED &&
               status != TaskStatus.CANCELLED;
    }

    // ---- Getters ----
    public long          getTaskId()          { return taskId; }
    public String        getTitle()           { return title; }
    public String        getDescription()     { return description; }
    public LocalDate     getDueDate()         { return dueDate; }
    public TaskPriority  getPriority()        { return priority; }
    public TaskStatus    getStatus()          { return status; }
    public TaskType      getType()            { return type; }
    public long          getCreatedByUserId() { return createdByUserId; }
    public long          getAssignedToUserId(){ return assignedToUserId; }
    public long          getProjectId()       { return projectId; }
    public Set<String>   getTags()            { return Collections.unmodifiableSet(tags); }
    public LocalDateTime getCreatedAt()       { return createdAt; }
    public LocalDateTime getUpdatedAt()       { return updatedAt; }
    public LocalDateTime getCompletedAt()     { return completedAt; }
    public List<TaskComment> getComments()    { return Collections.unmodifiableList(comments); }
    public List<Reminder>    getReminders()   { return Collections.unmodifiableList(reminders); }

    @Override public String toString() {
        return String.format("Task[#%d | %-30s | %s | %s | due=%s | assigned=%d%s]",
            taskId, title, priority, status, dueDate, assignedToUserId,
            isOverdue() ? " ⚠OVERDUE" : "");
    }

    static class Builder {
        private final String      title;
        private final long        createdByUserId;
        private       String      description      = "";
        private       LocalDate   dueDate          = null;
        private       TaskPriority priority        = TaskPriority.MEDIUM;
        private       TaskType    type             = TaskType.PERSONAL;
        private       long        assignedToUserId = 0;
        private       long        projectId        = 0;
        private       List<String> tags            = new ArrayList<>();

        public Builder(String title, long createdByUserId) {
            this.title = title; this.createdByUserId = createdByUserId;
        }
        public Builder description(String d)     { this.description = d;       return this; }
        public Builder dueDate(LocalDate d)      { this.dueDate = d;           return this; }
        public Builder priority(TaskPriority p)  { this.priority = p;          return this; }
        public Builder type(TaskType t)          { this.type = t;              return this; }
        public Builder assignedTo(long userId)   { this.assignedToUserId = userId; return this; }
        public Builder project(long projectId)   { this.projectId = projectId; return this; }
        public Builder tags(String... t)         { tags.addAll(Arrays.asList(t)); return this; }
        public Task build()                      { return new Task(this); }
    }
}

// ============================================================
// 6. TASK FACTORY — FACTORY PATTERN (Req 7)
// ============================================================
class TaskFactory {
    public static Task personal(String title, long userId,
                                 LocalDate due, TaskPriority priority) {
        return new Task.Builder(title, userId)
            .priority(priority).dueDate(due).type(TaskType.PERSONAL).build();
    }

    public static Task teamTask(String title, long createdBy,
                                 long assignedTo, LocalDate due,
                                 TaskPriority priority, long projectId) {
        return new Task.Builder(title, createdBy)
            .assignedTo(assignedTo).dueDate(due)
            .priority(priority).type(TaskType.TEAM).project(projectId).build();
    }

    public static Task bugReport(String title, long reportedBy,
                                  long assignedTo, String description) {
        return new Task.Builder("[BUG] " + title, reportedBy)
            .description(description).assignedTo(assignedTo)
            .priority(TaskPriority.HIGH).type(TaskType.BUG)
            .dueDate(LocalDate.now().plusDays(3)).build();
    }

    public static Task feature(String title, long createdBy,
                                long assignedTo, LocalDate due) {
        return new Task.Builder("[FEAT] " + title, createdBy)
            .assignedTo(assignedTo).dueDate(due)
            .priority(TaskPriority.MEDIUM).type(TaskType.FEATURE).build();
    }
}

// ============================================================
// 7. TASK SORT STRATEGY — STRATEGY PATTERN (Req 4 + 7)
// ============================================================
interface TaskSortStrategy {
    String getName();
    Comparator<Task> getComparator();
}

class SortByDueDate implements TaskSortStrategy {
    @Override public String getName() { return "DueDate (earliest first)"; }
    @Override public Comparator<Task> getComparator() {
        return Comparator.comparing(
            t -> t.getDueDate() == null ? LocalDate.MAX : t.getDueDate());
    }
}

class SortByPriority implements TaskSortStrategy {
    // CRITICAL(0) < HIGH(1) < MEDIUM(2) < LOW(3) — lower ordinal = higher priority
    @Override public String getName() { return "Priority (highest first)"; }
    @Override public Comparator<Task> getComparator() {
        return Comparator.comparingInt(t -> t.getPriority().ordinal());
    }
}

class SortByAssignee implements TaskSortStrategy {
    @Override public String getName() { return "Assignee"; }
    @Override public Comparator<Task> getComparator() {
        return Comparator.comparingLong(Task::getAssignedToUserId);
    }
}

class SortByUpdated implements TaskSortStrategy {
    @Override public String getName() { return "Last Updated (newest first)"; }
    @Override public Comparator<Task> getComparator() {
        return Comparator.comparing(Task::getUpdatedAt).reversed();
    }
}

// ============================================================
// 8. TASK FILTER ITERATOR — ITERATOR PATTERN (Req 4)
//    Composable filter pipeline: chain multiple filters
// ============================================================
class TaskFilter {
    private List<Task> tasks;

    public TaskFilter(Collection<Task> tasks) {
        this.tasks = new ArrayList<>(tasks);
    }

    // Req 4: filter by priority
    public TaskFilter byPriority(TaskPriority priority) {
        tasks = tasks.stream()
            .filter(t -> t.getPriority() == priority)
            .collect(Collectors.toList());
        return this;
    }

    // Req 4: filter by assigned user
    public TaskFilter byAssignee(long userId) {
        tasks = tasks.stream()
            .filter(t -> t.getAssignedToUserId() == userId)
            .collect(Collectors.toList());
        return this;
    }

    // Req 4: filter by due date range
    public TaskFilter byDueDateRange(LocalDate from, LocalDate to) {
        tasks = tasks.stream()
            .filter(t -> t.getDueDate() != null)
            .filter(t -> !t.getDueDate().isBefore(from) &&
                         !t.getDueDate().isAfter(to))
            .collect(Collectors.toList());
        return this;
    }

    public TaskFilter byStatus(TaskStatus status) {
        tasks = tasks.stream()
            .filter(t -> t.getStatus() == status)
            .collect(Collectors.toList());
        return this;
    }

    public TaskFilter byType(TaskType type) {
        tasks = tasks.stream()
            .filter(t -> t.getType() == type)
            .collect(Collectors.toList());
        return this;
    }

    public TaskFilter byTag(String tag) {
        tasks = tasks.stream()
            .filter(t -> t.getTags().contains(tag.toLowerCase()))
            .collect(Collectors.toList());
        return this;
    }

    public TaskFilter overdue() {
        tasks = tasks.stream()
            .filter(Task::isOverdue)
            .collect(Collectors.toList());
        return this;
    }

    // Req 4: text search in title + description
    public TaskFilter search(String query) {
        String q = query.toLowerCase();
        tasks = tasks.stream()
            .filter(t -> t.getTitle().toLowerCase().contains(q) ||
                         t.getDescription().toLowerCase().contains(q))
            .collect(Collectors.toList());
        return this;
    }

    public TaskFilter sortedBy(TaskSortStrategy strategy) {
        tasks = tasks.stream()
            .sorted(strategy.getComparator())
            .collect(Collectors.toList());
        return this;
    }

    public List<Task> toList() { return Collections.unmodifiableList(tasks); }
    public int count()         { return tasks.size(); }
}

// ============================================================
// 9. OBSERVER — TASK EVENTS (Req 3 + 7)
// ============================================================
interface TaskEventObserver {
    void onTaskCreated(Task task);
    void onTaskUpdated(Task task, String change);
    void onTaskCompleted(Task task);
    void onTaskAssigned(Task task, long newAssigneeId);
    void onTaskDeleted(long taskId, String title);
    void onReminderDue(Reminder reminder, Task task);
}

class NotificationObserver implements TaskEventObserver {
    private final UserService userService;

    public NotificationObserver(UserService us) { this.userService = us; }

    @Override
    public void onTaskCreated(Task task) {
        System.out.printf("[Notif] Task created: '%s' (priority=%s, due=%s)%n",
            task.getTitle(), task.getPriority(), task.getDueDate());
    }

    @Override
    public void onTaskUpdated(Task task, String change) {
        System.out.printf("[Notif] Task #%d updated: %s%n", task.getTaskId(), change);
    }

    @Override
    public void onTaskCompleted(Task task) {
        System.out.printf("[Notif] ✅ Task completed: '%s' at %s%n",
            task.getTitle(), task.getCompletedAt());
    }

    // Req 3: notify new assignee
    @Override
    public void onTaskAssigned(Task task, long newAssigneeId) {
        User assignee = userService.getUser(newAssigneeId);
        if (assignee != null && assignee.notificationsEnabled()) {
            System.out.printf("[Email → %s] Task assigned to you: '%s' | Due: %s | Priority: %s%n",
                assignee.getEmail(), task.getTitle(),
                task.getDueDate(), task.getPriority());
        }
    }

    @Override
    public void onTaskDeleted(long taskId, String title) {
        System.out.printf("[Notif] Task #%d '%s' deleted%n", taskId, title);
    }

    // Req 3: reminder alert
    @Override
    public void onReminderDue(Reminder reminder, Task task) {
        User user = userService.getUser(reminder.getUserId());
        if (user != null) {
            System.out.printf("[%s → %s] ⏰ Reminder: '%s' is due %s%n",
                reminder.getType(), user.getEmail(),
                task.getTitle(), task.getDueDate());
        }
    }
}

class AuditObserver implements TaskEventObserver {
    // Req 5: complete audit trail
    private final List<String> auditLog = new CopyOnWriteArrayList<>();

    private void log(String entry) {
        String line = LocalDateTime.now() + " | " + entry;
        auditLog.add(line);
        System.out.println("[Audit] " + line);
    }

    @Override public void onTaskCreated(Task t) {
        log("CREATED task#" + t.getTaskId() + " '" + t.getTitle() + "'");
    }
    @Override public void onTaskUpdated(Task t, String change) {
        log("UPDATED task#" + t.getTaskId() + " | " + change);
    }
    @Override public void onTaskCompleted(Task t) {
        log("COMPLETED task#" + t.getTaskId() + " '" + t.getTitle() + "'");
    }
    @Override public void onTaskAssigned(Task t, long uid) {
        log("ASSIGNED task#" + t.getTaskId() + " → user#" + uid);
    }
    @Override public void onTaskDeleted(long id, String title) {
        log("DELETED task#" + id + " '" + title + "'");
    }
    @Override public void onReminderDue(Reminder r, Task t) {
        log("REMINDER triggered for task#" + t.getTaskId());
    }

    public List<String> getAuditLog()           { return auditLog; }
    public List<String> getLogForTask(long taskId) {
        return auditLog.stream()
            .filter(e -> e.contains("task#" + taskId))
            .collect(Collectors.toList());
    }
}

class AnalyticsObserver implements TaskEventObserver {
    private long created   = 0, completed = 0, deleted = 0;
    private final Map<TaskPriority, Long> completedByPriority = new ConcurrentHashMap<>();

    @Override public synchronized void onTaskCreated(Task t)   { created++; }
    @Override public synchronized void onTaskCompleted(Task t) {
        completed++;
        completedByPriority.merge(t.getPriority(), 1L, Long::sum);
    }
    @Override public synchronized void onTaskDeleted(long id, String s) { deleted++; }
    @Override public void onTaskUpdated(Task t, String c) {}
    @Override public void onTaskAssigned(Task t, long uid) {}
    @Override public void onReminderDue(Reminder r, Task t) {}

    public void printReport() {
        System.out.println("\n[Analytics] Task Statistics:");
        System.out.printf("  Created: %d | Completed: %d | Deleted: %d%n",
            created, completed, deleted);
        System.out.println("  Completed by priority: " + completedByPriority);
    }
}

// ============================================================
// 10. TASK COMMAND — COMMAND PATTERN (Req 1 + 7: undo support)
// ============================================================
interface TaskCommand {
    void execute();
    void undo();
    String getDescription();
}

class CreateTaskCommand implements TaskCommand {
    private final Task task;
    private final ConcurrentHashMap<Long, Task> store;
    private final List<TaskEventObserver> observers;

    public CreateTaskCommand(Task task,
                              ConcurrentHashMap<Long, Task> store,
                              List<TaskEventObserver> observers) {
        this.task = task; this.store = store; this.observers = observers;
    }

    @Override public void execute() {
        store.put(task.getTaskId(), task);
        observers.forEach(o -> o.onTaskCreated(task));
    }

    @Override public void undo() {
        store.remove(task.getTaskId());
        observers.forEach(o -> o.onTaskDeleted(task.getTaskId(), task.getTitle()));
    }

    @Override public String getDescription() {
        return "Create task '" + task.getTitle() + "'";
    }
}

class UpdateTaskCommand implements TaskCommand {
    private final Task         task;
    private final String       oldTitle, newTitle;
    private final String       oldDesc,  newDesc;
    private final LocalDate    oldDue,   newDue;
    private final TaskPriority oldPrio,  newPrio;
    private final long         actorId;
    private final List<TaskEventObserver> observers;

    public UpdateTaskCommand(Task task, String newTitle, String newDesc,
                              LocalDate newDue, TaskPriority newPrio,
                              long actorId, List<TaskEventObserver> observers) {
        this.task     = task;
        this.oldTitle = task.getTitle();   this.newTitle = newTitle;
        this.oldDesc  = task.getDescription(); this.newDesc = newDesc;
        this.oldDue   = task.getDueDate(); this.newDue  = newDue;
        this.oldPrio  = task.getPriority();this.newPrio  = newPrio;
        this.actorId  = actorId;
        this.observers= observers;
    }

    @Override public void execute() {
        task.update(newTitle, newDesc, newDue, newPrio, actorId);
        observers.forEach(o -> o.onTaskUpdated(task, getDescription()));
    }

    @Override public void undo() {
        // Rollback to old values
        task.update(oldTitle, oldDesc, oldDue, oldPrio, actorId);
        observers.forEach(o -> o.onTaskUpdated(task, "Reverted: " + getDescription()));
    }

    @Override public String getDescription() {
        return "Update task #" + task.getTaskId();
    }
}

class DeleteTaskCommand implements TaskCommand {
    private final Task         task;
    private final ConcurrentHashMap<Long, Task> store;
    private final List<TaskEventObserver> observers;

    public DeleteTaskCommand(Task task,
                              ConcurrentHashMap<Long, Task> store,
                              List<TaskEventObserver> observers) {
        this.task = task; this.store = store; this.observers = observers;
    }

    @Override public void execute() {
        task.transition(TaskStatus.CANCELLED, 0L);
        store.remove(task.getTaskId());
        observers.forEach(o -> o.onTaskDeleted(task.getTaskId(), task.getTitle()));
    }

    @Override public void undo() {
        // Restore task (soft-delete undo)
        store.put(task.getTaskId(), task);
        observers.forEach(o -> o.onTaskCreated(task));
    }

    @Override public String getDescription() {
        return "Delete task #" + task.getTaskId() + " '" + task.getTitle() + "'";
    }
}

// ============================================================
// 11. USER SERVICE — SINGLETON
// ============================================================
class UserService {
    private static volatile UserService instance;
    private final ConcurrentHashMap<Long, User>     usersById    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, User>   usersByEmail = new ConcurrentHashMap<>();

    private UserService() {}

    public static UserService getInstance() {
        if (instance == null) {
            synchronized (UserService.class) {
                if (instance == null) instance = new UserService();
            }
        }
        return instance;
    }

    public User register(User user) {
        if (usersByEmail.containsKey(user.getEmail())) {
            System.out.println("[UserService] Email already exists: " + user.getEmail());
            return null;
        }
        usersById.put(user.getUserId(), user);
        usersByEmail.put(user.getEmail(), user);
        System.out.println("[UserService] Registered: " + user);
        return user;
    }

    public User getUser(long id)         { return usersById.get(id); }
    public User getUserByEmail(String e) { return usersByEmail.get(e); }
    public Collection<User> getAllUsers(){ return usersById.values(); }
}

// ============================================================
// 12. REMINDER SCHEDULER
//    Req 3: background scanner for due reminders
// ============================================================
class ReminderScheduler {
    private final List<Reminder>           pendingReminders = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, Task> taskStore;
    private final List<TaskEventObserver>  observers;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reminder-scanner");
            t.setDaemon(true);
            return t;
        });

    public ReminderScheduler(ConcurrentHashMap<Long, Task> taskStore,
                              List<TaskEventObserver> observers) {
        this.taskStore = taskStore;
        this.observers = observers;
        // Scan every 30 seconds
        scheduler.scheduleAtFixedRate(this::scan, 0, 30, TimeUnit.SECONDS);
    }

    public void addReminder(Reminder r) { pendingReminders.add(r); }

    private void scan() {
        pendingReminders.stream()
            .filter(Reminder::isDue)
            .forEach(r -> {
                r.trigger();
                Task task = taskStore.get(r.getTaskId());
                if (task != null) {
                    observers.forEach(o -> o.onReminderDue(r, task));
                }
            });
    }

    public void shutdown() { scheduler.shutdown(); }
}

// ============================================================
// 13. TASK MANAGEMENT SERVICE — SINGLETON
//     Top-level entry point for all operations
// ============================================================
class TaskManagementService {
    private static volatile TaskManagementService instance;

    private final ConcurrentHashMap<Long, Task>    tasks       = new ConcurrentHashMap<>();
    // Command history for undo (per-user stack)
    private final ConcurrentHashMap<Long, Deque<TaskCommand>> commandHistory
        = new ConcurrentHashMap<>();
    private final UserService                      userService = UserService.getInstance();
    private final List<TaskEventObserver>          observers   = new ArrayList<>();
    private final AuditObserver                    audit       = new AuditObserver();
    private final AnalyticsObserver                analytics   = new AnalyticsObserver();
    private       TaskSortStrategy                 sortStrategy= new SortByDueDate();
    private final ReminderScheduler                reminderSched;

    private TaskManagementService() {
        NotificationObserver notif = new NotificationObserver(userService);
        observers.add(notif);
        observers.add(audit);
        observers.add(analytics);
        reminderSched = new ReminderScheduler(tasks, observers);
    }

    public static TaskManagementService getInstance() {
        if (instance == null) {
            synchronized (TaskManagementService.class) {
                if (instance == null) instance = new TaskManagementService();
            }
        }
        return instance;
    }

    public void setSortStrategy(TaskSortStrategy s) {
        this.sortStrategy = s;
        System.out.println("[Service] Sort: " + s.getName());
    }

    public void addObserver(TaskEventObserver o) { observers.add(o); }

    // ---- Req 1: Create task ----
    public Task createTask(Task task, long actorId) {
        CreateTaskCommand cmd = new CreateTaskCommand(task, tasks, observers);
        cmd.execute();
        pushCommand(actorId, cmd);

        // Notify assignee if set
        if (task.getAssignedToUserId() != 0) {
            observers.forEach(o -> o.onTaskAssigned(task, task.getAssignedToUserId()));
        }

        System.out.println("[Service] Created: " + task);
        return task;
    }

    // ---- Req 1: Update task ----
    public boolean updateTask(long taskId, String newTitle, String newDesc,
                               LocalDate newDue, TaskPriority newPriority,
                               long actorId) {
        Task task = tasks.get(taskId);
        if (task == null) return false;

        UpdateTaskCommand cmd = new UpdateTaskCommand(
            task, newTitle, newDesc, newDue, newPriority, actorId, observers);
        cmd.execute();
        pushCommand(actorId, cmd);
        return true;
    }

    // ---- Req 1: Delete task ----
    public boolean deleteTask(long taskId, long actorId) {
        Task task = tasks.get(taskId);
        if (task == null) return false;

        DeleteTaskCommand cmd = new DeleteTaskCommand(task, tasks, observers);
        cmd.execute();
        pushCommand(actorId, cmd);
        return true;
    }

    // ---- Undo last action (Req 7: extensible) ----
    public boolean undo(long actorId) {
        Deque<TaskCommand> history = commandHistory.get(actorId);
        if (history == null || history.isEmpty()) {
            System.out.println("[Service] Nothing to undo for user#" + actorId);
            return false;
        }
        TaskCommand last = history.pop();
        last.undo();
        System.out.println("[Service] Undone: " + last.getDescription());
        return true;
    }

    // ---- Req 3: Assign task ----
    public boolean assignTask(long taskId, long newAssigneeId, long actorId) {
        Task task     = tasks.get(taskId);
        User assignee = userService.getUser(newAssigneeId);
        if (task == null || assignee == null) return false;

        task.assignTo(newAssigneeId, actorId);
        observers.forEach(o -> o.onTaskAssigned(task, newAssigneeId));
        return true;
    }

    // ---- Req 3: Add reminder ----
    public Reminder addReminder(long taskId, long userId,
                                 LocalDateTime remindAt, ReminderType type) {
        Task task = tasks.get(taskId);
        if (task == null) return null;

        Reminder reminder = new Reminder.Builder(taskId, userId, remindAt)
            .type(type)
            .message("Reminder: '" + task.getTitle() +
                     "' is due " + task.getDueDate())
            .build();

        task.addReminder(reminder);
        reminderSched.addReminder(reminder);
        System.out.println("[Service] " + reminder);
        return reminder;
    }

    // ---- Req 2 + 5: Status transition ----
    public boolean updateStatus(long taskId, TaskStatus newStatus, long actorId) {
        Task task = tasks.get(taskId);
        if (task == null) return false;

        boolean ok = task.transition(newStatus, actorId);
        if (ok && newStatus == TaskStatus.COMPLETED) {
            observers.forEach(o -> o.onTaskCompleted(task));
        }
        return ok;
    }

    // ---- Req 3: Add comment ----
    public void addComment(long taskId, long authorId, String content) {
        Task task = tasks.get(taskId);
        if (task != null)
            task.addComment(authorId, content, CommentType.NOTE);
    }

    // ---- Req 4: Search + filter ----
    public TaskFilter filter() {
        return new TaskFilter(tasks.values());
    }

    // Req 4: get tasks for a user
    public List<Task> getTasksForUser(long userId) {
        return filter().byAssignee(userId)
            .sortedBy(sortStrategy).toList();
    }

    // Req 5: completed task history for a user
    public List<Task> getCompletedHistory(long userId) {
        return filter().byAssignee(userId)
            .byStatus(TaskStatus.COMPLETED)
            .sortedBy(new SortByUpdated()).toList();
    }

    // Req 5: view task activity log
    public void printTaskHistory(long taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return;
        System.out.println("\n── Activity for Task #" + taskId +
            " '" + task.getTitle() + "' ──");
        task.getComments().forEach(c -> System.out.println("  " + c));
    }

    private void pushCommand(long userId, TaskCommand cmd) {
        commandHistory.computeIfAbsent(userId, k -> new ArrayDeque<>()).push(cmd);
    }

    public Task      getTask(long id)      { return tasks.get(id); }
    public int       getTaskCount()        { return tasks.size(); }
    public AuditObserver getAudit()        { return audit; }
    public void      printAnalytics()      { analytics.printReport(); }
    public void      shutdown()            { reminderSched.shutdown(); }
}

// ============================================================
// 14. MAIN — DRIVER CODE
// ============================================================
public class TaskManagementSystem {
    public static void main(String[] args) throws InterruptedException {

        TaskManagementService service = TaskManagementService.getInstance();
        UserService           users   = UserService.getInstance();

        // ---- Setup users ----
        User alice = users.register(
            new User.Builder("Alice", "alice@team.com").role(UserRole.MANAGER).build());
        User bob   = users.register(
            new User.Builder("Bob",   "bob@team.com").role(UserRole.MEMBER).build());
        User carol = users.register(
            new User.Builder("Carol", "carol@team.com").role(UserRole.MEMBER).build());
        User dave  = users.register(
            new User.Builder("Dave",  "dave@team.com").role(UserRole.VIEWER).build());

        // ===== SCENARIO 1: Req 1 + 2 — Create tasks =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Create Tasks (Req 1 + 2)");
        System.out.println("=".repeat(60));

        Task t1 = service.createTask(
            TaskFactory.personal("Design system architecture",
                alice.getUserId(), LocalDate.now().plusDays(3), TaskPriority.HIGH),
            alice.getUserId());

        Task t2 = service.createTask(
            TaskFactory.teamTask("Implement login API", alice.getUserId(),
                bob.getUserId(), LocalDate.now().plusDays(5),
                TaskPriority.CRITICAL, 100L),
            alice.getUserId());

        Task t3 = service.createTask(
            TaskFactory.bugReport("Null pointer in payment flow",
                alice.getUserId(), bob.getUserId(),
                "NPE thrown when card expiry is null"),
            alice.getUserId());

        Task t4 = service.createTask(
            TaskFactory.feature("Dark mode toggle", alice.getUserId(),
                carol.getUserId(), LocalDate.now().plusDays(14)),
            alice.getUserId());

        Task t5 = service.createTask(
            new Task.Builder("Write unit tests", bob.getUserId())
                .dueDate(LocalDate.now().minusDays(2))  // overdue!
                .priority(TaskPriority.MEDIUM)
                .assignedTo(bob.getUserId())
                .tags("testing", "backend").build(),
            bob.getUserId());

        // ===== SCENARIO 2: Req 2 — Status transitions =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Status Transitions (Req 2)");
        System.out.println("=".repeat(60));

        service.updateStatus(t2.getTaskId(), TaskStatus.IN_PROGRESS, bob.getUserId());
        service.updateStatus(t2.getTaskId(), TaskStatus.REVIEW,      bob.getUserId());
        service.updateStatus(t2.getTaskId(), TaskStatus.COMPLETED,   alice.getUserId());

        // Invalid transition: COMPLETED → IN_PROGRESS (should fail)
        boolean invalid = service.updateStatus(t2.getTaskId(),
            TaskStatus.IN_PROGRESS, alice.getUserId());
        System.out.println("Invalid transition result: " + invalid);

        // ===== SCENARIO 3: Req 3 — Assign + reminders =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Assign Tasks + Set Reminders (Req 3)");
        System.out.println("=".repeat(60));

        // Reassign t4 from carol to dave
        service.assignTask(t4.getTaskId(), dave.getUserId(), alice.getUserId());

        // Add reminders (due soon for demo — 1 minute from now)
        service.addReminder(t1.getTaskId(), alice.getUserId(),
            LocalDateTime.now().plusMinutes(1), ReminderType.EMAIL);

        service.addReminder(t3.getTaskId(), bob.getUserId(),
            LocalDateTime.now().plusMinutes(2), ReminderType.PUSH);

        // Add a comment to t3
        service.addComment(t3.getTaskId(), bob.getUserId(),
            "Reproduced locally — null check missing in CardService.charge()");

        // ===== SCENARIO 4: Req 4 — Search + filter =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Search and Filter Tasks (Req 4)");
        System.out.println("=".repeat(60));

        System.out.println("Filter: HIGH + CRITICAL priority tasks:");
        service.filter().byPriority(TaskPriority.CRITICAL)
            .sortedBy(new SortByPriority()).toList()
            .forEach(t -> System.out.println("  " + t));

        System.out.println("\nFilter: Bob's assigned tasks:");
        service.getTasksForUser(bob.getUserId())
            .forEach(t -> System.out.println("  " + t));

        System.out.println("\nFilter: Overdue tasks:");
        service.filter().overdue().toList()
            .forEach(t -> System.out.println("  " + t));

        System.out.println("\nFilter: tasks due in next 7 days (not completed):");
        service.filter()
            .byDueDateRange(LocalDate.now(), LocalDate.now().plusDays(7))
            .toList().forEach(t -> System.out.println("  " + t));

        System.out.println("\nSearch: tasks containing 'login':");
        service.filter().search("login").toList()
            .forEach(t -> System.out.println("  " + t));

        System.out.println("\nFilter: TEAM tasks sorted by due date:");
        service.filter().byType(TaskType.TEAM)
            .sortedBy(new SortByDueDate()).toList()
            .forEach(t -> System.out.println("  " + t));

        // ===== SCENARIO 5: Req 5 — Mark complete + history =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Mark Complete + View History (Req 5)");
        System.out.println("=".repeat(60));

        service.updateStatus(t3.getTaskId(), TaskStatus.IN_PROGRESS, bob.getUserId());
        service.addComment(t3.getTaskId(), bob.getUserId(),
            "Fix applied — added null check before expiry access");
        service.updateStatus(t3.getTaskId(), TaskStatus.COMPLETED, alice.getUserId());

        // View history
        service.printTaskHistory(t3.getTaskId());

        System.out.println("\nBob's completed task history:");
        service.getCompletedHistory(bob.getUserId())
            .forEach(t -> System.out.println("  " + t +
                " | completed at: " + t.getCompletedAt()));

        // ===== SCENARIO 6: Req 6 — Concurrent updates =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Concurrent Task Updates (Req 6)");
        System.out.println("=".repeat(60));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        Task sharedTask = service.createTask(
            new Task.Builder("Shared concurrent task", alice.getUserId())
                .dueDate(LocalDate.now().plusDays(10))
                .priority(TaskPriority.HIGH)
                .assignedTo(bob.getUserId()).build(),
            alice.getUserId());

        // 4 threads try to update/transition the same task simultaneously
        pool.submit(() -> service.updateStatus(sharedTask.getTaskId(),
            TaskStatus.IN_PROGRESS, bob.getUserId()));
        pool.submit(() -> service.updateTask(sharedTask.getTaskId(),
            "Updated title", null, null, TaskPriority.CRITICAL, alice.getUserId()));
        pool.submit(() -> service.addComment(sharedTask.getTaskId(),
            carol.getUserId(), "Carol's concurrent comment"));
        pool.submit(() -> service.assignTask(sharedTask.getTaskId(),
            carol.getUserId(), alice.getUserId()));

        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        System.out.println("\nShared task after concurrent updates:");
        System.out.println("  " + sharedTask);
        System.out.println("  Comments: " + sharedTask.getComments().size());

        // ===== SCENARIO 7: Undo (Req 7 extensibility) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Undo Last Action (Req 7)");
        System.out.println("=".repeat(60));

        Task tempTask = service.createTask(
            new Task.Builder("Temporary task to undo", alice.getUserId())
                .dueDate(LocalDate.now().plusDays(1)).build(),
            alice.getUserId());
        System.out.println("Before undo — task count: " + service.getTaskCount());
        service.undo(alice.getUserId()); // undo the create
        System.out.println("After undo  — task count: " + service.getTaskCount());

        // ===== SCENARIO 8: Strategy swap =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Sort Strategy Swap (Req 7)");
        System.out.println("=".repeat(60));

        service.setSortStrategy(new SortByPriority());
        System.out.println("Alice's tasks sorted by PRIORITY:");
        service.getTasksForUser(alice.getUserId())
            .forEach(t -> System.out.println("  " + t));

        service.setSortStrategy(new SortByDueDate());
        System.out.println("\nAlice's tasks sorted by DUE DATE:");
        service.getTasksForUser(alice.getUserId())
            .forEach(t -> System.out.println("  " + t));

        // ===== FINAL =====
        service.printAnalytics();
        service.shutdown();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|----------------------------------------------------------
            Singleton  | TaskManagementService, UserService (double-checked lock)
            State      | TaskStatus: PENDING→IN_PROGRESS→REVIEW→COMPLETED/CANCELLED
            Strategy   | TaskSortStrategy (DueDate / Priority / Assignee / Updated)
            Observer   | TaskEventObserver (Notification / Audit / Analytics)
            Factory    | TaskFactory (personal / teamTask / bugReport / feature)
            Builder    | Task.Builder, User.Builder, Reminder.Builder
            Command    | Create/Update/DeleteTaskCommand with full undo support
            Iterator   | TaskFilter — composable chainable filter pipeline
            """);
    }
}
