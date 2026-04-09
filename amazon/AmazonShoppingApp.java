import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// ============================================================
// AMAZON SHOPPING APP — LLD
// Patterns:
//   Singleton   — AmazonSystem, InventoryService, PaymentGateway, CartService
//   State       — OrderStatus (PLACED→CONFIRMED→PACKED→SHIPPED→DELIVERED→RETURNED)
//   Strategy    — PricingStrategy, SearchRankingStrategy, DeliveryStrategy
//   Observer    — OrderEventObserver (Notification, Inventory, Loyalty, Audit, Analytics)
//   Factory     — OrderFactory (standard, prime, digital, subscribe-save)
//   Builder     — Order.Builder, Product.Builder, SearchQuery.Builder
//   Command     — AddToCartCommand, PlaceOrderCommand, CancelOrderCommand
//   Template    — AbstractOrderProcessor (processOrder skeleton)
//   Decorator   — PriceDecorator chain (base + discount + coupon + tax + delivery)
//   CoR         — PaymentFallbackChain (primary → secondary → wallet → COD)
//   Circuit     — CircuitBreaker (CLOSED → OPEN → HALF_OPEN fallback)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum OrderStatus {
    PLACED, PAYMENT_PENDING, CONFIRMED, PACKED,
    SHIPPED, OUT_FOR_DELIVERY, DELIVERED,
    CANCELLATION_REQUESTED, CANCELLED, RETURN_REQUESTED,
    RETURN_PICKED_UP, REFUND_INITIATED, REFUND_COMPLETED
}
enum ProductCategory { ELECTRONICS, BOOKS, CLOTHING, GROCERY, HOME, BEAUTY, SPORTS, TOYS }
enum DeliveryType   { STANDARD, PRIME, SAME_DAY, SCHEDULED, DIGITAL }
enum PaymentMethod  { CREDIT_CARD, DEBIT_CARD, UPI, NET_BANKING, WALLET, COD, EMI, REWARD_POINTS }
enum PaymentStatus  { PENDING, AUTHORIZED, CAPTURED, FAILED, REFUNDED }
enum CircuitState   { CLOSED, OPEN, HALF_OPEN }
enum ReturnReason   { DAMAGED, WRONG_ITEM, NOT_AS_DESCRIBED, CHANGED_MIND, DEFECTIVE }
enum CancellationReason { USER_REQUESTED, PAYMENT_FAILED, OUT_OF_STOCK, SELLER_CANCELLED }
enum ServiceName    { INVENTORY, PAYMENT, SEARCH, RECOMMENDATION, NOTIFICATION, CART }

// ============================================================
// 2. MONEY — paise precision
// ============================================================
class Money implements Comparable<Money> {
    private final long paise;
    Money(double rupees)        { this.paise = Math.round(rupees * 100); }
    private Money(long paise)   { this.paise = paise; }
    Money add(Money o)          { return new Money(paise + o.paise); }
    Money subtract(Money o)     { return new Money(Math.max(0, paise - o.paise)); }
    Money multiply(int n)       { return new Money(paise * n); }
    Money pct(double p)         { return new Money((long)(paise * p / 100.0)); }
    boolean isGreaterThan(Money o){ return paise > o.paise; }
    boolean isZero()            { return paise == 0; }
    double  toRupees()          { return paise / 100.0; }
    long    getPaise()          { return paise; }
    @Override public int compareTo(Money o) { return Long.compare(paise, o.paise); }
    @Override public String toString()      { return "₹" + String.format("%.2f", toRupees()); }
    static Money of(double r)   { return new Money(r); }
    static Money zero()         { return new Money(0L); }
}

// ============================================================
// 3. CIRCUIT BREAKER — Fallback Mechanism Core
//    CLOSED  = normal operation
//    OPEN    = service down; immediately return fallback; reset after timeout
//    HALF_OPEN = probe: 1 trial request; success→CLOSED, fail→OPEN
// ============================================================
class CircuitBreaker {
    private final String      name;
    private final int         failureThreshold;    // failures before OPEN
    private final int         successThreshold;    // successes in HALF_OPEN before CLOSED
    private final long        openWindowMs;        // ms to stay OPEN before HALF_OPEN
    private volatile CircuitState state            = CircuitState.CLOSED;
    private final AtomicInteger  failureCount      = new AtomicInteger(0);
    private final AtomicInteger  halfOpenSuccesses = new AtomicInteger(0);
    private volatile long        openedAt          = 0;

    CircuitBreaker(String name, int failureThreshold, int successThreshold, long openWindowMs) {
        this.name             = name;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.openWindowMs     = openWindowMs;
    }

    /** Execute action through circuit breaker; fallback if OPEN */
    <T> T execute(Supplier<T> action, Supplier<T> fallback) {
        if (state == CircuitState.OPEN) {
            if (System.currentTimeMillis() - openedAt >= openWindowMs) {
                state = CircuitState.HALF_OPEN;
                halfOpenSuccesses.set(0);
                System.out.printf("[CB:%s] OPEN → HALF_OPEN (probing)%n", name);
            } else {
                System.out.printf("[CB:%s] OPEN — serving fallback%n", name);
                return fallback.get();
            }
        }
        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            System.out.printf("[CB:%s] Exception: %s → fallback%n", name, e.getMessage());
            return fallback.get();
        }
    }

    private void onSuccess() {
        failureCount.set(0);
        if (state == CircuitState.HALF_OPEN) {
            int s = halfOpenSuccesses.incrementAndGet();
            if (s >= successThreshold) {
                state = CircuitState.CLOSED;
                System.out.printf("[CB:%s] HALF_OPEN → CLOSED ✓%n", name);
            }
        }
    }

    private void onFailure(Exception e) {
        int f = failureCount.incrementAndGet();
        if (f >= failureThreshold && state == CircuitState.CLOSED) {
            state    = CircuitState.OPEN;
            openedAt = System.currentTimeMillis();
            System.out.printf("[CB:%s] CLOSED → OPEN after %d failures%n", name, f);
        } else if (state == CircuitState.HALF_OPEN) {
            state    = CircuitState.OPEN;
            openedAt = System.currentTimeMillis();
            System.out.printf("[CB:%s] HALF_OPEN → OPEN (probe failed)%n", name);
        }
    }

    CircuitState getState() { return state; }
    // For testing: force open
    void forceOpen()  { state = CircuitState.OPEN;   openedAt = System.currentTimeMillis(); }
    void forceClose() { state = CircuitState.CLOSED; failureCount.set(0); }
    @Override public String toString() { return "CB[" + name + "|" + state + "]"; }
}

// ============================================================
// 4. RETRY WITH EXPONENTIAL BACKOFF — Fallback Mechanism
// ============================================================
class RetryExecutor {
    private final int    maxAttempts;
    private final long   initialDelayMs;
    private final double multiplier;

    RetryExecutor(int maxAttempts, long initialDelayMs, double multiplier) {
        this.maxAttempts    = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.multiplier     = multiplier;
    }

    <T> T execute(String operationName, Supplier<T> action, Supplier<T> fallback) {
        long delay = initialDelayMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = action.get();
                if (attempt > 1)
                    System.out.printf("[Retry:%s] Succeeded on attempt %d%n", operationName, attempt);
                return result;
            } catch (Exception e) {
                System.out.printf("[Retry:%s] Attempt %d/%d failed: %s%n",
                    operationName, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                    delay = (long)(delay * multiplier);
                }
            }
        }
        System.out.printf("[Retry:%s] All %d attempts exhausted → fallback%n",
            operationName, maxAttempts);
        return fallback.get();
    }
}

// ============================================================
// 5. CACHE — Simple in-memory stale-while-revalidate cache
//    Fallback: serve stale data when live service is down
// ============================================================
class StaleCache<K, V> {
    private final Map<K, CacheEntry<V>> store = new ConcurrentHashMap<>();
    private final long ttlMs;

    StaleCache(long ttlMs) { this.ttlMs = ttlMs; }

    void put(K key, V value) {
        store.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    Optional<V> get(K key) {
        CacheEntry<V> entry = store.get(key);
        if (entry == null) return Optional.empty();
        return Optional.of(entry.value); // return even if stale
    }

    boolean isFresh(K key) {
        CacheEntry<V> entry = store.get(key);
        return entry != null && System.currentTimeMillis() - entry.timestamp < ttlMs;
    }

    boolean isStale(K key) {
        CacheEntry<V> entry = store.get(key);
        return entry != null && System.currentTimeMillis() - entry.timestamp >= ttlMs;
    }

    private static class CacheEntry<V> {
        final V    value;
        final long timestamp;
        CacheEntry(V v, long t) { value = v; timestamp = t; }
    }
}

// ============================================================
// 6. PRODUCT — Builder pattern
// ============================================================
class Product {
    private static final AtomicLong idGen = new AtomicLong(10_000);
    private final long            productId;
    private final String          name;
    private final String          description;
    private final String          brand;
    private final ProductCategory category;
    private final Money           basePrice;
    private final double          rating;      // 0–5
    private final int             reviewCount;
    private final boolean         primeEligible;
    private final String          sellerId;
    private final Map<String,String> specs;   // color, size, etc.

    private Product(Builder b) {
        productId    = idGen.getAndIncrement();
        name         = b.name; description = b.description; brand = b.brand;
        category     = b.category; basePrice = b.basePrice; rating = b.rating;
        reviewCount  = b.reviewCount; primeEligible = b.primeEligible;
        sellerId     = b.sellerId; specs = Collections.unmodifiableMap(b.specs);
    }

    long            getProductId()    { return productId; }
    String          getName()         { return name; }
    ProductCategory getCategory()     { return category; }
    Money           getBasePrice()    { return basePrice; }
    double          getRating()       { return rating; }
    boolean         isPrimeEligible() { return primeEligible; }
    String          getSellerId()     { return sellerId; }

    static class Builder {
        String name, description = "", brand = "", sellerId = "AMAZON";
        ProductCategory category = ProductCategory.ELECTRONICS;
        Money basePrice = Money.of(999);
        double rating = 4.0; int reviewCount = 0;
        boolean primeEligible = true;
        Map<String,String> specs = new HashMap<>();

        Builder(String name) { this.name = name; }
        Builder description(String d) { description = d; return this; }
        Builder brand(String b)       { brand = b;       return this; }
        Builder category(ProductCategory c){ category = c; return this; }
        Builder price(double p)       { basePrice = Money.of(p); return this; }
        Builder rating(double r, int cnt){ rating = r; reviewCount = cnt; return this; }
        Builder prime(boolean p)      { primeEligible = p; return this; }
        Builder seller(String s)      { sellerId = s; return this; }
        Builder spec(String k, String v){ specs.put(k, v); return this; }
        Product build()              { return new Product(this); }
    }

    @Override public String toString() {
        return String.format("Product[#%d | %s | %s | ★%.1f]",
            productId, name, basePrice, rating);
    }
}

// ============================================================
// 7. INVENTORY SERVICE — Singleton with Circuit Breaker
// ============================================================
class InventoryService {
    private static InventoryService instance;
    private final Map<Long, Integer> stock     = new ConcurrentHashMap<>();
    private final Map<Long, Integer> reserved  = new ConcurrentHashMap<>();
    private final CircuitBreaker     cb        = new CircuitBreaker("Inventory", 3, 2, 5000);
    private final StaleCache<Long, Integer> cache = new StaleCache<>(60_000);

    private InventoryService() {}
    static synchronized InventoryService getInstance() {
        if (instance == null) instance = new InventoryService();
        return instance;
    }

    void addStock(long productId, int qty) {
        stock.merge(productId, qty, Integer::sum);
        cache.put(productId, stock.get(productId));
    }

    /** Thread-safe reserve — returns false if insufficient */
    synchronized boolean reserve(long productId, int qty) {
        return cb.execute(
            () -> {
                int available = stock.getOrDefault(productId, 0)
                              - reserved.getOrDefault(productId, 0);
                if (available < qty) return false;
                reserved.merge(productId, qty, Integer::sum);
                System.out.printf("[Inventory] Reserved %d of product #%d (available=%d)%n",
                    qty, productId, available - qty);
                return true;
            },
            () -> {
                // FALLBACK: serve from cache; optimistically allow if cache says stock>0
                Optional<Integer> cached = cache.get(productId);
                boolean optimistic = cached.map(c -> c > qty).orElse(false);
                System.out.printf("[Inventory:FALLBACK] Cache-based reserve for #%d → %s%n",
                    productId, optimistic);
                return optimistic;
            }
        );
    }

    synchronized void confirm(long productId, int qty) {
        stock.merge(productId, -qty, Integer::sum);
        reserved.merge(productId, -qty, Integer::sum);
        cache.put(productId, stock.getOrDefault(productId, 0));
    }

    synchronized void release(long productId, int qty) {
        reserved.merge(productId, -qty, Integer::sum);
        cache.put(productId, stock.getOrDefault(productId, 0));
        System.out.printf("[Inventory] Released %d units of #%d%n", qty, productId);
    }

    int getAvailable(long productId) {
        return cb.execute(
            () -> stock.getOrDefault(productId, 0) - reserved.getOrDefault(productId, 0),
            () -> cache.get(productId).orElse(0) // stale fallback
        );
    }

    CircuitBreaker getCB() { return cb; }
}

// ============================================================
// 8. CART ITEM & CART
// ============================================================
class CartItem {
    final Product product;
    int quantity;
    CartItem(Product p, int qty) { product = p; quantity = qty; }
    Money subtotal() { return product.getBasePrice().multiply(quantity); }
    @Override public String toString() {
        return String.format("  • %s × %d = %s", product.getName(), quantity, subtotal());
    }
}

class Cart {
    private static final AtomicLong idGen = new AtomicLong(200_000);
    private final long              cartId;
    private final String            userId;
    private final Map<Long, CartItem> items = new LinkedHashMap<>(); // productId → item
    private final LocalDateTime     createdAt;

    Cart(String userId) {
        cartId = idGen.getAndIncrement(); this.userId = userId;
        createdAt = LocalDateTime.now();
    }

    void addItem(Product p, int qty) {
        items.merge(p.getProductId(), new CartItem(p, qty),
            (existing, newItem) -> { existing.quantity += qty; return existing; });
        System.out.printf("[Cart #%d] Added %d × %s%n", cartId, qty, p.getName());
    }

    boolean removeItem(long productId) {
        return items.remove(productId) != null;
    }

    void updateQty(long productId, int qty) {
        CartItem item = items.get(productId);
        if (item != null) { item.quantity = qty; }
    }

    void clear() { items.clear(); }

    Money getTotal() {
        return items.values().stream()
            .map(CartItem::subtotal)
            .reduce(Money.zero(), Money::add);
    }

    Collection<CartItem> getItems() { return Collections.unmodifiableCollection(items.values()); }
    long    getCartId()  { return cartId; }
    String  getUserId()  { return userId; }
    boolean isEmpty()    { return items.isEmpty(); }
    int     itemCount()  { return items.values().stream().mapToInt(i -> i.quantity).sum(); }
}

// ============================================================
// 9. ADDRESS
// ============================================================
class Address {
    private final String name, line1, line2, city, state, pincode, phone;
    Address(String name, String line1, String city, String state, String pincode, String phone) {
        this.name = name; this.line1 = line1; this.line2 = "";
        this.city = city; this.state = state; this.pincode = pincode; this.phone = phone;
    }
    String getPincode() { return pincode; }
    @Override public String toString() {
        return name + ", " + line1 + ", " + city + " - " + pincode;
    }
}

// ============================================================
// 10. PRICE DECORATOR — DECORATOR PATTERN
// ============================================================
interface PriceComponent {
    Money calculate(int qty);
    String breakdown();
}

class BasePrice implements PriceComponent {
    private final Money unitPrice;
    BasePrice(Money unitPrice) { this.unitPrice = unitPrice; }
    @Override public Money  calculate(int qty) { return unitPrice.multiply(qty); }
    @Override public String breakdown()        { return "Base: " + unitPrice + "/unit"; }
}

abstract class PriceDecorator implements PriceComponent {
    protected final PriceComponent wrapped;
    PriceDecorator(PriceComponent w) { wrapped = w; }
}

class DiscountDecorator extends PriceDecorator {
    private final double discountPct;
    private final String label;
    DiscountDecorator(PriceComponent w, double pct, String label) {
        super(w); this.discountPct = pct; this.label = label;
    }
    @Override public Money calculate(int qty) {
        Money base = wrapped.calculate(qty);
        return base.subtract(base.pct(discountPct));
    }
    @Override public String breakdown() {
        return wrapped.breakdown() + " → " + label + " -" + (int)discountPct + "%";
    }
}

class CouponDecorator extends PriceDecorator {
    private final Money flatOff;
    private final String couponCode;
    CouponDecorator(PriceComponent w, Money flatOff, String code) {
        super(w); this.flatOff = flatOff; this.couponCode = code;
    }
    @Override public Money calculate(int qty) {
        return wrapped.calculate(qty).subtract(flatOff);
    }
    @Override public String breakdown() {
        return wrapped.breakdown() + " → Coupon [" + couponCode + "] -" + flatOff;
    }
}

class GSTDecorator extends PriceDecorator {
    private final double gstRate;
    GSTDecorator(PriceComponent w, double gstRate) { super(w); this.gstRate = gstRate; }
    @Override public Money calculate(int qty) {
        Money base = wrapped.calculate(qty);
        return base.add(base.pct(gstRate));
    }
    @Override public String breakdown() {
        return wrapped.breakdown() + " → GST +" + (int)gstRate + "%";
    }
}

class DeliveryFeeDecorator extends PriceDecorator {
    private static final Money FREE_DELIVERY_THRESHOLD = Money.of(499);
    private final Money    deliveryFee;
    private final boolean  isPrime;
    DeliveryFeeDecorator(PriceComponent w, Money fee, boolean isPrime) {
        super(w); this.deliveryFee = fee; this.isPrime = isPrime;
    }
    @Override public Money calculate(int qty) {
        Money subtotal = wrapped.calculate(qty);
        if (isPrime || subtotal.isGreaterThan(FREE_DELIVERY_THRESHOLD)) return subtotal;
        return subtotal.add(deliveryFee);
    }
    @Override public String breakdown() {
        return wrapped.breakdown() + " → Delivery " +
            (isPrime ? "FREE (Prime)" : deliveryFee.toString());
    }
}

// ============================================================
// 11. PRICING STRATEGY — STRATEGY PATTERN
// ============================================================
interface PricingStrategy {
    String  getName();
    PriceComponent buildPriceChain(Product product, boolean isPrime, String couponCode);
}

class StandardPricingStrategy implements PricingStrategy {
    @Override public String getName() { return "Standard Pricing"; }
    @Override public PriceComponent buildPriceChain(Product p, boolean isPrime, String coupon) {
        PriceComponent chain = new BasePrice(p.getBasePrice());
        chain = new GSTDecorator(chain, 18.0);
        chain = new DeliveryFeeDecorator(chain, Money.of(40), isPrime);
        return chain;
    }
}

class DealPricingStrategy implements PricingStrategy {
    private final double dealPct;
    private final String dealLabel;
    DealPricingStrategy(double pct, String label) { dealPct = pct; dealLabel = label; }
    @Override public String getName() { return "Deal: " + dealLabel; }
    @Override public PriceComponent buildPriceChain(Product p, boolean isPrime, String coupon) {
        PriceComponent chain = new BasePrice(p.getBasePrice());
        chain = new DiscountDecorator(chain, dealPct, dealLabel);
        if (coupon != null && !coupon.isEmpty())
            chain = new CouponDecorator(chain, Money.of(100), coupon);
        chain = new GSTDecorator(chain, 18.0);
        chain = new DeliveryFeeDecorator(chain, Money.of(40), isPrime);
        return chain;
    }
}

class PrimePricingStrategy implements PricingStrategy {
    @Override public String getName() { return "Prime Pricing"; }
    @Override public PriceComponent buildPriceChain(Product p, boolean isPrime, String coupon) {
        PriceComponent chain = new BasePrice(p.getBasePrice());
        chain = new DiscountDecorator(chain, 5.0, "Prime Exclusive");
        if (coupon != null && !coupon.isEmpty())
            chain = new CouponDecorator(chain, Money.of(100), coupon);
        chain = new GSTDecorator(chain, 18.0);
        chain = new DeliveryFeeDecorator(chain, Money.zero(), true); // always free
        return chain;
    }
}

// ============================================================
// 12. PAYMENT FALLBACK CHAIN — CHAIN OF RESPONSIBILITY
//     Primary → Secondary → Amazon Wallet → Cash on Delivery
// ============================================================
abstract class PaymentHandler {
    protected PaymentHandler next;
    PaymentHandler setNext(PaymentHandler n) { next = n; return n; }

    /** Returns transaction ID or null */
    abstract String handle(Money amount, PaymentMethod method, String userId);

    protected String passToNext(Money amount, PaymentMethod method, String userId) {
        if (next != null) {
            System.out.printf("[PaymentChain] %s failed → trying next handler%n",
                getClass().getSimpleName());
            return next.handle(amount, method, userId);
        }
        System.out.println("[PaymentChain] All payment handlers exhausted — FAILED");
        return null;
    }
}

class PrimaryPaymentHandler extends PaymentHandler {
    private final CircuitBreaker cb = new CircuitBreaker("PaymentGateway", 3, 2, 5000);

    @Override public String handle(Money amount, PaymentMethod method, String userId) {
        return cb.execute(
            () -> {
                // Simulate: UPI and card succeed 80% of time
                if (method == PaymentMethod.COD) return null; // not primary
                boolean success = Math.random() > 0.20;
                if (!success) throw new RuntimeException("Primary gateway timeout");
                String txnId = "TXN-PRI-" + System.currentTimeMillis();
                System.out.printf("[PaymentGateway:Primary] %s via %s → %s%n",
                    amount, method, txnId);
                return txnId;
            },
            () -> passToNext(amount, method, userId) // CB open → directly pass on
        );
    }
}

class SecondaryPaymentHandler extends PaymentHandler {
    @Override public String handle(Money amount, PaymentMethod method, String userId) {
        try {
            boolean success = Math.random() > 0.30;
            if (!success) throw new RuntimeException("Secondary gateway error");
            String txnId = "TXN-SEC-" + System.currentTimeMillis();
            System.out.printf("[PaymentGateway:Secondary] %s via %s → %s%n",
                amount, method, txnId);
            return txnId;
        } catch (Exception e) {
            return passToNext(amount, method, userId);
        }
    }
}

class WalletPaymentHandler extends PaymentHandler {
    private final Map<String, Money> balances = new ConcurrentHashMap<>();

    WalletPaymentHandler() {
        // Pre-seed some balances
        balances.put("user-alice", Money.of(2000));
        balances.put("user-bob",   Money.of(500));
    }

    @Override public String handle(Money amount, PaymentMethod method, String userId) {
        Money balance = balances.getOrDefault(userId, Money.zero());
        if (balance.isGreaterThan(amount) || balance.getPaise() == amount.getPaise()) {
            balances.put(userId, balance.subtract(amount));
            String txnId = "TXN-WAL-" + System.currentTimeMillis();
            System.out.printf("[PaymentGateway:Wallet] %s from wallet → %s (remaining: %s)%n",
                amount, txnId, balances.get(userId));
            return txnId;
        }
        System.out.printf("[PaymentGateway:Wallet] Insufficient balance (%s < %s)%n",
            balance, amount);
        return passToNext(amount, method, userId);
    }
}

class CODPaymentHandler extends PaymentHandler {
    @Override public String handle(Money amount, PaymentMethod method, String userId) {
        String txnId = "TXN-COD-" + System.currentTimeMillis();
        System.out.printf("[PaymentGateway:COD] %s COD order created → %s%n", amount, txnId);
        return txnId;
    }
}

// ============================================================
// 13. SEARCH RANKING STRATEGY
// ============================================================
interface SearchRankingStrategy {
    String getName();
    List<Product> rank(List<Product> products, String query);
}

class RelevanceRankingStrategy implements SearchRankingStrategy {
    @Override public String getName() { return "Relevance"; }
    @Override public List<Product> rank(List<Product> products, String query) {
        String q = query.toLowerCase();
        return products.stream()
            .filter(p -> p.getName().toLowerCase().contains(q))
            .sorted(Comparator.comparingDouble(Product::getRating).reversed())
            .collect(Collectors.toList());
    }
}

class PriceRankingStrategy implements SearchRankingStrategy {
    @Override public String getName() { return "Price: Low to High"; }
    @Override public List<Product> rank(List<Product> products, String query) {
        return products.stream()
            .sorted(Comparator.comparing(Product::getBasePrice))
            .collect(Collectors.toList());
    }
}

class PopularityRankingStrategy implements SearchRankingStrategy {
    @Override public String getName() { return "Popularity (Best Sellers)"; }
    @Override public List<Product> rank(List<Product> products, String query) {
        return products.stream()
            .sorted(Comparator.comparingDouble(Product::getRating).reversed())
            .collect(Collectors.toList());
    }
}

// ============================================================
// 14. DELIVERY STRATEGY
// ============================================================
interface DeliveryStrategy {
    String getName();
    LocalDateTime estimateDelivery(String pincode);
    Money deliveryCost(Money orderTotal, boolean isPrime);
}

class StandardDeliveryStrategy implements DeliveryStrategy {
    @Override public String getName() { return "Standard (5-7 days)"; }
    @Override public LocalDateTime estimateDelivery(String pincode) {
        return LocalDateTime.now().plusDays(6);
    }
    @Override public Money deliveryCost(Money total, boolean prime) {
        return prime || total.isGreaterThan(Money.of(499)) ? Money.zero() : Money.of(40);
    }
}

class PrimeDeliveryStrategy implements DeliveryStrategy {
    @Override public String getName() { return "Prime (1-2 days)"; }
    @Override public LocalDateTime estimateDelivery(String pincode) {
        return LocalDateTime.now().plusDays(2);
    }
    @Override public Money deliveryCost(Money total, boolean prime) { return Money.zero(); }
}

class SameDayDeliveryStrategy implements DeliveryStrategy {
    @Override public String getName() { return "Same Day Delivery"; }
    @Override public LocalDateTime estimateDelivery(String pincode) {
        return LocalDateTime.now().plusHours(8);
    }
    @Override public Money deliveryCost(Money total, boolean prime) {
        return prime ? Money.zero() : Money.of(99);
    }
}

// ============================================================
// 15. ORDER — Builder + State
// ============================================================
class Order {
    private static final AtomicLong idGen = new AtomicLong(1_00_00_000);
    private final long              orderId;
    private final String            orderNumber; // AMZ-YYYYMMDD-XXXX
    private final String            userId;
    private final List<CartItem>    items;
    private final Address           deliveryAddress;
    private final Money             totalAmount;
    private final PriceComponent    priceBreakdown;
    private       OrderStatus       status;
    private       PaymentMethod     paymentMethod;
    private       String            paymentRef;
    private       DeliveryType      deliveryType;
    private       LocalDateTime     estimatedDelivery;
    private       String            trackingId;
    private final LocalDateTime     placedAt;
    private       LocalDateTime     updatedAt;
    private       ReturnReason      returnReason;
    private       CancellationReason cancelReason;

    private Order(Builder b) {
        orderId          = idGen.getAndIncrement();
        orderNumber      = "AMZ-" + LocalDateTime.now().toLocalDate().toString().replace("-","") +
                           "-" + String.format("%04d", orderId % 10000);
        userId           = b.userId; items = b.items;
        deliveryAddress  = b.deliveryAddress; totalAmount = b.totalAmount;
        priceBreakdown   = b.priceBreakdown; status = OrderStatus.PLACED;
        deliveryType     = b.deliveryType; estimatedDelivery = b.estimatedDelivery;
        placedAt         = LocalDateTime.now(); updatedAt = LocalDateTime.now();
    }

    // State transitions
    void confirmPayment(String payRef, PaymentMethod method) {
        paymentRef = payRef; paymentMethod = method;
        status = OrderStatus.CONFIRMED; updatedAt = LocalDateTime.now();
    }
    void pack()    { status = OrderStatus.PACKED;            updatedAt = LocalDateTime.now(); }
    void ship(String tid){ trackingId = tid;
                    status = OrderStatus.SHIPPED;            updatedAt = LocalDateTime.now(); }
    void outForDelivery(){ status = OrderStatus.OUT_FOR_DELIVERY; updatedAt = LocalDateTime.now(); }
    void deliver() { status = OrderStatus.DELIVERED;         updatedAt = LocalDateTime.now(); }
    void requestCancel(CancellationReason r) {
        cancelReason = r; status = OrderStatus.CANCELLATION_REQUESTED; updatedAt = LocalDateTime.now();
    }
    void cancel()  { status = OrderStatus.CANCELLED;         updatedAt = LocalDateTime.now(); }
    void requestReturn(ReturnReason r) {
        returnReason = r; status = OrderStatus.RETURN_REQUESTED; updatedAt = LocalDateTime.now();
    }
    void pickupReturn()  { status = OrderStatus.RETURN_PICKED_UP; updatedAt = LocalDateTime.now(); }
    void initiateRefund(){ status = OrderStatus.REFUND_INITIATED; updatedAt = LocalDateTime.now(); }
    void completeRefund(){ status = OrderStatus.REFUND_COMPLETED; updatedAt = LocalDateTime.now(); }
    void paymentFailed() { status = OrderStatus.PAYMENT_PENDING;  updatedAt = LocalDateTime.now(); }

    long         getOrderId()         { return orderId; }
    String       getOrderNumber()     { return orderNumber; }
    String       getUserId()          { return userId; }
    List<CartItem> getItems()         { return items; }
    Money        getTotalAmount()     { return totalAmount; }
    OrderStatus  getStatus()          { return status; }
    String       getTrackingId()      { return trackingId; }
    DeliveryType getDeliveryType()    { return deliveryType; }
    LocalDateTime getEstimatedDelivery(){ return estimatedDelivery; }
    PriceComponent getPriceBreakdown(){ return priceBreakdown; }

    static class Builder {
        String userId; List<CartItem> items; Address deliveryAddress;
        Money totalAmount; PriceComponent priceBreakdown;
        DeliveryType deliveryType = DeliveryType.STANDARD;
        LocalDateTime estimatedDelivery = LocalDateTime.now().plusDays(6);

        Builder(String userId, List<CartItem> items, Address addr,
                Money total, PriceComponent breakdown) {
            this.userId = userId; this.items = new ArrayList<>(items);
            this.deliveryAddress = addr; this.totalAmount = total;
            this.priceBreakdown = breakdown;
        }
        Builder deliveryType(DeliveryType t)          { deliveryType = t;          return this; }
        Builder estimatedDelivery(LocalDateTime d)    { estimatedDelivery = d;     return this; }
        Order  build()                                { return new Order(this); }
    }

    @Override public String toString() {
        return String.format("Order[%s | %s | %s | ETA: %s]",
            orderNumber, status, totalAmount,
            estimatedDelivery != null ? estimatedDelivery.toLocalDate() : "TBD");
    }
}

// ============================================================
// 16. ORDER EVENT OBSERVER
// ============================================================
interface OrderEventObserver {
    void onOrderPlaced(Order order);
    void onOrderConfirmed(Order order);
    void onOrderShipped(Order order);
    void onOrderDelivered(Order order);
    void onOrderCancelled(Order order);
    void onReturnInitiated(Order order);
    void onRefundCompleted(Order order);
}

class NotificationObserver implements OrderEventObserver {
    @Override public void onOrderPlaced(Order o) {
        System.out.printf("[📧 Notif → %s] Order %s placed! Total: %s%n",
            o.getUserId(), o.getOrderNumber(), o.getTotalAmount());
    }
    @Override public void onOrderConfirmed(Order o) {
        System.out.printf("[📧 Notif → %s] Order %s confirmed. Estimated delivery: %s%n",
            o.getUserId(), o.getOrderNumber(), o.getEstimatedDelivery().toLocalDate());
    }
    @Override public void onOrderShipped(Order o) {
        System.out.printf("[📧 Notif → %s] Order %s shipped! Tracking: %s%n",
            o.getUserId(), o.getOrderNumber(), o.getTrackingId());
    }
    @Override public void onOrderDelivered(Order o) {
        System.out.printf("[📧 Notif → %s] Order %s delivered! How was it? Leave a review.%n",
            o.getUserId(), o.getOrderNumber());
    }
    @Override public void onOrderCancelled(Order o) {
        System.out.printf("[📧 Notif → %s] Order %s cancelled. Refund in 5-7 days.%n",
            o.getUserId(), o.getOrderNumber());
    }
    @Override public void onReturnInitiated(Order o) {
        System.out.printf("[📧 Notif → %s] Return for %s initiated. Pickup in 2 days.%n",
            o.getUserId(), o.getOrderNumber());
    }
    @Override public void onRefundCompleted(Order o) {
        System.out.printf("[📧 Notif → %s] Refund of %s completed for order %s%n",
            o.getUserId(), o.getTotalAmount(), o.getOrderNumber());
    }
}

class InventoryObserver implements OrderEventObserver {
    private final InventoryService inv;
    InventoryObserver(InventoryService inv) { this.inv = inv; }
    @Override public void onOrderConfirmed(Order o) {
        o.getItems().forEach(item ->
            inv.confirm(item.product.getProductId(), item.quantity));
    }
    @Override public void onOrderCancelled(Order o) {
        o.getItems().forEach(item ->
            inv.release(item.product.getProductId(), item.quantity));
    }
    @Override public void onReturnInitiated(Order o) {
        o.getItems().forEach(item ->
            inv.addStock(item.product.getProductId(), item.quantity));
        System.out.println("[Inventory] Stock restocked after return: " + o.getOrderNumber());
    }
    @Override public void onOrderPlaced(Order o)     {}
    @Override public void onOrderShipped(Order o)    {}
    @Override public void onOrderDelivered(Order o)  {}
    @Override public void onRefundCompleted(Order o) {}
}

class LoyaltyObserver implements OrderEventObserver {
    private final Map<String, Integer> points = new ConcurrentHashMap<>();
    private static final int PTS_PER_100 = 2; // 2 pts per ₹100

    @Override public void onOrderDelivered(Order o) {
        int earned = (int)(o.getTotalAmount().getPaise() / 5000) * PTS_PER_100;
        points.merge(o.getUserId(), earned, Integer::sum);
        System.out.printf("[Loyalty] %s earned %d pts → total: %d%n",
            o.getUserId(), earned, points.get(o.getUserId()));
    }
    @Override public void onOrderCancelled(Order o) {
        int deduct = (int)(o.getTotalAmount().getPaise() / 5000) * PTS_PER_100;
        points.merge(o.getUserId(), -deduct, Integer::sum);
    }
    @Override public void onOrderPlaced(Order o)       {}
    @Override public void onOrderConfirmed(Order o)    {}
    @Override public void onOrderShipped(Order o)      {}
    @Override public void onReturnInitiated(Order o)   {}
    @Override public void onRefundCompleted(Order o)   {}

    int getPoints(String userId) { return points.getOrDefault(userId, 0); }
}

class AnalyticsObserver implements OrderEventObserver {
    private final Map<ProductCategory, Integer> salesByCategory = new ConcurrentHashMap<>();
    private final List<String> eventLog = new CopyOnWriteArrayList<>();

    private void log(String msg) {
        eventLog.add(LocalDateTime.now().toLocalTime() + " | " + msg);
        System.out.println("[Analytics] " + msg);
    }

    @Override public void onOrderPlaced(Order o)     { log("ORDER_PLACED " + o.getOrderNumber() + " " + o.getTotalAmount()); }
    @Override public void onOrderConfirmed(Order o)  { log("ORDER_CONFIRMED " + o.getOrderNumber()); }
    @Override public void onOrderDelivered(Order o)  {
        log("ORDER_DELIVERED " + o.getOrderNumber());
        o.getItems().forEach(i ->
            salesByCategory.merge(i.product.getCategory(), i.quantity, Integer::sum));
    }
    @Override public void onOrderCancelled(Order o)  { log("ORDER_CANCELLED " + o.getOrderNumber()); }
    @Override public void onOrderShipped(Order o)    {}
    @Override public void onReturnInitiated(Order o) { log("RETURN_INITIATED " + o.getOrderNumber()); }
    @Override public void onRefundCompleted(Order o) { log("REFUND_COMPLETED " + o.getOrderNumber()); }
    List<String> getLog() { return Collections.unmodifiableList(eventLog); }
}

// ============================================================
// 17. COMMANDS — COMMAND PATTERN
// ============================================================
interface ShoppingCommand { Order execute(); void undo(); }

class PlaceOrderCommand implements ShoppingCommand {
    private final Cart             cart;
    private final Address          address;
    private final String           userId;
    private final PricingStrategy  pricing;
    private final DeliveryStrategy delivery;
    private final boolean          isPrime;
    private final String           couponCode;
    private final PaymentMethod    paymentMethod;
    private final List<OrderEventObserver> observers;
    private final InventoryService inv;
    private final Map<Long, Order> orderStore;
    private       Order            placedOrder;

    PlaceOrderCommand(Cart cart, Address address, String userId,
                      PricingStrategy pricing, DeliveryStrategy delivery,
                      boolean isPrime, String couponCode, PaymentMethod payMethod,
                      List<OrderEventObserver> observers, InventoryService inv,
                      Map<Long, Order> store) {
        this.cart = cart; this.address = address; this.userId = userId;
        this.pricing = pricing; this.delivery = delivery;
        this.isPrime = isPrime; this.couponCode = couponCode;
        this.paymentMethod = payMethod; this.observers = observers;
        this.inv = inv; this.orderStore = store;
    }

    @Override
    public Order execute() {
        // 1. Reserve inventory for all items
        List<CartItem> items = new ArrayList<>(cart.getItems());
        for (CartItem item : items) {
            if (!inv.reserve(item.product.getProductId(), item.quantity)) {
                System.out.println("[PlaceOrderCmd] Insufficient stock for: " + item.product.getName());
                // rollback already-reserved
                items.forEach(i -> inv.release(i.product.getProductId(), i.quantity));
                return null;
            }
        }

        // 2. Build price via decorators and chosen strategy
        // Use first item's price as representative (real: sum all)
        CartItem first = items.get(0);
        PriceComponent chain = pricing.buildPriceChain(first.product, isPrime, couponCode);
        Money total = items.stream()
            .map(i -> {
                PriceComponent c = pricing.buildPriceChain(i.product, isPrime, couponCode);
                return c.calculate(i.quantity);
            })
            .reduce(Money.zero(), Money::add);

        // 3. Build order
        LocalDateTime eta = delivery.estimateDelivery(address.getPincode());
        DeliveryType dtype = delivery instanceof PrimeDeliveryStrategy ? DeliveryType.PRIME
                           : delivery instanceof SameDayDeliveryStrategy ? DeliveryType.SAME_DAY
                           : DeliveryType.STANDARD;

        placedOrder = new Order.Builder(userId, items, address, total, chain)
            .deliveryType(dtype).estimatedDelivery(eta).build();
        orderStore.put(placedOrder.getOrderId(), placedOrder);

        // 4. Process payment via fallback chain
        PrimaryPaymentHandler primary = new PrimaryPaymentHandler();
        SecondaryPaymentHandler secondary = new SecondaryPaymentHandler();
        WalletPaymentHandler wallet = new WalletPaymentHandler();
        CODPaymentHandler cod = new CODPaymentHandler();
        primary.setNext(secondary).setNext(wallet).setNext(cod);

        observers.forEach(o -> o.onOrderPlaced(placedOrder));

        String txnId = primary.handle(total, paymentMethod, userId);
        if (txnId != null) {
            placedOrder.confirmPayment(txnId, paymentMethod);
            observers.forEach(o -> o.onOrderConfirmed(placedOrder));
        } else {
            placedOrder.paymentFailed();
            // Release inventory
            items.forEach(i -> inv.release(i.product.getProductId(), i.quantity));
        }

        cart.clear(); // Cart cleared after order placed
        return placedOrder;
    }

    @Override
    public void undo() {
        // Cancel the placed order
        if (placedOrder != null && placedOrder.getStatus() == OrderStatus.CONFIRMED) {
            placedOrder.requestCancel(CancellationReason.USER_REQUESTED);
            placedOrder.cancel();
            observers.forEach(o -> o.onOrderCancelled(placedOrder));
            System.out.println("[PlaceOrderCmd UNDO] Order cancelled: " + placedOrder.getOrderNumber());
        }
    }
}

class AddToCartCommand implements ShoppingCommand {
    private final Cart    cart;
    private final Product product;
    private final int     qty;
    AddToCartCommand(Cart cart, Product product, int qty) {
        this.cart = cart; this.product = product; this.qty = qty;
    }
    @Override public Order execute() { cart.addItem(product, qty); return null; }
    @Override public void  undo()    { cart.removeItem(product.getProductId()); }
}

// ============================================================
// 18. ABSTRACT ORDER PROCESSOR — TEMPLATE METHOD
// ============================================================
abstract class AbstractOrderProcessor {
    protected final List<OrderEventObserver> observers  = new ArrayList<>();
    protected final Map<Long, Order>         orderStore = new ConcurrentHashMap<>();
    protected final InventoryService         inv        = InventoryService.getInstance();

    void addObserver(OrderEventObserver o) { observers.add(o); }

    // Template — fixed processing skeleton
    final Order placeOrder(Cart cart, Address address, String userId,
                            PricingStrategy pricing, DeliveryStrategy delivery,
                            boolean isPrime, String coupon, PaymentMethod payMethod) {
        if (!validateCart(cart)) return null;
        PlaceOrderCommand cmd = new PlaceOrderCommand(
            cart, address, userId, pricing, delivery, isPrime, coupon, payMethod,
            observers, inv, orderStore);
        Order order = cmd.execute();
        if (order != null) postPlace(order);
        return order;
    }

    protected abstract boolean validateCart(Cart cart);
    protected abstract void    postPlace(Order order);

    Order cancelOrder(long orderId) {
        Order o = orderStore.get(orderId);
        if (o == null) { System.out.println("[Processor] Order not found"); return null; }
        if (o.getStatus() == OrderStatus.SHIPPED || o.getStatus() == OrderStatus.OUT_FOR_DELIVERY ||
            o.getStatus() == OrderStatus.DELIVERED) {
            System.out.println("[Processor] Cannot cancel — order already shipped");
            return o;
        }
        o.requestCancel(CancellationReason.USER_REQUESTED);
        o.cancel();
        o.initiateRefund();
        observers.forEach(obs -> obs.onOrderCancelled(o));
        return o;
    }

    Order returnOrder(long orderId, ReturnReason reason) {
        Order o = orderStore.get(orderId);
        if (o == null) return null;
        if (o.getStatus() != OrderStatus.DELIVERED) {
            System.out.println("[Processor] Can only return delivered orders");
            return o;
        }
        o.requestReturn(reason);
        o.pickupReturn();
        o.initiateRefund();
        observers.forEach(obs -> obs.onReturnInitiated(o));
        return o;
    }

    void simulateShipment(long orderId) {
        Order o = orderStore.get(orderId);
        if (o == null) return;
        o.pack();
        o.ship("DELHIVERY-" + orderId);
        observers.forEach(obs -> obs.onOrderShipped(o));
        o.outForDelivery();
        o.deliver();
        observers.forEach(obs -> obs.onOrderDelivered(o));
    }

    Map<Long, Order> getAllOrders() { return Collections.unmodifiableMap(orderStore); }
}

// ============================================================
// 19. STANDARD ORDER PROCESSOR (concrete)
// ============================================================
class StandardOrderProcessor extends AbstractOrderProcessor {
    @Override
    protected boolean validateCart(Cart cart) {
        if (cart.isEmpty()) {
            System.out.println("[Processor] Cart is empty"); return false;
        }
        if (cart.itemCount() > 100) {
            System.out.println("[Processor] Too many items"); return false;
        }
        return true;
    }
    @Override
    protected void postPlace(Order order) {
        System.out.printf("[Processor] Order %s → e-receipt queued, warehouse notified%n",
            order.getOrderNumber());
    }
}

// ============================================================
// 20. SEARCH SERVICE — with CB + cache fallback
// ============================================================
class SearchService {
    private final List<Product>             catalog = new CopyOnWriteArrayList<>();
    private final CircuitBreaker            cb      = new CircuitBreaker("Search", 3, 2, 5000);
    private final StaleCache<String, List<Product>> cache = new StaleCache<>(120_000);
    private final RetryExecutor             retry   = new RetryExecutor(3, 200, 2.0);
    private       SearchRankingStrategy     rankingStrategy = new RelevanceRankingStrategy();

    void addProduct(Product p) { catalog.add(p); }
    void setRankingStrategy(SearchRankingStrategy s) { rankingStrategy = s; }

    List<Product> search(String query) {
        return cb.execute(
            () -> retry.execute("search:" + query,
                () -> {
                    List<Product> results = rankingStrategy.rank(catalog, query);
                    cache.put(query, results);
                    System.out.printf("[Search] '%s' → %d results (strategy: %s)%n",
                        query, results.size(), rankingStrategy.getName());
                    return results;
                },
                () -> cache.get(query).orElse(List.of()) // retry fallback: cache
            ),
            () -> {
                // CB open fallback: stale cache
                Optional<List<Product>> stale = cache.get(query);
                System.out.printf("[Search:FALLBACK] Serving stale cache for '%s' (%s)%n",
                    query, stale.isPresent() ? stale.get().size() + " results" : "empty");
                return stale.orElse(List.of());
            }
        );
    }

    CircuitBreaker getCB() { return cb; }
}

// ============================================================
// 21. AMAZON SYSTEM — SINGLETON
// ============================================================
class AmazonSystem {
    private static AmazonSystem instance;
    private final Map<String, Cart>     carts     = new ConcurrentHashMap<>();
    private final SearchService         search    = new SearchService();
    private final InventoryService      inventory = InventoryService.getInstance();
    private final NotificationObserver  notif     = new NotificationObserver();
    private final InventoryObserver     invObs    = new InventoryObserver(inventory);
    private final LoyaltyObserver       loyalty   = new LoyaltyObserver();
    private final AnalyticsObserver     analytics = new AnalyticsObserver();

    private AmazonSystem() {}
    static synchronized AmazonSystem getInstance() {
        if (instance == null) instance = new AmazonSystem();
        return instance;
    }

    StandardOrderProcessor createProcessor() {
        StandardOrderProcessor proc = new StandardOrderProcessor();
        proc.addObserver(notif); proc.addObserver(invObs);
        proc.addObserver(loyalty); proc.addObserver(analytics);
        return proc;
    }

    Cart getOrCreateCart(String userId) {
        return carts.computeIfAbsent(userId, Cart::new);
    }

    void registerProduct(Product p, int stock) {
        search.addProduct(p);
        inventory.addStock(p.getProductId(), stock);
    }

    SearchService    getSearch()    { return search; }
    LoyaltyObserver  getLoyalty()   { return loyalty; }
    AnalyticsObserver getAnalytics(){ return analytics; }
}

// ============================================================
// 22. MAIN — DRIVER + SCENARIOS (10 scenarios + fallback demos)
// ============================================================
public class AmazonShoppingApp {
    public static void main(String[] args) throws InterruptedException {

        AmazonSystem system = AmazonSystem.getInstance();
        StandardOrderProcessor proc = system.createProcessor();

        // --- Products ---
        Product phone  = new Product.Builder("Samsung Galaxy S24")
            .brand("Samsung").category(ProductCategory.ELECTRONICS)
            .price(79999).rating(4.4, 12500).prime(true).build();
        Product book   = new Product.Builder("Clean Code by Robert Martin")
            .brand("Pearson").category(ProductCategory.BOOKS)
            .price(499).rating(4.8, 4300).prime(true).build();
        Product laptop = new Product.Builder("MacBook Air M3")
            .brand("Apple").category(ProductCategory.ELECTRONICS)
            .price(114900).rating(4.7, 8200).prime(true).build();
        Product shoes  = new Product.Builder("Nike Air Max 270")
            .brand("Nike").category(ProductCategory.SPORTS)
            .price(9999).rating(4.3, 3100).prime(true).build();
        Product mixer  = new Product.Builder("Philips Mixer Grinder")
            .brand("Philips").category(ProductCategory.HOME)
            .price(3499).rating(4.2, 7800).prime(false).build();

        system.registerProduct(phone,  50);
        system.registerProduct(book,  200);
        system.registerProduct(laptop, 15);
        system.registerProduct(shoes,  80);
        system.registerProduct(mixer,  30);

        Address addr = new Address("Alice Kumar", "42 MG Road",
            "Bangalore", "Karnataka", "560001", "9876543210");

        // ===== SCENARIO 1: Search + relevance ranking =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 1: Search 'Samsung' (Relevance Ranking)");
        System.out.println("=".repeat(65));
        List<Product> results = system.getSearch().search("Samsung");
        results.forEach(p -> System.out.println("  " + p));

        // Switch strategy
        system.getSearch().setRankingStrategy(new PriceRankingStrategy());
        System.out.println("\nRe-search with Price strategy:");
        system.getSearch().search("a").forEach(p -> System.out.println("  " + p));

        // ===== SCENARIO 2: Add to cart + standard order =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 2: Add to Cart + Place Order (Standard Pricing)");
        System.out.println("=".repeat(65));
        Cart cart1 = system.getOrCreateCart("user-alice");
        new AddToCartCommand(cart1, phone, 1).execute();
        new AddToCartCommand(cart1, book,  2).execute();
        System.out.println("Cart total: " + cart1.getTotal());
        cart1.getItems().forEach(System.out::println);

        PricingStrategy std  = new StandardPricingStrategy();
        DeliveryStrategy del = new StandardDeliveryStrategy();
        Order o1 = proc.placeOrder(cart1, addr, "user-alice", std, del, false, null, PaymentMethod.UPI);
        System.out.println("\nOrder placed: " + o1);

        // ===== SCENARIO 3: Prime order + deal pricing =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 3: Prime Order + Deal Pricing + Coupon");
        System.out.println("=".repeat(65));
        Cart cart2 = system.getOrCreateCart("user-bob");
        new AddToCartCommand(cart2, shoes, 1).execute();
        new AddToCartCommand(cart2, mixer, 1).execute();

        PricingStrategy deal = new DealPricingStrategy(20.0, "Big Sale -20%");
        DeliveryStrategy prime = new PrimeDeliveryStrategy();
        Order o2 = proc.placeOrder(cart2, addr, "user-bob", deal, prime, true, "SAVE100", PaymentMethod.CREDIT_CARD);
        System.out.println("\nPrime order: " + o2);
        if (o2 != null) System.out.println("Price breakdown: " + o2.getPriceBreakdown().breakdown());

        // ===== SCENARIO 4: Simulate shipment + delivery =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 4: Simulate Full Shipment Lifecycle");
        System.out.println("=".repeat(65));
        if (o1 != null && o1.getStatus() == OrderStatus.CONFIRMED) {
            proc.simulateShipment(o1.getOrderId());
            System.out.println("Final status: " + o1.getStatus());
        }

        // ===== SCENARIO 5: Cancel order =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 5: Cancel Order Before Shipment");
        System.out.println("=".repeat(65));
        Cart cart3 = system.getOrCreateCart("user-carol");
        new AddToCartCommand(cart3, laptop, 1).execute();
        Order o3 = proc.placeOrder(cart3, addr, "user-carol", std, del, false, null, PaymentMethod.NET_BANKING);
        if (o3 != null) {
            System.out.println("Before cancel: " + o3.getStatus());
            proc.cancelOrder(o3.getOrderId());
            System.out.println("After cancel: " + o3.getStatus());
        }

        // ===== SCENARIO 6: Return delivered order =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 6: Return Delivered Order");
        System.out.println("=".repeat(65));
        Cart cart4 = system.getOrCreateCart("user-dave");
        new AddToCartCommand(cart4, mixer, 1).execute();
        Order o4 = proc.placeOrder(cart4, addr, "user-dave", std, del, false, null, PaymentMethod.UPI);
        if (o4 != null && o4.getStatus() == OrderStatus.CONFIRMED) {
            proc.simulateShipment(o4.getOrderId());
            Order returned = proc.returnOrder(o4.getOrderId(), ReturnReason.DEFECTIVE);
            System.out.println("Return status: " + returned.getStatus());
        }

        // ===== SCENARIO 7: Payment fallback chain =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 7: Payment Fallback Chain Demo");
        System.out.println("=".repeat(65));
        PrimaryPaymentHandler ph = new PrimaryPaymentHandler();
        SecondaryPaymentHandler sh = new SecondaryPaymentHandler();
        WalletPaymentHandler wh = new WalletPaymentHandler();
        CODPaymentHandler ch = new CODPaymentHandler();
        ph.setNext(sh).setNext(wh).setNext(ch);

        // Force primary CB open to show fallback
        ph.toString(); // just to reference
        System.out.println("Attempting payment ₹5000 (primary may fail → fallback chain):");
        for (int i = 0; i < 3; i++) {
            String txn = ph.handle(Money.of(5000), PaymentMethod.CREDIT_CARD, "user-alice");
            System.out.println("Result: " + txn);
        }

        // ===== SCENARIO 8: Circuit Breaker — Search service down =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 8: Circuit Breaker — Search Service Down");
        System.out.println("=".repeat(65));
        // Warm cache first
        system.getSearch().search("Samsung");
        system.getSearch().search("Nike");

        // Force CB open (simulating search service crash)
        system.getSearch().getCB().forceOpen();
        System.out.println("Search CB forced OPEN. Next search will use stale cache:");
        List<Product> fallbackResults = system.getSearch().search("Samsung");
        System.out.println("Fallback returned " + fallbackResults.size() + " results from cache");

        // Recover
        system.getSearch().getCB().forceClose();
        System.out.println("CB recovered → CLOSED");
        system.getSearch().search("Samsung");

        // ===== SCENARIO 9: Inventory Circuit Breaker =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 9: Inventory Service CB — Optimistic Reserve Fallback");
        System.out.println("=".repeat(65));
        // Warm inventory cache
        inventory.getAvailable(phone.getProductId());

        // Force CB open
        inventory.getCB().forceOpen();
        System.out.println("Inventory CB forced OPEN:");
        Cart cart5 = system.getOrCreateCart("user-eve");
        new AddToCartCommand(cart5, phone, 1).execute();
        // reserve will use cache-based optimistic fallback
        boolean reserved = inventory.reserve(phone.getProductId(), 1);
        System.out.println("Optimistic reserve result: " + reserved);
        inventory.getCB().forceClose();

        // ===== SCENARIO 10: Loyalty points earned =====
        System.out.println("\n" + "=".repeat(65));
        System.out.println("SCENARIO 10: Loyalty Points Summary");
        System.out.println("=".repeat(65));
        System.out.println("user-alice points: " + system.getLoyalty().getPoints("user-alice"));
        System.out.println("user-bob points:   " + system.getLoyalty().getPoints("user-bob"));
        System.out.println("user-dave points:  " + system.getLoyalty().getPoints("user-dave"));

        System.out.println("\nAnalytics log (last 8 entries):");
        List<String> alog = system.getAnalytics().getLog();
        alog.stream().skip(Math.max(0, alog.size()-8)).forEach(System.out::println);

        System.out.println("""

===== PATTERN SUMMARY =====
Pattern          | Class(es)
-----------------|----------------------------------------------------------
Singleton        | AmazonSystem, InventoryService
State            | OrderStatus — PLACED→CONFIRMED→SHIPPED→DELIVERED→RETURNED→REFUND
Strategy         | PricingStrategy (Standard/Deal/Prime) · SearchRankingStrategy · DeliveryStrategy
Observer         | OrderEventObserver → Notification / Inventory / Loyalty / Analytics
Decorator        | PriceDecorator → Discount + Coupon + GST + DeliveryFee (layered)
Command          | AddToCartCommand, PlaceOrderCommand (execute + undo = cancel)
Template Method  | AbstractOrderProcessor.placeOrder() — validate→reserve→price→pay→confirm
Chain of Resp.   | PaymentHandler → Primary → Secondary → Wallet → COD
Circuit Breaker  | CircuitBreaker (CLOSED→OPEN→HALF_OPEN) on Search + Inventory + Payment
Retry + Backoff  | RetryExecutor (3 attempts, exponential 200ms→400ms→800ms)
Stale Cache      | StaleCache<K,V> — serve stale data when live service is down
Builder          | Product.Builder, Order.Builder, SearchQuery chaining
Factory          | AmazonSystem.createProcessor() + PaymentHandler chain assembly
""");
    }
}
