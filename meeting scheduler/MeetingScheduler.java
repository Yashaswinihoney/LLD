import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// MEETING SCHEDULER LLD — Google Calendar Style
// Patterns:
//   Singleton  — CalendarService, RoomRegistry
//   Strategy   — SlotFinderStrategy (earliest / optimal / balanced)
//   Observer   — MeetingEventObserver (notifications, reminders)
//   Factory    — MeetingFactory (one-time / recurring / all-day)
//   Builder    — Meeting, TimeSlot construction
//   State      — MeetingStatus, InviteStatus (PENDING→ACCEPTED/DECLINED)
//   Iterator   — RecurrenceIterator (expand recurring meetings)
//   Command    — RescheduleCommand (execute + undo)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum MeetingStatus   { DRAFT, SCHEDULED, ONGOING, COMPLETED, CANCELLED }
enum InviteStatus    { PENDING, ACCEPTED, DECLINED, TENTATIVE, NO_RESPONSE }
enum MeetingType     { ONE_TIME, RECURRING, ALL_DAY, BLOCK_TIME }
enum RecurrenceRule  { DAILY, WEEKLY, BIWEEKLY, MONTHLY, WEEKDAYS, CUSTOM }
enum MeetingPriority { LOW, NORMAL, HIGH, URGENT }
enum RoomFeature     { PROJECTOR, WHITEBOARD, VIDEO_CONF, PHONE, STANDING_DESK }
enum NotifChannel    { EMAIL, PUSH, SMS, IN_APP }
enum ConflictType    { HARD_CONFLICT, SOFT_CONFLICT, ROOM_CONFLICT, NONE }

// ==========================================
// 2. TIME SLOT — BUILDER PATTERN
// All times stored in UTC internally
// ==========================================
class TimeSlot {
    private final ZonedDateTime start;
    private final ZonedDateTime end;

    public TimeSlot(ZonedDateTime start, ZonedDateTime end) {
        if (!start.isBefore(end))
            throw new IllegalArgumentException(
                "Start must be before end: " + start + " → " + end);
        this.start = start;
        this.end   = end;
    }

    // Convenience: create from local time + timezone
    public static TimeSlot of(LocalDate date, LocalTime start,
                               LocalTime end, ZoneId zone) {
        return new TimeSlot(
            ZonedDateTime.of(date, start, zone),
            ZonedDateTime.of(date, end,   zone));
    }

    public static TimeSlot ofMinutes(ZonedDateTime start, int durationMinutes) {
        return new TimeSlot(start, start.plusMinutes(durationMinutes));
    }

    public boolean overlaps(TimeSlot other) {
        return start.isBefore(other.end) && end.isAfter(other.start);
    }

    public boolean contains(ZonedDateTime point) {
        return !point.isBefore(start) && point.isBefore(end);
    }

    public long getDurationMinutes() {
        return Duration.between(start, end).toMinutes();
    }

    public TimeSlot shiftBy(int days) {
        return new TimeSlot(start.plusDays(days), end.plusDays(days));
    }

    // Convert to a specific timezone for display
    public String display(ZoneId zone) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
        return start.withZoneSameInstant(zone).format(fmt) +
               " → " + end.withZoneSameInstant(zone).format(fmt);
    }

    public ZonedDateTime getStart() { return start; }
    public ZonedDateTime getEnd()   { return end; }

    @Override public String toString() {
        return display(ZoneId.of("Asia/Kolkata")); // show in IST by default
    }
}

// ==========================================
// 3. USER + CALENDAR
// ==========================================
class User {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long     id;
    private final  String   name;
    private final  String   email;
    private        ZoneId   timezone;
    private        TimeSlot workingHours;   // daily working window
    private final  Set<DayOfWeek> workDays;
    private        boolean  doNotDisturb;

    public User(String name, String email, ZoneId timezone) {
        this.id           = idGen.getAndIncrement();
        this.name         = name;
        this.email        = email;
        this.timezone     = timezone;
        this.workDays     = EnumSet.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        // Default working hours 9am-6pm in user's timezone
        LocalDate today   = LocalDate.now(timezone);
        this.workingHours = TimeSlot.of(today, LocalTime.of(9, 0),
                                         LocalTime.of(18, 0), timezone);
    }

    public boolean isWorkingDay(DayOfWeek day) { return workDays.contains(day); }

    public long    getId()         { return id; }
    public String  getName()       { return name; }
    public String  getEmail()      { return email; }
    public ZoneId  getTimezone()   { return timezone; }
    public TimeSlot getWorkingHours(){ return workingHours; }
    public Set<DayOfWeek> getWorkDays(){ return workDays; }

    public void setTimezone(ZoneId tz)        { this.timezone = tz; }
    public void setWorkingHours(TimeSlot wh)  { this.workingHours = wh; }
    public void setDoNotDisturb(boolean dnd)  { this.doNotDisturb = dnd; }
    public boolean isDoNotDisturb()           { return doNotDisturb; }

    @Override public String toString() {
        return "User[" + name + " | " + email + " | " + timezone.getId() + "]";
    }
}

// ==========================================
// 4. ROOM
// ==========================================
class Room {
    private static final AtomicLong idGen = new AtomicLong(100);

    private final  long            id;
    private final  String          name;
    private final  String          location;
    private final  int             capacity;
    private final  Set<RoomFeature> features;
    private        boolean         isActive;

    public Room(String name, String location, int capacity,
                RoomFeature... features) {
        this.id       = idGen.getAndIncrement();
        this.name     = name;
        this.location = location;
        this.capacity = capacity;
        this.features = features.length > 0
            ? EnumSet.copyOf(Arrays.asList(features))
            : EnumSet.noneOf(RoomFeature.class);
        this.isActive = true;
    }

    public boolean hasFeature(RoomFeature f) { return features.contains(f); }
    public boolean canFit(int attendees)     { return capacity >= attendees; }

    public long             getId()       { return id; }
    public String           getName()     { return name; }
    public String           getLocation() { return location; }
    public int              getCapacity() { return capacity; }
    public Set<RoomFeature> getFeatures() { return features; }
    public boolean          isActive()    { return isActive; }

    @Override public String toString() {
        return "Room[" + name + " | " + location +
               " | cap:" + capacity + " | " + features + "]";
    }
}

// ==========================================
// 5. INVITE — STATE PATTERN
// ==========================================
class Invite {
    private final User        invitee;
    private       InviteStatus status;
    private       String      responseNote;
    private       boolean     isOrganiser;
    private       boolean     isOptional;
    private       LocalDateTime respondedAt;

    public Invite(User invitee, boolean isOrganiser, boolean isOptional) {
        this.invitee      = invitee;
        this.isOrganiser  = isOrganiser;
        this.isOptional   = isOptional;
        this.status       = isOrganiser
            ? InviteStatus.ACCEPTED : InviteStatus.PENDING;
    }

    public void accept(String note) {
        this.status       = InviteStatus.ACCEPTED;
        this.responseNote = note;
        this.respondedAt  = LocalDateTime.now();
        System.out.println("[Invite] " + invitee.getName() + " ACCEPTED");
    }

    public void decline(String note) {
        this.status       = InviteStatus.DECLINED;
        this.responseNote = note;
        this.respondedAt  = LocalDateTime.now();
        System.out.println("[Invite] " + invitee.getName() + " DECLINED: " + note);
    }

    public void tentative(String note) {
        this.status       = InviteStatus.TENTATIVE;
        this.responseNote = note;
        System.out.println("[Invite] " + invitee.getName() + " TENTATIVE");
    }

    public User        getInvitee()    { return invitee; }
    public InviteStatus getStatus()    { return status; }
    public boolean     isOrganiser()   { return isOrganiser; }
    public boolean     isOptional()    { return isOptional; }
    public String      getResponseNote(){ return responseNote; }

    @Override public String toString() {
        return invitee.getName() + ":" + status +
               (isOrganiser ? "(org)" : "") +
               (isOptional  ? "(opt)" : "");
    }
}

// ==========================================
// 6. RECURRENCE CONFIG
// Stores recurrence rule for repeating meetings
// ==========================================
class RecurrenceConfig {
    private final RecurrenceRule rule;
    private final int            interval;      // every N weeks/months
    private final LocalDate      endDate;       // null = no end
    private final int            occurrences;   // 0 = use endDate
    private final Set<DayOfWeek> daysOfWeek;   // for WEEKLY/CUSTOM

    public RecurrenceConfig(RecurrenceRule rule, int interval,
                             LocalDate endDate, int occurrences,
                             DayOfWeek... days) {
        this.rule        = rule;
        this.interval    = interval;
        this.endDate     = endDate;
        this.occurrences = occurrences;
        this.daysOfWeek  = days.length > 0
            ? EnumSet.copyOf(Arrays.asList(days))
            : EnumSet.noneOf(DayOfWeek.class);
    }

    // Static factories for common patterns
    public static RecurrenceConfig weekly(LocalDate endDate) {
        return new RecurrenceConfig(RecurrenceRule.WEEKLY, 1, endDate, 0);
    }

    public static RecurrenceConfig daily(int occurrences) {
        return new RecurrenceConfig(RecurrenceRule.DAILY, 1, null, occurrences);
    }

    public static RecurrenceConfig weekdays(LocalDate endDate) {
        return new RecurrenceConfig(RecurrenceRule.WEEKDAYS, 1, endDate, 0,
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
    }

    public static RecurrenceConfig biweekly(LocalDate endDate) {
        return new RecurrenceConfig(RecurrenceRule.BIWEEKLY, 2, endDate, 0);
    }

    public RecurrenceRule  getRule()       { return rule; }
    public int             getInterval()   { return interval; }
    public LocalDate       getEndDate()    { return endDate; }
    public int             getOccurrences(){ return occurrences; }
    public Set<DayOfWeek>  getDaysOfWeek() { return daysOfWeek; }

    @Override public String toString() {
        return rule + (interval > 1 ? " every " + interval : "") +
               (endDate != null ? " until " + endDate : "");
    }
}

// ==========================================
// 7. MEETING — BUILDER + STATE PATTERN
// ==========================================
class Meeting {
    private static final AtomicLong idGen = new AtomicLong(10_000);

    private final  long              id;
    private        String            title;
    private        String            description;
    private        String            agenda;
    private        MeetingType       type;
    private        MeetingPriority   priority;
    private        TimeSlot          timeSlot;
    private        Room              room;           // null = virtual
    private        String            meetingLink;    // Zoom/Meet/Teams URL
    private final  List<Invite>      invites;
    private        MeetingStatus     status;
    private final  User              organiser;
    private        RecurrenceConfig  recurrence;     // null if one-time
    private        Long              parentMeetingId;// for recurring instances
    private final  LocalDateTime     createdAt;
    private        LocalDateTime     updatedAt;
    private        String            cancelReason;
    private final  List<String>      notes = new ArrayList<>(); // meeting notes

    private Meeting(Builder b) {
        this.id             = idGen.getAndIncrement();
        this.title          = b.title;
        this.description    = b.description;
        this.agenda         = b.agenda;
        this.type           = b.type;
        this.priority       = b.priority;
        this.timeSlot       = b.timeSlot;
        this.room           = b.room;
        this.meetingLink    = b.meetingLink;
        this.invites        = new CopyOnWriteArrayList<>(b.invites);
        this.status         = MeetingStatus.DRAFT;
        this.organiser      = b.organiser;
        this.recurrence     = b.recurrence;
        this.parentMeetingId = b.parentMeetingId;
        this.createdAt      = LocalDateTime.now();
        this.updatedAt      = LocalDateTime.now();
    }

    // ---- State transitions ----
    public void schedule() {
        status    = MeetingStatus.SCHEDULED;
        updatedAt = LocalDateTime.now();
        System.out.println("[Meeting #" + id + "] SCHEDULED: " + title);
    }

    public void start() {
        if (status == MeetingStatus.SCHEDULED) {
            status    = MeetingStatus.ONGOING;
            updatedAt = LocalDateTime.now();
        }
    }

    public void complete() {
        status    = MeetingStatus.COMPLETED;
        updatedAt = LocalDateTime.now();
    }

    public void cancel(String reason) {
        if (status != MeetingStatus.COMPLETED) {
            status        = MeetingStatus.CANCELLED;
            cancelReason  = reason;
            updatedAt     = LocalDateTime.now();
            System.out.println("[Meeting #" + id + "] CANCELLED: " + reason);
        }
    }

    // ---- Invite management ----
    public Invite getInvite(long userId) {
        return invites.stream()
            .filter(i -> i.getInvitee().getId() == userId)
            .findFirst().orElse(null);
    }

    public void addInvitee(User user, boolean optional) {
        if (getInvite(user.getId()) == null) {
            invites.add(new Invite(user, false, optional));
            System.out.println("[Meeting #" + id + "] Added: " +
                user.getName() + (optional ? " (optional)" : ""));
        }
    }

    public void removeInvitee(long userId) {
        invites.removeIf(i -> i.getInvitee().getId() == userId);
    }

    public long getAcceptedCount() {
        return invites.stream()
            .filter(i -> i.getStatus() == InviteStatus.ACCEPTED).count();
    }

    public long getDeclinedCount() {
        return invites.stream()
            .filter(i -> i.getStatus() == InviteStatus.DECLINED).count();
    }

    public boolean hasConflict(Meeting other) {
        if (this.id == other.id) return false;
        return this.timeSlot.overlaps(other.timeSlot);
    }

    public void addNote(String note)        { notes.add(note); }
    public void reschedule(TimeSlot slot)   {
        this.timeSlot = slot;
        this.updatedAt = LocalDateTime.now();
        System.out.println("[Meeting #" + id + "] Rescheduled to: " + slot);
    }

    public long             getId()            { return id; }
    public String           getTitle()         { return title; }
    public String           getDescription()   { return description; }
    public String           getAgenda()        { return agenda; }
    public MeetingType      getType()          { return type; }
    public MeetingPriority  getPriority()      { return priority; }
    public TimeSlot         getTimeSlot()      { return timeSlot; }
    public Room             getRoom()          { return room; }
    public String           getMeetingLink()   { return meetingLink; }
    public List<Invite>     getInvites()       { return Collections.unmodifiableList(invites); }
    public MeetingStatus    getStatus()        { return status; }
    public User             getOrganiser()     { return organiser; }
    public RecurrenceConfig getRecurrence()    { return recurrence; }
    public Long             getParentId()      { return parentMeetingId; }
    public List<String>     getNotes()         { return notes; }

    public void setRoom(Room r)                { this.room = r; }
    public void setMeetingLink(String url)     { this.meetingLink = url; }
    public void setTitle(String t)             { this.title = t; }

    @Override public String toString() {
        return String.format("Meeting[#%d | %-25s | %s | %s | invites=%d | %s]",
            id, title, timeSlot, type, invites.size(), status);
    }

    // ---- BUILDER ----
    static class Builder {
        private final String        title;
        private final User          organiser;
        private final TimeSlot      timeSlot;
        private       String        description    = "";
        private       String        agenda         = "";
        private       MeetingType   type           = MeetingType.ONE_TIME;
        private       MeetingPriority priority     = MeetingPriority.NORMAL;
        private       Room          room;
        private       String        meetingLink;
        private final List<Invite>  invites        = new ArrayList<>();
        private       RecurrenceConfig recurrence;
        private       Long          parentMeetingId;

        public Builder(String title, User organiser, TimeSlot slot) {
            this.title     = title;
            this.organiser = organiser;
            this.timeSlot  = slot;
            // Organiser is auto-added as accepted
            this.invites.add(new Invite(organiser, true, false));
        }

        public Builder description(String d)     { this.description = d;    return this; }
        public Builder agenda(String a)          { this.agenda = a;         return this; }
        public Builder type(MeetingType t)       { this.type = t;           return this; }
        public Builder priority(MeetingPriority p){ this.priority = p;      return this; }
        public Builder room(Room r)              { this.room = r;           return this; }
        public Builder meetingLink(String url)   { this.meetingLink = url;  return this; }
        public Builder recurrence(RecurrenceConfig r){ this.recurrence = r; return this; }
        public Builder parentId(Long id)         { this.parentMeetingId=id; return this; }

        public Builder invite(User user, boolean optional) {
            if (!user.equals(organiser))
                invites.add(new Invite(user, false, optional));
            return this;
        }

        public Builder invite(User... users) {
            for (User u : users) invite(u, false);
            return this;
        }

        public Meeting build() { return new Meeting(this); }
    }
}

// ==========================================
// 8. MEETING FACTORY
// ==========================================
class MeetingFactory {
    public static Meeting oneOnOne(User organiser, User invitee,
                                    String title, TimeSlot slot) {
        return new Meeting.Builder(title, organiser, slot)
            .type(MeetingType.ONE_TIME)
            .meetingLink("https://meet.google.com/" +
                UUID.randomUUID().toString().substring(0, 8))
            .invite(invitee, false)
            .build();
    }

    public static Meeting teamMeeting(User organiser, String title,
                                       TimeSlot slot, Room room, User... members) {
        Meeting.Builder b = new Meeting.Builder(title, organiser, slot)
            .type(MeetingType.ONE_TIME)
            .room(room);
        for (User m : members) b.invite(m, false);
        return b.build();
    }

    public static Meeting recurringStandup(User organiser, String title,
                                            TimeSlot firstSlot,
                                            RecurrenceConfig recurrence,
                                            User... members) {
        Meeting.Builder b = new Meeting.Builder(title, organiser, firstSlot)
            .type(MeetingType.RECURRING)
            .recurrence(recurrence);
        for (User m : members) b.invite(m, false);
        return b.build();
    }

    public static Meeting allDayEvent(User organiser, String title,
                                       LocalDate date, ZoneId zone) {
        TimeSlot slot = TimeSlot.of(date, LocalTime.of(0, 0),
                                     LocalTime.of(23, 59), zone);
        return new Meeting.Builder(title, organiser, slot)
            .type(MeetingType.ALL_DAY)
            .build();
    }

    public static Meeting blockTime(User user, String reason, TimeSlot slot) {
        return new Meeting.Builder("BLOCK: " + reason, user, slot)
            .type(MeetingType.BLOCK_TIME)
            .build();
    }
}

// ==========================================
// 9. RECURRENCE ITERATOR — ITERATOR PATTERN
// Expands a recurring meeting into concrete instances
// ==========================================
class RecurrenceIterator implements Iterator<TimeSlot> {
    private final TimeSlot          baseSlot;
    private final RecurrenceConfig  config;
    private       ZonedDateTime     current;
    private       int               count = 0;

    public RecurrenceIterator(TimeSlot base, RecurrenceConfig config) {
        this.baseSlot = base;
        this.config   = config;
        this.current  = base.getStart();
    }

    @Override public boolean hasNext() {
        if (config.getOccurrences() > 0 && count >= config.getOccurrences())
            return false;
        if (config.getEndDate() != null &&
            current.toLocalDate().isAfter(config.getEndDate()))
            return false;
        return true;
    }

    @Override public TimeSlot next() {
        ZonedDateTime slotStart = current;
        ZonedDateTime slotEnd   = slotStart.plus(
            Duration.between(baseSlot.getStart(), baseSlot.getEnd()));

        // Advance current based on rule
        switch (config.getRule()) {
            case DAILY    -> current = current.plusDays(config.getInterval());
            case WEEKLY   -> current = current.plusWeeks(config.getInterval());
            case BIWEEKLY -> current = current.plusWeeks(2);
            case MONTHLY  -> current = current.plusMonths(config.getInterval());
            case WEEKDAYS -> {
                current = current.plusDays(1);
                // Skip weekends
                while (current.getDayOfWeek() == DayOfWeek.SATURDAY ||
                       current.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    current = current.plusDays(1);
                }
            }
            default -> current = current.plusWeeks(1);
        }

        count++;
        return new TimeSlot(slotStart, slotEnd);
    }

    // Expand up to maxCount instances
    public List<TimeSlot> expandAll(int maxCount) {
        List<TimeSlot> slots = new ArrayList<>();
        int limit = 0;
        while (hasNext() && limit++ < maxCount) {
            slots.add(next());
        }
        return slots;
    }
}

// ==========================================
// 10. STRATEGY — SLOT FINDER
// ==========================================
interface SlotFinderStrategy {
    String getName();
    // Find available slots for all required attendees + optional room
    List<TimeSlot> findSlots(List<User> attendees, int durationMinutes,
                              LocalDate searchStart, LocalDate searchEnd,
                              Room room, CalendarService calendarService);
}

// Earliest available slot — first match wins
class EarliestSlotStrategy implements SlotFinderStrategy {
    @Override public String getName() { return "Earliest Available"; }

    @Override
    public List<TimeSlot> findSlots(List<User> attendees, int durationMinutes,
                                     LocalDate searchStart, LocalDate searchEnd,
                                     Room room, CalendarService calendarService) {
        List<TimeSlot> available = new ArrayList<>();
        ZoneId tz = attendees.get(0).getTimezone();

        LocalDate date = searchStart;
        while (!date.isAfter(searchEnd) && available.size() < 5) {
            // Skip weekends
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY ||
                date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
                continue;
            }

            // Try every 30-min slot during 9am-6pm
            LocalTime slotStart = LocalTime.of(9, 0);
            while (slotStart.plusMinutes(durationMinutes).compareTo(
                    LocalTime.of(18, 0)) <= 0) {

                TimeSlot candidate = TimeSlot.of(date, slotStart,
                    slotStart.plusMinutes(durationMinutes), tz);

                boolean allFree = attendees.stream()
                    .allMatch(u -> calendarService.isUserFree(u, candidate));

                boolean roomFree = room == null ||
                    calendarService.isRoomFree(room, candidate);

                if (allFree && roomFree) {
                    available.add(candidate);
                    if (available.size() >= 5) break;
                }

                slotStart = slotStart.plusMinutes(30);
            }
            date = date.plusDays(1);
        }
        return available;
    }
}

// Balanced slot — prefer mid-morning or after-lunch (avoids 9am and 5pm)
class OptimalSlotStrategy implements SlotFinderStrategy {
    @Override public String getName() { return "Optimal (Balanced Hours)"; }

    @Override
    public List<TimeSlot> findSlots(List<User> attendees, int durationMinutes,
                                     LocalDate searchStart, LocalDate searchEnd,
                                     Room room, CalendarService calendarService) {
        List<TimeSlot> available = new ArrayList<>();
        ZoneId tz = attendees.get(0).getTimezone();

        // Preferred windows: 10am-12pm and 2pm-4pm
        LocalTime[] prefStarts = {
            LocalTime.of(10, 0), LocalTime.of(10, 30),
            LocalTime.of(11, 0), LocalTime.of(14, 0),
            LocalTime.of(14, 30), LocalTime.of(15, 0)
        };

        LocalDate date = searchStart;
        while (!date.isAfter(searchEnd) && available.size() < 5) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY ||
                date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.plusDays(1);
                continue;
            }

            for (LocalTime start : prefStarts) {
                TimeSlot candidate = TimeSlot.of(date, start,
                    start.plusMinutes(durationMinutes), tz);

                boolean allFree = attendees.stream()
                    .allMatch(u -> calendarService.isUserFree(u, candidate));
                boolean roomFree = room == null ||
                    calendarService.isRoomFree(room, candidate);

                if (allFree && roomFree) {
                    available.add(candidate);
                    if (available.size() >= 5) break;
                }
            }
            date = date.plusDays(1);
        }
        return available;
    }
}

// ==========================================
// 11. OBSERVER — MEETING EVENTS
// ==========================================
interface MeetingEventObserver {
    void onMeetingScheduled(Meeting meeting);
    void onMeetingCancelled(Meeting meeting);
    void onMeetingUpdated(Meeting meeting, String changeDesc);
    void onInviteResponsed(Meeting meeting, Invite invite);
    void onReminderDue(Meeting meeting, User user, int minutesBefore);
}

class EmailNotifier implements MeetingEventObserver {
    @Override
    public void onMeetingScheduled(Meeting m) {
        m.getInvites().stream()
            .filter(i -> !i.isOrganiser())
            .forEach(i -> System.out.println(
                "[Email → " + i.getInvitee().getEmail() + "] " +
                "Invitation: " + m.getTitle() + " | " + m.getTimeSlot() +
                " | Please accept/decline"));
    }

    @Override
    public void onMeetingCancelled(Meeting m) {
        m.getInvites().forEach(i -> System.out.println(
            "[Email → " + i.getInvitee().getEmail() + "] " +
            "CANCELLED: " + m.getTitle()));
    }

    @Override
    public void onMeetingUpdated(Meeting m, String change) {
        m.getInvites().forEach(i -> System.out.println(
            "[Email → " + i.getInvitee().getEmail() + "] " +
            "UPDATED: " + m.getTitle() + " — " + change));
    }

    @Override
    public void onInviteResponsed(Meeting m, Invite invite) {
        System.out.println("[Email → " + m.getOrganiser().getEmail() + "] " +
            invite.getInvitee().getName() + " " + invite.getStatus() +
            " your meeting: " + m.getTitle());
    }

    @Override
    public void onReminderDue(Meeting m, User u, int mins) {
        System.out.println("[Reminder → " + u.getEmail() + "] " +
            "'" + m.getTitle() + "' starts in " + mins + " minutes! " +
            (m.getMeetingLink() != null ? m.getMeetingLink() : ""));
    }
}

class CalendarSyncObserver implements MeetingEventObserver {
    @Override
    public void onMeetingScheduled(Meeting m) {
        System.out.println("[CalendarSync] Syncing meeting #" + m.getId() +
            " to Google Calendar / Outlook for all attendees");
    }

    @Override public void onMeetingCancelled(Meeting m) {
        System.out.println("[CalendarSync] Removing cancelled meeting #" + m.getId());
    }

    @Override public void onMeetingUpdated(Meeting m, String change) {
        System.out.println("[CalendarSync] Updated meeting #" + m.getId() + ": " + change);
    }

    @Override public void onInviteResponsed(Meeting m, Invite i) {}
    @Override public void onReminderDue(Meeting m, User u, int mins) {}
}

// ==========================================
// 12. RESCHEDULE COMMAND — COMMAND PATTERN
// ==========================================
class RescheduleCommand {
    private final Meeting    meeting;
    private final TimeSlot   newSlot;
    private final TimeSlot   previousSlot;  // for undo
    private final Room       newRoom;
    private final Room       previousRoom;

    public RescheduleCommand(Meeting meeting, TimeSlot newSlot, Room newRoom) {
        this.meeting      = meeting;
        this.newSlot      = newSlot;
        this.previousSlot = meeting.getTimeSlot();
        this.newRoom      = newRoom;
        this.previousRoom = meeting.getRoom();
    }

    public void execute() {
        meeting.reschedule(newSlot);
        if (newRoom != null) meeting.setRoom(newRoom);
    }

    public void undo() {
        meeting.reschedule(previousSlot);
        meeting.setRoom(previousRoom);
        System.out.println("[Undo] Meeting #" + meeting.getId() +
            " restored to: " + previousSlot);
    }
}

// ==========================================
// 13. ROOM REGISTRY — SINGLETON
// ==========================================
class RoomRegistry {
    private static RoomRegistry instance;
    private final Map<Long, Room>           rooms      = new ConcurrentHashMap<>();
    private final Map<Long, List<Meeting>>  roomBookings = new ConcurrentHashMap<>();

    private RoomRegistry() {}

    public static synchronized RoomRegistry getInstance() {
        if (instance == null) instance = new RoomRegistry();
        return instance;
    }

    public void register(Room room) {
        rooms.put(room.getId(), room);
        roomBookings.put(room.getId(), new CopyOnWriteArrayList<>());
        System.out.println("[RoomRegistry] Registered: " + room);
    }

    public void bookRoom(Room room, Meeting meeting) {
        roomBookings.computeIfAbsent(room.getId(),
            k -> new CopyOnWriteArrayList<>()).add(meeting);
    }

    public void releaseRoom(Room room, long meetingId) {
        List<Meeting> bookings = roomBookings.get(room.getId());
        if (bookings != null) bookings.removeIf(m -> m.getId() == meetingId);
    }

    public boolean isRoomAvailable(Room room, TimeSlot slot) {
        List<Meeting> bookings = roomBookings.getOrDefault(
            room.getId(), Collections.emptyList());
        return bookings.stream()
            .filter(m -> m.getStatus() == MeetingStatus.SCHEDULED ||
                         m.getStatus() == MeetingStatus.ONGOING)
            .noneMatch(m -> m.getTimeSlot().overlaps(slot));
    }

    public List<Room> findAvailableRooms(TimeSlot slot, int minCapacity,
                                          RoomFeature... requiredFeatures) {
        Set<RoomFeature> required = requiredFeatures.length > 0
            ? EnumSet.copyOf(Arrays.asList(requiredFeatures))
            : EnumSet.noneOf(RoomFeature.class);

        return rooms.values().stream()
            .filter(Room::isActive)
            .filter(r -> r.canFit(minCapacity))
            .filter(r -> required.isEmpty() ||
                required.stream().allMatch(r::hasFeature))
            .filter(r -> isRoomAvailable(r, slot))
            .sorted(Comparator.comparingInt(Room::getCapacity))
            .collect(Collectors.toList());
    }

    public void printBookings(Room room) {
        System.out.println("[Bookings for " + room.getName() + "]");
        roomBookings.getOrDefault(room.getId(), Collections.emptyList())
            .stream()
            .filter(m -> m.getStatus() == MeetingStatus.SCHEDULED)
            .forEach(m -> System.out.println("  " + m.getTimeSlot() +
                " — " + m.getTitle()));
    }

    public List<Room> getAllRooms() { return new ArrayList<>(rooms.values()); }
}

// ==========================================
// 14. CALENDAR SERVICE — SINGLETON (core)
// ==========================================
class CalendarService {
    private static CalendarService instance;

    // userId → list of meetings
    private final Map<Long, List<Meeting>>  userMeetings   = new ConcurrentHashMap<>();
    private final Map<Long, Meeting>        allMeetings    = new ConcurrentHashMap<>();
    private final List<MeetingEventObserver> observers     = new ArrayList<>();
    private final RoomRegistry              roomRegistry   = RoomRegistry.getInstance();
    private       SlotFinderStrategy        slotStrategy   = new EarliestSlotStrategy();

    private CalendarService() {
        observers.add(new EmailNotifier());
        observers.add(new CalendarSyncObserver());
    }

    public static synchronized CalendarService getInstance() {
        if (instance == null) instance = new CalendarService();
        return instance;
    }

    // ---- Configuration ----
    public void setSlotStrategy(SlotFinderStrategy s) {
        this.slotStrategy = s;
        System.out.println("[Service] Slot strategy: " + s.getName());
    }

    // ---- User management ----
    public void registerUser(User user) {
        userMeetings.put(user.getId(), new CopyOnWriteArrayList<>());
        System.out.println("[Service] Registered: " + user);
    }

    // ---- SCHEDULE MEETING (core flow) ----
    public Meeting scheduleMeeting(Meeting meeting) {
        // 1. Conflict detection for all attendees
        List<String> conflicts = detectConflicts(meeting);
        if (!conflicts.isEmpty()) {
            System.out.println("[Schedule] ⚠ Conflicts detected:");
            conflicts.forEach(c -> System.out.println("  " + c));
            // In production: offer to proceed anyway or show alternatives
            // For demo: proceed with warning
        }

        // 2. Book room if specified
        if (meeting.getRoom() != null) {
            if (!roomRegistry.isRoomAvailable(meeting.getRoom(), meeting.getTimeSlot())) {
                System.out.println("[Schedule] ❌ Room " + meeting.getRoom().getName() +
                    " not available — auto-finding alternative...");
                List<Room> alternatives = roomRegistry.findAvailableRooms(
                    meeting.getTimeSlot(),
                    meeting.getInvites().size());
                if (!alternatives.isEmpty()) {
                    meeting.setRoom(alternatives.get(0));
                    System.out.println("[Schedule] Auto-assigned room: " +
                        meeting.getRoom().getName());
                } else {
                    meeting.setRoom(null);
                    System.out.println("[Schedule] No room available — virtual meeting");
                }
            }
            if (meeting.getRoom() != null)
                roomRegistry.bookRoom(meeting.getRoom(), meeting);
        }

        // 3. Schedule
        meeting.schedule();
        allMeetings.put(meeting.getId(), meeting);

        // 4. Add to each attendee's calendar
        meeting.getInvites().forEach(invite -> {
            long uid = invite.getInvitee().getId();
            userMeetings.computeIfAbsent(uid,
                k -> new CopyOnWriteArrayList<>()).add(meeting);
        });

        // 5. Notify all observers
        observers.forEach(o -> o.onMeetingScheduled(meeting));

        // 6. Schedule recurring instances if applicable
        if (meeting.getRecurrence() != null) {
            scheduleRecurringInstances(meeting);
        }

        return meeting;
    }

    // Expand and schedule recurring instances
    private void scheduleRecurringInstances(Meeting parent) {
        RecurrenceIterator iterator = new RecurrenceIterator(
            parent.getTimeSlot(), parent.getRecurrence());

        List<TimeSlot> slots = iterator.expandAll(52); // up to 52 occurrences
        slots.remove(0); // skip first (already scheduled as parent)

        System.out.println("[Recurrence] Expanding " + parent.getTitle() +
            " → " + slots.size() + " instances");

        for (int i = 0; i < Math.min(slots.size(), 5); i++) { // limit for demo
            TimeSlot slot    = slots.get(i);
            Meeting instance = new Meeting.Builder(parent.getTitle(),
                parent.getOrganiser(), slot)
                .type(MeetingType.RECURRING)
                .description(parent.getDescription())
                .room(parent.getRoom())
                .meetingLink(parent.getMeetingLink())
                .parentId(parent.getId())
                .build();

            parent.getInvites().stream()
                .filter(inv -> !inv.isOrganiser())
                .forEach(inv -> instance.addInvitee(inv.getInvitee(), inv.isOptional()));

            instance.schedule();
            allMeetings.put(instance.getId(), instance);
            parent.getInvites().forEach(inv -> {
                List<Meeting> cal = userMeetings.get(inv.getInvitee().getId());
                if (cal != null) cal.add(instance);
            });
        }
    }

    // ---- CANCEL MEETING ----
    public void cancelMeeting(long meetingId, User requestedBy, String reason) {
        Meeting meeting = allMeetings.get(meetingId);
        if (meeting == null) {
            System.out.println("[Cancel] Meeting not found: #" + meetingId);
            return;
        }
        if (meeting.getOrganiser().getId() != requestedBy.getId()) {
            System.out.println("[Cancel] Only organiser can cancel: " + meeting.getTitle());
            return;
        }

        meeting.cancel(reason);
        if (meeting.getRoom() != null) {
            roomRegistry.releaseRoom(meeting.getRoom(), meetingId);
        }
        observers.forEach(o -> o.onMeetingCancelled(meeting));
    }

    // ---- RESPOND TO INVITE ----
    public void respondToInvite(long meetingId, User user,
                                 InviteStatus response, String note) {
        Meeting meeting = allMeetings.get(meetingId);
        if (meeting == null) return;

        Invite invite = meeting.getInvite(user.getId());
        if (invite == null) {
            System.out.println("[Respond] User " + user.getName() +
                " not invited to meeting #" + meetingId);
            return;
        }

        switch (response) {
            case ACCEPTED  -> invite.accept(note);
            case DECLINED  -> invite.decline(note);
            case TENTATIVE -> invite.tentative(note);
            default        -> {}
        }

        observers.forEach(o -> o.onInviteResponsed(meeting, invite));
    }

    // ---- RESCHEDULE ----
    public RescheduleCommand rescheduleMeeting(long meetingId, TimeSlot newSlot,
                                                Room newRoom) {
        Meeting meeting = allMeetings.get(meetingId);
        if (meeting == null) return null;

        // Release old room
        if (meeting.getRoom() != null) {
            roomRegistry.releaseRoom(meeting.getRoom(), meetingId);
        }

        RescheduleCommand cmd = new RescheduleCommand(meeting, newSlot, newRoom);
        cmd.execute();

        // Book new room
        if (newRoom != null) roomRegistry.bookRoom(newRoom, meeting);

        observers.forEach(o -> o.onMeetingUpdated(meeting, "Rescheduled"));
        return cmd;
    }

    // ---- CONFLICT DETECTION ----
    public List<String> detectConflicts(Meeting newMeeting) {
        List<String> conflicts = new ArrayList<>();

        newMeeting.getInvites().forEach(invite -> {
            User user = invite.getInvitee();
            List<Meeting> existing = getUserMeetings(user, newMeeting.getTimeSlot());

            existing.stream()
                .filter(m -> m.getStatus() == MeetingStatus.SCHEDULED ||
                             m.getStatus() == MeetingStatus.ONGOING)
                .filter(m -> m.getTimeSlot().overlaps(newMeeting.getTimeSlot()))
                .forEach(conflict -> conflicts.add(
                    user.getName() + " has conflict with: '" +
                    conflict.getTitle() + "' [" + conflict.getTimeSlot() + "]"));
        });

        return conflicts;
    }

    // ---- FIND AVAILABLE SLOTS ----
    public List<TimeSlot> findAvailableSlots(List<User> attendees,
                                              int durationMinutes,
                                              LocalDate from, LocalDate to,
                                              Room room) {
        System.out.println("[Slot Finder] Looking for " + durationMinutes +
            " min slot for: " + attendees.stream()
            .map(User::getName).collect(Collectors.joining(", ")));

        List<TimeSlot> slots = slotStrategy.findSlots(
            attendees, durationMinutes, from, to, room, this);

        System.out.println("[Slot Finder] Found " + slots.size() + " options:");
        slots.forEach(s -> System.out.println("  " + s));
        return slots;
    }

    // ---- CONFLICT CHECK HELPERS ----
    public boolean isUserFree(User user, TimeSlot slot) {
        List<Meeting> meetings = userMeetings.getOrDefault(
            user.getId(), Collections.emptyList());
        return meetings.stream()
            .filter(m -> m.getStatus() == MeetingStatus.SCHEDULED ||
                         m.getStatus() == MeetingStatus.ONGOING)
            .noneMatch(m -> m.getTimeSlot().overlaps(slot));
    }

    public boolean isRoomFree(Room room, TimeSlot slot) {
        return roomRegistry.isRoomAvailable(room, slot);
    }

    // ---- USER CALENDAR VIEW ----
    public List<Meeting> getUserMeetings(User user, TimeSlot range) {
        return userMeetings.getOrDefault(user.getId(), Collections.emptyList())
            .stream()
            .filter(m -> m.getStatus() != MeetingStatus.CANCELLED)
            .filter(m -> m.getTimeSlot().overlaps(range))
            .sorted(Comparator.comparing(m -> m.getTimeSlot().getStart()))
            .collect(Collectors.toList());
    }

    public List<Meeting> getUserMeetingsForDay(User user, LocalDate date) {
        ZoneId tz = user.getTimezone();
        TimeSlot daySlot = TimeSlot.of(date, LocalTime.MIDNIGHT,
            LocalTime.of(23, 59), tz);
        return getUserMeetings(user, daySlot);
    }

    public void printUserCalendar(User user, LocalDate date) {
        System.out.println("\n[Calendar: " + user.getName() +
            " | " + date + " | " + user.getTimezone().getId() + "]");
        List<Meeting> meetings = getUserMeetingsForDay(user, date);
        if (meetings.isEmpty()) {
            System.out.println("  No meetings today");
        } else {
            meetings.forEach(m -> System.out.printf("  %s — %s [%s] %s%n",
                m.getTimeSlot().getStart()
                    .withZoneSameInstant(user.getTimezone())
                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                m.getTitle(),
                m.getStatus(),
                m.getRoom() != null ? "📍" + m.getRoom().getName() : "💻 Virtual"));
        }
    }

    public Meeting getMeeting(long id) { return allMeetings.get(id); }
    public int getMeetingCount()       { return allMeetings.size(); }
}

// ==========================================
// 15. MAIN — DRIVER CODE
// ==========================================
public class MeetingScheduler {
    public static void main(String[] args) throws InterruptedException {

        CalendarService service  = CalendarService.getInstance();
        RoomRegistry    roomReg  = RoomRegistry.getInstance();

        // ---- Setup Rooms ----
        Room confA = new Room("Conf-A", "Floor 2", 8,
            RoomFeature.PROJECTOR, RoomFeature.WHITEBOARD, RoomFeature.VIDEO_CONF);
        Room confB = new Room("Conf-B", "Floor 3", 4,
            RoomFeature.VIDEO_CONF, RoomFeature.PHONE);
        Room boardRoom = new Room("Board Room", "Floor 5", 20,
            RoomFeature.PROJECTOR, RoomFeature.VIDEO_CONF, RoomFeature.WHITEBOARD);

        roomReg.register(confA);
        roomReg.register(confB);
        roomReg.register(boardRoom);

        // ---- Setup Users ----
        User alice = new User("Alice",  "alice@company.com",  ZoneId.of("Asia/Kolkata"));
        User bob   = new User("Bob",    "bob@company.com",    ZoneId.of("Asia/Kolkata"));
        User carol = new User("Carol",  "carol@company.com",  ZoneId.of("America/New_York"));
        User dave  = new User("Dave",   "dave@company.com",   ZoneId.of("Asia/Kolkata"));
        User eve   = new User("Eve",    "eve@company.com",    ZoneId.of("Europe/London"));

        service.registerUser(alice);
        service.registerUser(bob);
        service.registerUser(carol);
        service.registerUser(dave);
        service.registerUser(eve);

        LocalDate today = LocalDate.now();
        ZoneId    IST   = ZoneId.of("Asia/Kolkata");

        // ===== SCENARIO 1: One-on-One meeting =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Schedule One-on-One Meeting");
        System.out.println("=".repeat(60));

        TimeSlot slot1 = TimeSlot.of(today, LocalTime.of(10, 0),
            LocalTime.of(10, 30), IST);

        Meeting oneOnOne = service.scheduleMeeting(
            MeetingFactory.oneOnOne(alice, bob, "1:1 — Sprint Review", slot1));

        // ===== SCENARIO 2: Team meeting with room =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Team Meeting with Room Booking");
        System.out.println("=".repeat(60));

        TimeSlot slot2 = TimeSlot.of(today, LocalTime.of(11, 0),
            LocalTime.of(12, 0), IST);

        Meeting teamMeeting = service.scheduleMeeting(
            MeetingFactory.teamMeeting(alice, "Q3 Planning",
                slot2, confA, bob, carol, dave));

        // ===== SCENARIO 3: Conflict detection =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Conflict Detection");
        System.out.println("=".repeat(60));

        // Try to schedule overlapping meeting for Bob
        TimeSlot conflictSlot = TimeSlot.of(today, LocalTime.of(10, 15),
            LocalTime.of(10, 45), IST);

        Meeting conflicting = new Meeting.Builder(
            "Conflicting Meeting", alice, conflictSlot)
            .invite(bob, false) // Bob already has 1:1 at 10:00-10:30
            .build();

        List<String> conflicts = service.detectConflicts(conflicting);
        System.out.println("Conflicts found: " + conflicts.size());
        conflicts.forEach(c -> System.out.println("  ❌ " + c));

        // ===== SCENARIO 4: RSVP responses =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: RSVP — Accept/Decline Invites");
        System.out.println("=".repeat(60));

        service.respondToInvite(teamMeeting.getId(), bob,
            InviteStatus.ACCEPTED, "See you there!");
        service.respondToInvite(teamMeeting.getId(), carol,
            InviteStatus.DECLINED, "Clash with client call at 1:30 EST");
        service.respondToInvite(teamMeeting.getId(), dave,
            InviteStatus.TENTATIVE, "Will try to join");

        System.out.println("Accepted: " + teamMeeting.getAcceptedCount() +
            " | Declined: " + teamMeeting.getDeclinedCount());

        // ===== SCENARIO 5: Find available slots =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Find Available Slots for Group");
        System.out.println("=".repeat(60));

        service.setSlotStrategy(new OptimalSlotStrategy());
        List<TimeSlot> available = service.findAvailableSlots(
            List.of(alice, bob, dave), 60,
            today, today.plusDays(3), confB);

        // ===== SCENARIO 6: Recurring standup =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Daily Standup (Recurring)");
        System.out.println("=".repeat(60));

        TimeSlot standupSlot = TimeSlot.of(
            today.plusDays(1), LocalTime.of(9, 30), LocalTime.of(9, 45), IST);

        Meeting standup = service.scheduleMeeting(
            MeetingFactory.recurringStandup(alice, "Daily Standup",
                standupSlot,
                RecurrenceConfig.weekdays(today.plusDays(30)),
                bob, dave));

        // ===== SCENARIO 7: Reschedule + undo =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Reschedule + Undo");
        System.out.println("=".repeat(60));

        TimeSlot newSlot = TimeSlot.of(today, LocalTime.of(15, 0),
            LocalTime.of(15, 30), IST);

        System.out.println("Before: " + oneOnOne.getTimeSlot());
        RescheduleCommand rescheduleCmd = service.rescheduleMeeting(
            oneOnOne.getId(), newSlot, confB);
        System.out.println("After:  " + oneOnOne.getTimeSlot());

        // Undo the reschedule
        if (rescheduleCmd != null) rescheduleCmd.undo();
        System.out.println("Undone: " + oneOnOne.getTimeSlot());

        // ===== SCENARIO 8: Cross-timezone meeting =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Cross-Timezone Meeting (IST + EST + GMT)");
        System.out.println("=".repeat(60));

        // 6:30 PM IST = 9:00 AM EST = 2:00 PM GMT — overlap window
        TimeSlot crossTzSlot = TimeSlot.of(today.plusDays(1),
            LocalTime.of(18, 30), LocalTime.of(19, 0), IST);

        Meeting crossTz = service.scheduleMeeting(
            new Meeting.Builder("Cross-TZ Architecture Review", alice, crossTzSlot)
                .invite(carol, false)  // EST
                .invite(eve,   false)  // GMT
                .meetingLink("https://meet.google.com/cross-tz-review")
                .build());

        // Show same meeting in each user's timezone
        System.out.println("\nSame meeting in different timezones:");
        System.out.println("  Alice (IST): " + crossTz.getTimeSlot().display(IST));
        System.out.println("  Carol (EST): " + crossTz.getTimeSlot().display(
            ZoneId.of("America/New_York")));
        System.out.println("  Eve   (GMT): " + crossTz.getTimeSlot().display(
            ZoneId.of("Europe/London")));

        // ===== SCENARIO 9: Block time =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Block Time (Focus / OOO)");
        System.out.println("=".repeat(60));

        TimeSlot blockSlot = TimeSlot.of(today.plusDays(2),
            LocalTime.of(14, 0), LocalTime.of(16, 0), IST);

        Meeting block = service.scheduleMeeting(
            MeetingFactory.blockTime(alice, "Deep Work — No Meetings", blockSlot));

        System.out.println("Block time scheduled: " + block.getTitle());

        // ===== SCENARIO 10: Available rooms for a slot =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Find Available Rooms");
        System.out.println("=".repeat(60));

        List<Room> availRooms = roomReg.findAvailableRooms(
            slot2, 4, RoomFeature.VIDEO_CONF);
        System.out.println("Rooms with video conf, capacity≥4, free " + slot2 + ":");
        availRooms.forEach(r -> System.out.println("  " + r));

        // ===== SCENARIO 11: Cancel meeting =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 11: Cancel Meeting");
        System.out.println("=".repeat(60));

        service.cancelMeeting(teamMeeting.getId(), alice,
            "Moved to next week — holiday conflict");
        System.out.println("Team meeting status: " + teamMeeting.getStatus());

        // ===== SCENARIO 12: User calendar view =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 12: Alice's Calendar Today");
        System.out.println("=".repeat(60));

        service.printUserCalendar(alice, today);

        // ===== SUMMARY =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println("Total meetings scheduled: " + service.getMeetingCount());
        System.out.println("Rooms registered:         " + roomReg.getAllRooms().size());

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | CalendarService, RoomRegistry
            Strategy   | SlotFinderStrategy (Earliest / Optimal)
            Observer   | MeetingEventObserver (Email / CalendarSync)
            Factory    | MeetingFactory (oneOnOne/team/recurring/allDay)
            Builder    | Meeting.Builder, TimeSlot
            State      | MeetingStatus + InviteStatus state machines
            Iterator   | RecurrenceIterator (expand recurring → instances)
            Command    | RescheduleCommand (execute + undo)
            """);
    }
}
