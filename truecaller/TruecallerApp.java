import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// TRUECALLER LLD — CALLER ID + SPAM DETECTION
// Patterns:
//   Singleton  — CallerIDService, SpamService
//   Strategy   — SpamDetectionStrategy (rule / community / ML-score)
//   Observer   — CallEventObserver (spam alert, analytics, block trigger)
//   Factory    — ProfileFactory (personal / business / unknown)
//   Builder    — UserProfile, SpamReport construction
//   State      — SpamStatus (CLEAN→SUSPICIOUS→SPAM→BLOCKED)
//   Chain of Responsibility — LookupChain (privacy→block→spam→identity)
//   Decorator  — BusinessProfile wraps UserProfile with extra attributes
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum SpamStatus     { CLEAN, SUSPICIOUS, LIKELY_SPAM, CONFIRMED_SPAM, BLOCKED }
enum SpamCategory   { TELEMARKETING, FRAUD, ROBOCALL, DEBT_COLLECTION,
                      SURVEY, POLITICAL, UNKNOWN }
enum ProfileType    { PERSONAL, BUSINESS, UNKNOWN_CALLER, UNREGISTERED }
enum PrivacyLevel   { PUBLIC, CONTACTS_ONLY, PRIVATE }
enum CallDirection  { INCOMING, OUTGOING, MISSED }
enum ReportReason   { SPAM, FRAUD, HARASSMENT, WRONG_NUMBER, OTHER }
enum ContactSource  { MANUAL, PHONE_BOOK_SYNC, TRUECALLER_DB, BUSINESS }

// ==========================================
// 2. PHONE NUMBER — VALUE OBJECT
// ==========================================
class PhoneNumber {
    private final String countryCode;  // +91, +1 etc.
    private final String number;       // 10-digit national number
    private final String normalized;   // E.164 format: +919876543210

    public PhoneNumber(String raw) {
        raw = raw.replaceAll("[\\s\\-()]", "");
        if (raw.startsWith("+")) {
            // already has country code
            this.normalized   = raw;
            this.countryCode  = raw.substring(0, raw.length() > 12 ? 3 : 2);
            this.number       = raw.substring(countryCode.length());
        } else if (raw.startsWith("0")) {
            // local format with leading 0
            this.countryCode  = "+91"; // default India
            this.number       = raw.substring(1);
            this.normalized   = countryCode + this.number;
        } else {
            this.countryCode  = "+91";
            this.number       = raw;
            this.normalized   = countryCode + raw;
        }
    }

    public static PhoneNumber of(String number) { return new PhoneNumber(number); }

    public boolean isValid() {
        return normalized.matches("\\+[1-9]\\d{7,14}");
    }

    public String getCountryCode() { return countryCode; }
    public String getNumber()      { return number; }
    public String getNormalized()  { return normalized; }

    @Override public boolean equals(Object o) {
        return o instanceof PhoneNumber p && normalized.equals(p.normalized);
    }
    @Override public int hashCode()  { return normalized.hashCode(); }
    @Override public String toString(){ return normalized; }
}

// ==========================================
// 3. USER PROFILE — BUILDER PATTERN
// Every registered Truecaller user
// ==========================================
class UserProfile {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long          userId;
    private final  PhoneNumber   phoneNumber;
    private        String        name;
    private        String        email;
    private        String        profilePhotoUrl;
    private        ProfileType   type;
    private        PrivacyLevel  nameVisibility;
    private        PrivacyLevel  phoneVisibility;
    private        boolean       isVerified;
    private        boolean       isOptedOut;      // opted out of caller ID
    private        String        countryCode;
    private        String        city;
    private final  Set<String>   tags = new HashSet<>();
    private final  LocalDateTime joinedAt;
    private        LocalDateTime lastActiveAt;
    private        SpamStatus    spamStatus = SpamStatus.CLEAN;
    private        int           spamScore  = 0;  // 0-100

    // Contact book: who this user has saved (name → number)
    private final Map<String, PhoneNumber> contacts = new ConcurrentHashMap<>();

    // Numbers this user has blocked
    private final Set<String> blockedNumbers = ConcurrentHashMap.newKeySet();

    private UserProfile(Builder b) {
        this.userId         = idGen.getAndIncrement();
        this.phoneNumber    = b.phoneNumber;
        this.name           = b.name;
        this.email          = b.email;
        this.type           = b.type;
        this.nameVisibility = b.nameVisibility;
        this.phoneVisibility= b.phoneVisibility;
        this.isVerified     = b.isVerified;
        this.isOptedOut     = false;
        this.countryCode    = b.countryCode;
        this.city           = b.city;
        this.joinedAt       = LocalDateTime.now();
        this.lastActiveAt   = LocalDateTime.now();
    }

    // ---- Contact management ----
    public void addContact(String name, PhoneNumber number) {
        contacts.put(name, number);
    }

    public void syncContacts(Map<String, PhoneNumber> deviceContacts) {
        contacts.putAll(deviceContacts);
        System.out.println("[Contact Sync] " + name + " synced " +
            deviceContacts.size() + " contacts");
    }

    public Optional<String> getContactName(PhoneNumber number) {
        return contacts.entrySet().stream()
            .filter(e -> e.getValue().equals(number))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    public void blockNumber(PhoneNumber number) {
        blockedNumbers.add(number.getNormalized());
        System.out.println("[Block] " + this.name +
            " blocked: " + number);
    }

    public void unblockNumber(PhoneNumber number) {
        blockedNumbers.remove(number.getNormalized());
    }

    public boolean hasBlocked(PhoneNumber number) {
        return blockedNumbers.contains(number.getNormalized());
    }

    public void optOut() {
        isOptedOut = true;
        System.out.println("[OptOut] " + name +
            " opted out of caller ID — name won't show to others");
    }

    public void optIn() { isOptedOut = false; }

    public void updateSpamStatus(SpamStatus status, int score) {
        this.spamStatus = status;
        this.spamScore  = score;
    }

    // ---- Getters ----
    public long          getUserId()         { return userId; }
    public PhoneNumber   getPhoneNumber()    { return phoneNumber; }
    public String        getName()           { return name; }
    public String        getEmail()          { return email; }
    public ProfileType   getType()           { return type; }
    public PrivacyLevel  getNameVisibility() { return nameVisibility; }
    public boolean       isVerified()        { return isVerified; }
    public boolean       isOptedOut()        { return isOptedOut; }
    public SpamStatus    getSpamStatus()     { return spamStatus; }
    public int           getSpamScore()      { return spamScore; }
    public String        getCity()           { return city; }
    public Map<String, PhoneNumber> getContacts()  { return Collections.unmodifiableMap(contacts); }
    public Set<String>   getBlockedNumbers() { return Collections.unmodifiableSet(blockedNumbers); }

    public void setName(String n)            { this.name = n; }
    public void setNameVisibility(PrivacyLevel l){ this.nameVisibility = l; }
    public void setVerified(boolean v)       { this.isVerified = v; }
    public void setLastActive()              { this.lastActiveAt = LocalDateTime.now(); }

    @Override public String toString() {
        return String.format("Profile[#%d | %-20s | %s | %s | spam=%s(%d)]",
            userId, name, phoneNumber, type, spamStatus, spamScore);
    }

    // ---- BUILDER ----
    static class Builder {
        private final PhoneNumber  phoneNumber;
        private       String       name         = "Unknown";
        private       String       email        = "";
        private       ProfileType  type         = ProfileType.PERSONAL;
        private       PrivacyLevel nameVisibility  = PrivacyLevel.PUBLIC;
        private       PrivacyLevel phoneVisibility = PrivacyLevel.CONTACTS_ONLY;
        private       boolean      isVerified   = false;
        private       String       countryCode  = "+91";
        private       String       city         = "";

        public Builder(PhoneNumber phone)        { this.phoneNumber = phone; }
        public Builder name(String n)            { this.name = n;         return this; }
        public Builder email(String e)           { this.email = e;        return this; }
        public Builder type(ProfileType t)       { this.type = t;         return this; }
        public Builder nameVisibility(PrivacyLevel l){ this.nameVisibility = l; return this; }
        public Builder verified(boolean v)       { this.isVerified = v;   return this; }
        public Builder city(String c)            { this.city = c;         return this; }
        public UserProfile build()               { return new UserProfile(this); }
    }
}

// ==========================================
// 4. BUSINESS PROFILE — DECORATOR PATTERN
// Wraps UserProfile with business-specific attributes
// ==========================================
class BusinessProfile {
    private final UserProfile base;
    private       String      businessName;
    private       String      category;         // restaurant, bank, hospital...
    private       String      website;
    private       String      address;
    private       String      gstin;
    private       boolean     isTrueCallVerified; // blue tick for businesses
    private       String      callPurpose;        // "delivery update", "OTP" etc.
    private final List<String> trustedPurposes = new ArrayList<>();

    public BusinessProfile(UserProfile base, String businessName, String category) {
        this.base          = base;
        this.businessName  = businessName;
        this.category      = category;
        base.setVerified(true);
        base.setNameVisibility(PrivacyLevel.PUBLIC);
    }

    public void addTrustedPurpose(String purpose) {
        trustedPurposes.add(purpose);
    }

    public UserProfile getBase()           { return base; }
    public String      getBusinessName()   { return businessName; }
    public String      getCategory()       { return category; }
    public String      getWebsite()        { return website; }
    public boolean     isTrueCallVerified(){ return isTrueCallVerified; }
    public List<String> getTrustedPurposes(){ return trustedPurposes; }

    public void setWebsite(String w)           { this.website = w; }
    public void setTrueCallVerified(boolean v) { this.isTrueCallVerified = v; }
    public void setCallPurpose(String p)       { this.callPurpose = p; }

    @Override public String toString() {
        return "Business[" + businessName + " | " + category +
               (isTrueCallVerified ? " ✓" : "") + " | " + base.getPhoneNumber() + "]";
    }
}

// ==========================================
// 5. SPAM REPORT — BUILDER PATTERN
// Crowdsourced spam report from a user
// ==========================================
class SpamReport {
    private static final AtomicLong idGen = new AtomicLong(200_000);

    private final  long          reportId;
    private final  PhoneNumber   reportedNumber;
    private final  long          reporterUserId;
    private final  ReportReason  reason;
    private final  SpamCategory  category;
    private        String        description;
    private final  LocalDateTime reportedAt;
    private        boolean       verified;  // was reporter a valid Truecaller user

    private SpamReport(Builder b) {
        this.reportId        = idGen.getAndIncrement();
        this.reportedNumber  = b.reportedNumber;
        this.reporterUserId  = b.reporterUserId;
        this.reason          = b.reason;
        this.category        = b.category;
        this.description     = b.description;
        this.reportedAt      = LocalDateTime.now();
        this.verified        = b.verified;
    }

    public long          getReportId()       { return reportId; }
    public PhoneNumber   getReportedNumber() { return reportedNumber; }
    public long          getReporterUserId() { return reporterUserId; }
    public ReportReason  getReason()         { return reason; }
    public SpamCategory  getCategory()       { return category; }
    public boolean       isVerified()        { return verified; }
    public LocalDateTime getReportedAt()     { return reportedAt; }

    @Override public String toString() {
        return "SpamReport[#" + reportId + " | " + reportedNumber +
               " | " + reason + " | " + category + "]";
    }

    static class Builder {
        private final PhoneNumber  reportedNumber;
        private final long         reporterUserId;
        private       ReportReason reason      = ReportReason.SPAM;
        private       SpamCategory category    = SpamCategory.UNKNOWN;
        private       String       description = "";
        private       boolean      verified    = true;

        public Builder(PhoneNumber num, long userId) {
            this.reportedNumber = num;
            this.reporterUserId = userId;
        }
        public Builder reason(ReportReason r)   { this.reason = r;       return this; }
        public Builder category(SpamCategory c) { this.category = c;     return this; }
        public Builder description(String d)    { this.description = d;  return this; }
        public SpamReport build()               { return new SpamReport(this); }
    }
}

// ==========================================
// 6. CALLER IDENTITY RESULT
// What gets shown when someone calls you
// ==========================================
class CallerIdentity {
    private final PhoneNumber  callerNumber;
    private final String       displayName;    // what to show on screen
    private final ContactSource source;        // how we found this name
    private final ProfileType  profileType;
    private final SpamStatus   spamStatus;
    private final int          spamScore;
    private final SpamCategory spamCategory;
    private final boolean      isBlocked;
    private final boolean      isVerified;
    private final String       businessCategory; // for business callers
    private final String       callPurpose;
    private final boolean      isInContacts;   // caller is in user's contacts
    private       String       tagline;

    private CallerIdentity(Builder b) {
        this.callerNumber     = b.callerNumber;
        this.displayName      = b.displayName;
        this.source           = b.source;
        this.profileType      = b.profileType;
        this.spamStatus       = b.spamStatus;
        this.spamScore        = b.spamScore;
        this.spamCategory     = b.spamCategory;
        this.isBlocked        = b.isBlocked;
        this.isVerified       = b.isVerified;
        this.businessCategory = b.businessCategory;
        this.callPurpose      = b.callPurpose;
        this.isInContacts     = b.isInContacts;
    }

    public PhoneNumber  getCallerNumber()    { return callerNumber; }
    public String       getDisplayName()     { return displayName; }
    public ContactSource getSource()         { return source; }
    public ProfileType  getProfileType()     { return profileType; }
    public SpamStatus   getSpamStatus()      { return spamStatus; }
    public int          getSpamScore()       { return spamScore; }
    public boolean      isBlocked()          { return isBlocked; }
    public boolean      isVerified()         { return isVerified; }
    public boolean      isInContacts()       { return isInContacts; }
    public String       getCallPurpose()     { return callPurpose; }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CallerID[").append(displayName)
          .append(" | ").append(callerNumber)
          .append(" | ").append(source)
          .append(" | spam=").append(spamStatus);
        if (isBlocked) sb.append(" | 🚫BLOCKED");
        if (isVerified) sb.append(" | ✓VERIFIED");
        if (callPurpose != null) sb.append(" | purpose=").append(callPurpose);
        sb.append("]");
        return sb.toString();
    }

    static class Builder {
        private final PhoneNumber callerNumber;
        private       String       displayName    = "Unknown";
        private       ContactSource source        = ContactSource.TRUECALLER_DB;
        private       ProfileType  profileType    = ProfileType.UNKNOWN_CALLER;
        private       SpamStatus   spamStatus     = SpamStatus.CLEAN;
        private       int          spamScore      = 0;
        private       SpamCategory spamCategory   = SpamCategory.UNKNOWN;
        private       boolean      isBlocked      = false;
        private       boolean      isVerified     = false;
        private       String       businessCategory;
        private       String       callPurpose;
        private       boolean      isInContacts   = false;

        public Builder(PhoneNumber number)         { this.callerNumber = number; }
        public Builder displayName(String n)       { this.displayName = n;        return this; }
        public Builder source(ContactSource s)     { this.source = s;             return this; }
        public Builder profileType(ProfileType p)  { this.profileType = p;        return this; }
        public Builder spamStatus(SpamStatus s, int score) {
            this.spamStatus = s; this.spamScore = score;          return this;
        }
        public Builder spamCategory(SpamCategory c){ this.spamCategory = c;       return this; }
        public Builder blocked(boolean b)          { this.isBlocked = b;          return this; }
        public Builder verified(boolean v)         { this.isVerified = v;         return this; }
        public Builder businessCategory(String c)  { this.businessCategory = c;   return this; }
        public Builder callPurpose(String p)       { this.callPurpose = p;        return this; }
        public Builder inContacts(boolean b)       { this.isInContacts = b;       return this; }
        public CallerIdentity build()              { return new CallerIdentity(this); }
    }
}

// ==========================================
// 7. SPAM DETECTION STRATEGY — STRATEGY PATTERN
// ==========================================
interface SpamDetectionStrategy {
    String getName();
    int computeSpamScore(PhoneNumber number,
                          List<SpamReport> reports,
                          CallStats callStats);
    SpamStatus classify(int score);
}

// Community-based: purely from report count
class CommunitySpamStrategy implements SpamDetectionStrategy {
    @Override public String getName() { return "Community Reports"; }

    @Override
    public int computeSpamScore(PhoneNumber number,
                                 List<SpamReport> reports,
                                 CallStats stats) {
        int reportCount = reports.size();
        // Score: 0 reports = 0, 1 report = 15, 5 reports = 50, 20+ = 100
        if (reportCount == 0)   return 0;
        if (reportCount <= 2)   return reportCount * 15;
        if (reportCount <= 10)  return 30 + (reportCount - 2) * 5;
        return Math.min(100, 70 + (reportCount - 10) * 2);
    }

    @Override
    public SpamStatus classify(int score) {
        if (score == 0)       return SpamStatus.CLEAN;
        if (score < 30)       return SpamStatus.SUSPICIOUS;
        if (score < 60)       return SpamStatus.LIKELY_SPAM;
        return                       SpamStatus.CONFIRMED_SPAM;
    }
}

// Call-pattern based: high call volume in short time = likely spam
class CallPatternStrategy implements SpamDetectionStrategy {
    @Override public String getName() { return "Call Pattern Analysis"; }

    @Override
    public int computeSpamScore(PhoneNumber number,
                                 List<SpamReport> reports,
                                 CallStats stats) {
        int score = 0;

        // Many unique recipients in short window → spam
        if (stats.getUniqueCallees24h() > 100) score += 40;
        else if (stats.getUniqueCallees24h() > 50) score += 20;

        // Short call duration → likely spam (robocall)
        if (stats.getAvgCallDurationSeconds() < 10) score += 30;
        else if (stats.getAvgCallDurationSeconds() < 30) score += 15;

        // High rejection rate → people aren't answering
        if (stats.getRejectionRate() > 0.8) score += 20;

        // Report boost
        score += Math.min(40, reports.size() * 5);

        System.out.printf("[PatternSpam] %s: callees24h=%d avgDuration=%.0fs " +
            "rejRate=%.1f%% score=%d%n",
            number, stats.getUniqueCallees24h(),
            stats.getAvgCallDurationSeconds(),
            stats.getRejectionRate() * 100, score);

        return Math.min(100, score);
    }

    @Override
    public SpamStatus classify(int score) {
        if (score < 20)  return SpamStatus.CLEAN;
        if (score < 40)  return SpamStatus.SUSPICIOUS;
        if (score < 65)  return SpamStatus.LIKELY_SPAM;
        return                  SpamStatus.CONFIRMED_SPAM;
    }
}

// Composite strategy: weighted combination of multiple strategies
class CompositeSpamStrategy implements SpamDetectionStrategy {
    private final List<SpamDetectionStrategy> strategies;
    private final List<Double>               weights;

    public CompositeSpamStrategy(List<SpamDetectionStrategy> strategies,
                                  List<Double> weights) {
        this.strategies = strategies;
        this.weights    = weights;
    }

    @Override public String getName() { return "Composite (Weighted)"; }

    @Override
    public int computeSpamScore(PhoneNumber number,
                                 List<SpamReport> reports,
                                 CallStats stats) {
        double weightedScore = 0;
        for (int i = 0; i < strategies.size(); i++) {
            int score = strategies.get(i).computeSpamScore(number, reports, stats);
            weightedScore += score * weights.get(i);
        }
        return (int) weightedScore;
    }

    @Override
    public SpamStatus classify(int score) {
        if (score < 20)  return SpamStatus.CLEAN;
        if (score < 40)  return SpamStatus.SUSPICIOUS;
        if (score < 65)  return SpamStatus.LIKELY_SPAM;
        return                  SpamStatus.CONFIRMED_SPAM;
    }
}

// ==========================================
// 8. CALL STATS — value object for pattern analysis
// ==========================================
class CallStats {
    private final int    uniqueCallees24h;
    private final double avgCallDurationSeconds;
    private final double rejectionRate;
    private final int    totalCallsThisMonth;

    public CallStats(int callees24h, double avgDuration,
                     double rejectionRate, int totalMonth) {
        this.uniqueCallees24h        = callees24h;
        this.avgCallDurationSeconds  = avgDuration;
        this.rejectionRate           = rejectionRate;
        this.totalCallsThisMonth     = totalMonth;
    }

    public static CallStats normal() {
        return new CallStats(5, 120, 0.1, 30);
    }

    public static CallStats spamLike() {
        return new CallStats(150, 8, 0.85, 4200);
    }

    public int    getUniqueCallees24h()        { return uniqueCallees24h; }
    public double getAvgCallDurationSeconds()  { return avgCallDurationSeconds; }
    public double getRejectionRate()           { return rejectionRate; }
    public int    getTotalCallsThisMonth()     { return totalCallsThisMonth; }
}

// ==========================================
// 9. CHAIN OF RESPONSIBILITY — LOOKUP CHAIN
// Steps to resolve a caller's identity
// ==========================================
abstract class LookupStep {
    protected LookupStep next;

    public LookupStep setNext(LookupStep next) {
        this.next = next;
        return next;
    }

    public abstract CallerIdentity.Builder resolve(
        PhoneNumber caller, UserProfile lookupRequester,
        CallerIdentity.Builder builder, CallerIDService service);

    protected CallerIdentity.Builder passToNext(
        PhoneNumber caller, UserProfile requester,
        CallerIdentity.Builder builder, CallerIDService service) {
        return next != null
            ? next.resolve(caller, requester, builder, service)
            : builder;
    }
}

// Step 1: Check if requester has blocked the caller
class BlockCheckStep extends LookupStep {
    @Override
    public CallerIdentity.Builder resolve(PhoneNumber caller, UserProfile requester,
                                           CallerIdentity.Builder builder,
                                           CallerIDService service) {
        if (requester != null && requester.hasBlocked(caller)) {
            System.out.println("[Lookup] 🚫 Caller is blocked by " + requester.getName());
            return builder.blocked(true).displayName("Blocked Number");
            // Don't pass further — blocked numbers get minimal info
        }
        return passToNext(caller, requester, builder, service);
    }
}

// Step 2: Check if caller is in the requester's own contacts
class PersonalContactStep extends LookupStep {
    @Override
    public CallerIdentity.Builder resolve(PhoneNumber caller, UserProfile requester,
                                           CallerIdentity.Builder builder,
                                           CallerIDService service) {
        if (requester != null) {
            Optional<String> contactName = requester.getContactName(caller);
            if (contactName.isPresent()) {
                System.out.println("[Lookup] ✓ Found in personal contacts: " +
                    contactName.get());
                return builder
                    .displayName(contactName.get())
                    .source(ContactSource.MANUAL)
                    .profileType(ProfileType.PERSONAL)
                    .inContacts(true);
                // Don't pass further — personal contact name takes priority
            }
        }
        return passToNext(caller, requester, builder, service);
    }
}

// Step 3: Check spam status
class SpamCheckStep extends LookupStep {
    @Override
    public CallerIdentity.Builder resolve(PhoneNumber caller, UserProfile requester,
                                           CallerIdentity.Builder builder,
                                           CallerIDService service) {
        SpamStatus status = service.getSpamStatus(caller);
        int        score  = service.getSpamScore(caller);
        SpamCategory cat  = service.getSpamCategory(caller);

        if (status != SpamStatus.CLEAN) {
            builder.spamStatus(status, score).spamCategory(cat);
            System.out.println("[Lookup] ⚠ Spam detected: " + status +
                " score=" + score + " for " + caller);
        }

        return passToNext(caller, requester, builder, service);
    }
}

// Step 4: Resolve identity from Truecaller database
class IdentityResolutionStep extends LookupStep {
    @Override
    public CallerIdentity.Builder resolve(PhoneNumber caller, UserProfile requester,
                                           CallerIdentity.Builder builder,
                                           CallerIDService service) {
        Optional<UserProfile> profile = service.lookupProfile(caller);

        if (profile.isPresent()) {
            UserProfile p = profile.get();

            // Respect privacy setting
            if (p.isOptedOut()) {
                System.out.println("[Lookup] User opted out — showing number only");
                return passToNext(caller, requester,
                    builder.displayName(caller.getNormalized())
                           .source(ContactSource.TRUECALLER_DB)
                           .profileType(p.getType()),
                    service);
            }

            if (p.getNameVisibility() == PrivacyLevel.PRIVATE) {
                return passToNext(caller, requester,
                    builder.displayName("Private Number")
                           .source(ContactSource.TRUECALLER_DB),
                    service);
            }

            builder.displayName(p.getName())
                   .source(ContactSource.TRUECALLER_DB)
                   .profileType(p.getType())
                   .verified(p.isVerified());

            System.out.println("[Lookup] ✓ Found in Truecaller DB: " + p.getName());
        } else {
            // Check community-contributed names (crowdsourced)
            Optional<String> crowdsourcedName = service.getCrowdsourcedName(caller);
            if (crowdsourcedName.isPresent()) {
                builder.displayName(crowdsourcedName.get())
                       .source(ContactSource.PHONE_BOOK_SYNC)
                       .profileType(ProfileType.UNREGISTERED);
                System.out.println("[Lookup] ✓ Crowdsourced name: " +
                    crowdsourcedName.get());
            } else {
                builder.displayName("Unknown " + caller.getNormalized())
                       .profileType(ProfileType.UNKNOWN_CALLER);
                System.out.println("[Lookup] ❓ Number not found in any database");
            }
        }

        return passToNext(caller, requester, builder, service);
    }
}

// Step 5: Business profile enrichment
class BusinessEnrichmentStep extends LookupStep {
    @Override
    public CallerIdentity.Builder resolve(PhoneNumber caller, UserProfile requester,
                                           CallerIdentity.Builder builder,
                                           CallerIDService service) {
        Optional<BusinessProfile> biz = service.lookupBusiness(caller);
        if (biz.isPresent()) {
            BusinessProfile b = biz.get();
            builder.displayName(b.getBusinessName())
                   .businessCategory(b.getCategory())
                   .profileType(ProfileType.BUSINESS)
                   .verified(b.isTrueCallVerified());
            if (!b.getTrustedPurposes().isEmpty()) {
                builder.callPurpose(b.getTrustedPurposes().get(0));
            }
            System.out.println("[Lookup] 🏢 Business: " + b.getBusinessName());
        }
        return passToNext(caller, requester, builder, service);
    }
}

// ==========================================
// 10. OBSERVER — CALL EVENTS
// ==========================================
interface CallEventObserver {
    void onIncomingCall(PhoneNumber caller, PhoneNumber receiver,
                        CallerIdentity identity);
    void onSpamReported(SpamReport report, SpamStatus newStatus);
    void onNumberBlocked(UserProfile blocker, PhoneNumber blocked);
}

class AnalyticsObserver implements CallEventObserver {
    private final Map<String, Long>    lookupCount   = new ConcurrentHashMap<>();
    private final Map<String, Long>    spamByCategory= new ConcurrentHashMap<>();
    private       long                 totalLookups  = 0;
    private       long                 spamCallsDetected = 0;

    @Override
    public void onIncomingCall(PhoneNumber caller, PhoneNumber receiver,
                                CallerIdentity identity) {
        totalLookups++;
        lookupCount.merge(caller.getCountryCode(), 1L, Long::sum);
        if (identity.getSpamStatus() != SpamStatus.CLEAN) spamCallsDetected++;
    }

    @Override
    public void onSpamReported(SpamReport report, SpamStatus newStatus) {
        spamByCategory.merge(report.getCategory().name(), 1L, Long::sum);
        System.out.println("[Analytics] Spam report logged: " + report.getCategory());
    }

    @Override
    public void onNumberBlocked(UserProfile blocker, PhoneNumber blocked) {
        System.out.println("[Analytics] Block recorded: " + blocked);
    }

    public void printReport() {
        System.out.println("\n[Analytics Report]");
        System.out.println("  Total lookups:      " + totalLookups);
        System.out.println("  Spam calls detected:" + spamCallsDetected);
        System.out.println("  Spam rate:          " +
            (totalLookups > 0
                ? String.format("%.1f%%", 100.0 * spamCallsDetected / totalLookups)
                : "N/A"));
        System.out.println("  Spam by category:   " + spamByCategory);
    }
}

class SpamAlertObserver implements CallEventObserver {
    @Override
    public void onIncomingCall(PhoneNumber caller, PhoneNumber receiver,
                                CallerIdentity identity) {
        if (identity.getSpamStatus() == SpamStatus.CONFIRMED_SPAM) {
            System.out.println("[SpamAlert] 🚨 HIGH RISK spam call from " +
                caller + " to " + receiver);
        }
    }

    @Override
    public void onSpamReported(SpamReport report, SpamStatus newStatus) {
        if (newStatus == SpamStatus.CONFIRMED_SPAM) {
            System.out.println("[SpamAlert] 🔴 Number " +
                report.getReportedNumber() + " now CONFIRMED SPAM");
        }
    }

    @Override public void onNumberBlocked(UserProfile b, PhoneNumber n) {}
}

// ==========================================
// 11. SPAM SERVICE — SINGLETON
// ==========================================
class SpamService {
    private static SpamService instance;

    // number → list of reports
    private final Map<String, List<SpamReport>> spamReports = new ConcurrentHashMap<>();
    // number → (status, score, category)
    private final Map<String, SpamStatus>        statusCache = new ConcurrentHashMap<>();
    private final Map<String, Integer>           scoreCache  = new ConcurrentHashMap<>();
    private final Map<String, SpamCategory>      categoryCache = new ConcurrentHashMap<>();

    // call stats per number (in production: from CDR feed)
    private final Map<String, CallStats>         callStats   = new ConcurrentHashMap<>();

    private SpamDetectionStrategy strategy;

    private SpamService() {
        this.strategy = new CompositeSpamStrategy(
            List.of(new CommunitySpamStrategy(), new CallPatternStrategy()),
            List.of(0.6, 0.4));
    }

    public static synchronized SpamService getInstance() {
        if (instance == null) instance = new SpamService();
        return instance;
    }

    public void setStrategy(SpamDetectionStrategy s) {
        this.strategy = s;
        System.out.println("[SpamService] Strategy: " + s.getName());
    }

    public SpamReport reportSpam(SpamReport report) {
        String key = report.getReportedNumber().getNormalized();
        spamReports.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                   .add(report);

        // Recompute spam score
        recomputeStatus(report.getReportedNumber());
        System.out.println("[SpamService] Reported: " + report);
        return report;
    }

    public void registerCallStats(PhoneNumber number, CallStats stats) {
        callStats.put(number.getNormalized(), stats);
    }

    private void recomputeStatus(PhoneNumber number) {
        String key   = number.getNormalized();
        List<SpamReport> reports = spamReports.getOrDefault(key, Collections.emptyList());
        CallStats stats = callStats.getOrDefault(key, CallStats.normal());

        int      score  = strategy.computeSpamScore(number, reports, stats);
        SpamStatus status = strategy.classify(score);

        statusCache.put(key, status);
        scoreCache.put(key, score);

        // Determine dominant category
        reports.stream()
            .collect(Collectors.groupingBy(SpamReport::getCategory, Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .ifPresent(e -> categoryCache.put(key, e.getKey()));
    }

    public SpamStatus   getStatus(PhoneNumber n)   {
        return statusCache.getOrDefault(n.getNormalized(), SpamStatus.CLEAN);
    }
    public int          getScore(PhoneNumber n)    {
        return scoreCache.getOrDefault(n.getNormalized(), 0);
    }
    public SpamCategory getCategory(PhoneNumber n) {
        return categoryCache.getOrDefault(n.getNormalized(), SpamCategory.UNKNOWN);
    }

    public List<SpamReport> getReports(PhoneNumber n) {
        return spamReports.getOrDefault(n.getNormalized(), Collections.emptyList());
    }

    public int getReportCount(PhoneNumber n) {
        return spamReports.getOrDefault(n.getNormalized(), Collections.emptyList()).size();
    }
}

// ==========================================
// 12. PROFILE FACTORY
// ==========================================
class ProfileFactory {
    public static UserProfile personal(String phone, String name, String email) {
        return new UserProfile.Builder(PhoneNumber.of(phone))
            .name(name).email(email).type(ProfileType.PERSONAL).build();
    }

    public static UserProfile business(String phone, String bizName) {
        return new UserProfile.Builder(PhoneNumber.of(phone))
            .name(bizName).type(ProfileType.BUSINESS)
            .nameVisibility(PrivacyLevel.PUBLIC).build();
    }

    public static UserProfile anonymous(String phone) {
        return new UserProfile.Builder(PhoneNumber.of(phone))
            .name("Unknown").type(ProfileType.UNKNOWN_CALLER)
            .nameVisibility(PrivacyLevel.PRIVATE).build();
    }
}

// ==========================================
// 13. CALLER ID SERVICE — SINGLETON (core)
// ==========================================
class CallerIDService {
    private static CallerIDService instance;

    // phone normalized → UserProfile
    private final Map<String, UserProfile>     profiles       = new ConcurrentHashMap<>();
    // phone normalized → BusinessProfile
    private final Map<String, BusinessProfile> businesses     = new ConcurrentHashMap<>();
    // crowdsourced: phone → most common name from synced contacts across all users
    private final Map<String, Map<String, Long>> crowdsourced = new ConcurrentHashMap<>();
    // phone → resolved name (most frequent in crowdsourced map)
    private final Map<String, String>          nameCache      = new ConcurrentHashMap<>();

    private final SpamService                  spamService    = SpamService.getInstance();
    private final List<CallEventObserver>      observers      = new ArrayList<>();
    private final AnalyticsObserver            analytics      = new AnalyticsObserver();
    private       LookupStep                   lookupChain;

    private CallerIDService() {
        observers.add(analytics);
        observers.add(new SpamAlertObserver());

        // Build lookup chain
        LookupStep block    = new BlockCheckStep();
        LookupStep contact  = new PersonalContactStep();
        LookupStep spam     = new SpamCheckStep();
        LookupStep business = new BusinessEnrichmentStep();
        LookupStep identity = new IdentityResolutionStep();

        block.setNext(contact).setNext(spam).setNext(business).setNext(identity);
        lookupChain = block;
    }

    public static synchronized CallerIDService getInstance() {
        if (instance == null) instance = new CallerIDService();
        return instance;
    }

    // ---- USER REGISTRATION ----
    public UserProfile registerUser(UserProfile profile) {
        profiles.put(profile.getPhoneNumber().getNormalized(), profile);
        System.out.println("[Service] Registered: " + profile);
        return profile;
    }

    public BusinessProfile registerBusiness(BusinessProfile biz) {
        businesses.put(biz.getBase().getPhoneNumber().getNormalized(), biz);
        profiles.put(biz.getBase().getPhoneNumber().getNormalized(), biz.getBase());
        System.out.println("[Service] Business registered: " + biz);
        return biz;
    }

    // ---- CONTACT SYNC (core Truecaller mechanism) ----
    // When Alice syncs contacts, any unknown number that Alice has saved
    // contributes to the crowdsourced name database
    public void syncContacts(UserProfile user, Map<String, PhoneNumber> contacts) {
        user.syncContacts(contacts);

        // Contribute to crowdsourced name pool
        contacts.forEach((name, number) -> {
            String key = number.getNormalized();
            // Skip numbers already registered (they have their own name)
            if (!profiles.containsKey(key)) {
                crowdsourced.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                            .merge(name, 1L, Long::sum);
                // Recompute most popular name
                recomputeCrowdsourcedName(number);
            }
        });
    }

    private void recomputeCrowdsourcedName(PhoneNumber number) {
        Map<String, Long> nameCounts = crowdsourced.get(number.getNormalized());
        if (nameCounts == null || nameCounts.isEmpty()) return;

        String bestName = nameCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        if (bestName != null) {
            nameCache.put(number.getNormalized(), bestName);
        }
    }

    // ---- CORE: LOOKUP CALLER IDENTITY ----
    public CallerIdentity lookup(PhoneNumber callerNumber, UserProfile receiver) {
        System.out.println("\n[Lookup] " + callerNumber +
            " → receiver: " + (receiver != null ? receiver.getName() : "anonymous"));

        CallerIdentity.Builder builder =
            new CallerIdentity.Builder(callerNumber);

        // Run through the chain
        CallerIdentity.Builder resolved =
            lookupChain.resolve(callerNumber, receiver, builder, this);

        CallerIdentity identity = resolved.build();
        System.out.println("[Lookup] Result: " + identity);

        // Notify observers
        if (receiver != null) {
            observers.forEach(o -> o.onIncomingCall(callerNumber,
                receiver.getPhoneNumber(), identity));
        }

        return identity;
    }

    // ---- SPAM REPORTING ----
    public SpamStatus reportSpam(UserProfile reporter, PhoneNumber spamNumber,
                                  ReportReason reason, SpamCategory category) {
        SpamReport report = new SpamReport.Builder(spamNumber, reporter.getUserId())
            .reason(reason).category(category).build();

        spamService.reportSpam(report);
        SpamStatus newStatus = spamService.getStatus(spamNumber);

        // Update profile if registered
        Optional<UserProfile> profile = lookupProfile(spamNumber);
        profile.ifPresent(p -> p.updateSpamStatus(newStatus,
            spamService.getScore(spamNumber)));

        observers.forEach(o -> o.onSpamReported(report, newStatus));
        return newStatus;
    }

    // ---- BLOCK NUMBER ----
    public void blockNumber(UserProfile user, PhoneNumber toBlock) {
        user.blockNumber(toBlock);
        observers.forEach(o -> o.onNumberBlocked(user, toBlock));
    }

    // ---- SEARCH ----
    public List<UserProfile> searchByName(String name, UserProfile requester) {
        String q = name.toLowerCase().trim();
        return profiles.values().stream()
            .filter(p -> !p.isOptedOut())
            .filter(p -> p.getNameVisibility() != PrivacyLevel.PRIVATE)
            .filter(p -> p.getName().toLowerCase().contains(q))
            .sorted(Comparator.comparing(UserProfile::getName))
            .collect(Collectors.toList());
    }

    public Optional<UserProfile> searchByPhone(PhoneNumber number) {
        return Optional.ofNullable(profiles.get(number.getNormalized()));
    }

    // ---- HELPER METHODS used by lookup chain ----
    public Optional<UserProfile>  lookupProfile(PhoneNumber n) {
        return Optional.ofNullable(profiles.get(n.getNormalized()));
    }

    public Optional<BusinessProfile> lookupBusiness(PhoneNumber n) {
        return Optional.ofNullable(businesses.get(n.getNormalized()));
    }

    public Optional<String> getCrowdsourcedName(PhoneNumber n) {
        return Optional.ofNullable(nameCache.get(n.getNormalized()));
    }

    public SpamStatus   getSpamStatus(PhoneNumber n)   { return spamService.getStatus(n); }
    public int          getSpamScore(PhoneNumber n)    { return spamService.getScore(n); }
    public SpamCategory getSpamCategory(PhoneNumber n) { return spamService.getCategory(n); }

    public void printAnalytics() { analytics.printReport(); }
    public int  getRegisteredUsers() { return profiles.size(); }
}

// ==========================================
// 14. MAIN — DRIVER CODE
// ==========================================
public class TruecallerApp {
    public static void main(String[] args) {

        CallerIDService service  = CallerIDService.getInstance();
        SpamService     spamSvc  = SpamService.getInstance();

        // ---- Register users ----
        UserProfile alice = service.registerUser(
            ProfileFactory.personal("+919876543210", "Alice Sharma", "alice@gmail.com"));

        UserProfile bob = service.registerUser(
            ProfileFactory.personal("+919876543211", "Bob Mehta", "bob@gmail.com"));

        UserProfile carol = service.registerUser(
            ProfileFactory.personal("+919876543212", "Carol Patel", "carol@gmail.com"));

        // ---- Register business ----
        UserProfile hdfcBase = ProfileFactory.business("+918001601601", "HDFC Bank");
        BusinessProfile hdfc = new BusinessProfile(hdfcBase, "HDFC Bank", "Banking");
        hdfc.addTrustedPurpose("OTP Verification");
        hdfc.addTrustedPurpose("Account Alert");
        hdfc.setTrueCallVerified(true);
        hdfc.setWebsite("https://www.hdfcbank.com");
        service.registerBusiness(hdfc);

        UserProfile swiggyBase = ProfileFactory.business("+918008001234", "Swiggy");
        BusinessProfile swiggy = new BusinessProfile(swiggyBase, "Swiggy", "Food Delivery");
        swiggy.addTrustedPurpose("Order Update");
        swiggy.setTrueCallVerified(true);
        service.registerBusiness(swiggy);

        // ---- Contact sync ----
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SETUP: Contact Sync (crowdsourcing mechanism)");
        System.out.println("=".repeat(60));

        // Alice syncs her phone contacts — contributes to crowdsourced names
        service.syncContacts(alice, Map.of(
            "Bob Mehta",    PhoneNumber.of("+919876543211"),
            "Carol Patel",  PhoneNumber.of("+919876543212"),
            "Ravi (Gym)",   PhoneNumber.of("+919000000001"),  // unregistered
            "Delivery Guy", PhoneNumber.of("+919000000002")   // unregistered
        ));

        // Bob syncs — contributes to crowdsourced pool
        service.syncContacts(bob, Map.of(
            "Alice",       PhoneNumber.of("+919876543210"),
            "Ravi Kumar",  PhoneNumber.of("+919000000001"), // same number, diff name
            "Carol P",     PhoneNumber.of("+919876543212")
        ));

        // Carol syncs
        service.syncContacts(carol, Map.of(
            "Ravi Kumar",  PhoneNumber.of("+919000000001"), // "Ravi Kumar" wins (2 votes)
            "Alice S",     PhoneNumber.of("+919876543210")
        ));

        // ===== SCENARIO 1: Normal caller lookup =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Bob calls Alice");
        System.out.println("=".repeat(60));

        CallerIdentity id1 = service.lookup(bob.getPhoneNumber(), alice);

        // ===== SCENARIO 2: Business caller lookup =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: HDFC Bank calls Alice");
        System.out.println("=".repeat(60));

        CallerIdentity id2 = service.lookup(
            PhoneNumber.of("+918001601601"), alice);

        // ===== SCENARIO 3: Unknown number — crowdsourced name =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Unregistered number — crowdsourced name");
        System.out.println("=".repeat(60));

        // +919000000001 was saved as "Ravi Kumar" by 2 users and "Ravi (Gym)" by 1
        // → "Ravi Kumar" wins the majority vote
        CallerIdentity id3 = service.lookup(
            PhoneNumber.of("+919000000001"), alice);

        // ===== SCENARIO 4: Personal contact override =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Personal contact overrides DB name");
        System.out.println("=".repeat(60));

        // Alice has "Ravi (Gym)" saved — her personal name overrides crowdsourced
        CallerIdentity id4 = service.lookup(
            PhoneNumber.of("+919000000001"), alice);

        // ===== SCENARIO 5: Spam call detection =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Spam Number — Multiple Reports");
        System.out.println("=".repeat(60));

        PhoneNumber spamNum = PhoneNumber.of("+919111111111");
        // Register call stats showing spam-like pattern
        spamSvc.registerCallStats(spamNum, CallStats.spamLike());

        // Multiple users report it
        service.reportSpam(alice, spamNum, ReportReason.SPAM, SpamCategory.TELEMARKETING);
        service.reportSpam(bob,   spamNum, ReportReason.SPAM, SpamCategory.TELEMARKETING);
        service.reportSpam(carol, spamNum, ReportReason.SPAM, SpamCategory.TELEMARKETING);

        // Now lookup
        CallerIdentity spamId = service.lookup(spamNum, alice);
        System.out.println("Spam score: " + spamSvc.getScore(spamNum));
        System.out.println("Reports:    " + spamSvc.getReportCount(spamNum));

        // ===== SCENARIO 6: Block a number =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Block Number");
        System.out.println("=".repeat(60));

        service.blockNumber(alice, spamNum);
        CallerIdentity blocked = service.lookup(spamNum, alice); // should show BLOCKED
        System.out.println("Blocked lookup: " + blocked);

        // ===== SCENARIO 7: Opt out of caller ID =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: User Opts Out of Caller ID");
        System.out.println("=".repeat(60));

        carol.optOut();
        CallerIdentity carolId = service.lookup(carol.getPhoneNumber(), alice);
        System.out.println("Carol after opt-out: " + carolId.getDisplayName());

        // ===== SCENARIO 8: Private name visibility =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Private Name Visibility");
        System.out.println("=".repeat(60));

        bob.setNameVisibility(PrivacyLevel.PRIVATE);
        CallerIdentity privateId = service.lookup(bob.getPhoneNumber(), carol);
        System.out.println("Bob private lookup: " + privateId.getDisplayName());

        // Restore
        bob.setNameVisibility(PrivacyLevel.PUBLIC);

        // ===== SCENARIO 9: Search by name =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Search by Name");
        System.out.println("=".repeat(60));

        System.out.println("Search 'alice':");
        service.searchByName("alice", bob)
            .forEach(p -> System.out.println("  " + p));

        System.out.println("Search 'mehta':");
        service.searchByName("mehta", alice)
            .forEach(p -> System.out.println("  " + p));

        // ===== SCENARIO 10: Spam strategy swap =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Switch to Community-Only Spam Strategy");
        System.out.println("=".repeat(60));

        spamSvc.setStrategy(new CommunitySpamStrategy());
        PhoneNumber newSpam = PhoneNumber.of("+919222222222");
        service.reportSpam(alice, newSpam, ReportReason.FRAUD, SpamCategory.FRAUD);
        service.reportSpam(bob,   newSpam, ReportReason.FRAUD, SpamCategory.FRAUD);
        service.reportSpam(carol, newSpam, ReportReason.FRAUD, SpamCategory.FRAUD);
        service.reportSpam(alice, newSpam, ReportReason.FRAUD, SpamCategory.FRAUD);
        service.reportSpam(bob,   newSpam, ReportReason.FRAUD, SpamCategory.FRAUD);

        CallerIdentity fraudId = service.lookup(newSpam, carol);
        System.out.println("Fraud score: " + spamSvc.getScore(newSpam) +
            " | Status: " + spamSvc.getStatus(newSpam));

        // ===== SUMMARY =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(60));
        service.printAnalytics();
        System.out.println("Registered users: " + service.getRegisteredUsers());

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern                  | Class
            -------------------------|--------------------------------------------------
            Singleton                | CallerIDService, SpamService
            Strategy                 | SpamDetectionStrategy (Community/Pattern/Composite)
            Observer                 | CallEventObserver (Analytics / SpamAlert)
            Factory                  | ProfileFactory (personal/business/anonymous)
            Builder                  | UserProfile.Builder, SpamReport.Builder, CallerIdentity.Builder
            State                    | SpamStatus (CLEAN→SUSPICIOUS→LIKELY_SPAM→CONFIRMED_SPAM)
            Chain of Responsibility  | LookupChain (Block→Contact→Spam→Business→Identity)
            Decorator                | BusinessProfile wraps UserProfile with business attributes
            """);
    }
}
