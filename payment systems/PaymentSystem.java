import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// PAYMENT SYSTEM LLD
// Patterns:
//   Singleton  — PaymentService, TransactionLedger
//   Strategy   — PaymentGatewayStrategy (Razorpay/Stripe/Wallet/UPI)
//   Factory    — PaymentMethodFactory
//   Builder    — Transaction, Refund construction
//   State      — TransactionStatus (INITIATED→PROCESSING→SUCCESS/FAILED)
//   Observer   — PaymentEventObserver (notification/ledger/analytics)
//   Chain of Responsibility — PaymentValidator (amount/method/fraud/duplicate)
//   Command+Saga — splitPayment with rollback on partial failure
//   Value Object — Money (avoids floating point precision errors)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum TransactionStatus {
    INITIATED, PROCESSING, SUCCESS, FAILED,
    REFUND_INITIATED, REFUNDED, PARTIALLY_REFUNDED,
    DISPUTED, EXPIRED
}

enum PaymentMethodType {
    UPI, CREDIT_CARD, DEBIT_CARD,
    NET_BANKING, WALLET, BNPL, BANK_TRANSFER
}

enum Currency { INR, USD, EUR, GBP }

enum RefundReason {
    CUSTOMER_REQUEST, ORDER_CANCELLED, FRAUD_DETECTED,
    DUPLICATE_CHARGE, ITEM_NOT_RECEIVED, DEFECTIVE_ITEM
}

enum GatewayProvider { RAZORPAY, STRIPE, PAYTM, INTERNAL }

// ==========================================
// 2. MONEY — VALUE OBJECT
// Store in smallest unit (paise) to avoid floating-point errors
// e.g. 0.1 + 0.2 = 0.30000000000000004 in double — unacceptable for payments
// ==========================================
class Money {
    private final long     amountPaise; // ₹1 = 100 paise
    private final Currency currency;

    public Money(double amount, Currency currency) {
        this.amountPaise = Math.round(amount * 100);
        this.currency    = currency;
    }

    private Money(long paise, Currency currency) {
        this.amountPaise = paise;
        this.currency    = currency;
    }

    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(amountPaise + other.amountPaise, currency);
    }

    public Money subtract(Money other) {
        validateSameCurrency(other);
        if (amountPaise < other.amountPaise)
            throw new IllegalArgumentException("Result would be negative");
        return new Money(amountPaise - other.amountPaise, currency);
    }

    public boolean isGreaterThan(Money other) {
        validateSameCurrency(other);
        return amountPaise > other.amountPaise;
    }

    public boolean isZero() { return amountPaise == 0; }

    private void validateSameCurrency(Money other) {
        if (currency != other.currency)
            throw new IllegalArgumentException(
                "Currency mismatch: " + currency + " vs " + other.currency);
    }

    public double   getAmount()   { return amountPaise / 100.0; }
    public long     getPaise()    { return amountPaise; }
    public Currency getCurrency() { return currency; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Money m)) return false;
        return amountPaise == m.amountPaise && currency == m.currency;
    }

    @Override
    public String toString() {
        return currency + " " + String.format("%.2f", getAmount());
    }
}

// ==========================================
// 3. PAYMENT METHODS
// ==========================================
abstract class PaymentMethod {
    protected final String            id;
    protected final PaymentMethodType type;
    protected final String            ownerId;
    protected       boolean           isActive = true;

    protected PaymentMethod(String id, PaymentMethodType type, String ownerId) {
        this.id      = id;
        this.type    = type;
        this.ownerId = ownerId;
    }

    public abstract String getMaskedDisplay();

    public String            getId()       { return id; }
    public PaymentMethodType getType()     { return type; }
    public boolean           isActive()    { return isActive; }
    public void              deactivate()  { isActive = false; }
}

// UPI — Virtual Payment Address
class UpiMethod extends PaymentMethod {
    private final String vpa; // e.g. alice@okicici

    public UpiMethod(String vpa, String ownerId) {
        super("upi-" + vpa, PaymentMethodType.UPI, ownerId);
        this.vpa = vpa;
    }

    public String getVpa() { return vpa; }

    @Override public String getMaskedDisplay() { return "UPI: " + vpa; }
}

// Card — credit or debit
class CardMethod extends PaymentMethod {
    private final String  last4;
    private final String  network;    // VISA, MASTERCARD, RUPAY
    private final String  holderName;
    private final String  expiry;     // MM/YY
    private final boolean isCredit;

    public CardMethod(String last4, String network, String holderName,
                      String expiry, boolean isCredit, String ownerId) {
        super("card-" + last4 + "-" + ownerId,
            isCredit ? PaymentMethodType.CREDIT_CARD : PaymentMethodType.DEBIT_CARD,
            ownerId);
        this.last4      = last4;
        this.network    = network;
        this.holderName = holderName;
        this.expiry     = expiry;
        this.isCredit   = isCredit;
    }

    @Override
    public String getMaskedDisplay() {
        return (isCredit ? "CC" : "DC") + " " + network + " ****" + last4 + " (" + expiry + ")";
    }
}

// Wallet — internal balance
class WalletMethod extends PaymentMethod {
    private Money balance;

    public WalletMethod(String ownerId, double initialBalance, Currency currency) {
        super("wallet-" + ownerId, PaymentMethodType.WALLET, ownerId);
        this.balance = new Money(initialBalance, currency);
    }

    public boolean hasSufficientBalance(Money amount) {
        return balance.getPaise() >= amount.getPaise();
    }

    public synchronized void debit(Money amount) {
        balance = balance.subtract(amount);
        System.out.println("[Wallet] Debited " + amount +
            " | Remaining: " + balance);
    }

    public synchronized void credit(Money amount) {
        balance = balance.add(amount);
        System.out.println("[Wallet] Credited " + amount +
            " | New balance: " + balance);
    }

    public Money getBalance() { return balance; }

    @Override
    public String getMaskedDisplay() {
        return "Wallet [Balance: " + balance + "]";
    }
}

// ==========================================
// 4. PAYMENT METHOD FACTORY
// ==========================================
class PaymentMethodFactory {
    public static UpiMethod createUpi(String vpa, String ownerId) {
        return new UpiMethod(vpa, ownerId);
    }

    public static CardMethod createCreditCard(String last4, String network,
            String holder, String expiry, String ownerId) {
        return new CardMethod(last4, network, holder, expiry, true, ownerId);
    }

    public static CardMethod createDebitCard(String last4, String network,
            String holder, String expiry, String ownerId) {
        return new CardMethod(last4, network, holder, expiry, false, ownerId);
    }

    public static WalletMethod createWallet(String ownerId,
            double balance, Currency currency) {
        return new WalletMethod(ownerId, balance, currency);
    }
}

// ==========================================
// 5. TRANSACTION — BUILDER + STATE
// ==========================================
class Transaction {
    private static final AtomicLong idGen = new AtomicLong(100_000);

    private final  String            id;
    private final  String            orderId;
    private final  String            payerId;
    private final  String            payeeId;
    private final  Money             amount;
    private final  PaymentMethod     method;
    private final  String            idempotencyKey;
    private        TransactionStatus status;
    private        String            gatewayTxnId;
    private        String            failureReason;
    private final  LocalDateTime     initiatedAt;
    private        LocalDateTime     completedAt;
    private        Money             refundedAmount;

    private Transaction(Builder b) {
        this.id             = "txn_" + idGen.getAndIncrement();
        this.orderId        = b.orderId;
        this.payerId        = b.payerId;
        this.payeeId        = b.payeeId;
        this.amount         = b.amount;
        this.method         = b.method;
        this.idempotencyKey = b.idempotencyKey;
        this.status         = TransactionStatus.INITIATED;
        this.initiatedAt    = LocalDateTime.now();
        this.refundedAmount = new Money(0, b.amount.getCurrency());
    }

    // ---- State transitions ----
    public void markProcessing() {
        if (status == TransactionStatus.INITIATED) {
            status = TransactionStatus.PROCESSING;
            System.out.println("[Txn " + id + "] INITIATED → PROCESSING");
        }
    }

    public void markSuccess(String gwTxnId) {
        if (status == TransactionStatus.PROCESSING) {
            status          = TransactionStatus.SUCCESS;
            gatewayTxnId    = gwTxnId;
            completedAt     = LocalDateTime.now();
            System.out.println("[Txn " + id + "] → SUCCESS | GW: " +
                gwTxnId + " | " + amount);
        }
    }

    public void markFailed(String reason) {
        if (status == TransactionStatus.PROCESSING ||
            status == TransactionStatus.INITIATED) {
            status        = TransactionStatus.FAILED;
            failureReason = reason;
            completedAt   = LocalDateTime.now();
            System.out.println("[Txn " + id + "] → FAILED: " + reason);
        }
    }

    public void initiateRefund() {
        if (status == TransactionStatus.SUCCESS)
            status = TransactionStatus.REFUND_INITIATED;
    }

    public synchronized void applyRefund(Money refundAmt) {
        refundedAmount = refundedAmount.add(refundAmt);
        status = refundedAmount.equals(amount)
            ? TransactionStatus.REFUNDED
            : TransactionStatus.PARTIALLY_REFUNDED;
        System.out.println("[Txn " + id + "] Refund applied: " + refundAmt +
            " | Total refunded: " + refundedAmount + " | Status: " + status);
    }

    public void markDisputed() { status = TransactionStatus.DISPUTED; }

    // ---- Getters ----
    public String            getId()             { return id; }
    public String            getOrderId()        { return orderId; }
    public String            getPayerId()        { return payerId; }
    public String            getPayeeId()        { return payeeId; }
    public Money             getAmount()         { return amount; }
    public PaymentMethod     getMethod()         { return method; }
    public String            getIdempotencyKey() { return idempotencyKey; }
    public TransactionStatus getStatus()         { return status; }
    public String            getGatewayTxnId()   { return gatewayTxnId; }
    public String            getFailureReason()  { return failureReason; }
    public Money             getRefundedAmount() { return refundedAmount; }

    @Override public String toString() {
        return "Transaction[" + id + " | " + amount + " | " +
               method.getMaskedDisplay() + " | " + status + "]";
    }

    // ---- Builder ----
    static class Builder {
        private String        orderId;
        private String        payerId;
        private String        payeeId;
        private Money         amount;
        private PaymentMethod method;
        private String        idempotencyKey = UUID.randomUUID().toString();

        public Builder orderId(String o)        { this.orderId = o;         return this; }
        public Builder payerId(String p)        { this.payerId = p;         return this; }
        public Builder payeeId(String p)        { this.payeeId = p;         return this; }
        public Builder amount(Money a)          { this.amount = a;          return this; }
        public Builder method(PaymentMethod m)  { this.method = m;          return this; }
        public Builder idempotencyKey(String k) { this.idempotencyKey = k;  return this; }
        public Transaction build()              { return new Transaction(this); }
    }
}

// ==========================================
// 6. REFUND
// ==========================================
class Refund {
    private static final AtomicLong idGen = new AtomicLong(200_000);
    private final String      id;
    private final String      transactionId;
    private final Money       amount;
    private final RefundReason reason;
    private       String      status = "PENDING";
    private       String      gatewayRefundId;
    private final LocalDateTime initiatedAt;

    public Refund(String transactionId, Money amount, RefundReason reason) {
        this.id            = "rfnd_" + idGen.getAndIncrement();
        this.transactionId = transactionId;
        this.amount        = amount;
        this.reason        = reason;
        this.initiatedAt   = LocalDateTime.now();
    }

    public void markProcessed(String gwRefundId) {
        this.status          = "PROCESSED";
        this.gatewayRefundId = gwRefundId;
    }

    public void markFailed() { this.status = "FAILED"; }

    public String       getId()            { return id; }
    public String       getTransactionId() { return transactionId; }
    public Money        getAmount()        { return amount; }
    public RefundReason getReason()        { return reason; }
    public String       getStatus()        { return status; }

    @Override public String toString() {
        return "Refund[" + id + " | " + amount + " | " + reason + " | " + status + "]";
    }
}

// ==========================================
// 7. PAYMENT EXCEPTION
// ==========================================
class PaymentException extends RuntimeException {
    private final String errorCode;

    public PaymentException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}

// ==========================================
// 8. STRATEGY — PAYMENT GATEWAYS
// ==========================================
interface PaymentGatewayStrategy {
    GatewayProvider getProvider();
    String          charge(Transaction txn) throws PaymentException;
    String          refund(Transaction txn, Refund refund) throws PaymentException;
    boolean         supports(PaymentMethodType methodType);
}

class RazorpayGateway implements PaymentGatewayStrategy {
    private static final AtomicLong idGen = new AtomicLong(1);

    @Override public GatewayProvider getProvider() { return GatewayProvider.RAZORPAY; }

    @Override
    public String charge(Transaction txn) throws PaymentException {
        System.out.println("[Razorpay] Charging " + txn.getAmount() +
            " via " + txn.getMethod().getMaskedDisplay());
        // Simulate occasional gateway timeout
        if (Math.random() < 0.05)
            throw new PaymentException("GW_TIMEOUT", "Razorpay gateway timeout");
        String gwId = "rzp_" + idGen.getAndIncrement();
        System.out.println("[Razorpay] SUCCESS → " + gwId);
        return gwId;
    }

    @Override
    public String refund(Transaction txn, Refund refund) throws PaymentException {
        System.out.println("[Razorpay] Refunding " + refund.getAmount());
        String gwId = "rzp_rfnd_" + idGen.getAndIncrement();
        System.out.println("[Razorpay] Refund OK → " + gwId);
        return gwId;
    }

    @Override
    public boolean supports(PaymentMethodType t) {
        return t == PaymentMethodType.UPI || t == PaymentMethodType.CREDIT_CARD
            || t == PaymentMethodType.DEBIT_CARD || t == PaymentMethodType.NET_BANKING;
    }
}

class StripeGateway implements PaymentGatewayStrategy {
    private static final AtomicLong idGen = new AtomicLong(1);

    @Override public GatewayProvider getProvider() { return GatewayProvider.STRIPE; }

    @Override
    public String charge(Transaction txn) throws PaymentException {
        System.out.println("[Stripe] Charging " + txn.getAmount() +
            " via " + txn.getMethod().getMaskedDisplay());
        String gwId = "ch_stripe_" + idGen.getAndIncrement();
        System.out.println("[Stripe] SUCCESS → " + gwId);
        return gwId;
    }

    @Override
    public String refund(Transaction txn, Refund refund) throws PaymentException {
        System.out.println("[Stripe] Refunding " + refund.getAmount());
        return "re_stripe_" + idGen.getAndIncrement();
    }

    @Override
    public boolean supports(PaymentMethodType t) {
        return t == PaymentMethodType.CREDIT_CARD || t == PaymentMethodType.DEBIT_CARD;
    }
}

class WalletGateway implements PaymentGatewayStrategy {
    @Override public GatewayProvider getProvider() { return GatewayProvider.INTERNAL; }

    @Override
    public String charge(Transaction txn) throws PaymentException {
        if (!(txn.getMethod() instanceof WalletMethod wallet))
            throw new PaymentException("INVALID_METHOD", "Not a wallet method");

        if (!wallet.hasSufficientBalance(txn.getAmount()))
            throw new PaymentException("INSUFFICIENT_BALANCE",
                "Wallet has " + wallet.getBalance() +
                " but needs " + txn.getAmount());

        wallet.debit(txn.getAmount());
        return "wallet_txn_" + System.currentTimeMillis();
    }

    @Override
    public String refund(Transaction txn, Refund refund) throws PaymentException {
        if (txn.getMethod() instanceof WalletMethod wallet)
            wallet.credit(refund.getAmount());
        return "wallet_rfnd_" + System.currentTimeMillis();
    }

    @Override
    public boolean supports(PaymentMethodType t) {
        return t == PaymentMethodType.WALLET;
    }
}

// ==========================================
// 9. CHAIN OF RESPONSIBILITY — VALIDATION
// ==========================================
abstract class PaymentValidator {
    protected PaymentValidator next;

    public PaymentValidator setNext(PaymentValidator next) {
        this.next = next;
        return next;
    }

    public abstract void validate(Transaction txn) throws PaymentException;

    protected void passToNext(Transaction txn) throws PaymentException {
        if (next != null) next.validate(txn);
    }
}

// Step 1: Amount must be > 0 and within limits
class AmountValidator extends PaymentValidator {
    private static final long MAX_PAISE = 1_00_00_000_00L; // ₹1 crore

    @Override
    public void validate(Transaction txn) throws PaymentException {
        if (txn.getAmount().isZero())
            throw new PaymentException("INVALID_AMOUNT", "Amount cannot be zero");
        if (txn.getAmount().getPaise() > MAX_PAISE)
            throw new PaymentException("AMOUNT_EXCEEDED",
                "Amount exceeds max limit of ₹1,00,00,000");
        System.out.println("[Validator] ✓ Amount: " + txn.getAmount());
        passToNext(txn);
    }
}

// Step 2: Payment method must be active
class MethodValidator extends PaymentValidator {
    @Override
    public void validate(Transaction txn) throws PaymentException {
        if (!txn.getMethod().isActive())
            throw new PaymentException("INACTIVE_METHOD",
                "Payment method is inactive: " + txn.getMethod().getId());
        System.out.println("[Validator] ✓ Method: " + txn.getMethod().getMaskedDisplay());
        passToNext(txn);
    }
}

// Step 3: Fraud detection — simple velocity check
class FraudValidator extends PaymentValidator {
    private final Map<String, AtomicLong> methodHitCount = new ConcurrentHashMap<>();
    private static final int FRAUD_THRESHOLD = 20;

    @Override
    public void validate(Transaction txn) throws PaymentException {
        String key = txn.getMethod().getId();
        long count = methodHitCount
            .computeIfAbsent(key, k -> new AtomicLong(0))
            .incrementAndGet();
        if (count > FRAUD_THRESHOLD)
            throw new PaymentException("FRAUD_SUSPECTED",
                "High velocity on method — possible fraud (" + count + " attempts)");
        System.out.println("[Validator] ✓ Fraud check (velocity: " + count + ")");
        passToNext(txn);
    }
}

// Step 4: Idempotency — prevent duplicate charges
class DuplicateValidator extends PaymentValidator {
    private final Map<String, String> idempotencyStore; // key → txnId

    public DuplicateValidator(Map<String, String> store) {
        this.idempotencyStore = store;
    }

    @Override
    public void validate(Transaction txn) throws PaymentException {
        String existing = idempotencyStore.get(txn.getIdempotencyKey());
        if (existing != null)
            throw new PaymentException("DUPLICATE_TXN",
                "Already processed — idempotency key: " +
                txn.getIdempotencyKey() + " → " + existing);
        System.out.println("[Validator] ✓ Idempotency — no duplicate");
        passToNext(txn);
    }
}

// ==========================================
// 10. OBSERVER — PAYMENT EVENTS
// ==========================================
interface PaymentEventObserver {
    void onPaymentSuccess(Transaction txn);
    void onPaymentFailure(Transaction txn);
    void onRefundProcessed(Transaction txn, Refund refund);
}

class NotificationObserver implements PaymentEventObserver {
    @Override
    public void onPaymentSuccess(Transaction txn) {
        System.out.println("[Notif] Payment success → " + txn.getPayerId() +
            " | " + txn.getAmount() + " | Order: " + txn.getOrderId());
    }

    @Override
    public void onPaymentFailure(Transaction txn) {
        System.out.println("[Notif] Payment FAILED → " + txn.getPayerId() +
            " | Reason: " + txn.getFailureReason());
    }

    @Override
    public void onRefundProcessed(Transaction txn, Refund refund) {
        System.out.println("[Notif] Refund " + refund.getAmount() +
            " initiated → " + txn.getPayerId() + " | ETA: 3-5 business days");
    }
}

class LedgerObserver implements PaymentEventObserver {
    @Override
    public void onPaymentSuccess(Transaction txn) {
        // Double-entry: debit payer, credit payee
        System.out.println("[Ledger] Debit payer:" + txn.getPayerId() +
            " | Credit payee:" + txn.getPayeeId() +
            " | Amount:" + txn.getAmount());
    }

    @Override
    public void onPaymentFailure(Transaction txn) {
        System.out.println("[Ledger] No entry — payment failed");
    }

    @Override
    public void onRefundProcessed(Transaction txn, Refund refund) {
        System.out.println("[Ledger] Refund entry: Debit payee:" + txn.getPayeeId() +
            " | Credit payer:" + txn.getPayerId() + " | " + refund.getAmount());
    }
}

class AnalyticsObserver implements PaymentEventObserver {
    private long  successCount = 0;
    private long  failureCount = 0;
    private Money totalVolume  = new Money(0, Currency.INR);

    @Override
    public synchronized void onPaymentSuccess(Transaction txn) {
        successCount++;
        if (txn.getAmount().getCurrency() == Currency.INR)
            totalVolume = totalVolume.add(txn.getAmount());
    }

    @Override public synchronized void onPaymentFailure(Transaction txn) { failureCount++; }
    @Override public void onRefundProcessed(Transaction txn, Refund refund) {}

    public void printReport() {
        long total = successCount + failureCount;
        System.out.println("[Analytics]" +
            " Success=" + successCount +
            " | Failures=" + failureCount +
            " | SuccessRate=" +
            (total > 0 ? String.format("%.1f%%", 100.0 * successCount / total) : "N/A") +
            " | TotalVolume=" + totalVolume);
    }
}

// ==========================================
// 11. TRANSACTION LEDGER — SINGLETON
// Source of truth for all transactions
// ==========================================
class TransactionLedger {
    private static TransactionLedger instance;

    // txnId → Transaction
    private final Map<String, Transaction>      transactions   = new ConcurrentHashMap<>();
    // idempotency key → txnId (prevents duplicate charges)
    private final Map<String, String>           idempotencyMap = new ConcurrentHashMap<>();
    // orderId → list of txnIds
    private final Map<String, List<String>>     orderTxnMap    = new ConcurrentHashMap<>();

    private TransactionLedger() {}

    public static synchronized TransactionLedger getInstance() {
        if (instance == null) instance = new TransactionLedger();
        return instance;
    }

    public void record(Transaction txn) {
        transactions.put(txn.getId(), txn);
        idempotencyMap.put(txn.getIdempotencyKey(), txn.getId());
        orderTxnMap.computeIfAbsent(txn.getOrderId(),
            k -> new CopyOnWriteArrayList<>()).add(txn.getId());
    }

    public Transaction        getById(String txnId) { return transactions.get(txnId); }
    public Map<String, String> getIdempotencyMap()  { return idempotencyMap; }

    public List<Transaction> getByOrderId(String orderId) {
        return orderTxnMap.getOrDefault(orderId, Collections.emptyList())
            .stream().map(transactions::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    public int count() { return transactions.size(); }
}

// ==========================================
// 12. PAYMENT SERVICE — SINGLETON (core)
// ==========================================
class PaymentService {
    private static PaymentService instance;

    private final TransactionLedger            ledger    = TransactionLedger.getInstance();
    private final List<PaymentGatewayStrategy> gateways  = new ArrayList<>();
    private final List<PaymentEventObserver>   observers = new ArrayList<>();
    private final AnalyticsObserver            analytics = new AnalyticsObserver();
    private       PaymentValidator             validationChain;

    private PaymentService() {
        // Register gateways (order matters — first match wins)
        gateways.add(new WalletGateway());
        gateways.add(new RazorpayGateway());
        gateways.add(new StripeGateway());

        // Register observers
        observers.add(new NotificationObserver());
        observers.add(new LedgerObserver());
        observers.add(analytics);

        // Build validation CoR chain
        PaymentValidator amount    = new AmountValidator();
        PaymentValidator method    = new MethodValidator();
        PaymentValidator fraud     = new FraudValidator();
        PaymentValidator duplicate = new DuplicateValidator(ledger.getIdempotencyMap());

        amount.setNext(method).setNext(fraud).setNext(duplicate);
        validationChain = amount;
    }

    public static synchronized PaymentService getInstance() {
        if (instance == null) instance = new PaymentService();
        return instance;
    }

    // ---- GATEWAY SELECTION (Strategy) ----
    private PaymentGatewayStrategy selectGateway(PaymentMethodType type) {
        return gateways.stream()
            .filter(g -> g.supports(type))
            .findFirst()
            .orElseThrow(() -> new PaymentException("NO_GATEWAY",
                "No gateway supports method: " + type));
    }

    // ==========================================
    // CORE: INITIATE PAYMENT
    // ==========================================
    public Transaction initiatePayment(Transaction txn) {
        System.out.println("\n[PaymentService] Initiating: " +
            txn.getAmount() + " | " + txn.getMethod().getMaskedDisplay() +
            " | Order: " + txn.getOrderId());

        // Step 1: Record immediately — even INITIATED state is persisted
        // This ensures we can answer "did we receive this payment attempt?"
        ledger.record(txn);

        try {
            // Step 2: Run validation chain (CoR)
            validationChain.validate(txn);

            // Step 3: Mark processing — prevents concurrent double-charge
            txn.markProcessing();

            // Step 4: Select gateway + charge
            PaymentGatewayStrategy gateway = selectGateway(txn.getMethod().getType());
            String gwTxnId = gateway.charge(txn);

            // Step 5: Mark success
            txn.markSuccess(gwTxnId);

            // Step 6: Notify all observers async in production (Kafka)
            observers.forEach(o -> o.onPaymentSuccess(txn));

        } catch (PaymentException e) {
            txn.markFailed(e.getMessage());
            observers.forEach(o -> o.onPaymentFailure(txn));
        }

        return txn;
    }

    // ==========================================
    // IDEMPOTENT PAYMENT — same key = same result
    // Critical: client must retry with same idempotency key after network timeout
    // ==========================================
    public Transaction initiatePaymentIdempotent(Transaction txn) {
        String existingId = ledger.getIdempotencyMap().get(txn.getIdempotencyKey());
        if (existingId != null) {
            Transaction existing = ledger.getById(existingId);
            System.out.println("[Idempotency] Key already used → returning: " + existing);
            return existing;
        }
        return initiatePayment(txn);
    }

    // ==========================================
    // REFUND
    // ==========================================
    public Refund initiateRefund(String txnId, double refundAmount, RefundReason reason) {
        Transaction txn = ledger.getById(txnId);
        if (txn == null) {
            System.out.println("[Refund] Transaction not found: " + txnId);
            return null;
        }

        if (txn.getStatus() != TransactionStatus.SUCCESS &&
            txn.getStatus() != TransactionStatus.PARTIALLY_REFUNDED) {
            System.out.println("[Refund] Cannot refund — status: " + txn.getStatus());
            return null;
        }

        Money refundMoney = new Money(refundAmount, txn.getAmount().getCurrency());

        // Validate: don't refund more than (original - already refunded)
        Money alreadyRefunded = txn.getRefundedAmount();
        Money maxRefundable   = txn.getAmount().subtract(alreadyRefunded);
        if (refundMoney.isGreaterThan(maxRefundable)) {
            System.out.println("[Refund] ❌ Amount " + refundMoney +
                " exceeds refundable: " + maxRefundable);
            return null;
        }

        Refund refund = new Refund(txnId, refundMoney, reason);
        txn.initiateRefund();

        try {
            PaymentGatewayStrategy gateway = selectGateway(txn.getMethod().getType());
            String gwRefundId = gateway.refund(txn, refund);
            refund.markProcessed(gwRefundId);
            txn.applyRefund(refundMoney);
            observers.forEach(o -> o.onRefundProcessed(txn, refund));
        } catch (PaymentException e) {
            refund.markFailed();
            System.out.println("[Refund] ❌ Failed: " + e.getMessage());
        }

        System.out.println("[Refund] " + refund);
        return refund;
    }

    // ==========================================
    // SPLIT PAYMENT — Saga pattern
    // Pay with multiple methods; rollback all if any fails
    // ==========================================
    public List<Transaction> splitPayment(String orderId, String payerId,
                                           String payeeId,
                                           List<PaymentMethod> methods,
                                           List<Double> amounts,
                                           Currency currency) {
        if (methods.size() != amounts.size()) {
            System.out.println("[Split] Methods/amounts count mismatch");
            return Collections.emptyList();
        }

        System.out.println("\n[Split Payment] Order: " + orderId +
            " | " + methods.size() + " payment legs");

        List<Transaction> txns = new ArrayList<>();

        for (int i = 0; i < methods.size(); i++) {
            Transaction txn = new Transaction.Builder()
                .orderId(orderId)
                .payerId(payerId)
                .payeeId(payeeId)
                .amount(new Money(amounts.get(i), currency))
                .method(methods.get(i))
                .idempotencyKey(orderId + "-split-leg-" + i)
                .build();
            txns.add(initiatePaymentIdempotent(txn));
        }

        long successCount = txns.stream()
            .filter(t -> t.getStatus() == TransactionStatus.SUCCESS)
            .count();

        System.out.println("[Split] " + successCount + "/" + txns.size() + " legs succeeded");

        // SAGA ROLLBACK: if any leg failed, refund all successful legs
        if (successCount < txns.size()) {
            System.out.println("[Split] ⚠ Partial failure — rolling back " +
                successCount + " successful legs");
            txns.stream()
                .filter(t -> t.getStatus() == TransactionStatus.SUCCESS)
                .forEach(t -> initiateRefund(t.getId(),
                    t.getAmount().getAmount(), RefundReason.ORDER_CANCELLED));
        }

        return txns;
    }

    public void printAnalytics()         { analytics.printReport(); }
    public TransactionLedger getLedger() { return ledger; }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class PaymentSystem {
    public static void main(String[] args) throws InterruptedException {

        PaymentService service = PaymentService.getInstance();

        // ---- Setup payment methods ----
        UpiMethod    aliceUpi    = PaymentMethodFactory.createUpi("alice@okicici", "alice");
        CardMethod   aliceCard   = PaymentMethodFactory.createCreditCard(
            "4567", "VISA", "Alice Smith", "12/27", "alice");
        WalletMethod aliceWallet = PaymentMethodFactory.createWallet("alice", 3000, Currency.INR);
        CardMethod   bobCard     = PaymentMethodFactory.createDebitCard(
            "1234", "RUPAY", "Bob Kumar", "06/26", "bob");
        WalletMethod bobWallet   = PaymentMethodFactory.createWallet("bob", 400, Currency.INR);

        // ===== SCENARIO 1: Successful UPI payment =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: UPI Payment — Happy Path");
        System.out.println("=".repeat(55));

        Transaction t1 = service.initiatePayment(new Transaction.Builder()
            .orderId("order_001")
            .payerId("alice").payeeId("flipkart")
            .amount(new Money(1499.00, Currency.INR))
            .method(aliceUpi)
            .idempotencyKey("order_001_attempt_1")
            .build());

        System.out.println("Result: " + t1);

        // ===== SCENARIO 2: Idempotency — client retries same payment =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Idempotent Retry (network timeout simulation)");
        System.out.println("=".repeat(55));

        // Simulate: client didn't receive response, retries with same key
        Transaction t1Retry = service.initiatePaymentIdempotent(new Transaction.Builder()
            .orderId("order_001")
            .payerId("alice").payeeId("flipkart")
            .amount(new Money(1499.00, Currency.INR))
            .method(aliceUpi)
            .idempotencyKey("order_001_attempt_1") // same key — must not double-charge
            .build());

        System.out.println("Same txnId returned: " + t1.getId().equals(t1Retry.getId()));
        System.out.println("Alice was charged exactly once ✓");

        // ===== SCENARIO 3: Credit card payment =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Credit Card Payment");
        System.out.println("=".repeat(55));

        Transaction t2 = service.initiatePayment(new Transaction.Builder()
            .orderId("order_002")
            .payerId("alice").payeeId("amazon")
            .amount(new Money(8999.00, Currency.INR))
            .method(aliceCard)
            .idempotencyKey("order_002_attempt_1")
            .build());

        System.out.println("Result: " + t2);

        // ===== SCENARIO 4: Wallet payment — sufficient balance =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: Wallet Payment (sufficient balance)");
        System.out.println("=".repeat(55));

        System.out.println("Alice wallet before: " + aliceWallet.getBalance());
        Transaction t3 = service.initiatePayment(new Transaction.Builder()
            .orderId("order_003")
            .payerId("alice").payeeId("swiggy")
            .amount(new Money(650.00, Currency.INR))
            .method(aliceWallet)
            .idempotencyKey("order_003_attempt_1")
            .build());

        System.out.println("Result: " + t3);

        // ===== SCENARIO 5: Wallet payment — insufficient balance =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Wallet Payment (insufficient balance)");
        System.out.println("=".repeat(55));

        System.out.println("Bob wallet balance: " + bobWallet.getBalance());
        Transaction t4 = service.initiatePayment(new Transaction.Builder()
            .orderId("order_004")
            .payerId("bob").payeeId("zomato")
            .amount(new Money(1200.00, Currency.INR)) // more than ₹400
            .method(bobWallet)
            .idempotencyKey("order_004_attempt_1")
            .build());

        System.out.println("Result: " + t4);

        // ===== SCENARIO 6: Full refund =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Full Refund");
        System.out.println("=".repeat(55));

        if (t1.getStatus() == TransactionStatus.SUCCESS) {
            Refund r1 = service.initiateRefund(t1.getId(), 1499.00,
                RefundReason.CUSTOMER_REQUEST);
            System.out.println("Txn status after full refund: " + t1.getStatus());
        }

        // ===== SCENARIO 7: Partial refunds =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Partial Refund (2 items, return 1)");
        System.out.println("=".repeat(55));

        if (t2.getStatus() == TransactionStatus.SUCCESS) {
            Refund r2a = service.initiateRefund(t2.getId(), 2000.00,
                RefundReason.ITEM_NOT_RECEIVED);
            System.out.println("After partial refund 1 — status: " + t2.getStatus());

            Refund r2b = service.initiateRefund(t2.getId(), 6999.00,
                RefundReason.DEFECTIVE_ITEM);
            System.out.println("After partial refund 2 — status: " + t2.getStatus());
        }

        // ===== SCENARIO 8: Over-refund attempt =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Over-refund Attempt (more than original)");
        System.out.println("=".repeat(55));

        if (t3.getStatus() == TransactionStatus.SUCCESS) {
            service.initiateRefund(t3.getId(), 99999.00, RefundReason.ORDER_CANCELLED);
        }

        // ===== SCENARIO 9: Validation failures =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 9: Validation Chain Failures");
        System.out.println("=".repeat(55));

        // 9a: Zero amount
        System.out.println("--- Zero amount ---");
        service.initiatePayment(new Transaction.Builder()
            .orderId("order_bad_1").payerId("alice").payeeId("merchant")
            .amount(new Money(0, Currency.INR)).method(aliceUpi).build());

        // 9b: Inactive payment method
        System.out.println("--- Inactive method ---");
        aliceCard.deactivate();
        service.initiatePayment(new Transaction.Builder()
            .orderId("order_bad_2").payerId("alice").payeeId("merchant")
            .amount(new Money(500, Currency.INR)).method(aliceCard).build());

        // ===== SCENARIO 10: Split payment (Wallet + UPI) — Saga =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 10: Split Payment — Wallet ₹1000 + UPI ₹2000");
        System.out.println("=".repeat(55));

        WalletMethod freshWallet = PaymentMethodFactory.createWallet(
            "carol", 1000, Currency.INR);
        UpiMethod freshUpi = PaymentMethodFactory.createUpi("carol@oksbi", "carol");

        List<Transaction> splitTxns = service.splitPayment(
            "order_split_001", "carol", "bigbasket",
            List.of(freshWallet, freshUpi),
            List.of(1000.0, 2000.0),
            Currency.INR
        );

        System.out.println("\nSplit payment results:");
        splitTxns.forEach(t -> System.out.println("  " + t));

        // ===== SCENARIO 11: Wallet refund (credit back) =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 11: Wallet Refund (balance restored)");
        System.out.println("=".repeat(55));

        System.out.println("Alice wallet before refund: " + aliceWallet.getBalance());
        if (t3.getStatus() == TransactionStatus.SUCCESS) {
            service.initiateRefund(t3.getId(), 650.00, RefundReason.ORDER_CANCELLED);
        }
        System.out.println("Alice wallet after refund:  " + aliceWallet.getBalance());

        // ===== LEDGER + ANALYTICS =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("LEDGER + ANALYTICS");
        System.out.println("=".repeat(55));

        service.printAnalytics();
        System.out.println("Total transactions in ledger: " +
            service.getLedger().count());

        System.out.println("\nAll transactions for order_001:");
        service.getLedger().getByOrderId("order_001")
            .forEach(t -> System.out.println("  " + t));

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern                  | Class
            -------------------------|--------------------------------------------------
            Singleton                | PaymentService, TransactionLedger
            Strategy                 | PaymentGatewayStrategy (Razorpay/Stripe/Wallet)
            Factory                  | PaymentMethodFactory
            Builder                  | Transaction.Builder
            State                    | TransactionStatus (INITIATED→PROCESSING→SUCCESS)
            Observer                 | PaymentEventObserver (Notif/Ledger/Analytics)
            Chain of Responsibility  | PaymentValidator (Amount→Method→Fraud→Duplicate)
            Command + Saga           | splitPayment with full rollback on partial failure
            Value Object             | Money — paise arithmetic avoids float errors
            """);
    }
}
