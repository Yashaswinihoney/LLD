import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ============================================================
// SECURE VOTING SYSTEM — LLD
// Patterns:
//   Singleton  — ElectionSystem, AuditService
//   State      — ElectionStatus lifecycle (DRAFT→REGISTRATION→VOTING→CLOSED→PUBLISHED)
//   Strategy   — TallyStrategy (FirstPastThePost, RankedChoice, ApprovalVoting)
//   Observer   — VoteEventObserver (AuditLogger, FraudDetector, NotificationService)
//   Factory    — ElectionFactory (general, referendum, corporate)
//   Builder    — Election.Builder, Ballot.Builder
//   Command    — CastVoteCommand (encapsulate + undo vote action)
//   Template   — AbstractVotingProcessor (processVote skeleton)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum ElectionStatus {
    DRAFT,               // being configured — no public visibility
    REGISTRATION_OPEN,   // voters can register; no voting yet
    VOTING_OPEN,         // active voting window
    VOTING_CLOSED,       // polls closed; counting begins
    RESULTS_PUBLISHED,   // winner declared, tally public
    ARCHIVED             // immutable historical record
}

enum VotingMethod  { FIRST_PAST_THE_POST, RANKED_CHOICE, APPROVAL_VOTING }
enum VoteStatus    { PENDING, CAST, VERIFIED, REJECTED }
enum VoterStatus   { UNREGISTERED, REGISTERED, VERIFIED, VOTED, DISQUALIFIED }
enum AuditEvent    {
    VOTER_REGISTERED, VOTER_VERIFIED, VOTER_DISQUALIFIED,
    VOTE_CAST, VOTE_REJECTED, VOTE_VERIFIED,
    ELECTION_CREATED, ELECTION_OPENED, ELECTION_CLOSED, RESULTS_PUBLISHED,
    FRAUD_ALERT, DOUBLE_VOTE_ATTEMPT, CANDIDATE_ADDED
}
enum RejectionReason {
    NOT_REGISTERED, ALREADY_VOTED, ELECTION_NOT_OPEN,
    INVALID_CANDIDATE, INVALID_BALLOT_FORMAT, VOTER_DISQUALIFIED
}

// ============================================================
// 2. VOTER — identity entity (decoupled from actual vote)
// ============================================================
class Voter {
    private static final AtomicLong idGen = new AtomicLong(10_000);

    private final long          voterId;
    private final String        nationalId;       // government ID — hashed for storage
    private final String        name;
    private final String        constituency;     // district/ward this voter belongs to
    private       VoterStatus   status;
    private       String        voteTokenHash;    // SHA-256 of issued token — NOT the vote itself
    private final LocalDateTime registeredAt;
    private       LocalDateTime votedAt;

    public Voter(String nationalId, String name, String constituency) {
        this.voterId      = idGen.getAndIncrement();
        this.nationalId   = sha256(nationalId);   // hash on intake — never store plaintext
        this.name         = name;
        this.constituency = constituency;
        this.status       = VoterStatus.UNREGISTERED;
        this.registeredAt = LocalDateTime.now();
    }

    public void register() {
        if (status == VoterStatus.UNREGISTERED) {
            status = VoterStatus.REGISTERED;
            System.out.printf("[Voter #%d] %s registered in %s%n",
                voterId, name, constituency);
        }
    }

    public void verify() {
        if (status == VoterStatus.REGISTERED) {
            status = VoterStatus.VERIFIED;
        }
    }

    public void markVoted(String tokenHash) {
        this.status        = VoterStatus.VOTED;
        this.voteTokenHash = tokenHash;
        this.votedAt       = LocalDateTime.now();
    }

    public void disqualify(String reason) {
        this.status = VoterStatus.DISQUALIFIED;
        System.out.println("[Voter #" + voterId + "] DISQUALIFIED: " + reason);
    }

    public boolean hasVoted()          { return status == VoterStatus.VOTED; }
    public boolean isEligible()        { return status == VoterStatus.REGISTERED
                                             || status == VoterStatus.VERIFIED; }
    public long          getVoterId()  { return voterId; }
    public String        getName()     { return name; }
    public String        getConstituency() { return constituency; }
    public VoterStatus   getStatus()   { return status; }
    public String        getNationalIdHash() { return nationalId; }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16) + "..."; // truncated for display
        } catch (NoSuchAlgorithmException e) { return "hash-error"; }
    }

    @Override public String toString() {
        return String.format("Voter[#%d | %s | %s | %s]",
            voterId, name, constituency, status);
    }
}

// ============================================================
// 3. CANDIDATE
// ============================================================
class Candidate {
    private static final AtomicLong idGen = new AtomicLong(200);

    private final long   candidateId;
    private final String name;
    private final String party;
    private final String constituency;

    public Candidate(String name, String party, String constituency) {
        this.candidateId  = idGen.getAndIncrement();
        this.name         = name;
        this.party        = party;
        this.constituency = constituency;
    }

    public long   getCandidateId()  { return candidateId; }
    public String getName()         { return name; }
    public String getParty()        { return party; }
    public String getConstituency() { return constituency; }

    @Override public String toString() {
        return String.format("Candidate[#%d | %s (%s) | %s]",
            candidateId, name, party, constituency);
    }
}

// ============================================================
// 4. BALLOT — what the voter sees and fills out
//    Built with BUILDER PATTERN
// ============================================================
class Ballot {
    private final String          ballotId;
    private final String          electionId;
    private final String          constituency;
    private final List<Candidate> candidates;       // ordered list on the ballot
    private final VotingMethod    method;
    private final int             maxSelections;    // for approval voting
    private final LocalDateTime   issuedAt;

    private Ballot(Builder b) {
        this.ballotId      = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.electionId    = b.electionId;
        this.constituency  = b.constituency;
        this.candidates    = Collections.unmodifiableList(b.candidates);
        this.method        = b.method;
        this.maxSelections = b.maxSelections;
        this.issuedAt      = LocalDateTime.now();
    }

    public String          getBallotId()     { return ballotId; }
    public String          getElectionId()   { return electionId; }
    public String          getConstituency() { return constituency; }
    public List<Candidate> getCandidates()   { return candidates; }
    public VotingMethod    getMethod()       { return method; }
    public int             getMaxSelections(){ return maxSelections; }

    public boolean isValidSelection(List<Long> candidateIds) {
        Set<Long> validIds = candidates.stream()
            .map(Candidate::getCandidateId).collect(Collectors.toSet());
        if (!validIds.containsAll(candidateIds)) return false;
        if (method == VotingMethod.FIRST_PAST_THE_POST && candidateIds.size() != 1)
            return false;
        if (method == VotingMethod.APPROVAL_VOTING
                && candidateIds.size() > maxSelections) return false;
        if (method == VotingMethod.RANKED_CHOICE
                && candidateIds.size() != candidates.size()) return false;
        return true;
    }

    static class Builder {
        private final String    electionId;
        private final String    constituency;
        private final List<Candidate> candidates = new ArrayList<>();
        private VotingMethod    method        = VotingMethod.FIRST_PAST_THE_POST;
        private int             maxSelections = 1;

        public Builder(String electionId, String constituency) {
            this.electionId   = electionId;
            this.constituency = constituency;
        }
        public Builder addCandidate(Candidate c) { candidates.add(c); return this; }
        public Builder method(VotingMethod m)    { this.method = m;   return this; }
        public Builder maxSelections(int n)      { maxSelections = n; return this; }
        public Ballot  build()                   { return new Ballot(this); }
    }

    @Override public String toString() {
        return String.format("Ballot[%s | %s | %s | %d candidates]",
            ballotId, electionId, method, candidates.size());
    }
}

// ============================================================
// 5. VOTE TOKEN — cryptographic bridge between voter and vote
//    Issued on auth; destroyed once used; token hash stored on voter
// ============================================================
class VoteToken {
    private final String tokenId;        // random UUID
    private final long   voterId;        // who was issued this token
    private final String electionId;
    private final String constituency;
    private       boolean used;
    private final LocalDateTime issuedAt;
    private final LocalDateTime expiresAt;

    public VoteToken(long voterId, String electionId, String constituency) {
        this.tokenId      = UUID.randomUUID().toString();
        this.voterId      = voterId;
        this.electionId   = electionId;
        this.constituency = constituency;
        this.used         = false;
        this.issuedAt     = LocalDateTime.now();
        this.expiresAt    = issuedAt.plusHours(1);
    }

    public boolean consume() {
        if (used || LocalDateTime.now().isAfter(expiresAt)) return false;
        this.used = true;
        return true;
    }

    public String  getTokenId()     { return tokenId; }
    public long    getVoterId()     { return voterId; }
    public String  getElectionId()  { return electionId; }
    public String  getConstituency(){ return constituency; }
    public boolean isUsed()         { return used; }
    public boolean isExpired()      { return LocalDateTime.now().isAfter(expiresAt); }
    public String  getHash()        { return Voter.sha256(tokenId); }
}

// ============================================================
// 6. VOTE — anonymised, immutable cast vote
//    No direct link to Voter — linked only via one-time token hash
// ============================================================
class Vote {
    private static final AtomicLong idGen = new AtomicLong(5_000_000);

    private final long          voteId;
    private final String        electionId;
    private final String        constituency;
    private final String        voterTokenHash;   // cryptographic proof; NOT voter ID
    private final List<Long>    candidateRanking; // [1st choice, 2nd choice, ...] for ranked;
                                                  // [only selection] for FPTP;
                                                  // [approved, approved, ...] for approval
    private final VotingMethod  method;
    private       VoteStatus    status;
    private final String        receiptHash;      // given to voter for verification
    private final LocalDateTime castAt;

    public Vote(String electionId, String constituency,
                String voterTokenHash, List<Long> candidateRanking,
                VotingMethod method) {
        this.voteId           = idGen.getAndIncrement();
        this.electionId       = electionId;
        this.constituency     = constituency;
        this.voterTokenHash   = voterTokenHash;
        this.candidateRanking = Collections.unmodifiableList(new ArrayList<>(candidateRanking));
        this.method           = method;
        this.status           = VoteStatus.PENDING;
        this.castAt           = LocalDateTime.now();
        // Receipt: hash of voteId + tokenHash — voter can verify their vote was counted
        this.receiptHash      = Voter.sha256(voteId + voterTokenHash);
    }

    public void markCast()     { this.status = VoteStatus.CAST; }
    public void markVerified() { this.status = VoteStatus.VERIFIED; }
    public void markRejected() { this.status = VoteStatus.REJECTED; }

    public long         getVoteId()          { return voteId; }
    public String       getElectionId()      { return electionId; }
    public String       getConstituency()    { return constituency; }
    public List<Long>   getCandidateRanking(){ return candidateRanking; }
    public VotingMethod getMethod()          { return method; }
    public VoteStatus   getStatus()          { return status; }
    public String       getReceiptHash()     { return receiptHash; }

    @Override public String toString() {
        return String.format("Vote[#%d | %s | %s | token:%.8s | status:%s]",
            voteId, electionId, method, voterTokenHash, status);
    }
}

// ============================================================
// 7. AUDIT ENTRY — immutable event record (append-only)
// ============================================================
class AuditEntry {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final long          entryId;
    private final AuditEvent    event;
    private final String        electionId;
    private final String        detail;
    private final LocalDateTime occurredAt;
    private final String        integrityHash;    // hash of previous entry — chain integrity

    public AuditEntry(AuditEvent event, String electionId,
                      String detail, String prevHash) {
        this.entryId       = idGen.getAndIncrement();
        this.event         = event;
        this.electionId    = electionId;
        this.detail        = detail;
        this.occurredAt    = LocalDateTime.now();
        // Each entry hashes itself + prev — creating an immutable chain (blockchain-lite)
        this.integrityHash = Voter.sha256(entryId + event.name() + detail + prevHash);
    }

    public long         getEntryId()      { return entryId; }
    public AuditEvent   getEvent()        { return event; }
    public String       getElectionId()   { return electionId; }
    public String       getDetail()       { return detail; }
    public LocalDateTime getOccurredAt()  { return occurredAt; }
    public String       getIntegrityHash(){ return integrityHash; }

    @Override public String toString() {
        return String.format("[Audit #%d | %s | %s | %s | hash:%s]",
            entryId, occurredAt.toLocalTime(), event, detail, integrityHash);
    }
}

// ============================================================
// 8. ELECTION RESULT
// ============================================================
class ElectionResult {
    private final String               electionId;
    private final Map<Long, Integer>   votesPerCandidate; // candidateId → vote count
    private final List<Candidate>      rankedWinners;     // ordered by vote count
    private final int                  totalVotesCast;
    private final int                  totalRegisteredVoters;
    private final LocalDateTime        computedAt;

    public ElectionResult(String electionId,
                          Map<Long, Integer> votesPerCandidate,
                          List<Candidate> rankedWinners,
                          int totalVotesCast, int totalRegisteredVoters) {
        this.electionId             = electionId;
        this.votesPerCandidate      = Collections.unmodifiableMap(votesPerCandidate);
        this.rankedWinners          = Collections.unmodifiableList(rankedWinners);
        this.totalVotesCast         = totalVotesCast;
        this.totalRegisteredVoters  = totalRegisteredVoters;
        this.computedAt             = LocalDateTime.now();
    }

    public String          getElectionId()          { return electionId; }
    public Map<Long,Integer> getVotesPerCandidate() { return votesPerCandidate; }
    public List<Candidate> getRankedWinners()       { return rankedWinners; }
    public int             getTotalVotesCast()      { return totalVotesCast; }
    public double          getTurnoutPercent()      {
        return totalRegisteredVoters == 0 ? 0
            : (totalVotesCast * 100.0) / totalRegisteredVoters;
    }

    public void print(Map<Long, Candidate> candidateMap) {
        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║         ELECTION RESULTS             ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf( "║  Total Votes Cast:    %-14d ║%n", totalVotesCast);
        System.out.printf( "║  Registered Voters:   %-14d ║%n", totalRegisteredVoters);
        System.out.printf( "║  Turnout:             %-13.1f%% ║%n", getTurnoutPercent());
        System.out.println("╠══════════════════════════════════════╣");
        rankedWinners.forEach(c -> {
            int votes = votesPerCandidate.getOrDefault(c.getCandidateId(), 0);
            double pct = totalVotesCast == 0 ? 0 : (votes * 100.0) / totalVotesCast;
            System.out.printf("║  %-18s %4d votes (%4.1f%%) ║%n",
                c.getName(), votes, pct);
        });
        System.out.println("╠══════════════════════════════════════╣");
        System.out.println("║  🏆 WINNER: " +
            String.format("%-25s", rankedWinners.get(0).getName()) + " ║");
        System.out.println("╚══════════════════════════════════════╝");
    }
}

// ============================================================
// 9. TALLY STRATEGY — STRATEGY PATTERN
// ============================================================
interface TallyStrategy {
    String getName();
    ElectionResult tally(String electionId, List<Vote> votes,
                         List<Candidate> candidates, int registeredVoters);
}

class FirstPastThePostStrategy implements TallyStrategy {
    @Override public String getName() { return "First Past The Post (FPTP)"; }

    @Override
    public ElectionResult tally(String electionId, List<Vote> votes,
                                List<Candidate> candidates, int registeredVoters) {
        Map<Long, Integer> counts = new HashMap<>();
        candidates.forEach(c -> counts.put(c.getCandidateId(), 0));

        for (Vote v : votes) {
            if (v.getStatus() == VoteStatus.REJECTED) continue;
            if (!v.getCandidateRanking().isEmpty()) {
                long chosen = v.getCandidateRanking().get(0);
                counts.merge(chosen, 1, Integer::sum);
            }
        }

        List<Candidate> ranked = candidates.stream()
            .sorted(Comparator.comparingInt(
                c -> -counts.getOrDefault(c.getCandidateId(), 0)))
            .collect(Collectors.toList());

        System.out.println("[Tally:FPTP] Counted " + votes.size() + " votes");
        return new ElectionResult(electionId, counts, ranked,
            (int) votes.stream().filter(v -> v.getStatus() != VoteStatus.REJECTED).count(),
            registeredVoters);
    }
}

class RankedChoiceStrategy implements TallyStrategy {
    @Override public String getName() { return "Ranked Choice (Instant Runoff)"; }

    @Override
    public ElectionResult tally(String electionId, List<Vote> votes,
                                List<Candidate> candidates, int registeredVoters) {
        // Instant Runoff: eliminate last-place candidate round by round
        List<Candidate> remaining = new ArrayList<>(candidates);
        Map<Long, Integer> finalCounts = new HashMap<>();
        candidates.forEach(c -> finalCounts.put(c.getCandidateId(), 0));

        int validVotes = (int) votes.stream()
            .filter(v -> v.getStatus() != VoteStatus.REJECTED).count();
        int majority = validVotes / 2 + 1;
        int round = 1;

        while (remaining.size() > 1) {
            Map<Long, Integer> roundCounts = new HashMap<>();
            remaining.forEach(c -> roundCounts.put(c.getCandidateId(), 0));

            Set<Long> remainingIds = remaining.stream()
                .map(Candidate::getCandidateId).collect(Collectors.toSet());

            for (Vote v : votes) {
                if (v.getStatus() == VoteStatus.REJECTED) continue;
                // Find first still-remaining preference
                for (long candidateId : v.getCandidateRanking()) {
                    if (remainingIds.contains(candidateId)) {
                        roundCounts.merge(candidateId, 1, Integer::sum);
                        break;
                    }
                }
            }

            System.out.println("[Tally:RCV] Round " + round + ": " + roundCounts);

            // Check for majority
            Optional<Map.Entry<Long, Integer>> winner = roundCounts.entrySet().stream()
                .filter(e -> e.getValue() >= majority).findFirst();

            if (winner.isPresent()) {
                finalCounts.putAll(roundCounts);
                break;
            }

            // Eliminate last-place
            long loser = roundCounts.entrySet().stream()
                .min(Map.Entry.comparingByValue()).get().getKey();
            remaining.removeIf(c -> c.getCandidateId() == loser);
            finalCounts.putAll(roundCounts);
            System.out.println("[Tally:RCV] Eliminated candidate #" + loser);
            round++;
        }

        List<Candidate> ranked = remaining.stream()
            .sorted(Comparator.comparingInt(
                c -> -finalCounts.getOrDefault(c.getCandidateId(), 0)))
            .collect(Collectors.toList());

        return new ElectionResult(electionId, finalCounts, ranked, validVotes, registeredVoters);
    }
}

class ApprovalVotingStrategy implements TallyStrategy {
    @Override public String getName() { return "Approval Voting"; }

    @Override
    public ElectionResult tally(String electionId, List<Vote> votes,
                                List<Candidate> candidates, int registeredVoters) {
        Map<Long, Integer> approvals = new HashMap<>();
        candidates.forEach(c -> approvals.put(c.getCandidateId(), 0));

        for (Vote v : votes) {
            if (v.getStatus() == VoteStatus.REJECTED) continue;
            // Every selected candidate gets +1 approval
            for (long candidateId : v.getCandidateRanking()) {
                approvals.merge(candidateId, 1, Integer::sum);
            }
        }

        List<Candidate> ranked = candidates.stream()
            .sorted(Comparator.comparingInt(
                c -> -approvals.getOrDefault(c.getCandidateId(), 0)))
            .collect(Collectors.toList());

        System.out.println("[Tally:Approval] Counts: " + approvals);
        return new ElectionResult(electionId, approvals, ranked,
            (int) votes.stream().filter(v -> v.getStatus() != VoteStatus.REJECTED).count(),
            registeredVoters);
    }
}

// ============================================================
// 10. VOTE EVENT OBSERVER — OBSERVER PATTERN
// ============================================================
interface VoteEventObserver {
    void onVoteCast(Vote vote, String electionId);
    void onVoteRejected(Vote vote, RejectionReason reason, String electionId);
    void onElectionStatusChange(ElectionStatus oldStatus, ElectionStatus newStatus,
                                String electionId);
    void onFraudAlert(String detail, String electionId);
    void onVoterRegistered(Voter voter, String electionId);
}

class AuditLogger implements VoteEventObserver {
    // Singleton audit service — append-only, hash-chained log
    private final List<AuditEntry> log       = new CopyOnWriteArrayList<>();
    private       String           prevHash  = "GENESIS";

    private synchronized AuditEntry append(AuditEvent event, String electionId, String detail) {
        AuditEntry entry = new AuditEntry(event, electionId, detail, prevHash);
        log.add(entry);
        prevHash = entry.getIntegrityHash();
        return entry;
    }

    @Override
    public void onVoteCast(Vote vote, String electionId) {
        append(AuditEvent.VOTE_CAST, electionId,
            "voteId=" + vote.getVoteId() + " receipt=" + vote.getReceiptHash());
    }

    @Override
    public void onVoteRejected(Vote vote, RejectionReason reason, String electionId) {
        append(AuditEvent.VOTE_REJECTED, electionId,
            "voteId=" + vote.getVoteId() + " reason=" + reason);
    }

    @Override
    public void onElectionStatusChange(ElectionStatus old, ElectionStatus nw, String electionId) {
        append(AuditEvent.values()[nw.ordinal() + 6], electionId, old + " → " + nw);
    }

    @Override
    public void onFraudAlert(String detail, String electionId) {
        append(AuditEvent.FRAUD_ALERT, electionId, detail);
        System.out.println("[AuditLogger] ⚠ FRAUD ALERT logged: " + detail);
    }

    @Override
    public void onVoterRegistered(Voter voter, String electionId) {
        append(AuditEvent.VOTER_REGISTERED, electionId,
            "voterId=" + voter.getVoterId() + " constituency=" + voter.getConstituency());
    }

    public boolean verifyChainIntegrity() {
        String hash = "GENESIS";
        for (AuditEntry e : log) {
            String expected = Voter.sha256(
                e.getEntryId() + e.getEvent().name() + e.getDetail() + hash);
            if (!expected.equals(e.getIntegrityHash())) {
                System.out.println("[AuditLogger] ❌ CHAIN BROKEN at entry #" + e.getEntryId());
                return false;
            }
            hash = e.getIntegrityHash();
        }
        System.out.println("[AuditLogger] ✅ Audit chain intact — " + log.size() + " entries");
        return true;
    }

    public List<AuditEntry> getLog()              { return Collections.unmodifiableList(log); }
    public List<AuditEntry> getLog(String electionId) {
        return log.stream().filter(e -> e.getElectionId().equals(electionId))
            .collect(Collectors.toList());
    }
}

class FraudDetector implements VoteEventObserver {
    // Track voting patterns per election for anomaly detection
    private final Map<String, Integer>   voteRatePerMinute = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> tokensSeen      = new ConcurrentHashMap<>();
    private static final int             MAX_VOTES_PER_MIN = 1000; // per election

    @Override
    public void onVoteCast(Vote vote, String electionId) {
        // Rate check
        voteRatePerMinute.merge(electionId, 1, Integer::sum);
        if (voteRatePerMinute.get(electionId) > MAX_VOTES_PER_MIN) {
            System.out.println("[FraudDetector] ⚠ Abnormally high vote rate in " + electionId);
        }
    }

    @Override
    public void onVoteRejected(Vote vote, RejectionReason reason, String electionId) {
        if (reason == RejectionReason.ALREADY_VOTED) {
            System.out.println("[FraudDetector] 🚨 Double-vote attempt detected in " + electionId);
        }
    }

    @Override public void onElectionStatusChange(ElectionStatus o, ElectionStatus n, String id) {}
    @Override public void onFraudAlert(String detail, String electionId) {
        System.out.println("[FraudDetector] 🚨 Alert: " + detail + " [" + electionId + "]");
    }
    @Override public void onVoterRegistered(Voter v, String id) {}
}

class NotificationService implements VoteEventObserver {
    @Override
    public void onVoteCast(Vote vote, String electionId) {
        System.out.printf("[Notif] Vote confirmed in %s — receipt: %s%n",
            electionId, vote.getReceiptHash());
    }

    @Override
    public void onVoteRejected(Vote vote, RejectionReason reason, String electionId) {
        System.out.printf("[Notif] Vote REJECTED in %s — reason: %s%n",
            electionId, reason);
    }

    @Override
    public void onElectionStatusChange(ElectionStatus old, ElectionStatus nw, String id) {
        System.out.printf("[Notif] Election %s is now %s%n", id, nw);
    }

    @Override public void onFraudAlert(String detail, String electionId) {
        System.out.println("[Notif] Admin alert: " + detail);
    }

    @Override
    public void onVoterRegistered(Voter voter, String electionId) {
        System.out.printf("[Notif → %s] You are registered for election %s%n",
            voter.getName(), electionId);
    }
}

// ============================================================
// 11. ELECTION — BUILDER PATTERN (State-bearing entity)
// ============================================================
class Election {
    private final String            electionId;
    private final String            title;
    private final String            description;
    private final VotingMethod      method;
    private final List<Candidate>   candidates;
    private final LocalDateTime     registrationDeadline;
    private final LocalDateTime     votingOpens;
    private final LocalDateTime     votingCloses;
    private       ElectionStatus    status;
    private final Map<String, Ballot> ballotsByConstituency;  // constituency → ballot

    private Election(Builder b) {
        this.electionId              = b.electionId;
        this.title                   = b.title;
        this.description             = b.description;
        this.method                  = b.method;
        this.candidates              = Collections.unmodifiableList(b.candidates);
        this.registrationDeadline    = b.registrationDeadline;
        this.votingOpens             = b.votingOpens;
        this.votingCloses            = b.votingCloses;
        this.status                  = ElectionStatus.DRAFT;
        this.ballotsByConstituency   = new ConcurrentHashMap<>();
    }

    // --- State transitions ---
    public boolean openRegistration() {
        if (status == ElectionStatus.DRAFT) {
            status = ElectionStatus.REGISTRATION_OPEN; return true;
        }
        return false;
    }

    public boolean openVoting() {
        if (status == ElectionStatus.REGISTRATION_OPEN) {
            status = ElectionStatus.VOTING_OPEN; return true;
        }
        return false;
    }

    public boolean closeVoting() {
        if (status == ElectionStatus.VOTING_OPEN) {
            status = ElectionStatus.VOTING_CLOSED; return true;
        }
        return false;
    }

    public boolean publishResults() {
        if (status == ElectionStatus.VOTING_CLOSED) {
            status = ElectionStatus.RESULTS_PUBLISHED; return true;
        }
        return false;
    }

    public boolean archive() {
        if (status == ElectionStatus.RESULTS_PUBLISHED) {
            status = ElectionStatus.ARCHIVED; return true;
        }
        return false;
    }

    public boolean isVotingOpen()         { return status == ElectionStatus.VOTING_OPEN; }
    public boolean isRegistrationOpen()   { return status == ElectionStatus.REGISTRATION_OPEN
                                               || status == ElectionStatus.VOTING_OPEN; }

    public void addBallot(String constituency, Ballot ballot) {
        ballotsByConstituency.put(constituency, ballot);
    }

    public Ballot getBallot(String constituency) {
        return ballotsByConstituency.get(constituency);
    }

    public String          getElectionId()   { return electionId; }
    public String          getTitle()        { return title; }
    public VotingMethod    getMethod()       { return method; }
    public List<Candidate> getCandidates()   { return candidates; }
    public ElectionStatus  getStatus()       { return status; }

    static class Builder {
        private final String       electionId = "ELEC-" +
            UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        private String             title;
        private String             description      = "";
        private VotingMethod       method           = VotingMethod.FIRST_PAST_THE_POST;
        private final List<Candidate> candidates    = new ArrayList<>();
        private LocalDateTime      registrationDeadline = LocalDateTime.now().plusDays(7);
        private LocalDateTime      votingOpens      = LocalDateTime.now().plusDays(8);
        private LocalDateTime      votingCloses     = LocalDateTime.now().plusDays(9);

        public Builder title(String t)                       { title = t;               return this; }
        public Builder description(String d)                 { description = d;         return this; }
        public Builder method(VotingMethod m)                { method = m;              return this; }
        public Builder addCandidate(Candidate c)             { candidates.add(c);       return this; }
        public Builder registrationDeadline(LocalDateTime d) { registrationDeadline = d;return this; }
        public Builder votingWindow(LocalDateTime open,
                                    LocalDateTime close)     { votingOpens = open;
                                                               votingCloses = close;    return this; }
        public Election build()                              { return new Election(this); }
    }

    @Override public String toString() {
        return String.format("Election[%s | %s | %s | %s]",
            electionId, title, method, status);
    }
}

// ============================================================
// 12. CAST VOTE COMMAND — COMMAND PATTERN
// ============================================================
interface VotingCommand {
    Vote execute();
    void undo();  // Admin only — not exposed to public; used only for error recovery
    RejectionReason getRejectionReason();
}

class CastVoteCommand implements VotingCommand {
    private final VoteToken         token;
    private final Ballot            ballot;
    private final List<Long>        selection;
    private final Voter             voter;
    private final List<Vote>        voteStore;
    private       Vote              castVote;
    private       RejectionReason   rejectionReason;

    public CastVoteCommand(VoteToken token, Ballot ballot,
                           List<Long> selection, Voter voter,
                           List<Vote> voteStore) {
        this.token     = token;
        this.ballot    = ballot;
        this.selection = selection;
        this.voter     = voter;
        this.voteStore = voteStore;
    }

    @Override
    public Vote execute() {
        castVote = new Vote(ballot.getElectionId(), ballot.getConstituency(),
            token.getHash(), selection, ballot.getMethod());

        // Validate selection format
        if (!ballot.isValidSelection(selection)) {
            castVote.markRejected();
            rejectionReason = RejectionReason.INVALID_BALLOT_FORMAT;
            System.out.println("[CastVoteCmd] Invalid ballot format — rejected");
            return castVote;
        }

        // Consume token (single use)
        if (!token.consume()) {
            castVote.markRejected();
            rejectionReason = RejectionReason.ALREADY_VOTED;
            System.out.println("[CastVoteCmd] Token already used — rejected");
            return castVote;
        }

        // Record vote and mark voter
        castVote.markCast();
        voteStore.add(castVote);
        voter.markVoted(token.getHash());

        System.out.printf("[CastVoteCmd] Vote #%d cast. Receipt: %s%n",
            castVote.getVoteId(), castVote.getReceiptHash());
        return castVote;
    }

    @Override
    public void undo() {
        // Admin recovery only — e.g., technical fault discovered post-cast
        if (castVote != null && castVote.getStatus() == VoteStatus.CAST) {
            castVote.markRejected();
            voteStore.remove(castVote);
            System.out.println("[CastVoteCmd] ADMIN UNDO: vote #" + castVote.getVoteId() +
                " voided");
        }
    }

    @Override
    public RejectionReason getRejectionReason() { return rejectionReason; }
}

// ============================================================
// 13. ABSTRACT VOTING PROCESSOR — TEMPLATE METHOD
// Defines vote processing skeleton; subclasses override hooks
// ============================================================
abstract class AbstractVotingProcessor {
    protected final Election             election;
    protected final List<Vote>           voteStore;
    protected final Map<Long, Voter>     registeredVoters;
    protected final Map<Long, VoteToken> issuedTokens;
    protected final List<VoteEventObserver> observers;

    public AbstractVotingProcessor(Election election) {
        this.election         = election;
        this.voteStore        = new CopyOnWriteArrayList<>();
        this.registeredVoters = new ConcurrentHashMap<>();
        this.issuedTokens     = new ConcurrentHashMap<>();
        this.observers        = new ArrayList<>();
    }

    public void addObserver(VoteEventObserver o) { observers.add(o); }

    // Template method — the fixed skeleton for processing a vote
    public final Vote processVote(long voterId, List<Long> selection) {
        // Step 1: authenticate voter
        Voter voter = authenticateVoter(voterId);
        if (voter == null) {
            Vote rejected = dummyRejectedVote(election.getElectionId());
            rejected.markRejected();
            observers.forEach(o -> o.onVoteRejected(
                rejected, RejectionReason.NOT_REGISTERED, election.getElectionId()));
            return rejected;
        }

        // Step 2: check eligibility
        RejectionReason ineligible = checkEligibility(voter);
        if (ineligible != null) {
            Vote rejected = dummyRejectedVote(election.getElectionId());
            rejected.markRejected();
            if (ineligible == RejectionReason.ALREADY_VOTED) {
                observers.forEach(o -> o.onFraudAlert(
                    "Double vote attempt by voter #" + voterId, election.getElectionId()));
            }
            observers.forEach(o -> o.onVoteRejected(
                rejected, ineligible, election.getElectionId()));
            return rejected;
        }

        // Step 3: issue token
        VoteToken token = issueToken(voter);

        // Step 4: get ballot
        Ballot ballot = getBallot(voter);

        // Step 5: build and execute command
        CastVoteCommand cmd = new CastVoteCommand(token, ballot, selection, voter, voteStore);
        Vote vote = cmd.execute();

        // Step 6: post-process (notify, validate, log)
        if (vote.getStatus() == VoteStatus.CAST) {
            vote.markVerified();
            postProcessVote(vote);
            observers.forEach(o -> o.onVoteCast(vote, election.getElectionId()));
        } else {
            observers.forEach(o -> o.onVoteRejected(
                vote, cmd.getRejectionReason(), election.getElectionId()));
        }

        return vote;
    }

    // Hooks — override in subclasses for different auth/ballot strategies
    protected abstract Voter     authenticateVoter(long voterId);
    protected abstract RejectionReason checkEligibility(Voter voter);
    protected abstract VoteToken issueToken(Voter voter);
    protected abstract Ballot    getBallot(Voter voter);
    protected abstract void      postProcessVote(Vote vote);

    private Vote dummyRejectedVote(String electionId) {
        return new Vote(electionId, "UNKNOWN", "invalid", List.of(), election.getMethod());
    }

    public void registerVoter(Voter voter) {
        voter.register();
        registeredVoters.put(voter.getVoterId(), voter);
        observers.forEach(o -> o.onVoterRegistered(voter, election.getElectionId()));
    }

    public int      getVoteCount()           { return voteStore.size(); }
    public List<Vote> getVotes()             { return Collections.unmodifiableList(voteStore); }
    public Map<Long, Voter> getRegisteredVoters() { return registeredVoters; }
}

// ============================================================
// 14. STANDARD VOTING PROCESSOR (concrete implementation)
// ============================================================
class StandardVotingProcessor extends AbstractVotingProcessor {
    private final Map<String, Ballot> ballots; // constituency → ballot

    public StandardVotingProcessor(Election election) {
        super(election);
        this.ballots = new HashMap<>();
        // Build default ballots per constituency from election candidates
        election.getCandidates().stream()
            .map(Candidate::getConstituency).distinct()
            .forEach(c -> ballots.put(c, new Ballot.Builder(election.getElectionId(), c)
                .method(election.getMethod())
                .build()));
    }

    public void addBallot(String constituency, Ballot ballot) {
        ballots.put(constituency, ballot);
        election.addBallot(constituency, ballot);
    }

    @Override
    protected Voter authenticateVoter(long voterId) {
        Voter v = registeredVoters.get(voterId);
        if (v == null) System.out.println("[Auth] Voter #" + voterId + " NOT FOUND");
        return v;
    }

    @Override
    protected RejectionReason checkEligibility(Voter voter) {
        if (!election.isVotingOpen())
            return RejectionReason.ELECTION_NOT_OPEN;
        if (voter.hasVoted())
            return RejectionReason.ALREADY_VOTED;
        if (voter.getStatus() == VoterStatus.DISQUALIFIED)
            return RejectionReason.VOTER_DISQUALIFIED;
        if (!voter.isEligible())
            return RejectionReason.NOT_REGISTERED;
        return null; // null = eligible
    }

    @Override
    protected VoteToken issueToken(Voter voter) {
        VoteToken token = new VoteToken(
            voter.getVoterId(), election.getElectionId(), voter.getConstituency());
        issuedTokens.put(voter.getVoterId(), token);
        System.out.printf("[Auth] Token issued to voter #%d (expires in 1h)%n",
            voter.getVoterId());
        return token;
    }

    @Override
    protected Ballot getBallot(Voter voter) {
        Ballot ballot = ballots.get(voter.getConstituency());
        if (ballot == null) {
            // Fallback: create ballot with all election candidates
            ballot = new Ballot.Builder(election.getElectionId(), voter.getConstituency())
                .method(election.getMethod())
                .build();
            ballots.put(voter.getConstituency(), ballot);
        }
        return ballot;
    }

    @Override
    protected void postProcessVote(Vote vote) {
        // In production: persist to DB, update live results cache, snapshot audit
        System.out.printf("[Processor] Vote #%d verified and stored%n", vote.getVoteId());
    }

    public ElectionResult tallyWith(TallyStrategy strategy) {
        return strategy.tally(
            election.getElectionId(),
            voteStore,
            election.getCandidates(),
            registeredVoters.size()
        );
    }
}

// ============================================================
// 15. ELECTION FACTORY
// ============================================================
class ElectionFactory {
    /** Standard general election — FPTP, multiple constituencies */
    public static Election generalElection(String title, List<Candidate> candidates) {
        Election.Builder b = new Election.Builder().title(title).method(VotingMethod.FIRST_PAST_THE_POST);
        candidates.forEach(b::addCandidate);
        return b.build();
    }

    /** Referendum — yes/no, single constituency, FPTP */
    public static Election referendum(String question) {
        Candidate yes = new Candidate("YES", "N/A", "NATIONAL");
        Candidate no  = new Candidate("NO",  "N/A", "NATIONAL");
        return new Election.Builder()
            .title("Referendum: " + question)
            .method(VotingMethod.FIRST_PAST_THE_POST)
            .addCandidate(yes).addCandidate(no)
            .build();
    }

    /** Corporate board election — approval voting, single body */
    public static Election corporateElection(String title, List<Candidate> candidates, int seats) {
        Election.Builder b = new Election.Builder()
            .title(title)
            .method(VotingMethod.APPROVAL_VOTING);
        candidates.forEach(b::addCandidate);
        return b.build();
    }

    /** Ranked-choice — preferential ballot */
    public static Election rankedChoiceElection(String title, List<Candidate> candidates) {
        Election.Builder b = new Election.Builder()
            .title(title)
            .method(VotingMethod.RANKED_CHOICE);
        candidates.forEach(b::addCandidate);
        return b.build();
    }
}

// ============================================================
// 16. ELECTION SYSTEM — SINGLETON
// ============================================================
class ElectionSystem {
    private static ElectionSystem instance;

    private final Map<String, Election>                elections   = new ConcurrentHashMap<>();
    private final Map<String, StandardVotingProcessor> processors  = new ConcurrentHashMap<>();
    private final AuditLogger                          auditLogger = new AuditLogger();
    private final FraudDetector                        fraudDetector = new FraudDetector();
    private final NotificationService                  notifications = new NotificationService();

    private ElectionSystem() {}

    public static synchronized ElectionSystem getInstance() {
        if (instance == null) instance = new ElectionSystem();
        return instance;
    }

    public StandardVotingProcessor registerElection(Election election) {
        StandardVotingProcessor processor = new StandardVotingProcessor(election);
        processor.addObserver(auditLogger);
        processor.addObserver(fraudDetector);
        processor.addObserver(notifications);
        elections.put(election.getElectionId(), election);
        processors.put(election.getElectionId(), processor);
        System.out.println("[ElectionSystem] Registered: " + election);
        return processor;
    }

    public boolean advanceStatus(String electionId) {
        Election e = elections.get(electionId);
        if (e == null) return false;
        ElectionStatus old = e.getStatus();
        boolean advanced = switch (e.getStatus()) {
            case DRAFT              -> e.openRegistration();
            case REGISTRATION_OPEN  -> e.openVoting();
            case VOTING_OPEN        -> e.closeVoting();
            case VOTING_CLOSED      -> e.publishResults();
            case RESULTS_PUBLISHED  -> e.archive();
            default -> false;
        };
        if (advanced) {
            observers().forEach(o -> o.onElectionStatusChange(old, e.getStatus(), electionId));
        }
        return advanced;
    }

    private List<VoteEventObserver> observers() {
        return List.of(auditLogger, fraudDetector, notifications);
    }

    public AuditLogger    getAuditLogger()   { return auditLogger; }
    public FraudDetector  getFraudDetector() { return fraudDetector; }
    public Election       getElection(String id) { return elections.get(id); }
    public StandardVotingProcessor getProcessor(String id) { return processors.get(id); }
}

// ============================================================
// 17. MAIN — DRIVER + SCENARIOS
// ============================================================
public class VotingSystem {
    public static void main(String[] args) throws InterruptedException {

        ElectionSystem system = ElectionSystem.getInstance();

        // ===== SCENARIO 1: General Election (FPTP) =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 1: General Election — First Past The Post");
        System.out.println("=".repeat(65));

        Candidate alice  = new Candidate("Alice Sharma",  "National Party",   "NORTH");
        Candidate bob    = new Candidate("Bob Mehta",     "People's Front",   "NORTH");
        Candidate carol  = new Candidate("Carol Nair",    "Green Alliance",   "NORTH");

        Election ge = ElectionFactory.generalElection("General Election 2025",
            List.of(alice, bob, carol));

        StandardVotingProcessor proc1 = system.registerElection(ge);

        // Lifecycle: DRAFT → REGISTRATION_OPEN → VOTING_OPEN
        system.advanceStatus(ge.getElectionId()); // → REGISTRATION_OPEN
        system.advanceStatus(ge.getElectionId()); // → VOTING_OPEN

        // Register voters
        Voter v1 = new Voter("IND-001", "Priya Kapoor",   "NORTH");
        Voter v2 = new Voter("IND-002", "Rahul Gupta",    "NORTH");
        Voter v3 = new Voter("IND-003", "Sneha Rao",      "NORTH");
        Voter v4 = new Voter("IND-004", "Arjun Mishra",   "NORTH");

        proc1.registerVoter(v1);
        proc1.registerVoter(v2);
        proc1.registerVoter(v3);
        proc1.registerVoter(v4);

        // Build ballot for NORTH constituency
        Ballot northBallot = new Ballot.Builder(ge.getElectionId(), "NORTH")
            .addCandidate(alice).addCandidate(bob).addCandidate(carol)
            .method(VotingMethod.FIRST_PAST_THE_POST)
            .build();
        proc1.addBallot("NORTH", northBallot);

        System.out.println("\n-- Casting votes --");
        // v1 votes Alice, v2 votes Bob, v3 votes Alice, v4 votes Carol
        Vote cast1 = proc1.processVote(v1.getVoterId(), List.of(alice.getCandidateId()));
        Vote cast2 = proc1.processVote(v2.getVoterId(), List.of(bob.getCandidateId()));
        Vote cast3 = proc1.processVote(v3.getVoterId(), List.of(alice.getCandidateId()));
        Vote cast4 = proc1.processVote(v4.getVoterId(), List.of(carol.getCandidateId()));

        Thread.sleep(100);
        System.out.println("\n-- Close and tally --");
        system.advanceStatus(ge.getElectionId()); // → VOTING_CLOSED

        ElectionResult result1 = proc1.tallyWith(new FirstPastThePostStrategy());
        system.advanceStatus(ge.getElectionId()); // → RESULTS_PUBLISHED
        result1.print(Map.of(
            alice.getCandidateId(), alice,
            bob.getCandidateId(), bob,
            carol.getCandidateId(), carol));

        // ===== SCENARIO 2: Double Vote Prevention =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 2: Double Vote Prevention");
        System.out.println("=".repeat(65));

        // v1 already voted — attempt second vote
        Vote duplicate = proc1.processVote(v1.getVoterId(), List.of(bob.getCandidateId()));
        System.out.println("Duplicate vote status: " + duplicate.getStatus());

        // ===== SCENARIO 3: Unregistered Voter Rejection =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 3: Unregistered Voter Rejection");
        System.out.println("=".repeat(65));

        Vote ghostVote = proc1.processVote(99999L, List.of(alice.getCandidateId()));
        System.out.println("Ghost vote status: " + ghostVote.getStatus());

        // ===== SCENARIO 4: Ranked Choice Election =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 4: Ranked Choice Election (Instant Runoff)");
        System.out.println("=".repeat(65));

        Candidate dave  = new Candidate("Dave Chen",   "Progressive",   "WEST");
        Candidate eve   = new Candidate("Eve Patel",   "Conservative",  "WEST");
        Candidate frank = new Candidate("Frank Osei",  "Independent",   "WEST");

        Election rcv = ElectionFactory.rankedChoiceElection(
            "Mayoral Election 2025", List.of(dave, eve, frank));
        StandardVotingProcessor proc2 = system.registerElection(rcv);

        system.advanceStatus(rcv.getElectionId()); // REGISTRATION_OPEN
        system.advanceStatus(rcv.getElectionId()); // VOTING_OPEN

        Voter u1 = new Voter("IND-101", "Uma Krishnan",  "WEST");
        Voter u2 = new Voter("IND-102", "Vijay Shetty",  "WEST");
        Voter u3 = new Voter("IND-103", "Wren Thomas",   "WEST");
        Voter u4 = new Voter("IND-104", "Xavier Lopes",  "WEST");
        Voter u5 = new Voter("IND-105", "Yash Choudhary","WEST");

        for (Voter u : List.of(u1, u2, u3, u4, u5)) proc2.registerVoter(u);

        Ballot westBallot = new Ballot.Builder(rcv.getElectionId(), "WEST")
            .addCandidate(dave).addCandidate(eve).addCandidate(frank)
            .method(VotingMethod.RANKED_CHOICE)
            .build();
        proc2.addBallot("WEST", westBallot);

        // Ranked ballots: [1st, 2nd, 3rd]
        proc2.processVote(u1.getVoterId(), List.of(dave.getCandidateId(),
            eve.getCandidateId(), frank.getCandidateId()));
        proc2.processVote(u2.getVoterId(), List.of(eve.getCandidateId(),
            dave.getCandidateId(), frank.getCandidateId()));
        proc2.processVote(u3.getVoterId(), List.of(frank.getCandidateId(),
            dave.getCandidateId(), eve.getCandidateId()));
        proc2.processVote(u4.getVoterId(), List.of(dave.getCandidateId(),
            frank.getCandidateId(), eve.getCandidateId()));
        proc2.processVote(u5.getVoterId(), List.of(eve.getCandidateId(),
            frank.getCandidateId(), dave.getCandidateId()));

        system.advanceStatus(rcv.getElectionId()); // VOTING_CLOSED

        ElectionResult rcvResult = proc2.tallyWith(new RankedChoiceStrategy());
        system.advanceStatus(rcv.getElectionId()); // RESULTS_PUBLISHED
        rcvResult.print(Map.of(
            dave.getCandidateId(), dave,
            eve.getCandidateId(), eve,
            frank.getCandidateId(), frank));

        // ===== SCENARIO 5: Approval Voting (Board Election) =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 5: Approval Voting — Corporate Board Election");
        System.out.println("=".repeat(65));

        Candidate b1c = new Candidate("Grace Liu",   "Nominee", "BOARD");
        Candidate b2c = new Candidate("Hiro Tanaka", "Nominee", "BOARD");
        Candidate b3c = new Candidate("Iris Okafor", "Nominee", "BOARD");

        Election board = ElectionFactory.corporateElection(
            "Board of Directors 2025", List.of(b1c, b2c, b3c), 2);
        StandardVotingProcessor proc3 = system.registerElection(board);

        system.advanceStatus(board.getElectionId());
        system.advanceStatus(board.getElectionId());

        Voter s1 = new Voter("EMP-001", "Shareholder A", "BOARD");
        Voter s2 = new Voter("EMP-002", "Shareholder B", "BOARD");
        Voter s3 = new Voter("EMP-003", "Shareholder C", "BOARD");

        for (Voter s : List.of(s1, s2, s3)) proc3.registerVoter(s);

        Ballot boardBallot = new Ballot.Builder(board.getElectionId(), "BOARD")
            .addCandidate(b1c).addCandidate(b2c).addCandidate(b3c)
            .method(VotingMethod.APPROVAL_VOTING)
            .maxSelections(2)
            .build();
        proc3.addBallot("BOARD", boardBallot);

        // Approval ballots: approve any subset
        proc3.processVote(s1.getVoterId(), List.of(b1c.getCandidateId(), b2c.getCandidateId()));
        proc3.processVote(s2.getVoterId(), List.of(b1c.getCandidateId(), b3c.getCandidateId()));
        proc3.processVote(s3.getVoterId(), List.of(b2c.getCandidateId(), b3c.getCandidateId()));

        system.advanceStatus(board.getElectionId());
        ElectionResult boardResult = proc3.tallyWith(new ApprovalVotingStrategy());
        system.advanceStatus(board.getElectionId());
        boardResult.print(Map.of(
            b1c.getCandidateId(), b1c,
            b2c.getCandidateId(), b2c,
            b3c.getCandidateId(), b3c));

        // ===== SCENARIO 6: Vote After Election Closed =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 6: Vote Cast After Election Closed");
        System.out.println("=".repeat(65));

        Voter lateVoter = new Voter("IND-999", "Late Voter", "NORTH");
        proc1.registerVoter(lateVoter);
        Vote lateVote = proc1.processVote(lateVoter.getVoterId(),
            List.of(alice.getCandidateId()));
        System.out.println("Late vote status: " + lateVote.getStatus());

        // ===== SCENARIO 7: Invalid Ballot Format =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 7: Invalid Ballot — Wrong Candidate ID");
        System.out.println("=".repeat(65));

        Election freshElec = ElectionFactory.generalElection("Test Election",
            List.of(new Candidate("P", "X", "TEST"), new Candidate("Q", "Y", "TEST")));
        StandardVotingProcessor procTest = system.registerElection(freshElec);
        system.advanceStatus(freshElec.getElectionId());
        system.advanceStatus(freshElec.getElectionId());

        Voter testVoter = new Voter("TST-001", "Test Voter", "TEST");
        procTest.registerVoter(testVoter);
        Ballot testBallot = new Ballot.Builder(freshElec.getElectionId(), "TEST")
            .addCandidate(freshElec.getCandidates().get(0))
            .addCandidate(freshElec.getCandidates().get(1))
            .method(VotingMethod.FIRST_PAST_THE_POST)
            .build();
        procTest.addBallot("TEST", testBallot);

        Vote invalid = procTest.processVote(testVoter.getVoterId(), List.of(99999L));
        System.out.println("Invalid candidate vote status: " + invalid.getStatus());

        // ===== SCENARIO 8: Audit Chain Verification =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 8: Audit Log Chain Integrity Verification");
        System.out.println("=".repeat(65));

        AuditLogger audit = system.getAuditLogger();
        System.out.println("Total audit entries: " + audit.getLog().size());
        audit.verifyChainIntegrity();

        System.out.println("\nLast 5 audit entries:");
        List<AuditEntry> log = audit.getLog();
        log.subList(Math.max(0, log.size() - 5), log.size())
            .forEach(System.out::println);

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern        | Class(es)
            ---------------|--------------------------------------------------
            Singleton      | ElectionSystem, AuditLogger (via ElectionSystem)
            State          | ElectionStatus — DRAFT→REGISTRATION→VOTING→CLOSED→PUBLISHED→ARCHIVED
            Strategy       | TallyStrategy → FirstPastThePost / RankedChoice / ApprovalVoting
            Observer       | VoteEventObserver → AuditLogger / FraudDetector / NotificationService
            Factory        | ElectionFactory (generalElection / referendum / corporate / rankedChoice)
            Builder        | Election.Builder, Ballot.Builder
            Command        | CastVoteCommand — execute() casts vote; undo() voids it (admin only)
            Template Method| AbstractVotingProcessor.processVote() — fixed skeleton, overrideable hooks
            """);
    }
}
