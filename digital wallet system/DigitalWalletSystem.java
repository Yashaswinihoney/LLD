import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ============================================================
// DIGITAL WALLET SYSTEM — LLD
//
// Requirements covered:
//   1. Create account + manage personal info
//   2. Add/remove payment methods (card, bank)
//   3. Fund transfers (user→user, user→external)
//   4. Transaction history + statement
//   5. Multi-currency + currency conversion
//   6. Security (encryption, fraud, rate limits)
//   7. Concurrent transactions + data consistency
//   8. Scalable design
//
// Design Patterns:
//   Singleton  — WalletService, CurrencyService, FraudDetectionService
//   Strategy   — TransferStrategy (instant / scheduled / batch)
//               CurrencyConversionStrategy (live / cached / fixed)
//   Observer   — TransactionEventObserver (notification, audit, fraud, analytics)
//   Factory    — PaymentMethodFactory (card / bank / UPI)
//   Builder    — Wallet, Transaction construction
//   State      — TransactionStatus (INITIATED→PROCESSING→COMPLETED/FAILED/REVERSED)
//               WalletStatus (ACTIVE→SUSPENDED→FROZEN→CLOSED)
//   Command    — TransferCommand (execute + reverse/rollback)
//   Iterator   — TransactionHistoryIterator (paginated filtered statement)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum WalletStatus       { ACTIVE, SUSPENDED, FROZEN, CLOSED }
enum TransactionStatus  { INITIATED, PROCESSING, COMPLETED, FAILED, REVERSED, PENDING_REVIEW }
enum TransactionType    { CREDIT, DEBIT, TRANSFER, REFUND, CASHBACK, FEE }
enum PaymentMethodType  { CREDIT_CARD, DEBIT_CARD, BANK_ACCOUNT, UPI, NET_BANKING }
enum KYCStatus          { PENDING, VERIFIED, REJECTED, EXPIRED }
enum Currency           { INR, USD, EUR, GBP, AED, JPY, SGD }
enum FraudRisk          { LOW, MEDIUM, HIGH, BLOCKED }

// ============================================================
// 2. MONEY — value object (Req 5 + 7: precision + thread-safe)
//    All amounts stored in smallest unit (paise, cents) to
//    avoid floating-point precision errors in financial calc
// ============================================================
class Money {
    private final long     amount;   // in smallest unit (paise / cents)
    private final Currency currency;

    public Money(long amount, Currency currency) {
        if (amount < 0) throw new IllegalArgumentException("Amount cannot be negative");
        this.amount   = amount;
        this.currency = currency;
    }

    public static Money of(double amount, Currency currency) {
        return new Money(Math.round(amount * 100), currency);
    }

    public Money add(Money other) {
        if (currency != other.currency)
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        return new Money(amount + other.amount, currency);
    }

    public Money subtract(Money other) {
        if (currency != other.currency)
            throw new IllegalArgumentException("Currency mismatch");
        if (amount < other.amount)
            throw new IllegalStateException("Insufficient balance");
        return new Money(amount - other.amount, currency);
    }

    public boolean isGreaterThanOrEqual(Money other) {
        if (currency != other.currency) throw new IllegalArgumentException("Currency mismatch");
        return amount >= other.amount;
    }

    public boolean isZero()             { return amount == 0; }
    public long     getAmount()         { return amount; }
    public Currency getCurrency()       { return currency; }
    public double   toDecimal()         { return amount / 100.0; }

    @Override public String toString() {
        return currency + " " + String.format("%.2f", toDecimal());
    }

    @Override public boolean equals(Object o) {
        return o instanceof Money m && amount == m.amount && currency == m.currency;
    }
}

// ============================================================
// 3. PAYMENT METHOD — FACTORY PATTERN (Req 2)
// ============================================================
abstract class PaymentMethod {
    private static final AtomicLong idGen = new AtomicLong(1);

    protected final long              methodId;
    protected final PaymentMethodType type;
    protected final String            maskedIdentifier; // last 4 digits etc.
    protected       boolean           isDefault;
    protected       boolean           isActive;
    protected final LocalDateTime     addedAt;

    public PaymentMethod(PaymentMethodType type, String maskedIdentifier) {
        this.methodId          = idGen.getAndIncrement();
        this.type              = type;
        this.maskedIdentifier  = maskedIdentifier;
        this.isActive          = true;
        this.isDefault         = false;
        this.addedAt           = LocalDateTime.now();
    }

    public abstract String getDisplayName();

    public long              getMethodId()  { return methodId; }
    public PaymentMethodType getType()      { return type; }
    public String            getMasked()    { return maskedIdentifier; }
    public boolean           isDefault()   { return isDefault; }
    public boolean           isActive()    { return isActive; }
    public void              setDefault(boolean d) { isDefault = d; }
    public void              deactivate()  { isActive = false; }

    @Override public String toString() {
        return getDisplayName() + " (" + maskedIdentifier + ")" +
               (isDefault ? " [DEFAULT]" : "");
    }
}

class CreditCard extends PaymentMethod {
    private final String cardNetwork; // VISA, MASTERCARD, AMEX
    private final String cardHolderName;
    private final String expiryMonth;
    private final String expiryYear;
    private final String tokenizedPAN; // Req 6: never store raw PAN

    public CreditCard(String lastFour, String network,
                       String holderName, String month, String year,
                       String token) {
        super(PaymentMethodType.CREDIT_CARD, "**** **** **** " + lastFour);
        this.cardNetwork    = network;
        this.cardHolderName = holderName;
        this.expiryMonth    = month;
        this.expiryYear     = year;
        this.tokenizedPAN   = token; // Req 6: tokenized by PCI vault
    }

    @Override public String getDisplayName() {
        return cardNetwork + " Credit Card";
    }
    public String getToken() { return tokenizedPAN; }
}

class BankAccount extends PaymentMethod {
    private final String bankName;
    private final String accountType; // SAVINGS / CURRENT
    private final String ifscCode;
    private final String encryptedAccountNumber; // Req 6: encrypted at rest

    public BankAccount(String lastFour, String bank,
                        String accountType, String ifsc,
                        String encryptedAccNum) {
        super(PaymentMethodType.BANK_ACCOUNT, "xxxx" + lastFour);
        this.bankName              = bank;
        this.accountType           = accountType;
        this.ifscCode              = ifsc;
        this.encryptedAccountNumber= encryptedAccNum;
    }

    @Override public String getDisplayName() {
        return bankName + " " + accountType;
    }
}

class UPIMethod extends PaymentMethod {
    private final String upiId; // alice@okicici

    public UPIMethod(String upiId) {
        super(PaymentMethodType.UPI, upiId);
        this.upiId = upiId;
    }

    @Override public String getDisplayName() { return "UPI: " + upiId; }
    public String getUpiId() { return upiId; }
}

// Factory
class PaymentMethodFactory {
    public static CreditCard creditCard(String lastFour, String network,
                                         String holderName, String month,
                                         String year) {
        String token = "TOK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new CreditCard(lastFour, network, holderName, month, year, token);
    }

    public static BankAccount bankAccount(String lastFour, String bank,
                                           String type, String ifsc) {
        String encAccNum = "ENC_" + lastFour; // In prod: AES-256 encryption
        return new BankAccount(lastFour, bank, type, ifsc, encAccNum);
    }

    public static UPIMethod upi(String upiId) {
        return new UPIMethod(upiId);
    }
}

// ============================================================
// 4. TRANSACTION — BUILDER PATTERN
//    STATE: INITIATED → PROCESSING → COMPLETED / FAILED / REVERSED
//    Req 4: every transaction is immutable after COMPLETED
// ============================================================
class Transaction {
    private static final AtomicLong idGen = new AtomicLong(1_000_000);

    private final  long              txnId;
    private final  String            txnRef;          // human-readable ref
    private final  long              fromWalletId;
    private final  long              toWalletId;      // 0 = external
    private final  Money             amount;
    private final  Money             feeAmount;
    private final  TransactionType   type;
    private        TransactionStatus status;
    private final  LocalDateTime     initiatedAt;
    private        LocalDateTime     completedAt;
    private        String            failureReason;
    private final  String            description;
    private final  String            idempotencyKey;  // Req 7: dedup
    private        String            externalTxnRef;  // from payment gateway

    private Transaction(Builder b) {
        this.txnId          = idGen.getAndIncrement();
        this.txnRef         = "TXN" + String.format("%010d", txnId);
        this.fromWalletId   = b.fromWalletId;
        this.toWalletId     = b.toWalletId;
        this.amount         = b.amount;
        this.feeAmount      = b.feeAmount;
        this.type           = b.type;
        this.status         = TransactionStatus.INITIATED;
        this.initiatedAt    = LocalDateTime.now();
        this.description    = b.description;
        this.idempotencyKey = b.idempotencyKey;
    }

    // ---- State transitions ----
    public void markProcessing()  { status = TransactionStatus.PROCESSING; }
    public void markCompleted(String extRef) {
        status          = TransactionStatus.COMPLETED;
        completedAt     = LocalDateTime.now();
        externalTxnRef  = extRef;
    }
    public void markFailed(String reason) {
        status        = TransactionStatus.FAILED;
        completedAt   = LocalDateTime.now();
        failureReason = reason;
    }
    public void markReversed() {
        status      = TransactionStatus.REVERSED;
        completedAt = LocalDateTime.now();
    }
    public void markPendingReview() { status = TransactionStatus.PENDING_REVIEW; }

    public long              getTxnId()          { return txnId; }
    public String            getTxnRef()         { return txnRef; }
    public long              getFromWalletId()   { return fromWalletId; }
    public long              getToWalletId()     { return toWalletId; }
    public Money             getAmount()         { return amount; }
    public Money             getFeeAmount()      { return feeAmount; }
    public TransactionType   getType()           { return type; }
    public TransactionStatus getStatus()         { return status; }
    public LocalDateTime     getInitiatedAt()    { return initiatedAt; }
    public LocalDateTime     getCompletedAt()    { return completedAt; }
    public String            getDescription()    { return description; }
    public String            getIdempotencyKey() { return idempotencyKey; }

    @Override public String toString() {
        return String.format("Txn[%s | %s → wallet#%d | %s | fee=%s | %s]",
            txnRef, fromWalletId > 0 ? "wallet#" + fromWalletId : "external",
            toWalletId, amount, feeAmount, status);
    }

    static class Builder {
        private final long          fromWalletId;
        private final long          toWalletId;
        private final Money         amount;
        private final TransactionType type;
        private       Money         feeAmount      = Money.of(0, Currency.INR);
        private       String        description    = "";
        private       String        idempotencyKey = UUID.randomUUID().toString();

        public Builder(long from, long to, Money amount, TransactionType type) {
            this.fromWalletId = from;
            this.toWalletId   = to;
            this.amount       = amount;
            this.type         = type;
        }
        public Builder fee(Money f)              { this.feeAmount = f;       return this; }
        public Builder description(String d)     { this.description = d;     return this; }
        public Builder idempotencyKey(String k)  { this.idempotencyKey = k;  return this; }
        public Transaction build()               { return new Transaction(this); }
    }
}

// ============================================================
// 5. WALLET — BUILDER PATTERN (Req 1 + 7)
//    Req 7: per-wallet ReentrantLock for concurrent-safe ops
//    WHY per-wallet and not global?
//    Alice sending to Bob and Carol sending to Dave are independent.
//    Only operations on the SAME wallet need to serialize.
// ============================================================
class Wallet {
    private static final AtomicLong idGen = new AtomicLong(100);

    private final  long              walletId;
    private final  long              userId;
    private        Money             balance;
    private        WalletStatus      status;
    private        KYCStatus         kycStatus;
    private final  Currency          primaryCurrency;
    private        Money             dailyTransferLimit;
    private        Money             dailyTransferUsed;
    private        LocalDateTime     limitResetAt;
    private final  LocalDateTime     createdAt;
    // Req 7: per-wallet fair lock
    private final  ReentrantLock     lock = new ReentrantLock(true);
    // Transaction log (append-only)
    private final  List<Transaction> txnHistory = new CopyOnWriteArrayList<>();

    private Wallet(Builder b) {
        this.walletId          = idGen.getAndIncrement();
        this.userId            = b.userId;
        this.balance           = new Money(0, b.primaryCurrency);
        this.status            = WalletStatus.ACTIVE;
        this.kycStatus         = KYCStatus.PENDING;
        this.primaryCurrency   = b.primaryCurrency;
        this.dailyTransferLimit= b.dailyTransferLimit;
        this.dailyTransferUsed = new Money(0, b.primaryCurrency);
        this.limitResetAt      = LocalDateTime.now().plusDays(1);
        this.createdAt         = LocalDateTime.now();
    }

    // ---- Req 7: atomic credit (under lock) ----
    public boolean credit(Money amount, Transaction txn) {
        lock.lock();
        try {
            if (!isOperational()) return false;
            balance = balance.add(amount);
            txnHistory.add(txn);
            System.out.printf("[Wallet #%d] CREDIT %s → balance=%s%n",
                walletId, amount, balance);
            return true;
        } finally { lock.unlock(); }
    }

    // ---- Req 7: atomic debit (under lock) ----
    public boolean debit(Money amount, Transaction txn) {
        lock.lock();
        try {
            if (!isOperational()) {
                System.out.println("[Wallet #" + walletId + "] Not operational: " + status);
                return false;
            }
            if (!balance.isGreaterThanOrEqual(amount)) {
                System.out.printf("[Wallet #%d] Insufficient balance: %s < %s%n",
                    walletId, balance, amount);
                return false;
            }
            // Req 6: daily limit check
            resetLimitIfNeeded();
            Money totalAmountWithUsed = dailyTransferUsed.add(amount);
            if (!dailyTransferLimit.isGreaterThanOrEqual(totalAmountWithUsed)) {
                System.out.printf("[Wallet #%d] Daily limit exceeded%n", walletId);
                return false;
            }
            balance            = balance.subtract(amount);
            dailyTransferUsed  = dailyTransferUsed.add(amount);
            txnHistory.add(txn);
            System.out.printf("[Wallet #%d] DEBIT %s → balance=%s%n",
                walletId, amount, balance);
            return true;
        } finally { lock.unlock(); }
    }

    private void resetLimitIfNeeded() {
        if (LocalDateTime.now().isAfter(limitResetAt)) {
            dailyTransferUsed = new Money(0, primaryCurrency);
            limitResetAt      = LocalDateTime.now().plusDays(1);
        }
    }

    public boolean isOperational() {
        return status == WalletStatus.ACTIVE && kycStatus == KYCStatus.VERIFIED;
    }

    // Req 1: KYC update
    public void updateKYC(KYCStatus newStatus) {
        this.kycStatus = newStatus;
        System.out.println("[Wallet #" + walletId + "] KYC → " + newStatus);
    }

    public void suspend(String reason) {
        status = WalletStatus.SUSPENDED;
        System.out.println("[Wallet #" + walletId + "] SUSPENDED: " + reason);
    }

    public void freeze(String reason) {
        status = WalletStatus.FROZEN;
        System.out.println("[Wallet #" + walletId + "] FROZEN: " + reason);
    }

    public void reactivate() {
        if (status == WalletStatus.SUSPENDED) {
            status = WalletStatus.ACTIVE;
            System.out.println("[Wallet #" + walletId + "] REACTIVATED");
        }
    }

    // Req 4: transaction history
    public List<Transaction> getHistory()       { return Collections.unmodifiableList(txnHistory); }
    public List<Transaction> getHistory(TransactionType type) {
        return txnHistory.stream()
            .filter(t -> t.getType() == type).collect(Collectors.toList());
    }
    public List<Transaction> getHistory(TransactionStatus status) {
        return txnHistory.stream()
            .filter(t -> t.getStatus() == status).collect(Collectors.toList());
    }
    public List<Transaction> getRecentHistory(int limit) {
        List<Transaction> all = new ArrayList<>(txnHistory);
        Collections.reverse(all);
        return all.stream().limit(limit).collect(Collectors.toList());
    }

    public long         getWalletId()          { return walletId; }
    public long         getUserId()            { return userId; }
    public Money        getBalance()           { return balance; }
    public WalletStatus getStatus()            { return status; }
    public KYCStatus    getKycStatus()         { return kycStatus; }
    public Currency     getPrimaryCurrency()   { return primaryCurrency; }

    @Override public String toString() {
        return String.format("Wallet[#%d | user=%d | balance=%s | %s | KYC=%s]",
            walletId, userId, balance, status, kycStatus);
    }

    static class Builder {
        private final long     userId;
        private       Currency primaryCurrency    = Currency.INR;
        private       Money    dailyTransferLimit = Money.of(100000, Currency.INR);

        public Builder(long userId)                        { this.userId = userId; }
        public Builder currency(Currency c)                { this.primaryCurrency = c; return this; }
        public Builder dailyLimit(double limit, Currency c){ this.dailyTransferLimit = Money.of(limit, c); return this; }
        public Wallet build()                              { return new Wallet(this); }
    }
}

// ============================================================
// 6. CURRENCY CONVERSION SERVICE — STRATEGY PATTERN (Req 5)
// ============================================================
interface CurrencyConversionStrategy {
    String getName();
    Money convert(Money amount, Currency toCurrency);
    double getRate(Currency from, Currency to);
}

// Simulated exchange rates (in production: pull from forex API)
class LiveRateConversionStrategy implements CurrencyConversionStrategy {
    // Rates relative to INR as base
    private static final Map<Currency, Double> RATES_TO_INR = Map.of(
        Currency.INR, 1.0,
        Currency.USD, 83.5,
        Currency.EUR, 90.2,
        Currency.GBP, 105.8,
        Currency.AED, 22.7,
        Currency.JPY,  0.56,
        Currency.SGD, 62.3
    );

    @Override public String getName() { return "LiveRate"; }

    @Override
    public double getRate(Currency from, Currency to) {
        double fromInINR = RATES_TO_INR.getOrDefault(from, 1.0);
        double toInINR   = RATES_TO_INR.getOrDefault(to,   1.0);
        return fromInINR / toInINR;
    }

    @Override
    public Money convert(Money amount, Currency toCurrency) {
        if (amount.getCurrency() == toCurrency) return amount;
        double rate          = getRate(amount.getCurrency(), toCurrency);
        long   convertedPaise= Math.round(amount.getAmount() * rate);
        return new Money(convertedPaise, toCurrency);
    }
}

class CurrencyService {
    private static volatile CurrencyService instance;
    private CurrencyConversionStrategy strategy = new LiveRateConversionStrategy();

    private CurrencyService() {}

    public static CurrencyService getInstance() {
        if (instance == null) {
            synchronized (CurrencyService.class) {
                if (instance == null) instance = new CurrencyService();
            }
        }
        return instance;
    }

    public void setStrategy(CurrencyConversionStrategy s) { this.strategy = s; }
    public Money convert(Money amount, Currency to)        { return strategy.convert(amount, to); }
    public double getRate(Currency from, Currency to)      { return strategy.getRate(from, to); }
}

// ============================================================
// 7. FRAUD DETECTION SERVICE — SINGLETON (Req 6)
// ============================================================
class FraudDetectionService {
    private static volatile FraudDetectionService instance;

    // Simple rules (in production: ML model)
    private static final Money LARGE_TXN_THRESHOLD  = Money.of(50000, Currency.INR);
    private static final int   MAX_TXN_PER_MIN      = 5;
    // userId → recent txn timestamps
    private final ConcurrentHashMap<Long, List<LocalDateTime>> recentActivity
        = new ConcurrentHashMap<>();

    private FraudDetectionService() {}

    public static FraudDetectionService getInstance() {
        if (instance == null) {
            synchronized (FraudDetectionService.class) {
                if (instance == null) instance = new FraudDetectionService();
            }
        }
        return instance;
    }

    public FraudRisk assess(long userId, Money amount) {
        // Rule 1: large transaction → MEDIUM risk
        if (amount.isGreaterThanOrEqual(LARGE_TXN_THRESHOLD)) {
            System.out.printf("[Fraud] Large transaction detected: %s for user#%d%n",
                amount, userId);
            return FraudRisk.MEDIUM;
        }

        // Rule 2: velocity check — too many txns in 1 minute
        List<LocalDateTime> recent = recentActivity
            .computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        LocalDateTime oneMinAgo = LocalDateTime.now().minusMinutes(1);
        recent.removeIf(t -> t.isBefore(oneMinAgo));

        if (recent.size() >= MAX_TXN_PER_MIN) {
            System.out.printf("[Fraud] Velocity exceeded for user#%d (%d txns/min)%n",
                userId, recent.size());
            return FraudRisk.HIGH;
        }

        recent.add(LocalDateTime.now());
        return FraudRisk.LOW;
    }

    public void flagUser(long userId) {
        System.out.println("[Fraud] User#" + userId + " flagged for review");
    }
}

// ============================================================
// 8. TRANSFER STRATEGY — STRATEGY PATTERN (Req 3 + 8)
// ============================================================
interface TransferStrategy {
    String getName();
    // Returns transaction ref on success
    String execute(Wallet from, Wallet to, Money amount,
                   Transaction txn) throws TransferException;
}

class TransferException extends Exception {
    public TransferException(String msg) { super(msg); }
}

// Instant transfer — both debit + credit in one atomic operation
class InstantTransferStrategy implements TransferStrategy {
    @Override public String getName() { return "Instant"; }

    @Override
    public String execute(Wallet from, Wallet to, Money amount,
                          Transaction txn) throws TransferException {
        txn.markProcessing();
        // Debit sender
        if (!from.debit(amount, txn)) {
            txn.markFailed("Debit failed");
            throw new TransferException("Could not debit from wallet#" + from.getWalletId());
        }
        // Credit receiver
        if (!to.credit(amount, txn)) {
            // Rollback: re-credit sender
            from.credit(amount, txn);
            txn.markFailed("Credit failed — debited amount refunded");
            throw new TransferException("Could not credit to wallet#" + to.getWalletId());
        }
        String ref = "INST_" + System.currentTimeMillis();
        txn.markCompleted(ref);
        return ref;
    }
}

// ============================================================
// 9. OBSERVER — TRANSACTION EVENTS (Req 4 + 6)
// ============================================================
interface TransactionEventObserver {
    void onTransactionInitiated(Transaction txn);
    void onTransactionCompleted(Transaction txn);
    void onTransactionFailed(Transaction txn, String reason);
    void onTransactionReversed(Transaction txn);
}

class NotificationObserver implements TransactionEventObserver {
    @Override public void onTransactionInitiated(Transaction txn) {
        System.out.printf("[Notif] Transaction %s initiated: %s%n",
            txn.getTxnRef(), txn.getAmount());
    }
    @Override public void onTransactionCompleted(Transaction txn) {
        System.out.printf("[Notif] ✅ %s completed: %s | %s%n",
            txn.getTxnRef(), txn.getAmount(), txn.getDescription());
    }
    @Override public void onTransactionFailed(Transaction txn, String reason) {
        System.out.printf("[Notif] ❌ %s FAILED: %s%n", txn.getTxnRef(), reason);
    }
    @Override public void onTransactionReversed(Transaction txn) {
        System.out.printf("[Notif] ↩ %s REVERSED%n", txn.getTxnRef());
    }
}

class AuditObserver implements TransactionEventObserver {
    private final List<String> log = new CopyOnWriteArrayList<>();

    @Override public void onTransactionInitiated(Transaction txn) {
        log.add(LocalDateTime.now() + " | INITIATED | " + txn.getTxnRef() + " | " + txn.getAmount());
    }
    @Override public void onTransactionCompleted(Transaction txn) {
        log.add(LocalDateTime.now() + " | COMPLETED | " + txn.getTxnRef());
    }
    @Override public void onTransactionFailed(Transaction txn, String reason) {
        log.add(LocalDateTime.now() + " | FAILED | " + txn.getTxnRef() + " | " + reason);
    }
    @Override public void onTransactionReversed(Transaction txn) {
        log.add(LocalDateTime.now() + " | REVERSED | " + txn.getTxnRef());
    }

    public List<String> getLog() { return Collections.unmodifiableList(log); }
    public void printLog() {
        System.out.println("\n[Audit Log]");
        log.forEach(e -> System.out.println("  " + e));
    }
}

class AnalyticsObserver implements TransactionEventObserver {
    private long totalCompleted = 0;
    private long totalFailed    = 0;
    private long totalVolume    = 0; // in paise

    @Override public synchronized void onTransactionCompleted(Transaction txn) {
        totalCompleted++;
        totalVolume += txn.getAmount().getAmount();
    }
    @Override public synchronized void onTransactionFailed(Transaction t, String r){ totalFailed++; }
    @Override public void onTransactionInitiated(Transaction t) {}
    @Override public void onTransactionReversed(Transaction t) {}

    public void printReport() {
        System.out.printf("%n[Analytics] Txns completed=%d failed=%d volume=%s%n",
            totalCompleted, totalFailed,
            new Money(totalVolume, Currency.INR));
    }
}

// ============================================================
// 10. TRANSFER COMMAND — COMMAND PATTERN (Req 3 + 7)
//     execute() = validate → fraud check → debit → credit
//     reverse() = re-credit sender → mark reversed
// ============================================================
class TransferCommand {
    private final Wallet                       fromWallet;
    private final Wallet                       toWallet;
    private final Money                        amount;
    private final Transaction                  txn;
    private final TransferStrategy             strategy;
    private final FraudDetectionService        fraud;
    private final List<TransactionEventObserver> observers;
    private       boolean                      executed = false;

    public TransferCommand(Wallet from, Wallet to, Money amount,
                            Transaction txn, TransferStrategy strategy,
                            FraudDetectionService fraud,
                            List<TransactionEventObserver> observers) {
        this.fromWallet = from;
        this.toWallet   = to;
        this.amount     = amount;
        this.txn        = txn;
        this.strategy   = strategy;
        this.fraud      = fraud;
        this.observers  = observers;
    }

    public boolean execute() {
        observers.forEach(o -> o.onTransactionInitiated(txn));

        // Req 6: fraud check
        FraudRisk risk = fraud.assess(fromWallet.getUserId(), amount);
        if (risk == FraudRisk.HIGH || risk == FraudRisk.BLOCKED) {
            txn.markPendingReview();
            observers.forEach(o -> o.onTransactionFailed(txn, "Fraud risk: " + risk));
            return false;
        }
        if (risk == FraudRisk.MEDIUM) {
            System.out.println("[TransferCmd] MEDIUM risk — proceeding with extra logging");
        }

        try {
            strategy.execute(fromWallet, toWallet, amount, txn);
            executed = true;
            observers.forEach(o -> o.onTransactionCompleted(txn));
            return true;
        } catch (TransferException e) {
            observers.forEach(o -> o.onTransactionFailed(txn, e.getMessage()));
            return false;
        }
    }

    public boolean reverse() {
        if (!executed) return false;
        // Re-credit the sender
        Transaction reversalTxn = new Transaction.Builder(
            toWallet.getWalletId(), fromWallet.getWalletId(),
            amount, TransactionType.REFUND)
            .description("Reversal of " + txn.getTxnRef())
            .build();

        fromWallet.credit(amount, reversalTxn);
        toWallet.debit(amount, reversalTxn);
        txn.markReversed();
        executed = false;
        observers.forEach(o -> o.onTransactionReversed(txn));
        return true;
    }
}

// ============================================================
// 11. TRANSACTION HISTORY ITERATOR (Req 4)
// ============================================================
class TransactionStatement {
    private List<Transaction> transactions;

    public TransactionStatement(List<Transaction> all) {
        this.transactions = new ArrayList<>(all);
    }

    public TransactionStatement byType(TransactionType type) {
        transactions = transactions.stream()
            .filter(t -> t.getType() == type)
            .collect(Collectors.toList());
        return this;
    }

    public TransactionStatement byStatus(TransactionStatus status) {
        transactions = transactions.stream()
            .filter(t -> t.getStatus() == status)
            .collect(Collectors.toList());
        return this;
    }

    public TransactionStatement between(LocalDateTime from, LocalDateTime to) {
        transactions = transactions.stream()
            .filter(t -> !t.getInitiatedAt().isBefore(from) &&
                         !t.getInitiatedAt().isAfter(to))
            .collect(Collectors.toList());
        return this;
    }

    public TransactionStatement latest(int n) {
        transactions = transactions.stream()
            .sorted(Comparator.comparing(Transaction::getInitiatedAt).reversed())
            .limit(n)
            .collect(Collectors.toList());
        return this;
    }

    public void print(String header) {
        System.out.println("\n═══ " + header + " ═══");
        System.out.printf("%-15s %-12s %-15s %-12s %-10s%n",
            "TxnRef", "Type", "Amount", "Status", "Date");
        System.out.println("─".repeat(68));
        transactions.forEach(t ->
            System.out.printf("%-15s %-12s %-15s %-12s %-10s%n",
                t.getTxnRef(), t.getType(),
                t.getAmount(), t.getStatus(),
                t.getInitiatedAt().toLocalDate()));
        System.out.println("─".repeat(68));
        System.out.println("Total transactions: " + transactions.size());
    }

    public List<Transaction> toList() { return Collections.unmodifiableList(transactions); }
}

// ============================================================
// 12. WALLET SERVICE — SINGLETON (Req 1 + 7 + 8)
// ============================================================
class WalletService {
    private static volatile WalletService instance;

    private final ConcurrentHashMap<Long, Wallet>         wallets           = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<PaymentMethod>> paymentMethods = new ConcurrentHashMap<>();
    // Req 7: idempotency — txnRef → result (prevents duplicate transactions)
    private final ConcurrentHashMap<String, Boolean>      idempotencyCache  = new ConcurrentHashMap<>();
    // txnId → TransferCommand (for reversal)
    private final ConcurrentHashMap<Long, TransferCommand> commandStore      = new ConcurrentHashMap<>();

    private final CurrencyService        currencyService = CurrencyService.getInstance();
    private final FraudDetectionService  fraudService    = FraudDetectionService.getInstance();
    private final List<TransactionEventObserver> observers = new ArrayList<>();
    private final AuditObserver          audit           = new AuditObserver();
    private final AnalyticsObserver      analytics       = new AnalyticsObserver();
    private       TransferStrategy       transferStrategy = new InstantTransferStrategy();

    private WalletService() {
        observers.add(new NotificationObserver());
        observers.add(audit);
        observers.add(analytics);
    }

    public static WalletService getInstance() {
        if (instance == null) {
            synchronized (WalletService.class) {
                if (instance == null) instance = new WalletService();
            }
        }
        return instance;
    }

    // ---- Req 1: Create wallet ----
    public Wallet createWallet(long userId, Currency currency) {
        Wallet wallet = new Wallet.Builder(userId)
            .currency(currency)
            .dailyLimit(100000, currency)
            .build();
        wallets.put(wallet.getWalletId(), wallet);
        paymentMethods.put(wallet.getWalletId(), new CopyOnWriteArrayList<>());
        System.out.println("[WalletSvc] Created: " + wallet);
        return wallet;
    }

    // ---- KYC verification ----
    public void verifyKYC(long walletId) {
        Wallet w = wallets.get(walletId);
        if (w != null) w.updateKYC(KYCStatus.VERIFIED);
    }

    // ---- Req 2: Add payment method ----
    public void addPaymentMethod(long walletId, PaymentMethod method) {
        List<PaymentMethod> methods = paymentMethods.get(walletId);
        if (methods == null) return;
        // First method becomes default
        if (methods.isEmpty()) method.setDefault(true);
        methods.add(method);
        System.out.println("[WalletSvc] Added " + method + " to wallet#" + walletId);
    }

    // ---- Req 2: Remove payment method ----
    public boolean removePaymentMethod(long walletId, long methodId) {
        List<PaymentMethod> methods = paymentMethods.get(walletId);
        if (methods == null) return false;
        boolean removed = methods.removeIf(m -> m.getMethodId() == methodId);
        if (removed) System.out.println("[WalletSvc] Removed method#" + methodId);
        return removed;
    }

    // ---- Top up wallet (credit from external source) ----
    public boolean topUp(long walletId, Money amount, String idempotencyKey) {
        // Req 7: idempotency check
        if (idempotencyCache.putIfAbsent(idempotencyKey, true) != null) {
            System.out.println("[WalletSvc] Duplicate top-up detected: " + idempotencyKey);
            return false;
        }
        Wallet wallet = wallets.get(walletId);
        if (wallet == null) return false;

        Transaction txn = new Transaction.Builder(0, walletId, amount, TransactionType.CREDIT)
            .description("Top-up").idempotencyKey(idempotencyKey).build();

        boolean ok = wallet.credit(amount, txn);
        if (ok) observers.forEach(o -> o.onTransactionCompleted(txn));
        return ok;
    }

    // ---- Req 3: Transfer wallet to wallet ----
    public boolean transfer(long fromWalletId, long toWalletId,
                             Money amount, String description,
                             String idempotencyKey) {
        // Req 7: idempotency
        if (idempotencyCache.putIfAbsent(idempotencyKey, true) != null) {
            System.out.println("[WalletSvc] Duplicate transfer: " + idempotencyKey);
            return false;
        }

        Wallet from = wallets.get(fromWalletId);
        Wallet to   = wallets.get(toWalletId);
        if (from == null || to == null) return false;

        // Req 5: currency conversion if needed
        Money toAmount = amount;
        if (from.getPrimaryCurrency() != to.getPrimaryCurrency()) {
            toAmount = currencyService.convert(amount, to.getPrimaryCurrency());
            System.out.printf("[WalletSvc] Converting %s → %s%n", amount, toAmount);
        }

        // Fee: 0.5% for cross-currency, 0 for same currency
        Money fee = (from.getPrimaryCurrency() != to.getPrimaryCurrency())
            ? new Money(amount.getAmount() / 200, amount.getCurrency())
            : new Money(0, amount.getCurrency());

        Transaction txn = new Transaction.Builder(
            fromWalletId, toWalletId, amount, TransactionType.TRANSFER)
            .fee(fee)
            .description(description)
            .idempotencyKey(idempotencyKey)
            .build();

        TransferCommand cmd = new TransferCommand(
            from, to, amount, txn, transferStrategy, fraudService, observers);

        boolean ok = cmd.execute();
        if (ok) commandStore.put(txn.getTxnId(), cmd);
        return ok;
    }

    // ---- Reverse a transaction ----
    public boolean reverse(long txnId) {
        TransferCommand cmd = commandStore.get(txnId);
        return cmd != null && cmd.reverse();
    }

    // ---- Req 4: Statement ----
    public TransactionStatement getStatement(long walletId) {
        Wallet wallet = wallets.get(walletId);
        if (wallet == null) return new TransactionStatement(Collections.emptyList());
        return new TransactionStatement(wallet.getHistory());
    }

    // ---- Req 5: Currency info ----
    public double getExchangeRate(Currency from, Currency to) {
        return currencyService.getRate(from, to);
    }

    public Wallet getWallet(long id) { return wallets.get(id); }
    public void printAuditLog()      { audit.printLog(); }
    public void printAnalytics()     { analytics.printReport(); }
}

// ============================================================
// 13. MAIN — DRIVER CODE
// ============================================================
public class DigitalWalletSystem {
    public static void main(String[] args) throws InterruptedException {

        WalletService service = WalletService.getInstance();

        // ===== SCENARIO 1: Req 1 — Create wallets + KYC =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Create Wallets + KYC (Req 1)");
        System.out.println("=".repeat(60));

        Wallet alice = service.createWallet(1001L, Currency.INR);
        Wallet bob   = service.createWallet(1002L, Currency.INR);
        Wallet carol = service.createWallet(1003L, Currency.USD);
        Wallet dave  = service.createWallet(1004L, Currency.INR);

        service.verifyKYC(alice.getWalletId());
        service.verifyKYC(bob.getWalletId());
        service.verifyKYC(carol.getWalletId());
        service.verifyKYC(dave.getWalletId());

        // ===== SCENARIO 2: Req 2 — Add payment methods =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Add Payment Methods (Req 2)");
        System.out.println("=".repeat(60));

        service.addPaymentMethod(alice.getWalletId(),
            PaymentMethodFactory.creditCard("4242", "VISA", "Alice Kumar", "12", "2026"));
        service.addPaymentMethod(alice.getWalletId(),
            PaymentMethodFactory.bankAccount("9876", "HDFC", "SAVINGS", "HDFC0001234"));
        service.addPaymentMethod(alice.getWalletId(),
            PaymentMethodFactory.upi("alice@okicici"));

        service.addPaymentMethod(bob.getWalletId(),
            PaymentMethodFactory.upi("bob@oksbi"));
        service.addPaymentMethod(bob.getWalletId(),
            PaymentMethodFactory.bankAccount("1111", "SBI", "SAVINGS", "SBI0004321"));

        // ===== SCENARIO 3: Top-up wallets =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Top-up Wallets");
        System.out.println("=".repeat(60));

        service.topUp(alice.getWalletId(), Money.of(50000, Currency.INR), "TOPUP-ALICE-001");
        service.topUp(bob.getWalletId(),   Money.of(20000, Currency.INR), "TOPUP-BOB-001");
        service.topUp(carol.getWalletId(), Money.of(1000, Currency.USD),   "TOPUP-CAROL-001");
        service.topUp(dave.getWalletId(),  Money.of(10000, Currency.INR),  "TOPUP-DAVE-001");

        // Idempotency check — duplicate top-up
        service.topUp(alice.getWalletId(), Money.of(50000, Currency.INR), "TOPUP-ALICE-001");

        System.out.println("\nBalances after top-up:");
        System.out.println("  Alice: " + alice.getBalance());
        System.out.println("  Bob:   " + bob.getBalance());
        System.out.println("  Carol: " + carol.getBalance());

        // ===== SCENARIO 4: Req 3 — Fund transfers =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Fund Transfers (Req 3)");
        System.out.println("=".repeat(60));

        // Alice → Bob
        boolean t1 = service.transfer(alice.getWalletId(), bob.getWalletId(),
            Money.of(5000, Currency.INR), "Dinner split", "TXN-AB-001");
        System.out.println("Alice→Bob transfer: " + t1);

        // Bob → Dave
        boolean t2 = service.transfer(bob.getWalletId(), dave.getWalletId(),
            Money.of(2000, Currency.INR), "Rent share", "TXN-BD-001");
        System.out.println("Bob→Dave transfer: " + t2);

        // Insufficient balance attempt
        boolean t3 = service.transfer(dave.getWalletId(), alice.getWalletId(),
            Money.of(50000, Currency.INR), "Too much", "TXN-DA-001");
        System.out.println("Dave→Alice (insufficient): " + t3);

        // ===== SCENARIO 5: Req 5 — Multi-currency transfer =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Multi-Currency Transfer (Req 5)");
        System.out.println("=".repeat(60));

        System.out.println("Exchange rates:");
        System.out.printf("  1 USD = %.2f INR%n",
            service.getExchangeRate(Currency.USD, Currency.INR));
        System.out.printf("  1 EUR = %.2f INR%n",
            service.getExchangeRate(Currency.EUR, Currency.INR));
        System.out.printf("  1 GBP = %.2f INR%n",
            service.getExchangeRate(Currency.GBP, Currency.INR));

        // Carol (USD) → Alice (INR): cross-currency transfer
        boolean crossCurrency = service.transfer(carol.getWalletId(), alice.getWalletId(),
            Money.of(100, Currency.USD), "Cross-currency transfer", "TXN-CA-001");
        System.out.println("Carol(USD)→Alice(INR): " + crossCurrency);
        System.out.println("  Carol balance after: " + carol.getBalance());
        System.out.println("  Alice balance after: " + alice.getBalance());

        // ===== SCENARIO 6: Req 7 — Concurrent transfers =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Concurrent Transfers (Req 7)");
        System.out.println("=".repeat(60));

        System.out.println("Alice balance before concurrent: " + alice.getBalance());

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Boolean> concurrentResults = new CopyOnWriteArrayList<>();

        // 5 concurrent transfers from Alice to Bob
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            pool.submit(() -> {
                boolean ok = service.transfer(
                    alice.getWalletId(), bob.getWalletId(),
                    Money.of(2000, Currency.INR),
                    "Concurrent-" + idx,
                    "TXN-CONCURRENT-" + idx);
                concurrentResults.add(ok);
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long succeeded = concurrentResults.stream().filter(r -> r).count();
        System.out.println("Concurrent transfers succeeded: " + succeeded + "/5");
        System.out.println("Alice balance after concurrent: " + alice.getBalance());

        // ===== SCENARIO 7: Req 4 — Transaction statement =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Transaction Statement (Req 4)");
        System.out.println("=".repeat(60));

        service.getStatement(alice.getWalletId()).print("Alice's Full Statement");
        service.getStatement(alice.getWalletId())
            .byType(TransactionType.TRANSFER)
            .print("Alice's Transfers Only");

        service.getStatement(bob.getWalletId())
            .latest(5)
            .print("Bob's Last 5 Transactions");

        // ===== SCENARIO 8: Req 6 — Fraud detection =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Fraud Detection (Req 6)");
        System.out.println("=".repeat(60));

        // Large transaction triggers MEDIUM fraud risk
        boolean largeTxn = service.transfer(alice.getWalletId(), bob.getWalletId(),
            Money.of(75000, Currency.INR), "Large transfer", "TXN-LARGE-001");
        System.out.println("Large txn result: " + largeTxn);

        // ===== ANALYTICS =====
        service.printAuditLog();
        service.printAnalytics();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|-----------------------------------------------------------
            Singleton  | WalletService, CurrencyService, FraudDetectionService
            State      | TransactionStatus: INITIATED→PROCESSING→COMPLETED/FAILED
                       | WalletStatus: ACTIVE→SUSPENDED→FROZEN→CLOSED
            Strategy   | TransferStrategy (Instant / Scheduled)
                       | CurrencyConversionStrategy (Live / Cached / Fixed)
            Observer   | TransactionEventObserver (Notification / Audit / Analytics)
            Factory    | PaymentMethodFactory (creditCard / bankAccount / upi)
            Builder    | Wallet.Builder, Transaction.Builder
            Command    | TransferCommand: execute() + reverse() (rollback)
            Iterator   | TransactionStatement: chainable filter + paginate
            """);

        System.out.println("===== THREAD-SAFETY (Req 7) =====");
        System.out.println("""
            Class            | Mechanism                 | Why
            -----------------|---------------------------|--------------------------
            Wallet.debit()   | ReentrantLock(fair=true)  | Atomic balance change
            Wallet.credit()  | Same per-wallet lock      | No concurrent write race
            WalletService    | ConcurrentHashMap         | Safe concurrent map ops
            Idempotency      | putIfAbsent()             | Atomic dedup check
            Txn History      | CopyOnWriteArrayList      | Safe reads >> writes
            """);
    }
}
