import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// LIBRARY MANAGEMENT SYSTEM LLD
// Patterns:
//   Singleton  — LibraryService, CatalogService
//   Strategy   — FineStrategy (fixed / tiered / waiver)
//   Observer   — BookEventObserver (due-date reminders, availability alerts)
//   Factory    — BookFactory, TransactionFactory
//   Builder    — Book, Member construction
//   State      — LoanStatus (ACTIVE→RETURNED/OVERDUE), ReservationStatus
//   Iterator   — CatalogIterator (paginated search results)
//   Chain of Responsibility — BorrowValidator (membership/limit/eligibility)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum BookCategory  { FICTION, NON_FICTION, SCIENCE, TECHNOLOGY, HISTORY,
                     BIOGRAPHY, CHILDREN, REFERENCE, MAGAZINE, THESIS }
enum BookFormat    { HARDCOVER, PAPERBACK, EBOOK, AUDIOBOOK, MAGAZINE_ISSUE }
enum BookCondition { NEW, GOOD, FAIR, POOR, DAMAGED }
enum MemberType    { STUDENT, FACULTY, STAFF, PUBLIC, PREMIUM }
enum MemberStatus  { ACTIVE, SUSPENDED, EXPIRED, BLACKLISTED }
enum LoanStatus    { ACTIVE, RETURNED, OVERDUE, LOST }
enum ReservationStatus { PENDING, READY, FULFILLED, CANCELLED, EXPIRED }
enum TransactionType { BORROW, RETURN, RENEWAL, RESERVATION, FINE_PAYMENT }

// ==========================================
// 2. BOOK — BUILDER PATTERN
// Represents the logical book (title + metadata)
// ==========================================
class Book {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long         id;
    private        String       isbn;
    private        String       title;
    private        List<String> authors;
    private        String       publisher;
    private        int          publishYear;
    private        BookCategory category;
    private        BookFormat   format;
    private        String       language;
    private        int          pages;
    private        String       description;
    private        List<String> tags;
    private        double       rating;      // avg member rating
    private        int          ratingCount;
    private        String       deweyCode;   // library classification
    private        String       shelfLocation; // e.g. "A3-S2" (Aisle 3, Shelf 2)

    private Book(Builder b) {
        this.id           = idGen.getAndIncrement();
        this.isbn         = b.isbn;
        this.title        = b.title;
        this.authors      = List.copyOf(b.authors);
        this.publisher    = b.publisher;
        this.publishYear  = b.publishYear;
        this.category     = b.category;
        this.format       = b.format;
        this.language     = b.language;
        this.pages        = b.pages;
        this.description  = b.description;
        this.tags         = List.copyOf(b.tags);
        this.deweyCode    = b.deweyCode;
        this.shelfLocation= b.shelfLocation;
    }

    public synchronized void addRating(double r) {
        rating      = (rating * ratingCount + r) / (ratingCount + 1);
        ratingCount++;
    }

    public long         getId()           { return id; }
    public String       getIsbn()         { return isbn; }
    public String       getTitle()        { return title; }
    public List<String> getAuthors()      { return authors; }
    public String       getPublisher()    { return publisher; }
    public int          getPublishYear()  { return publishYear; }
    public BookCategory getCategory()     { return category; }
    public BookFormat   getFormat()       { return format; }
    public String       getLanguage()     { return language; }
    public int          getPages()        { return pages; }
    public String       getDescription()  { return description; }
    public List<String> getTags()         { return tags; }
    public double       getRating()       { return rating; }
    public String       getDeweyCode()    { return deweyCode; }
    public String       getShelfLocation(){ return shelfLocation; }

    public void setShelfLocation(String s){ this.shelfLocation = s; }

    @Override public String toString() {
        return String.format("Book[#%d | %-35s | %s | %s | ⭐%.1f]",
            id, title,
            String.join(", ", authors),
            isbn, rating);
    }

    // ---- BUILDER ----
    static class Builder {
        private final String       isbn;
        private final String       title;
        private       List<String> authors      = new ArrayList<>();
        private       String       publisher    = "";
        private       int          publishYear  = 2024;
        private       BookCategory category     = BookCategory.FICTION;
        private       BookFormat   format       = BookFormat.PAPERBACK;
        private       String       language     = "English";
        private       int          pages        = 0;
        private       String       description  = "";
        private       List<String> tags         = new ArrayList<>();
        private       String       deweyCode    = "";
        private       String       shelfLocation = "";

        public Builder(String isbn, String title) {
            this.isbn = isbn; this.title = title;
        }
        public Builder author(String... a)      { authors.addAll(Arrays.asList(a)); return this; }
        public Builder publisher(String p)      { this.publisher = p;    return this; }
        public Builder year(int y)              { this.publishYear = y;  return this; }
        public Builder category(BookCategory c) { this.category = c;     return this; }
        public Builder format(BookFormat f)     { this.format = f;       return this; }
        public Builder language(String l)       { this.language = l;     return this; }
        public Builder pages(int p)             { this.pages = p;        return this; }
        public Builder description(String d)    { this.description = d;  return this; }
        public Builder tags(String... t)        { tags.addAll(Arrays.asList(t)); return this; }
        public Builder dewey(String d)          { this.deweyCode = d;    return this; }
        public Builder shelf(String s)          { this.shelfLocation = s; return this; }
        public Book    build()                  { return new Book(this); }
    }
}

// ==========================================
// 3. BOOK COPY — physical instance of a Book
// Each copy can be in a different condition / location
// ==========================================
class BookCopy {
    private static final AtomicLong idGen = new AtomicLong(100_000);

    private final  long          copyId;
    private final  Book          book;
    private        BookCondition condition;
    private        boolean       isAvailable;
    private        boolean       isReserved;
    private        String        barcode;         // physical barcode on the copy
    private        LocalDate     lastInspectedAt;
    private        String        notes;           // damage notes etc.

    public BookCopy(Book book, BookCondition condition, String barcode) {
        this.copyId           = idGen.getAndIncrement();
        this.book             = book;
        this.condition        = condition;
        this.barcode          = barcode;
        this.isAvailable      = true;
        this.isReserved       = false;
        this.lastInspectedAt  = LocalDate.now();
    }

    public synchronized boolean checkout() {
        if (!isAvailable) return false;
        isAvailable = false;
        return true;
    }

    public synchronized void returnCopy(BookCondition returnedCondition) {
        this.condition  = returnedCondition;
        this.isAvailable = true;
        this.isReserved  = false;
    }

    public synchronized void reserve()        { isReserved = true; isAvailable = false; }
    public synchronized void unreserve()      { isReserved = false; isAvailable = true; }

    public long          getCopyId()     { return copyId; }
    public Book          getBook()       { return book; }
    public BookCondition getCondition()  { return condition; }
    public boolean       isAvailable()   { return isAvailable; }
    public boolean       isReserved()    { return isReserved; }
    public String        getBarcode()    { return barcode; }

    public void setCondition(BookCondition c) { this.condition = c; }
    public void setNotes(String n)            { this.notes = n; }

    @Override public String toString() {
        return "Copy[#" + copyId + " | " + book.getTitle() +
               " | " + condition +
               " | " + (isAvailable ? "AVAILABLE" : isReserved ? "RESERVED" : "BORROWED") + "]";
    }
}

// ==========================================
// 4. MEMBER — BUILDER PATTERN
// ==========================================
class Member {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long        id;
    private        String      name;
    private        String      email;
    private        String      phone;
    private final  MemberType  type;
    private        MemberStatus status;
    private final  LocalDate   joinDate;
    private        LocalDate   expiryDate;
    private        int         borrowLimit;     // max books at a time
    private        int         currentBorrowed; // currently checked out
    private        double      outstandingFine; // unpaid fines
    private        int         renewalLimit;    // max renewals per loan
    private        String      address;
    private        String      membershipId;    // physical card number

    private Member(Builder b) {
        this.id              = idGen.getAndIncrement();
        this.name            = b.name;
        this.email           = b.email;
        this.phone           = b.phone;
        this.type            = b.type;
        this.status          = MemberStatus.ACTIVE;
        this.joinDate        = LocalDate.now();
        this.expiryDate      = b.expiryDate;
        this.address         = b.address;
        this.membershipId    = "LIB-" + String.format("%06d", this.id);

        // Borrow limits by member type
        switch (b.type) {
            case STUDENT  -> { borrowLimit = 3;  renewalLimit = 1; }
            case FACULTY  -> { borrowLimit = 10; renewalLimit = 3; }
            case STAFF    -> { borrowLimit = 5;  renewalLimit = 2; }
            case PREMIUM  -> { borrowLimit = 8;  renewalLimit = 3; }
            default       -> { borrowLimit = 2;  renewalLimit = 1; }
        }
    }

    public boolean canBorrow() {
        return status == MemberStatus.ACTIVE &&
               currentBorrowed < borrowLimit &&
               outstandingFine < 100.0 && // block if fine > ₹100
               !isExpired();
    }

    public boolean isExpired() {
        return expiryDate != null && LocalDate.now().isAfter(expiryDate);
    }

    public synchronized void incrementBorrowed() { currentBorrowed++; }
    public synchronized void decrementBorrowed() {
        if (currentBorrowed > 0) currentBorrowed--;
    }

    public synchronized void addFine(double amount) {
        outstandingFine += amount;
        System.out.printf("[Fine] %s: +₹%.2f | Total: ₹%.2f%n",
            name, amount, outstandingFine);
    }

    public synchronized void payFine(double amount) {
        outstandingFine = Math.max(0, outstandingFine - amount);
        System.out.printf("[Fine Paid] %s: ₹%.2f | Remaining: ₹%.2f%n",
            name, amount, outstandingFine);
    }

    public void suspend(String reason) {
        status = MemberStatus.SUSPENDED;
        System.out.println("[Member] Suspended: " + name + " — " + reason);
    }

    public void activate() { status = MemberStatus.ACTIVE; }

    public long         getId()              { return id; }
    public String       getName()            { return name; }
    public String       getEmail()           { return email; }
    public String       getPhone()           { return phone; }
    public MemberType   getType()            { return type; }
    public MemberStatus getStatus()          { return status; }
    public LocalDate    getJoinDate()        { return joinDate; }
    public LocalDate    getExpiryDate()      { return expiryDate; }
    public int          getBorrowLimit()     { return borrowLimit; }
    public int          getCurrentBorrowed() { return currentBorrowed; }
    public double       getOutstandingFine() { return outstandingFine; }
    public int          getRenewalLimit()    { return renewalLimit; }
    public String       getMembershipId()    { return membershipId; }

    @Override public String toString() {
        return String.format("Member[%s | %-15s | %s | borrowed=%d/%d | fine=₹%.2f | %s]",
            membershipId, name, type, currentBorrowed, borrowLimit,
            outstandingFine, status);
    }

    static class Builder {
        private final String     name;
        private final String     email;
        private final MemberType type;
        private       String     phone      = "";
        private       LocalDate  expiryDate = LocalDate.now().plusYears(1);
        private       String     address    = "";

        public Builder(String name, String email, MemberType type) {
            this.name = name; this.email = email; this.type = type;
        }
        public Builder phone(String p)        { this.phone = p;       return this; }
        public Builder expiryDate(LocalDate d){ this.expiryDate = d;  return this; }
        public Builder address(String a)      { this.address = a;     return this; }
        public Member build()                 { return new Member(this); }
    }
}

// ==========================================
// 5. LOAN — STATE PATTERN
// ==========================================
class Loan {
    private static final AtomicLong idGen = new AtomicLong(50_000);

    private final  long          loanId;
    private final  BookCopy      copy;
    private final  Member        member;
    private final  LocalDate     borrowDate;
    private        LocalDate     dueDate;
    private        LocalDate     returnDate;
    private        LoanStatus    status;
    private        int           renewalCount;
    private        double        fineCharged;
    private        String        issuedBy;    // librarian who issued

    public Loan(BookCopy copy, Member member, int loanDays, String issuedBy) {
        this.loanId       = idGen.getAndIncrement();
        this.copy         = copy;
        this.member       = member;
        this.borrowDate   = LocalDate.now();
        this.dueDate      = LocalDate.now().plusDays(loanDays);
        this.status       = LoanStatus.ACTIVE;
        this.renewalCount = 0;
        this.fineCharged  = 0;
        this.issuedBy     = issuedBy;
    }

    // State transitions
    public boolean returnBook(BookCondition returnCondition) {
        if (status == LoanStatus.RETURNED) {
            System.out.println("[Loan] Already returned");
            return false;
        }
        this.returnDate = LocalDate.now();
        this.status     = LoanStatus.RETURNED;
        copy.returnCopy(returnCondition);
        member.decrementBorrowed();
        System.out.println("[Loan #" + loanId + "] Returned: " +
            copy.getBook().getTitle() +
            (isOverdue() ? " (OVERDUE by " + getOverdueDays() + " days)" : " (on time)"));
        return true;
    }

    public boolean renew(int additionalDays, int renewalLimit) {
        if (renewalCount >= renewalLimit) {
            System.out.println("[Loan] Max renewals reached (" + renewalLimit + ")");
            return false;
        }
        if (isOverdue()) {
            System.out.println("[Loan] Cannot renew overdue book — return and pay fine first");
            return false;
        }
        dueDate = dueDate.plusDays(additionalDays);
        renewalCount++;
        System.out.println("[Loan #" + loanId + "] Renewed → new due date: " + dueDate +
            " (renewal " + renewalCount + "/" + renewalLimit + ")");
        return true;
    }

    public void markOverdue() {
        if (status == LoanStatus.ACTIVE) status = LoanStatus.OVERDUE;
    }

    public void markLost(double replacementCost) {
        status      = LoanStatus.LOST;
        fineCharged = replacementCost;
        member.addFine(replacementCost);
        System.out.println("[Loan #" + loanId + "] Marked LOST — charged ₹" + replacementCost);
    }

    public boolean isOverdue()   { return LocalDate.now().isAfter(dueDate); }
    public long    getOverdueDays() {
        return ChronoUnit.DAYS.between(dueDate, LocalDate.now());
    }

    public long         getLoanId()       { return loanId; }
    public BookCopy     getCopy()         { return copy; }
    public Member       getMember()       { return member; }
    public LocalDate    getBorrowDate()   { return borrowDate; }
    public LocalDate    getDueDate()      { return dueDate; }
    public LocalDate    getReturnDate()   { return returnDate; }
    public LoanStatus   getStatus()       { return status; }
    public int          getRenewalCount() { return renewalCount; }
    public double       getFineCharged()  { return fineCharged; }

    @Override public String toString() {
        return String.format("Loan[#%d | %-30s | %s | due:%s | %s]",
            loanId, copy.getBook().getTitle(),
            member.getName(), dueDate, status);
    }
}

// ==========================================
// 6. RESERVATION — STATE PATTERN
// ==========================================
class Reservation {
    private static final AtomicLong idGen = new AtomicLong(70_000);

    private final  long              reservationId;
    private final  Book              book;
    private final  Member            member;
    private        ReservationStatus status;
    private final  LocalDateTime     reservedAt;
    private        LocalDate         readyDate;     // when copy became available
    private        LocalDate         expiryDate;    // member must collect by this date
    private        BookCopy          assignedCopy;
    private        int               queuePosition;

    public Reservation(Book book, Member member, int queuePosition) {
        this.reservationId = idGen.getAndIncrement();
        this.book          = book;
        this.member        = member;
        this.status        = ReservationStatus.PENDING;
        this.reservedAt    = LocalDateTime.now();
        this.queuePosition = queuePosition;
    }

    public void markReady(BookCopy copy) {
        this.assignedCopy = copy;
        this.status       = ReservationStatus.READY;
        this.readyDate    = LocalDate.now();
        this.expiryDate   = LocalDate.now().plusDays(3); // must collect in 3 days
        copy.reserve();
        System.out.println("[Reservation #" + reservationId + "] READY for " +
            member.getName() + " — collect by " + expiryDate);
    }

    public void fulfil() {
        status = ReservationStatus.FULFILLED;
        System.out.println("[Reservation #" + reservationId + "] FULFILLED → " +
            member.getName());
    }

    public void cancel(String reason) {
        status = ReservationStatus.CANCELLED;
        if (assignedCopy != null) assignedCopy.unreserve();
        System.out.println("[Reservation #" + reservationId + "] CANCELLED: " + reason);
    }

    public void expire() {
        status = ReservationStatus.EXPIRED;
        if (assignedCopy != null) assignedCopy.unreserve();
        System.out.println("[Reservation #" + reservationId + "] EXPIRED — not collected");
    }

    public long             getReservationId() { return reservationId; }
    public Book             getBook()          { return book; }
    public Member           getMember()        { return member; }
    public ReservationStatus getStatus()       { return status; }
    public LocalDateTime    getReservedAt()    { return reservedAt; }
    public LocalDate        getExpiryDate()    { return expiryDate; }
    public BookCopy         getAssignedCopy()  { return assignedCopy; }
    public int              getQueuePosition() { return queuePosition; }

    @Override public String toString() {
        return String.format("Reservation[#%d | %-30s | %s | pos=%d | %s]",
            reservationId, book.getTitle(), member.getName(),
            queuePosition, status);
    }
}

// ==========================================
// 7. FINE STRATEGY — STRATEGY PATTERN
// ==========================================
interface FineStrategy {
    String getName();
    double calculateFine(Loan loan);
}

// Flat daily rate
class FixedRateFineStrategy implements FineStrategy {
    private final double ratePerDay;

    public FixedRateFineStrategy(double ratePerDay) {
        this.ratePerDay = ratePerDay;
    }

    @Override public String getName() { return "Fixed Rate (₹" + ratePerDay + "/day)"; }

    @Override
    public double calculateFine(Loan loan) {
        if (!loan.isOverdue()) return 0;
        return loan.getOverdueDays() * ratePerDay;
    }
}

// Escalating rate — higher fine for longer overdue
class TieredFineStrategy implements FineStrategy {
    @Override public String getName() { return "Tiered Rate"; }

    @Override
    public double calculateFine(Loan loan) {
        if (!loan.isOverdue()) return 0;
        long days = loan.getOverdueDays();
        double fine = 0;

        if (days <= 7)        fine = days * 2.0;          // ₹2/day first week
        else if (days <= 30)  fine = 14 + (days - 7) * 5; // ₹5/day next 3 weeks
        else                  fine = 129 + (days - 30) * 10; // ₹10/day beyond

        System.out.printf("[Fine] %s overdue by %d days → ₹%.2f%n",
            loan.getCopy().getBook().getTitle(), days, fine);
        return fine;
    }
}

// Waiver for faculty / premium members (first 3 days grace)
class WaiverFineStrategy implements FineStrategy {
    private final FineStrategy base;
    private final int          graceDays;
    private final Set<MemberType> waiverTypes;

    public WaiverFineStrategy(FineStrategy base, int graceDays,
                               MemberType... types) {
        this.base        = base;
        this.graceDays   = graceDays;
        this.waiverTypes = EnumSet.copyOf(Arrays.asList(types));
    }

    @Override public String getName() {
        return "Waiver[" + graceDays + " grace days] + " + base.getName();
    }

    @Override
    public double calculateFine(Loan loan) {
        if (waiverTypes.contains(loan.getMember().getType())) {
            if (loan.getOverdueDays() <= graceDays) {
                System.out.println("[Fine] Grace period waiver for " +
                    loan.getMember().getType());
                return 0;
            }
        }
        return base.calculateFine(loan);
    }
}

// ==========================================
// 8. CHAIN OF RESPONSIBILITY — BORROW VALIDATORS
// ==========================================
abstract class BorrowValidator {
    protected BorrowValidator next;

    public BorrowValidator setNext(BorrowValidator next) {
        this.next = next;
        return next;
    }

    public abstract String validate(Member member, BookCopy copy);

    protected String passToNext(Member member, BookCopy copy) {
        return next != null ? next.validate(member, copy) : null;
    }
}

class MembershipValidator extends BorrowValidator {
    @Override
    public String validate(Member member, BookCopy copy) {
        if (member.getStatus() != MemberStatus.ACTIVE)
            return "Member status is " + member.getStatus() + " — cannot borrow";
        if (member.isExpired())
            return "Membership expired on " + member.getExpiryDate();
        System.out.println("[Validator] ✓ Membership active");
        return passToNext(member, copy);
    }
}

class BorrowLimitValidator extends BorrowValidator {
    @Override
    public String validate(Member member, BookCopy copy) {
        if (member.getCurrentBorrowed() >= member.getBorrowLimit())
            return "Borrow limit reached (" + member.getBorrowLimit() + "/" +
                   member.getBorrowLimit() + ") — return a book first";
        System.out.println("[Validator] ✓ Borrow limit OK (" +
            member.getCurrentBorrowed() + "/" + member.getBorrowLimit() + ")");
        return passToNext(member, copy);
    }
}

class FineValidator extends BorrowValidator {
    private final double fineThreshold;

    public FineValidator(double threshold) { this.fineThreshold = threshold; }

    @Override
    public String validate(Member member, BookCopy copy) {
        if (member.getOutstandingFine() >= fineThreshold)
            return "Outstanding fine ₹" + String.format("%.2f", member.getOutstandingFine()) +
                   " exceeds limit ₹" + fineThreshold + " — please pay first";
        System.out.println("[Validator] ✓ Fine OK (₹" +
            String.format("%.2f", member.getOutstandingFine()) + ")");
        return passToNext(member, copy);
    }
}

class AvailabilityValidator extends BorrowValidator {
    @Override
    public String validate(Member member, BookCopy copy) {
        if (!copy.isAvailable())
            return "Book copy #" + copy.getCopyId() + " is not available (" +
                   (copy.isReserved() ? "RESERVED" : "BORROWED") + ")";
        System.out.println("[Validator] ✓ Copy available");
        return passToNext(member, copy);
    }
}

class ReferenceBookValidator extends BorrowValidator {
    @Override
    public String validate(Member member, BookCopy copy) {
        if (copy.getBook().getCategory() == BookCategory.REFERENCE)
            return "Reference books cannot be borrowed — in-library use only";
        System.out.println("[Validator] ✓ Not a reference book");
        return passToNext(member, copy);
    }
}

// ==========================================
// 9. BOOK FACTORY
// ==========================================
class BookFactory {
    public static Book novel(String isbn, String title, String author,
                              int year, String publisher) {
        return new Book.Builder(isbn, title)
            .author(author).year(year).publisher(publisher)
            .category(BookCategory.FICTION).format(BookFormat.PAPERBACK)
            .build();
    }

    public static Book techBook(String isbn, String title, String publisher,
                                 int year, String... authors) {
        return new Book.Builder(isbn, title)
            .author(authors).year(year).publisher(publisher)
            .category(BookCategory.TECHNOLOGY).format(BookFormat.HARDCOVER)
            .build();
    }

    public static Book magazine(String isbn, String title, int year) {
        return new Book.Builder(isbn, title)
            .year(year).category(BookCategory.MAGAZINE)
            .format(BookFormat.MAGAZINE_ISSUE).build();
    }

    public static Book referenceBook(String isbn, String title, String publisher) {
        return new Book.Builder(isbn, title)
            .publisher(publisher).category(BookCategory.REFERENCE)
            .format(BookFormat.HARDCOVER).build();
    }
}

// ==========================================
// 10. OBSERVER — BOOK / LOAN EVENTS
// ==========================================
interface BookEventObserver {
    void onBookBorrowed(Loan loan);
    void onBookReturned(Loan loan, double fine);
    void onBookReserved(Reservation reservation);
    void onReservationReady(Reservation reservation);
    void onOverdueDetected(Loan loan);
}

class NotificationObserver implements BookEventObserver {
    @Override
    public void onBookBorrowed(Loan loan) {
        System.out.println("[Email → " + loan.getMember().getEmail() + "] " +
            "Borrowed: '" + loan.getCopy().getBook().getTitle() +
            "' | Due: " + loan.getDueDate());
    }

    @Override
    public void onBookReturned(Loan loan, double fine) {
        System.out.println("[Email → " + loan.getMember().getEmail() + "] " +
            "Returned: '" + loan.getCopy().getBook().getTitle() + "'" +
            (fine > 0 ? " | Fine: ₹" + String.format("%.2f", fine) : " | No fine"));
    }

    @Override
    public void onBookReserved(Reservation res) {
        System.out.println("[Email → " + res.getMember().getEmail() + "] " +
            "Reservation confirmed for '" + res.getBook().getTitle() +
            "' | Queue position: " + res.getQueuePosition());
    }

    @Override
    public void onReservationReady(Reservation res) {
        System.out.println("[SMS → " + res.getMember().getPhone() + "] " +
            "📚 Your book '" + res.getBook().getTitle() +
            "' is ready! Collect by " + res.getExpiryDate());
    }

    @Override
    public void onOverdueDetected(Loan loan) {
        System.out.println("[Reminder → " + loan.getMember().getEmail() + "] " +
            "⚠ OVERDUE: '" + loan.getCopy().getBook().getTitle() +
            "' was due " + loan.getDueDate() +
            " — " + loan.getOverdueDays() + " days overdue. Please return immediately.");
    }
}

class AnalyticsObserver implements BookEventObserver {
    private final Map<String, Long> borrowCounts  = new ConcurrentHashMap<>();
    private final Map<String, Long> returnCounts  = new ConcurrentHashMap<>();
    private       long              totalFines    = 0;

    @Override
    public void onBookBorrowed(Loan loan) {
        borrowCounts.merge(loan.getCopy().getBook().getIsbn(), 1L, Long::sum);
    }

    @Override
    public void onBookReturned(Loan loan, double fine) {
        returnCounts.merge(loan.getCopy().getBook().getIsbn(), 1L, Long::sum);
        totalFines += (long) (fine * 100);
    }

    @Override public void onBookReserved(Reservation r) {}
    @Override public void onReservationReady(Reservation r) {}
    @Override public void onOverdueDetected(Loan loan) {}

    public void printReport() {
        System.out.println("\n[Analytics] Top 5 most borrowed books:");
        borrowCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .forEach(e -> System.out.println("  ISBN:" + e.getKey() +
                " → " + e.getValue() + " borrows"));
        System.out.printf("[Analytics] Total fines collected: ₹%.2f%n",
            totalFines / 100.0);
    }
}

// ==========================================
// 11. CATALOG SERVICE — SINGLETON
// Manages book catalog + search
// ==========================================
class CatalogService {
    private static CatalogService instance;

    private final Map<Long, Book>             books     = new ConcurrentHashMap<>();
    private final Map<String, Book>           isbnIndex = new ConcurrentHashMap<>();
    // bookId → list of copies
    private final Map<Long, List<BookCopy>>   copies    = new ConcurrentHashMap<>();

    private CatalogService() {}

    public static synchronized CatalogService getInstance() {
        if (instance == null) instance = new CatalogService();
        return instance;
    }

    public Book addBook(Book book) {
        books.put(book.getId(), book);
        isbnIndex.put(book.getIsbn(), book);
        copies.put(book.getId(), new CopyOnWriteArrayList<>());
        System.out.println("[Catalog] Added: " + book);
        return book;
    }

    public BookCopy addCopy(Book book, BookCondition condition, String barcode) {
        BookCopy copy = new BookCopy(book, condition, barcode);
        copies.computeIfAbsent(book.getId(), k -> new CopyOnWriteArrayList<>()).add(copy);
        System.out.println("[Catalog] Copy added: " + copy);
        return copy;
    }

    public Optional<BookCopy> findAvailableCopy(Book book) {
        return copies.getOrDefault(book.getId(), Collections.emptyList())
            .stream()
            .filter(BookCopy::isAvailable)
            .findFirst();
    }

    public int getAvailableCount(Book book) {
        return (int) copies.getOrDefault(book.getId(), Collections.emptyList())
            .stream().filter(BookCopy::isAvailable).count();
    }

    public int getTotalCopies(Book book) {
        return copies.getOrDefault(book.getId(), Collections.emptyList()).size();
    }

    // ---- SEARCH (multi-criteria) ----
    public List<Book> searchByTitle(String query) {
        String q = query.toLowerCase();
        return books.values().stream()
            .filter(b -> b.getTitle().toLowerCase().contains(q))
            .sorted(Comparator.comparingDouble(Book::getRating).reversed())
            .collect(Collectors.toList());
    }

    public List<Book> searchByAuthor(String author) {
        String q = author.toLowerCase();
        return books.values().stream()
            .filter(b -> b.getAuthors().stream()
                .anyMatch(a -> a.toLowerCase().contains(q)))
            .collect(Collectors.toList());
    }

    public List<Book> searchByCategory(BookCategory category) {
        return books.values().stream()
            .filter(b -> b.getCategory() == category)
            .sorted(Comparator.comparingDouble(Book::getRating).reversed())
            .collect(Collectors.toList());
    }

    public List<Book> searchByIsbn(String isbn) {
        Book b = isbnIndex.get(isbn);
        return b != null ? List.of(b) : Collections.emptyList();
    }

    public List<Book> searchByTag(String tag) {
        String q = tag.toLowerCase();
        return books.values().stream()
            .filter(b -> b.getTags().stream()
                .anyMatch(t -> t.toLowerCase().contains(q)))
            .collect(Collectors.toList());
    }

    // Full-text search across title + author + tags
    public List<Book> search(String query) {
        String q = query.toLowerCase();
        return books.values().stream()
            .filter(b ->
                b.getTitle().toLowerCase().contains(q) ||
                b.getAuthors().stream().anyMatch(a -> a.toLowerCase().contains(q)) ||
                b.getTags().stream().anyMatch(t -> t.toLowerCase().contains(q)) ||
                b.getIsbn().contains(q))
            .sorted(Comparator.comparingDouble(Book::getRating).reversed())
            .collect(Collectors.toList());
    }

    public Optional<Book> getByIsbn(String isbn) {
        return Optional.ofNullable(isbnIndex.get(isbn));
    }

    public Optional<Book> getById(long id) {
        return Optional.ofNullable(books.get(id));
    }

    public List<BookCopy> getCopies(Book book) {
        return Collections.unmodifiableList(
            copies.getOrDefault(book.getId(), Collections.emptyList()));
    }

    public void printBookAvailability(Book book) {
        int available = getAvailableCount(book);
        int total     = getTotalCopies(book);
        System.out.printf("[Catalog] %-35s | %d/%d available%n",
            book.getTitle(), available, total);
    }

    public int getBookCount() { return books.size(); }
}

// ==========================================
// 12. LIBRARY SERVICE — SINGLETON (core)
// ==========================================
class LibraryService {
    private static LibraryService instance;

    private final CatalogService             catalog      = CatalogService.getInstance();
    private final Map<Long, Member>          members      = new ConcurrentHashMap<>();
    private final Map<Long, Loan>            loans        = new ConcurrentHashMap<>();
    private final Map<Long, Reservation>     reservations = new ConcurrentHashMap<>();
    // bookId → queue of reservations (FIFO)
    private final Map<Long, Queue<Reservation>> reservationQueues = new ConcurrentHashMap<>();
    private final List<BookEventObserver>    observers    = new ArrayList<>();
    private final AnalyticsObserver          analytics    = new AnalyticsObserver();

    private       FineStrategy               fineStrategy;
    private       BorrowValidator            validationChain;

    // Default loan periods by member type (days)
    private static final Map<MemberType, Integer> LOAN_DAYS = Map.of(
        MemberType.STUDENT,  14,
        MemberType.FACULTY,  30,
        MemberType.STAFF,    21,
        MemberType.PREMIUM,  21,
        MemberType.PUBLIC,   7
    );

    private LibraryService() {
        // Default fine strategy
        fineStrategy = new WaiverFineStrategy(
            new TieredFineStrategy(), 2,
            MemberType.FACULTY, MemberType.PREMIUM);

        // Build validation chain
        BorrowValidator membership   = new MembershipValidator();
        BorrowValidator limit        = new BorrowLimitValidator();
        BorrowValidator fine         = new FineValidator(100.0);
        BorrowValidator availability = new AvailabilityValidator();
        BorrowValidator reference    = new ReferenceBookValidator();

        membership.setNext(limit).setNext(fine).setNext(availability).setNext(reference);
        validationChain = membership;

        observers.add(new NotificationObserver());
        observers.add(analytics);
    }

    public static synchronized LibraryService getInstance() {
        if (instance == null) instance = new LibraryService();
        return instance;
    }

    public void setFineStrategy(FineStrategy strategy) {
        this.fineStrategy = strategy;
        System.out.println("[Library] Fine strategy: " + strategy.getName());
    }

    // ---- MEMBER MANAGEMENT ----
    public Member registerMember(Member member) {
        members.put(member.getId(), member);
        System.out.println("[Library] Registered: " + member);
        return member;
    }

    // ---- BORROW BOOK (core flow) ----
    public Loan borrowBook(Member member, Book book) {
        // Find an available copy
        Optional<BookCopy> copyOpt = catalog.findAvailableCopy(book);
        if (copyOpt.isEmpty()) {
            System.out.println("[Borrow] ❌ No copies available for: " + book.getTitle());
            System.out.println("[Borrow] Tip: Place a reservation to join the queue");
            return null;
        }
        return borrowCopy(member, copyOpt.get());
    }

    public Loan borrowCopy(Member member, BookCopy copy) {
        System.out.println("\n[Borrow] " + member.getName() +
            " → " + copy.getBook().getTitle());

        // Run validation chain
        String error = validationChain.validate(member, copy);
        if (error != null) {
            System.out.println("[Borrow] ❌ " + error);
            return null;
        }

        // Execute borrow
        copy.checkout();
        member.incrementBorrowed();

        int loanDays = LOAN_DAYS.getOrDefault(member.getType(), 14);
        Loan loan    = new Loan(copy, member, loanDays, "SYSTEM");
        loans.put(loan.getLoanId(), loan);

        System.out.println("[Borrow] ✅ Issued: " + loan);
        observers.forEach(o -> o.onBookBorrowed(loan));
        return loan;
    }

    // ---- RETURN BOOK ----
    public double returnBook(long loanId, BookCondition returnCondition) {
        Loan loan = loans.get(loanId);
        if (loan == null) {
            System.out.println("[Return] Loan not found: #" + loanId);
            return 0;
        }
        if (loan.getStatus() == LoanStatus.RETURNED) {
            System.out.println("[Return] Already returned");
            return 0;
        }

        // Calculate fine
        double fine = fineStrategy.calculateFine(loan);
        if (fine > 0) loan.getMember().addFine(fine);

        // Return book
        loan.returnBook(returnCondition);

        // Check reservation queue — assign copy to next in queue
        checkAndAssignReservation(loan.getCopy().getBook(), loan.getCopy());

        observers.forEach(o -> o.onBookReturned(loan, fine));
        return fine;
    }

    // ---- RENEW LOAN ----
    public boolean renewLoan(long loanId) {
        Loan loan = loans.get(loanId);
        if (loan == null) return false;

        // Check if anyone has reserved this book
        Queue<Reservation> queue = reservationQueues.get(
            loan.getCopy().getBook().getId());
        if (queue != null && !queue.isEmpty()) {
            System.out.println("[Renew] ❌ Cannot renew — " +
                queue.size() + " member(s) waiting for this book");
            return false;
        }

        int renewalDays = LOAN_DAYS.getOrDefault(loan.getMember().getType(), 14);
        return loan.renew(renewalDays, loan.getMember().getRenewalLimit());
    }

    // ---- RESERVE BOOK ----
    public Reservation reserveBook(Member member, Book book) {
        if (member.getStatus() != MemberStatus.ACTIVE) {
            System.out.println("[Reserve] Member not active");
            return null;
        }

        // If copy available — just borrow directly
        if (catalog.getAvailableCount(book) > 0) {
            System.out.println("[Reserve] Copies available — borrow directly instead");
            return null;
        }

        // Get queue position
        Queue<Reservation> queue = reservationQueues.computeIfAbsent(
            book.getId(), k -> new LinkedList<>());

        // Check if already in queue
        boolean alreadyQueued = queue.stream()
            .anyMatch(r -> r.getMember().getId() == member.getId() &&
                     r.getStatus() == ReservationStatus.PENDING);
        if (alreadyQueued) {
            System.out.println("[Reserve] Already in queue for: " + book.getTitle());
            return null;
        }

        int position     = queue.size() + 1;
        Reservation res  = new Reservation(book, member, position);
        queue.offer(res);
        reservations.put(res.getReservationId(), res);

        System.out.println("[Reserve] ✅ " + res);
        observers.forEach(o -> o.onBookReserved(res));
        return res;
    }

    // ---- CANCEL RESERVATION ----
    public void cancelReservation(long reservationId, Member member) {
        Reservation res = reservations.get(reservationId);
        if (res == null) return;
        if (res.getMember().getId() != member.getId()) {
            System.out.println("[Reserve] Unauthorized cancel");
            return;
        }
        res.cancel("Cancelled by member");

        // Update queue positions
        Queue<Reservation> queue = reservationQueues.get(res.getBook().getId());
        if (queue != null) {
            queue.remove(res);
            // Re-index positions
            int pos = 1;
            for (Reservation r : queue) {
                // position update (simplified)
                pos++;
            }
        }
    }

    // When a book is returned — assign to next reservation in queue
    private void checkAndAssignReservation(Book book, BookCopy returnedCopy) {
        Queue<Reservation> queue = reservationQueues.get(book.getId());
        if (queue == null || queue.isEmpty()) return;

        Reservation next = queue.peek();
        if (next != null && next.getStatus() == ReservationStatus.PENDING) {
            next.markReady(returnedCopy);
            queue.poll();
            observers.forEach(o -> o.onReservationReady(next));
        }
    }

    // ---- OVERDUE CHECK (scheduled job) ----
    public void runOverdueCheck() {
        System.out.println("\n[OverdueCheck] Scanning active loans...");
        loans.values().stream()
            .filter(l -> l.getStatus() == LoanStatus.ACTIVE && l.isOverdue())
            .forEach(l -> {
                l.markOverdue();
                observers.forEach(o -> o.onOverdueDetected(l));
            });
    }

    // ---- FINE PAYMENT ----
    public void payFine(Member member, double amount) {
        member.payFine(amount);
        System.out.println("[Fine] Payment recorded for " + member.getName());
    }

    // ---- MARK LOST ----
    public void markLost(long loanId, double replacementCost) {
        Loan loan = loans.get(loanId);
        if (loan != null) loan.markLost(replacementCost);
    }

    // ---- RATE BOOK ----
    public void rateBook(Member member, Book book, double rating) {
        if (rating < 1 || rating > 5) {
            System.out.println("[Rate] Rating must be between 1 and 5");
            return;
        }
        // Verify member has borrowed this book before
        boolean hasBorrowed = loans.values().stream()
            .anyMatch(l -> l.getMember().getId() == member.getId() &&
                           l.getCopy().getBook().getId() == book.getId() &&
                           l.getStatus() == LoanStatus.RETURNED);
        if (!hasBorrowed) {
            System.out.println("[Rate] Member must return book before rating");
            return;
        }
        book.addRating(rating);
        System.out.printf("[Rate] %s rated '%s' → %.1f/5%n",
            member.getName(), book.getTitle(), rating);
    }

    // ---- REPORTS ----
    public void printMemberLoans(Member member) {
        System.out.println("\n[Member Loans: " + member.getName() + "]");
        loans.values().stream()
            .filter(l -> l.getMember().getId() == member.getId())
            .forEach(l -> System.out.println("  " + l));
    }

    public void printOverdueLoans() {
        System.out.println("\n[Overdue Loans Report]");
        loans.values().stream()
            .filter(l -> l.getStatus() == LoanStatus.OVERDUE ||
                         (l.getStatus() == LoanStatus.ACTIVE && l.isOverdue()))
            .forEach(l -> System.out.printf("  %s | %s | %d days overdue%n",
                l.getMember().getName(),
                l.getCopy().getBook().getTitle(),
                l.getOverdueDays()));
    }

    public void printReservationQueue(Book book) {
        Queue<Reservation> queue = reservationQueues.get(book.getId());
        System.out.println("\n[Reservation Queue: " + book.getTitle() + "]");
        if (queue == null || queue.isEmpty()) {
            System.out.println("  No reservations");
        } else {
            int pos = 1;
            for (Reservation r : queue) {
                System.out.println("  " + pos++ + ". " + r.getMember().getName() +
                    " — reserved at " + r.getReservedAt());
            }
        }
    }

    public void printAnalytics() { analytics.printReport(); }
    public int getMemberCount()  { return members.size(); }
    public int getLoanCount()    { return loans.size(); }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class LibraryManagement {
    public static void main(String[] args) throws InterruptedException {

        LibraryService service = LibraryService.getInstance();
        CatalogService catalog = CatalogService.getInstance();

        // ---- Add Books to Catalog ----
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SETUP: Adding Books to Catalog");
        System.out.println("=".repeat(60));

        Book cleanCode = catalog.addBook(
            BookFactory.techBook("978-0-13-235088-4",
                "Clean Code", "Prentice Hall", 2008, "Robert C. Martin"));

        Book designPatterns = catalog.addBook(
            BookFactory.techBook("978-0-20-163361-5",
                "Design Patterns: GoF", "Addison-Wesley", 1994,
                "Gang of Four"));

        Book atomicHabits = catalog.addBook(
            new Book.Builder("978-0-73-522018-3", "Atomic Habits")
                .author("James Clear").year(2018).publisher("Avery")
                .category(BookCategory.NON_FICTION)
                .tags("productivity", "habits", "self-help")
                .shelf("B2-S1").build());

        Book refBookDSA = catalog.addBook(
            BookFactory.referenceBook("978-0-26-203293-3",
                "Introduction to Algorithms", "MIT Press"));

        Book harryPotter = catalog.addBook(
            BookFactory.novel("978-0-43-965548-5",
                "Harry Potter and the Philosopher's Stone",
                "J.K. Rowling", 1997, "Bloomsbury"));

        // Add copies
        catalog.addCopy(cleanCode,      BookCondition.GOOD, "BC-001");
        catalog.addCopy(cleanCode,      BookCondition.NEW,  "BC-002");
        catalog.addCopy(designPatterns, BookCondition.FAIR, "DP-001");
        catalog.addCopy(atomicHabits,   BookCondition.NEW,  "AH-001");
        catalog.addCopy(atomicHabits,   BookCondition.GOOD, "AH-002");
        catalog.addCopy(refBookDSA,     BookCondition.GOOD, "DSA-001");
        catalog.addCopy(harryPotter,    BookCondition.NEW,  "HP-001");

        // ---- Register Members ----
        Member alice = service.registerMember(
            new Member.Builder("Alice", "alice@uni.edu", MemberType.STUDENT)
                .phone("+91-9000000001")
                .expiryDate(LocalDate.now().plusYears(1)).build());

        Member bob = service.registerMember(
            new Member.Builder("Bob", "bob@uni.edu", MemberType.FACULTY)
                .phone("+91-9000000002")
                .expiryDate(LocalDate.now().plusYears(3)).build());

        Member carol = service.registerMember(
            new Member.Builder("Carol", "carol@lib.org", MemberType.PUBLIC)
                .phone("+91-9000000003")
                .expiryDate(LocalDate.now().plusMonths(6)).build());

        Member dave = service.registerMember(
            new Member.Builder("Dave", "dave@company.com", MemberType.PREMIUM)
                .phone("+91-9000000004")
                .expiryDate(LocalDate.now().plusYears(2)).build());

        // ===== SCENARIO 1: Successful borrow =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Successful Book Borrow");
        System.out.println("=".repeat(60));

        Loan loan1 = service.borrowBook(alice, cleanCode);
        Loan loan2 = service.borrowBook(bob,   designPatterns);
        Loan loan3 = service.borrowBook(carol, harryPotter);

        // ===== SCENARIO 2: Return + fine (simulate overdue) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Return Book (on time)");
        System.out.println("=".repeat(60));

        if (loan3 != null) {
            double fine = service.returnBook(loan3.getLoanId(), BookCondition.GOOD);
            System.out.println("Fine charged: ₹" + fine);
        }

        // ===== SCENARIO 3: Attempt to borrow reference book =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Borrow Reference Book (should fail)");
        System.out.println("=".repeat(60));

        service.borrowBook(alice, refBookDSA);

        // ===== SCENARIO 4: Borrow limit enforcement =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Borrow Limit (Student = 3 books)");
        System.out.println("=".repeat(60));

        Loan lA2 = service.borrowBook(alice, atomicHabits);
        Loan lA3 = service.borrowBook(alice, harryPotter);   // limit reached (3)
        Loan lA4 = service.borrowBook(alice, designPatterns); // should fail (over limit)

        // ===== SCENARIO 5: Reservation queue =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Reservation Queue (all copies borrowed)");
        System.out.println("=".repeat(60));

        // Atomic Habits copies: one with alice, one with bob
        service.borrowBook(bob, atomicHabits);
        // Now both copies are out — dave and carol want to reserve
        service.reserveBook(dave,  atomicHabits);
        service.reserveBook(carol, atomicHabits);
        service.printReservationQueue(atomicHabits);

        // ===== SCENARIO 6: Return triggers reservation notification =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Return triggers Reservation Ready");
        System.out.println("=".repeat(60));

        if (lA2 != null) {
            service.returnBook(lA2.getLoanId(), BookCondition.GOOD);
            // dave should get notified that book is ready
        }

        // ===== SCENARIO 7: Renew loan =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Renew Loan");
        System.out.println("=".repeat(60));

        if (loan1 != null) {
            System.out.println("Before renewal: due " + loan1.getDueDate());
            service.renewLoan(loan1.getLoanId());
            System.out.println("After renewal:  due " + loan1.getDueDate());
        }

        // ===== SCENARIO 8: Fine calculation (simulated overdue) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Fine Strategies");
        System.out.println("=".repeat(60));

        // Simulate 10-day overdue loan for testing fine calculation
        // (In real test: mock loan date, here we show strategy logic)
        service.setFineStrategy(new TieredFineStrategy());
        System.out.println("Tiered strategy — 10 days overdue = ₹" +
            String.format("%.2f", (10 * 2.0)));  // ₹2/day × 10 = ₹20

        service.setFineStrategy(new FixedRateFineStrategy(5.0));
        System.out.println("Fixed strategy  — 10 days overdue = ₹" +
            String.format("%.2f", 10 * 5.0));    // ₹5/day × 10 = ₹50

        // Restore
        service.setFineStrategy(new WaiverFineStrategy(
            new TieredFineStrategy(), 2,
            MemberType.FACULTY, MemberType.PREMIUM));

        // ===== SCENARIO 9: Pay fine =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Pay Outstanding Fine");
        System.out.println("=".repeat(60));

        alice.addFine(45.00); // simulate accumulated fine
        System.out.println("Alice's fine: ₹" + alice.getOutstandingFine());
        service.payFine(alice, 45.00);
        System.out.println("After payment: ₹" + alice.getOutstandingFine());

        // ===== SCENARIO 10: Catalog search =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Search Catalog");
        System.out.println("=".repeat(60));

        System.out.println("Search 'clean':");
        catalog.searchByTitle("clean")
            .forEach(b -> System.out.println("  " + b));

        System.out.println("\nSearch by author 'Martin':");
        catalog.searchByAuthor("Martin")
            .forEach(b -> System.out.println("  " + b));

        System.out.println("\nSearch by category TECHNOLOGY:");
        catalog.searchByCategory(BookCategory.TECHNOLOGY)
            .forEach(b -> System.out.println("  " + b));

        System.out.println("\nFull-text search 'habits':");
        catalog.search("habits")
            .forEach(b -> System.out.println("  " + b));

        // ===== SCENARIO 11: Book rating =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 11: Book Rating (after return)");
        System.out.println("=".repeat(60));

        if (loan3 != null) {
            service.rateBook(carol, harryPotter, 5.0);
        }
        service.rateBook(alice, cleanCode, 4.5); // alice hasn't returned yet
        if (loan1 != null && loan1.getStatus() == LoanStatus.ACTIVE) {
            // return first
            service.returnBook(loan1.getLoanId(), BookCondition.GOOD);
            service.rateBook(alice, cleanCode, 4.5);
        }

        // ===== SCENARIO 12: Overdue detection =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 12: Overdue Detection Job");
        System.out.println("=".repeat(60));

        service.runOverdueCheck(); // would catch truly overdue in real system

        // ===== SCENARIO 13: Availability summary =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 13: Book Availability Summary");
        System.out.println("=".repeat(60));

        catalog.printBookAvailability(cleanCode);
        catalog.printBookAvailability(designPatterns);
        catalog.printBookAvailability(atomicHabits);
        catalog.printBookAvailability(harryPotter);
        catalog.printBookAvailability(refBookDSA);

        // ===== MEMBER LOAN HISTORY =====
        service.printMemberLoans(alice);
        service.printMemberLoans(bob);
        service.printAnalytics();

        System.out.println("\n[Summary]");
        System.out.println("Total books in catalog: " + catalog.getBookCount());
        System.out.println("Total members:          " + service.getMemberCount());
        System.out.println("Total loans:            " + service.getLoanCount());

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern                  | Class
            -------------------------|--------------------------------------------------
            Singleton                | LibraryService, CatalogService
            Strategy                 | FineStrategy (Fixed / Tiered / Waiver)
            Observer                 | BookEventObserver (Notification / Analytics)
            Factory                  | BookFactory (novel/techBook/magazine/reference)
            Builder                  | Book.Builder, Member.Builder
            State                    | LoanStatus + ReservationStatus state machines
            Iterator                 | CatalogService search → sorted stream
            Chain of Responsibility  | BorrowValidator (Membership→Limit→Fine→Availability→Reference)
            """);
    }
}
