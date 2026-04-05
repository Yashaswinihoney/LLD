import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// SHOPPING CART WITH FLUCTUATING PRICES LLD
// Patterns:
//   Singleton  — CartService, PriceEngine
//   Observer   — PriceChangeObserver (notify carts of price drops/rises)
//   Strategy   — PricingStrategy (flat, dynamic, flash sale, membership)
//   Factory    — DiscountFactory (percentage, flat, BOGO, coupon)
//   Builder    — Order construction
//   State      — Cart states (ACTIVE → LOCKED → ORDERED → ABANDONED)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum CartStatus     { ACTIVE, LOCKED, ORDERED, ABANDONED, EXPIRED }
enum OrderStatus    { PENDING, PAYMENT_INITIATED, CONFIRMED, FAILED, CANCELLED }
enum StockStatus    { IN_STOCK, LOW_STOCK, OUT_OF_STOCK }
enum PriceChangeType { INCREASE, DECREASE, FLASH_SALE_START, FLASH_SALE_END }

// ==========================================
// 2. USER
// ==========================================
class User {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final  long   id;
    private final  String name;
    private final  String email;
    private        boolean isPrimeMember;

    public User(String name, String email, boolean isPrimeMember) {
        this.id            = idGen.getAndIncrement();
        this.name          = name;
        this.email         = email;
        this.isPrimeMember = isPrimeMember;
    }

    public long    getId()           { return id; }
    public String  getName()         { return name; }
    public String  getEmail()        { return email; }
    public boolean isPrimeMember()   { return isPrimeMember; }

    @Override public String toString() {
        return "User[" + name + (isPrimeMember ? " 👑" : "") + "]";
    }
}

// ==========================================
// 3. PRODUCT
// ==========================================
class Product {
    private static final AtomicLong idGen = new AtomicLong(100);
    private final  long        id;
    private final  String      name;
    private final  String      category;
    private        double      basePrice;       // original MRP
    private        double      currentPrice;    // live price (fluctuates)
    private        int         stockCount;
    private        StockStatus stockStatus;
    private        boolean     isFlashSale;
    private        double      flashSalePrice;

    public Product(String name, String category, double basePrice, int stock) {
        this.id           = idGen.getAndIncrement();
        this.name         = name;
        this.category     = category;
        this.basePrice    = basePrice;
        this.currentPrice = basePrice;
        this.stockCount   = stock;
        this.stockStatus  = stock > 10 ? StockStatus.IN_STOCK :
                           (stock > 0  ? StockStatus.LOW_STOCK :
                                         StockStatus.OUT_OF_STOCK);
    }

    public synchronized boolean reserveStock(int qty) {
        if (stockCount >= qty) {
            stockCount -= qty;
            updateStockStatus();
            return true;
        }
        return false;
    }

    public synchronized void releaseStock(int qty) {
        stockCount += qty;
        updateStockStatus();
    }

    private void updateStockStatus() {
        this.stockStatus = stockCount > 10 ? StockStatus.IN_STOCK :
                          (stockCount > 0  ? StockStatus.LOW_STOCK :
                                             StockStatus.OUT_OF_STOCK);
    }

    public synchronized void updatePrice(double newPrice) {
        this.currentPrice = newPrice;
    }

    public synchronized void startFlashSale(double salePrice) {
        this.isFlashSale   = true;
        this.flashSalePrice = salePrice;
        this.currentPrice  = salePrice;
        System.out.println("[FlashSale] " + name + " now ₹" + salePrice +
            " (was ₹" + basePrice + ")");
    }

    public synchronized void endFlashSale() {
        this.isFlashSale  = false;
        this.currentPrice = basePrice;
        System.out.println("[FlashSale] " + name + " flash sale ended. Back to ₹" + basePrice);
    }

    public long        getId()           { return id; }
    public String      getName()         { return name; }
    public String      getCategory()     { return category; }
    public double      getBasePrice()    { return basePrice; }
    public double      getCurrentPrice() { return currentPrice; }
    public int         getStockCount()   { return stockCount; }
    public StockStatus getStockStatus()  { return stockStatus; }
    public boolean     isFlashSale()     { return isFlashSale; }

    @Override public String toString() {
        return "Product[" + name + " | ₹" + currentPrice +
               (isFlashSale ? " 🔥FLASH" : "") + " | " + stockStatus + "]";
    }
}

// ==========================================
// 4. STRATEGY PATTERN — PRICING
// ==========================================
interface PricingStrategy {
    String  getName();
    double  computePrice(Product product, User user, int quantity);
    boolean isApplicable(Product product, User user);
}

class StandardPricingStrategy implements PricingStrategy {
    @Override public String getName() { return "Standard"; }

    @Override
    public double computePrice(Product p, User u, int qty) {
        return p.getCurrentPrice() * qty;
    }

    @Override public boolean isApplicable(Product p, User u) { return true; }
}

class MembershipPricingStrategy implements PricingStrategy {
    private final double discountPct;

    public MembershipPricingStrategy(double discountPct) {
        this.discountPct = discountPct;
    }

    @Override public String getName() { return "Membership (" + discountPct + "% off)"; }

    @Override
    public double computePrice(Product p, User u, int qty) {
        double base = p.getCurrentPrice() * qty;
        return base * (1 - discountPct / 100.0);
    }

    @Override
    public boolean isApplicable(Product p, User u) {
        return u.isPrimeMember();
    }
}

class BulkDiscountStrategy implements PricingStrategy {
    private final int    minQty;
    private final double discountPct;

    public BulkDiscountStrategy(int minQty, double discountPct) {
        this.minQty      = minQty;
        this.discountPct = discountPct;
    }

    @Override public String getName() { return "Bulk (" + minQty + "+ = " + discountPct + "% off)"; }

    @Override
    public double computePrice(Product p, User u, int qty) {
        double base = p.getCurrentPrice() * qty;
        return qty >= minQty ? base * (1 - discountPct / 100.0) : base;
    }

    @Override
    public boolean isApplicable(Product p, User u) { return true; }
}

class FlashSalePricingStrategy implements PricingStrategy {
    @Override public String getName() { return "Flash Sale"; }

    @Override
    public double computePrice(Product p, User u, int qty) {
        return p.getCurrentPrice() * qty; // flash sale price already in currentPrice
    }

    @Override
    public boolean isApplicable(Product p, User u) { return p.isFlashSale(); }
}

// ==========================================
// 5. DISCOUNT FACTORY
// ==========================================
abstract class Discount {
    protected final String code;
    protected final String description;

    public Discount(String code, String description) {
        this.code        = code;
        this.description = description;
    }

    public abstract double apply(double cartTotal, List<CartItem> items);
    public String getCode()        { return code; }
    public String getDescription() { return description; }
}

class PercentageDiscount extends Discount {
    private final double pct;
    private final double maxCap;

    public PercentageDiscount(String code, double pct, double maxCap) {
        super(code, pct + "% off (max ₹" + maxCap + ")");
        this.pct    = pct;
        this.maxCap = maxCap;
    }

    @Override
    public double apply(double cartTotal, List<CartItem> items) {
        return Math.min(cartTotal * pct / 100.0, maxCap);
    }
}

class FlatDiscount extends Discount {
    private final double amount;
    private final double minOrderValue;

    public FlatDiscount(String code, double amount, double minOrderValue) {
        super(code, "Flat ₹" + amount + " off on orders above ₹" + minOrderValue);
        this.amount        = amount;
        this.minOrderValue = minOrderValue;
    }

    @Override
    public double apply(double cartTotal, List<CartItem> items) {
        return cartTotal >= minOrderValue ? amount : 0;
    }
}

class CategoryDiscount extends Discount {
    private final String category;
    private final double pct;

    public CategoryDiscount(String code, String category, double pct) {
        super(code, pct + "% off on " + category);
        this.category = category;
        this.pct      = pct;
    }

    @Override
    public double apply(double cartTotal, List<CartItem> items) {
        return items.stream()
            .filter(i -> i.getProduct().getCategory().equalsIgnoreCase(category))
            .mapToDouble(i -> i.getEffectivePrice() * pct / 100.0)
            .sum();
    }
}

class DiscountFactory {
    public enum DiscountType { PERCENTAGE, FLAT, CATEGORY }

    public static Discount create(DiscountType type, String code,
                                   double value, double threshold, String extra) {
        return switch (type) {
            case PERCENTAGE -> new PercentageDiscount(code, value, threshold);
            case FLAT       -> new FlatDiscount(code, value, threshold);
            case CATEGORY   -> new CategoryDiscount(code, extra, value);
        };
    }
}

// ==========================================
// 6. CART ITEM — price snapshot + live price
// ==========================================
class CartItem {
    private final  Product product;
    private        int     quantity;
    private final  double  priceAtAdd;     // price when item was added
    private        double  currentPrice;   // live price (may have changed)
    private        double  effectivePrice; // after pricing strategy
    private        boolean priceChanged;   // flag for UI warning

    public CartItem(Product product, int quantity, double priceAtAdd) {
        this.product       = product;
        this.quantity      = quantity;
        this.priceAtAdd    = priceAtAdd;
        this.currentPrice  = priceAtAdd;
        this.effectivePrice = priceAtAdd;
        this.priceChanged  = false;
    }

    public void refreshPrice() {
        double live = product.getCurrentPrice();
        if (Math.abs(live - currentPrice) > 0.001) {
            System.out.println("[PriceRefresh] " + product.getName() +
                ": ₹" + currentPrice + " → ₹" + live +
                (live < currentPrice ? " ▼ CHEAPER!" : " ▲ Price rose"));
            this.priceChanged = true;
        }
        this.currentPrice  = live;
        this.effectivePrice = live;
    }

    public double getSubtotal()        { return effectivePrice * quantity; }
    public double getPriceAtAdd()      { return priceAtAdd; }
    public double getPriceDifference() { return currentPrice - priceAtAdd; }
    public boolean hasPriceIncreased() { return currentPrice > priceAtAdd; }
    public boolean hasPriceDecreased() { return currentPrice < priceAtAdd; }

    public Product getProduct()        { return product; }
    public int     getQuantity()       { return quantity; }
    public double  getCurrentPrice()   { return currentPrice; }
    public double  getEffectivePrice() { return effectivePrice; }
    public boolean isPriceChanged()    { return priceChanged; }

    public void setQuantity(int q)          { this.quantity = q; }
    public void setEffectivePrice(double p) { this.effectivePrice = p; }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-30s x%-2d | added@₹%-8.0f current@₹%-8.0f | subtotal=₹%.0f",
            product.getName(), quantity, priceAtAdd, currentPrice, getSubtotal()));
        if (priceChanged) {
            sb.append(hasPriceDecreased() ? " ▼CHEAPER" : " ▲PRICIER");
        }
        return sb.toString();
    }
}

// ==========================================
// 7. OBSERVER — PRICE CHANGE NOTIFIER
// ==========================================
interface PriceChangeObserver {
    void onPriceChanged(Product product, double oldPrice, double newPrice,
                        PriceChangeType changeType);
}

class CartPriceWatcher implements PriceChangeObserver {
    private final Cart cart;

    public CartPriceWatcher(Cart cart) { this.cart = cart; }

    @Override
    public void onPriceChanged(Product product, double oldPrice, double newPrice,
                                PriceChangeType changeType) {
        // Find this product in the cart and refresh
        cart.getItems().stream()
            .filter(item -> item.getProduct().getId() == product.getId())
            .forEach(item -> {
                item.refreshPrice();
                System.out.println("[CartWatcher] Cart #" + cart.getId() +
                    " notified: " + product.getName() +
                    " price changed ₹" + oldPrice + " → ₹" + newPrice);
            });
    }
}

// ==========================================
// 8. CART — STATE MACHINE
// ==========================================
class Cart {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final  long              id;
    private final  User              user;
    private final  List<CartItem>    items        = new CopyOnWriteArrayList<>();
    private        CartStatus        status       = CartStatus.ACTIVE;
    private        Discount          appliedDiscount;
    private final  LocalDateTime     createdAt    = LocalDateTime.now();
    private        LocalDateTime     updatedAt    = LocalDateTime.now();

    // Snapshot of totals at last checkout attempt
    private        double            lockedTotal;
    private        List<CartItem>    priceChangedItems = new ArrayList<>();

    public Cart(User user) {
        this.id   = idGen.getAndIncrement();
        this.user = user;
    }

    // ---- State transitions ----
    public void lock()     { transition(CartStatus.LOCKED); }
    public void unlock()   { transition(CartStatus.ACTIVE); }
    public void order()    { transition(CartStatus.ORDERED); }
    public void abandon()  { transition(CartStatus.ABANDONED); }
    public void expire()   { transition(CartStatus.EXPIRED); }

    private void transition(CartStatus next) {
        System.out.println("[Cart#" + id + "] " + status + " → " + next);
        this.status    = next;
        this.updatedAt = LocalDateTime.now();
    }

    // ---- Item management ----
    public synchronized void addItem(Product product, int qty, PricingStrategy strategy) {
        if (status != CartStatus.ACTIVE) {
            System.out.println("[Cart] Cannot modify cart in state: " + status);
            return;
        }
        if (product.getStockStatus() == StockStatus.OUT_OF_STOCK) {
            System.out.println("[Cart] " + product.getName() + " is out of stock");
            return;
        }

        // Check if already in cart
        Optional<CartItem> existing = items.stream()
            .filter(i -> i.getProduct().getId() == product.getId())
            .findFirst();

        double effectivePrice = strategy.computePrice(product, user, qty) / qty;

        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.setQuantity(item.getQuantity() + qty);
            System.out.println("[Cart] Updated qty: " + product.getName() +
                " → " + item.getQuantity());
        } else {
            CartItem item = new CartItem(product, qty, effectivePrice);
            item.setEffectivePrice(effectivePrice);
            items.add(item);
            System.out.println("[Cart] Added: " + product.getName() +
                " x" + qty + " @ ₹" + effectivePrice + " [" + strategy.getName() + "]");
        }
        this.updatedAt = LocalDateTime.now();
    }

    public synchronized void removeItem(long productId) {
        items.removeIf(i -> i.getProduct().getId() == productId);
        System.out.println("[Cart] Removed product #" + productId);
    }

    public synchronized void updateQty(long productId, int newQty) {
        if (newQty <= 0) { removeItem(productId); return; }
        items.stream()
            .filter(i -> i.getProduct().getId() == productId)
            .findFirst()
            .ifPresent(i -> {
                i.setQuantity(newQty);
                System.out.println("[Cart] Qty updated: " + i.getProduct().getName() +
                    " → " + newQty);
            });
    }

    // ---- Price refresh (called before checkout) ----
    public synchronized PriceRefreshResult refreshAllPrices() {
        priceChangedItems.clear();
        items.forEach(item -> {
            double before = item.getCurrentPrice();
            item.refreshPrice();
            if (item.isPriceChanged()) priceChangedItems.add(item);
        });

        boolean hasIncrease = priceChangedItems.stream().anyMatch(CartItem::hasPriceIncreased);
        boolean hasDecrease = priceChangedItems.stream().anyMatch(CartItem::hasPriceDecreased);

        return new PriceRefreshResult(priceChangedItems, hasIncrease, hasDecrease);
    }

    // ---- Apply discount ----
    public void applyDiscount(Discount discount) {
        double saving = discount.apply(getSubtotal(), items);
        if (saving > 0) {
            this.appliedDiscount = discount;
            System.out.println("[Discount] Applied: " + discount.getDescription() +
                " → saving ₹" + String.format("%.2f", saving));
        } else {
            System.out.println("[Discount] Code '" + discount.getCode() +
                "' not applicable to current cart");
        }
    }

    // ---- Totals ----
    public double getSubtotal() {
        return items.stream().mapToDouble(CartItem::getSubtotal).sum();
    }

    public double getDiscountAmount() {
        return appliedDiscount != null
            ? appliedDiscount.apply(getSubtotal(), items) : 0;
    }

    public double getTotal() {
        return getSubtotal() - getDiscountAmount();
    }

    public void lockTotal() { this.lockedTotal = getTotal(); }
    public double getLockedTotal() { return lockedTotal; }

    public long           getId()              { return id; }
    public User           getUser()            { return user; }
    public List<CartItem> getItems()           { return items; }
    public CartStatus     getStatus()          { return status; }
    public Discount       getAppliedDiscount() { return appliedDiscount; }

    public void printSummary() {
        System.out.println("\n--- Cart #" + id + " Summary [" + user.getName() + "] ---");
        items.forEach(System.out::println);
        System.out.printf("  Subtotal:  ₹%.2f%n", getSubtotal());
        if (appliedDiscount != null)
            System.out.printf("  Discount:  -₹%.2f (%s)%n",
                getDiscountAmount(), appliedDiscount.getDescription());
        System.out.printf("  TOTAL:     ₹%.2f%n", getTotal());
        System.out.println("  Status:    " + status);
    }
}

// ==========================================
// 9. PRICE REFRESH RESULT
// ==========================================
class PriceRefreshResult {
    final List<CartItem> changedItems;
    final boolean        hasIncrease;
    final boolean        hasDecrease;

    public PriceRefreshResult(List<CartItem> changed, boolean up, boolean down) {
        this.changedItems = changed;
        this.hasIncrease  = up;
        this.hasDecrease  = down;
    }

    public boolean hasAnyChange() { return !changedItems.isEmpty(); }
}

// ==========================================
// 10. ORDER — BUILDER PATTERN
// ==========================================
class Order {
    private static final AtomicLong idGen = new AtomicLong(9000);
    private final  long          id;
    private final  User          user;
    private final  List<CartItem> items;
    private final  double        totalAtOrder;   // price locked at order time
    private final  double        discount;
    private final  String        paymentMethod;
    private        OrderStatus   status;
    private final  LocalDateTime createdAt = LocalDateTime.now();

    private Order(Builder b) {
        this.id             = idGen.getAndIncrement();
        this.user           = b.user;
        this.items          = List.copyOf(b.items);
        this.totalAtOrder   = b.total;
        this.discount       = b.discount;
        this.paymentMethod  = b.paymentMethod;
        this.status         = OrderStatus.PENDING;
    }

    public void confirm()    { this.status = OrderStatus.CONFIRMED; }
    public void fail()       { this.status = OrderStatus.FAILED; }
    public void cancel()     { this.status = OrderStatus.CANCELLED; }

    public long        getId()            { return id; }
    public double      getTotalAtOrder()  { return totalAtOrder; }
    public OrderStatus getStatus()        { return status; }

    @Override public String toString() {
        return "Order[#" + id + " | " + user.getName() +
               " | ₹" + totalAtOrder + " | " + status + "]";
    }

    static class Builder {
        private User          user;
        private List<CartItem> items;
        private double        total;
        private double        discount;
        private String        paymentMethod = "UPI";

        public Builder user(User u)            { this.user = u;           return this; }
        public Builder items(List<CartItem> i) { this.items = i;          return this; }
        public Builder total(double t)         { this.total = t;          return this; }
        public Builder discount(double d)      { this.discount = d;       return this; }
        public Builder paymentMethod(String m) { this.paymentMethod = m;  return this; }
        public Order   build()                 { return new Order(this); }
    }
}

// ==========================================
// 11. PRICE ENGINE — SINGLETON
// Manages price updates + notifies watchers
// ==========================================
class PriceEngine {
    private static PriceEngine instance;
    private final  Map<Long, List<PriceChangeObserver>> watchers = new ConcurrentHashMap<>();
    private final  ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    private PriceEngine() {}

    public static synchronized PriceEngine getInstance() {
        if (instance == null) instance = new PriceEngine();
        return instance;
    }

    public void registerWatcher(long productId, PriceChangeObserver observer) {
        watchers.computeIfAbsent(productId, k -> new CopyOnWriteArrayList<>())
                .add(observer);
    }

    public void removeWatcher(long productId, PriceChangeObserver observer) {
        List<PriceChangeObserver> list = watchers.get(productId);
        if (list != null) list.remove(observer);
    }

    public void updatePrice(Product product, double newPrice) {
        double oldPrice = product.getCurrentPrice();
        product.updatePrice(newPrice);

        PriceChangeType type = newPrice < oldPrice
            ? PriceChangeType.DECREASE : PriceChangeType.INCREASE;

        System.out.println("[PriceEngine] " + product.getName() +
            ": ₹" + oldPrice + " → ₹" + newPrice);

        // Notify all cart watchers for this product
        List<PriceChangeObserver> obs = watchers.getOrDefault(product.getId(), List.of());
        obs.forEach(o -> o.onPriceChanged(product, oldPrice, newPrice, type));
    }

    public void startFlashSale(Product product, double salePrice, int durationSeconds) {
        double oldPrice = product.getCurrentPrice();
        product.startFlashSale(salePrice);

        List<PriceChangeObserver> obs = watchers.getOrDefault(product.getId(), List.of());
        obs.forEach(o -> o.onPriceChanged(product, oldPrice, salePrice,
            PriceChangeType.FLASH_SALE_START));

        // Auto-end after duration
        scheduler.schedule(() -> {
            double flashPrice = product.getCurrentPrice();
            product.endFlashSale();
            obs.forEach(o -> o.onPriceChanged(product, flashPrice,
                product.getCurrentPrice(), PriceChangeType.FLASH_SALE_END));
        }, durationSeconds, TimeUnit.SECONDS);
    }
}

// ==========================================
// 12. CART SERVICE — SINGLETON
// Central checkout coordinator
// ==========================================
class CartService {
    private static CartService instance;
    private final  Map<Long, Cart>            carts      = new ConcurrentHashMap<>();
    private final  Map<Long, Order>           orders     = new ConcurrentHashMap<>();
    private final  Map<String, Discount>      coupons    = new ConcurrentHashMap<>();
    private final  PriceEngine               priceEngine = PriceEngine.getInstance();

    private CartService() { registerDefaultCoupons(); }

    public static synchronized CartService getInstance() {
        if (instance == null) instance = new CartService();
        return instance;
    }

    private void registerDefaultCoupons() {
        coupons.put("SAVE10",    new PercentageDiscount("SAVE10",   10, 500));
        coupons.put("FLAT200",   new FlatDiscount("FLAT200",        200, 1000));
        coupons.put("PRIME15",   new PercentageDiscount("PRIME15",  15, 1000));
        coupons.put("ELEC20",    new CategoryDiscount("ELEC20", "Electronics", 20));
    }

    // ---- Cart lifecycle ----
    public Cart createCart(User user) {
        Cart cart = new Cart(user);
        carts.put(cart.getId(), cart);
        System.out.println("[CartService] Cart #" + cart.getId() +
            " created for " + user.getName());
        return cart;
    }

    public void addToCart(Cart cart, Product product, int qty) {
        // Pick best applicable pricing strategy
        PricingStrategy strategy = selectBestStrategy(product, cart.getUser(), qty);

        // Register price watcher for this product in this cart
        CartPriceWatcher watcher = new CartPriceWatcher(cart);
        priceEngine.registerWatcher(product.getId(), watcher);

        cart.addItem(product, qty, strategy);
    }

    private PricingStrategy selectBestStrategy(Product product, User user, int qty) {
        List<PricingStrategy> strategies = List.of(
            new FlashSalePricingStrategy(),
            new MembershipPricingStrategy(15),
            new BulkDiscountStrategy(3, 10),
            new StandardPricingStrategy()
        );

        return strategies.stream()
            .filter(s -> s.isApplicable(product, user))
            .findFirst()
            .orElse(new StandardPricingStrategy());
    }

    public boolean applyCoupon(Cart cart, String code) {
        Discount discount = coupons.get(code.toUpperCase());
        if (discount == null) {
            System.out.println("[Coupon] Invalid code: " + code);
            return false;
        }
        // PRIME15 only for prime members
        if (code.equals("PRIME15") && !cart.getUser().isPrimeMember()) {
            System.out.println("[Coupon] PRIME15 is for Prime members only");
            return false;
        }
        cart.applyDiscount(discount);
        return true;
    }

    // ---- CHECKOUT FLOW ----
    public Order checkout(Cart cart, String paymentMethod) {
        if (cart.getStatus() != CartStatus.ACTIVE) {
            System.out.println("[Checkout] Cart not in ACTIVE state: " + cart.getStatus());
            return null;
        }
        if (cart.getItems().isEmpty()) {
            System.out.println("[Checkout] Cart is empty");
            return null;
        }

        System.out.println("\n[Checkout] Starting for Cart #" + cart.getId());

        // 1. Refresh all prices — detect any changes since items were added
        PriceRefreshResult refresh = cart.refreshAllPrices();

        if (refresh.hasAnyChange()) {
            System.out.println("[Checkout] ⚠️  Price changes detected:");
            refresh.changedItems.forEach(item ->
                System.out.println("  " + item.getProduct().getName() +
                    " was ₹" + item.getPriceAtAdd() +
                    " now ₹" + item.getCurrentPrice() +
                    (item.hasPriceIncreased()
                        ? " (+₹" + String.format("%.0f", item.getPriceDifference()) + " ⬆)"
                        : " (-₹" + String.format("%.0f", -item.getPriceDifference()) + " ⬇)")));

            if (refresh.hasIncrease) {
                // Price increased — user MUST re-confirm (like Flipkart/Amazon)
                System.out.println("[Checkout] ❌ Some prices have INCREASED." +
                    " User must acknowledge and re-confirm.");
                // In a real system: return a special response code, show popup to user
                // For demo: proceed with note
                System.out.println("[Checkout] [Demo] Proceeding with updated prices...");
            }

            if (refresh.hasDecrease) {
                System.out.println("[Checkout] ✅ Good news! Some prices dropped." +
                    " You save more!");
            }
        }

        // 2. Validate stock for all items
        for (CartItem item : cart.getItems()) {
            if (item.getProduct().getStockStatus() == StockStatus.OUT_OF_STOCK) {
                System.out.println("[Checkout] ❌ " + item.getProduct().getName() +
                    " went out of stock");
                return null;
            }
            if (!item.getProduct().reserveStock(item.getQuantity())) {
                System.out.println("[Checkout] ❌ Insufficient stock for " +
                    item.getProduct().getName());
                return null;
            }
        }

        // 3. Lock cart + snapshot total
        cart.lock();
        cart.lockTotal();
        double finalTotal = cart.getLockedTotal();

        System.out.println("[Checkout] Cart locked. Final total: ₹" +
            String.format("%.2f", finalTotal));

        // 4. Create order
        Order order = new Order.Builder()
            .user(cart.getUser())
            .items(cart.getItems())
            .total(finalTotal)
            .discount(cart.getDiscountAmount())
            .paymentMethod(paymentMethod)
            .build();

        orders.put(order.getId(), order);
        System.out.println("[Checkout] Order created: " + order);

        // 5. Simulate payment
        boolean paymentSuccess = processPayment(order, paymentMethod);

        if (paymentSuccess) {
            order.confirm();
            cart.order();
            System.out.println("[Checkout] ✅ Order #" + order.getId() + " CONFIRMED!");
        } else {
            order.fail();
            cart.unlock(); // release cart for retry
            // Release reserved stock
            cart.getItems().forEach(i ->
                i.getProduct().releaseStock(i.getQuantity()));
            System.out.println("[Checkout] ❌ Payment failed. Cart unlocked for retry.");
        }

        return order;
    }

    private boolean processPayment(Order order, String method) {
        System.out.println("[Payment] Processing ₹" +
            String.format("%.2f", order.getTotalAtOrder()) + " via " + method);
        // Simulate: always succeeds in demo
        return true;
    }

    public Map<Long, Cart>  getAllCarts()  { return carts; }
    public Map<Long, Order> getAllOrders() { return orders; }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class ShoppingCart {
    public static void main(String[] args) throws InterruptedException {

        CartService   cartService  = CartService.getInstance();
        PriceEngine   priceEngine  = PriceEngine.getInstance();

        // ---- Products ----
        Product iphone  = new Product("iPhone 15 Pro",       "Electronics", 134900, 50);
        Product airpods = new Product("AirPods Pro 2",        "Electronics",  24900, 20);
        Product shirt   = new Product("Levi's Slim Fit Shirt","Clothing",      2499, 100);
        Product laptop  = new Product("MacBook Air M3",       "Electronics", 114900, 15);
        Product book    = new Product("System Design Book",   "Books",         1299, 200);

        // ---- Users ----
        User alice = new User("Alice",  "alice@gmail.com",  true);  // Prime
        User bob   = new User("Bob",    "bob@gmail.com",    false); // Regular
        User carol = new User("Carol",  "carol@gmail.com",  true);  // Prime

        // ===== SCENARIO 1: Basic cart with pricing strategies =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Cart with Multiple Pricing Strategies");
        System.out.println("=".repeat(55));

        Cart aliceCart = cartService.createCart(alice);
        cartService.addToCart(aliceCart, iphone,  1); // Prime → 15% off
        cartService.addToCart(aliceCart, airpods, 1); // Prime → 15% off
        cartService.addToCart(aliceCart, shirt,   3); // Bulk → 10% off (3+ qty)
        cartService.addToCart(aliceCart, book,    1);
        aliceCart.printSummary();

        // ===== SCENARIO 2: Coupon application =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Coupon Application");
        System.out.println("=".repeat(55));

        cartService.applyCoupon(aliceCart, "PRIME15");  // valid for Alice
        cartService.applyCoupon(aliceCart, "FLAT200");  // try flat discount
        aliceCart.printSummary();

        // Bob tries prime coupon
        Cart bobCart = cartService.createCart(bob);
        cartService.addToCart(bobCart, laptop, 1);
        cartService.applyCoupon(bobCart, "PRIME15"); // should reject
        cartService.applyCoupon(bobCart, "SAVE10");  // should apply
        cartService.applyCoupon(bobCart, "ELEC20");  // category discount
        bobCart.printSummary();

        // ===== SCENARIO 3: Price DECREASE while item is in cart =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Price DROP while item is in cart");
        System.out.println("=".repeat(55));

        Cart carolCart = cartService.createCart(carol);
        cartService.addToCart(carolCart, laptop, 1);
        System.out.println("\nLaptop added at ₹" + laptop.getCurrentPrice());

        // Price drops (e.g. Amazon Great Indian Sale starts)
        priceEngine.updatePrice(laptop, 99900); // ₹15000 drop!
        carolCart.printSummary();

        // ===== SCENARIO 4: Price INCREASE while item is in cart =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: Price RISE while item is in cart");
        System.out.println("=".repeat(55));

        Cart bobCart2 = cartService.createCart(bob);
        cartService.addToCart(bobCart2, airpods, 2);
        System.out.println("\nAirPods added at ₹" + airpods.getCurrentPrice());

        // Price rises (demand spike)
        priceEngine.updatePrice(airpods, 27900);
        bobCart2.printSummary();

        // ===== SCENARIO 5: Flash sale — price drops for limited time =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Flash Sale (price drops, then restores)");
        System.out.println("=".repeat(55));

        Cart flashCart = cartService.createCart(alice);
        cartService.addToCart(flashCart, iphone, 1);
        System.out.println("iPhone in cart at ₹" + iphone.getCurrentPrice());

        // Flash sale starts — 20% off for 3 seconds
        priceEngine.startFlashSale(iphone, 107920, 3);

        Thread.sleep(500); // cart picks up flash sale price
        flashCart.refreshAllPrices();
        flashCart.printSummary();

        Thread.sleep(3000); // flash sale ends
        System.out.println("\n[After flash sale ends]:");
        flashCart.refreshAllPrices();
        flashCart.printSummary();

        // ===== SCENARIO 6: Checkout with price change acknowledgment =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Checkout with Updated Prices");
        System.out.println("=".repeat(55));

        carolCart.printSummary();
        Order order1 = cartService.checkout(carolCart, "UPI");
        System.out.println("Order result: " + order1);

        // ===== SCENARIO 7: Out of stock during checkout =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Out of Stock During Checkout");
        System.out.println("=".repeat(55));

        // Drain all AirPods stock
        airpods.reserveStock(20); // buy all 20

        Cart lateCart = cartService.createCart(bob);
        cartService.addToCart(lateCart, airpods, 1); // should warn out of stock

        // ===== SCENARIO 8: Remove item + update qty =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Update Cart — Remove + Change Qty");
        System.out.println("=".repeat(55));

        aliceCart.updateQty(book.getId(), 3);
        aliceCart.removeItem(shirt.getId());
        aliceCart.printSummary();

        // ===== SUMMARY =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(55));
        System.out.println("Total carts:  " + cartService.getAllCarts().size());
        System.out.println("Total orders: " + cartService.getAllOrders().size());

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | CartService, PriceEngine
            Observer   | PriceChangeObserver / CartPriceWatcher
            Strategy   | PricingStrategy (Standard/Member/Bulk/FlashSale)
            Factory    | DiscountFactory (Percentage/Flat/Category)
            Builder    | Order.Builder
            State      | CartStatus (ACTIVE→LOCKED→ORDERED/ABANDONED)
            """);
    }
}
