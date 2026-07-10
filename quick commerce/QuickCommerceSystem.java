import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

// ============================================================
// QUICK COMMERCE SYSTEM — LLD
//
// Requirements covered:
//   1.  Serviceability check — is user within a dark store radius?
//   2.  Product browsing by category + full-text search
//   3.  Cart management — add / remove / update quantity
//   4.  Inventory — soft check at cart, hard atomic lock at order
//   5.  Order placement — idempotent, payment, saga compensation
//   6.  Rider assignment — nearest-first with waterfall offers
//   7.  Live tracking — GPS pings, ETA recomputation
//   8.  Real-time notifications — per state transition
//   9.  10-minute SLA monitoring + automatic breach compensation
//   10. Concurrent order safety — no oversell, no double-charge
//
// Design Patterns:
//   Singleton  — QuickCommerceService, InventoryService, DeliveryService
//   Strategy   — RiderAssignmentStrategy (Nearest / LoadBalanced)
//              — PricingStrategy (Standard / Surge / FreeDelivery)
//   Observer   — OrderEventObserver (Notification / Analytics / SLA / DarkStore / Rider)
//   Factory    — OrderFactory, DeliveryFactory
//   Builder    — Order, CartItem, Product, Rider, DarkStore
//   State      — OrderStatus (PENDING→CONFIRMED→PICKING→PICKED→OFD→DELIVERED)
//              — RiderStatus (AVAILABLE→ASSIGNED→PICKING→DELIVERING→AVAILABLE)
//   Command    — PlaceOrderCommand (execute + cancel/compensate)
//   Iterator   — ProductCatalogIterator (paginated, category/search filtered)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum OrderStatus   { PENDING, PAYMENT_PENDING, CONFIRMED, PICKING, PICKED,
                     OUT_FOR_DELIVERY, DELIVERED, CANCELLED, FAILED }
enum RiderStatus   { AVAILABLE, ASSIGNED, PICKING_UP, DELIVERING, OFFLINE }
enum PaymentMethod { UPI, CARD, WALLET, CASH_ON_DELIVERY }
enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
enum ProductCategory { FRUITS_VEGETABLES, DAIRY_EGGS, BEVERAGES, SNACKS,
                       PERSONAL_CARE, HOUSEHOLD, MEAT_FISH, BAKERY, FROZEN }

// ============================================================
// 2. GEO COORDINATE — value object
//    Haversine distance used for serviceability + ETA
// ============================================================
class GeoCoordinate {
    private final double lat;
    private final double lng;

    public GeoCoordinate(double lat, double lng) {
        this.lat = lat; this.lng = lng;
    }

    public double getLat() { return lat; }
    public double getLng() { return lng; }

    /**
     * Haversine formula — great-circle distance in kilometres.
     * Used for: serviceability check, rider proximity, ETA.
     */
    public double distanceKm(GeoCoordinate other) {
        final double R  = 6371.0; // Earth radius km
        double dLat = Math.toRadians(other.lat - this.lat);
        double dLng = Math.toRadians(other.lng - this.lng);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(this.lat))
            * Math.cos(Math.toRadians(other.lat))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Override public String toString() {
        return String.format("(%.4f, %.4f)", lat, lng);
    }
}

// ============================================================
// 3. MONEY — value object (paise precision)
// ============================================================
class Money {
    private final long   paise;
    private final String currency;

    public Money(long paise)                       { this(paise, "INR"); }
    public Money(long paise, String currency)      { this.paise = paise; this.currency = currency; }
    public static Money ofRupees(double rupees)    { return new Money(Math.round(rupees * 100)); }

    public Money add(Money o)      { return new Money(paise + o.paise, currency); }
    public Money subtract(Money o) { return new Money(Math.max(0, paise - o.paise), currency); }
    public Money multiply(int qty) { return new Money(paise * qty, currency); }
    public boolean isZero()        { return paise == 0; }
    public long    getPaise()      { return paise; }
    public double  toRupees()      { return paise / 100.0; }

    @Override public String toString() { return "₹" + String.format("%.2f", toRupees()); }
}

// ============================================================
// 4. PRODUCT — BUILDER PATTERN
// ============================================================
class Product {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long            productId;
    private final  String          sku;
    private final  String          name;
    private final  String          brand;
    private final  ProductCategory category;
    private final  String          unit;         // "500g", "1L", "6 pack"
    private        Money           price;
    private        String          imageUrl;
    private final  Set<String>     tags;         // "vegan", "organic", "bestseller"

    private Product(Builder b) {
        this.productId = idGen.getAndIncrement();
        this.sku       = b.sku;
        this.name      = b.name;
        this.brand     = b.brand;
        this.category  = b.category;
        this.unit      = b.unit;
        this.price     = b.price;
        this.imageUrl  = b.imageUrl;
        this.tags      = new HashSet<>(b.tags);
    }

    public long            getProductId() { return productId; }
    public String          getSku()       { return sku; }
    public String          getName()      { return name; }
    public String          getBrand()     { return brand; }
    public ProductCategory getCategory()  { return category; }
    public String          getUnit()      { return unit; }
    public Money           getPrice()     { return price; }
    public Set<String>     getTags()      { return tags; }

    public boolean matchesSearch(String query) {
        String q = query.toLowerCase();
        return name.toLowerCase().contains(q)
            || brand.toLowerCase().contains(q)
            || tags.stream().anyMatch(t -> t.contains(q));
    }

    @Override public String toString() {
        return String.format("Product[%s | %-30s | %s | %s | %s]",
            sku, name, brand, unit, price);
    }

    static class Builder {
        private final String          sku;
        private final String          name;
        private final String          brand;
        private final ProductCategory category;
        private final String          unit;
        private final Money           price;
        private       String          imageUrl = "";
        private       List<String>    tags     = new ArrayList<>();

        public Builder(String sku, String name, String brand,
                       ProductCategory cat, String unit, Money price) {
            this.sku = sku; this.name = name; this.brand = brand;
            this.category = cat; this.unit = unit; this.price = price;
        }
        public Builder imageUrl(String u)   { this.imageUrl = u;       return this; }
        public Builder tags(String... t)    { tags.addAll(Arrays.asList(t)); return this; }
        public Product build()             { return new Product(this); }
    }
}

// ============================================================
// 5. DARK STORE — BUILDER PATTERN
//    The hyperlocal micro-warehouse that fulfils every order.
// ============================================================
class DarkStore {
    private static final AtomicLong idGen = new AtomicLong(100);

    private final  long          storeId;
    private final  String        name;
    private final  GeoCoordinate location;
    private final  double        serviceRadiusKm;
    private        boolean       isOpen;
    // productId → stock count  (Redis HASH in production)
    private final  ConcurrentHashMap<Long, Integer> inventory = new ConcurrentHashMap<>();
    // Per-SKU lock — mirrors Redis Lua atomicity in-process
    // fair=true: FIFO ordering under concurrent stock pressure
    private final  ConcurrentHashMap<Long, ReentrantLock> itemLocks = new ConcurrentHashMap<>();

    private DarkStore(Builder b) {
        this.storeId         = idGen.getAndIncrement();
        this.name            = b.name;
        this.location        = b.location;
        this.serviceRadiusKm = b.serviceRadiusKm;
        this.isOpen          = true;
    }

    /** Serviceability: is this address within this store's delivery radius? */
    public boolean services(GeoCoordinate userLocation) {
        return isOpen && location.distanceKm(userLocation) <= serviceRadiusKm;
    }

    /** ETA to this store from a given coordinate (for rider proximity ranking). */
    public double distanceTo(GeoCoordinate point) {
        return location.distanceKm(point);
    }

    // ---- Inventory: stock a product ----
    public void stockProduct(long productId, int quantity) {
        inventory.put(productId, quantity);
        itemLocks.put(productId, new ReentrantLock(true));
    }

    /**
     * Soft check (cart add) — informational, not binding.
     * No lock held; reading a volatile int is acceptable here.
     */
    public boolean isSoftAvailable(long productId, int qty) {
        return inventory.getOrDefault(productId, 0) >= qty;
    }

    /**
     * HARD atomic decrement (order placement) — Layer 1 of 3.
     * Mirrors Redis Lua: check > 0 AND decrement in one locked block.
     * fair=true: prevents starvation under high concurrent order load.
     *
     * Returns true if reservation succeeded, false if out of stock.
     */
    public boolean hardReserve(long productId, int qty) {
        ReentrantLock lock = itemLocks.computeIfAbsent(
            productId, k -> new ReentrantLock(true));
        lock.lock();
        try {
            int current = inventory.getOrDefault(productId, 0);
            if (current < qty) return false;  // sold out
            inventory.put(productId, current - qty);
            System.out.printf("[Inventory] Reserved %d × SKU#%d at %s (remaining=%d)%n",
                qty, productId, name, current - qty);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Release reserved stock — called on payment failure / order cancellation.
     * Always idempotent — safe to call multiple times.
     */
    public void releaseReservation(long productId, int qty) {
        ReentrantLock lock = itemLocks.computeIfAbsent(
            productId, k -> new ReentrantLock(true));
        lock.lock();
        try {
            inventory.merge(productId, qty, Integer::sum);
            System.out.printf("[Inventory] Released %d × SKU#%d at %s (now=%d)%n",
                qty, productId, name, inventory.get(productId));
        } finally {
            lock.unlock();
        }
    }

    public int getStock(long productId) { return inventory.getOrDefault(productId, 0); }

    public long          getStoreId()         { return storeId; }
    public String        getName()            { return name; }
    public GeoCoordinate getLocation()        { return location; }
    public boolean       isOpen()             { return isOpen; }
    public void          close()              { isOpen = false; }
    public void          open()               { isOpen = true; }

    @Override public String toString() {
        return "DarkStore[#" + storeId + " | " + name + " | " + location + " | r=" + serviceRadiusKm + "km]";
    }

    static class Builder {
        private final String          name;
        private final GeoCoordinate   location;
        private       double          serviceRadiusKm = 3.0; // 3km default

        public Builder(String name, GeoCoordinate location) {
            this.name = name; this.location = location;
        }
        public Builder serviceRadius(double km) { this.serviceRadiusKm = km; return this; }
        public DarkStore build()               { return new DarkStore(this); }
    }
}

// ============================================================
// 6. RIDER — BUILDER PATTERN (State: AVAILABLE ↔ DELIVERING)
// ============================================================
class Rider {
    private static final AtomicLong idGen = new AtomicLong(200);

    private final  long          riderId;
    private final  String        name;
    private final  String        phone;
    private        GeoCoordinate location;
    private        RiderStatus   status;
    private        long          assignedDarkStoreId;
    private        long          activeOrderId;      // 0 if none
    private        int           activeOrderCount;   // riders can carry up to 2 orders
    private final  LocalDateTime registeredAt;

    private Rider(Builder b) {
        this.riderId    = idGen.getAndIncrement();
        this.name       = b.name;
        this.phone      = b.phone;
        this.location   = b.startLocation;
        this.status     = RiderStatus.AVAILABLE;
        this.assignedDarkStoreId = b.darkStoreId;
        this.registeredAt = LocalDateTime.now();
    }

    // ---- State transitions ----
    public void assign(long orderId)   {
        status          = RiderStatus.ASSIGNED;
        activeOrderId   = orderId;
        activeOrderCount++;
        System.out.printf("[Rider #%d] %s assigned to order#%d%n", riderId, name, orderId);
    }

    public void startPickup()          { status = RiderStatus.PICKING_UP; }
    public void startDelivery()        { status = RiderStatus.DELIVERING; }
    public void completeDelivery()     {
        activeOrderCount--;
        activeOrderId = 0;
        status = activeOrderCount == 0 ? RiderStatus.AVAILABLE : RiderStatus.DELIVERING;
        System.out.printf("[Rider #%d] %s delivery complete (remaining=%d)%n",
            riderId, name, activeOrderCount);
    }
    public void goOffline()            { status = RiderStatus.OFFLINE; }
    public void goOnline()             { status = RiderStatus.AVAILABLE; activeOrderCount = 0; }

    /** Compute ETA minutes to a destination point at avg 15 km/h. */
    public int etaMinutes(GeoCoordinate destination) {
        double distKm = location.distanceKm(destination);
        double hours  = distKm / 15.0; // avg 15 km/h on city roads
        return (int) Math.ceil(hours * 60);
    }

    public void updateLocation(GeoCoordinate loc) { this.location = loc; }

    public boolean isAvailable()          {
        return status == RiderStatus.AVAILABLE && activeOrderCount < 2;
    }
    public double  distanceTo(GeoCoordinate g) { return location.distanceKm(g); }

    public long          getRiderId()         { return riderId; }
    public String        getName()            { return name; }
    public String        getPhone()           { return phone; }
    public GeoCoordinate getLocation()        { return location; }
    public RiderStatus   getStatus()          { return status; }
    public long          getAssignedStore()   { return assignedDarkStoreId; }
    public int           getActiveOrderCount(){ return activeOrderCount; }

    @Override public String toString() {
        return String.format("Rider[#%d | %-12s | %s | orders=%d | %s]",
            riderId, name, location, activeOrderCount, status);
    }

    static class Builder {
        private final String          name;
        private final String          phone;
        private final GeoCoordinate   startLocation;
        private       long            darkStoreId = 0;

        public Builder(String name, String phone, GeoCoordinate startLocation) {
            this.name = name; this.phone = phone; this.startLocation = startLocation;
        }
        public Builder darkStore(long id) { this.darkStoreId = id; return this; }
        public Rider   build()           { return new Rider(this); }
    }
}

// ============================================================
// 7. CART ITEM + CART
// ============================================================
class CartItem {
    private final  long    productId;
    private final  String  sku;
    private final  String  name;
    private        int     quantity;
    private final  Money   unitPrice;

    public CartItem(long productId, String sku, String name, int qty, Money unitPrice) {
        this.productId = productId;
        this.sku       = sku;
        this.name      = name;
        this.quantity  = qty;
        this.unitPrice = unitPrice;
    }

    public Money getLineTotal()         { return unitPrice.multiply(quantity); }
    public void  setQuantity(int q)     { this.quantity = q; }

    public long   getProductId()        { return productId; }
    public String getSku()              { return sku; }
    public String getName()             { return name; }
    public int    getQuantity()         { return quantity; }
    public Money  getUnitPrice()        { return unitPrice; }

    @Override public String toString() {
        return String.format("  CartItem[%-25s x%d @ %s = %s]",
            name, quantity, unitPrice, getLineTotal());
    }
}

class Cart {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final long                         cartId;
    private final long                         userId;
    private final long                         darkStoreId;
    private final Map<Long, CartItem>          items        = new LinkedHashMap<>();
    private       LocalDateTime                createdAt;
    private       LocalDateTime                updatedAt;

    public Cart(long userId, long darkStoreId) {
        this.cartId      = idGen.getAndIncrement();
        this.userId      = userId;
        this.darkStoreId = darkStoreId;
        this.createdAt   = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
    }

    public void addItem(Product product, int qty) {
        items.merge(product.getProductId(),
            new CartItem(product.getProductId(), product.getSku(),
                         product.getName(), qty, product.getPrice()),
            (existing, newItem) -> {
                existing.setQuantity(existing.getQuantity() + qty);
                return existing;
            });
        updatedAt = LocalDateTime.now();
        System.out.println("[Cart] Added: " + product.getName() + " x" + qty);
    }

    public boolean removeItem(long productId) {
        boolean removed = items.remove(productId) != null;
        if (removed) updatedAt = LocalDateTime.now();
        return removed;
    }

    public void updateQuantity(long productId, int newQty) {
        CartItem item = items.get(productId);
        if (item != null) {
            if (newQty <= 0) items.remove(productId);
            else item.setQuantity(newQty);
            updatedAt = LocalDateTime.now();
        }
    }

    public Money getSubtotal() {
        return items.values().stream()
            .map(CartItem::getLineTotal)
            .reduce(Money.ofRupees(0), Money::add);
    }

    public boolean isEmpty()                     { return items.isEmpty(); }
    public Collection<CartItem> getItems()       { return items.values(); }
    public long   getCartId()                    { return cartId; }
    public long   getUserId()                    { return userId; }
    public long   getDarkStoreId()               { return darkStoreId; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }

    public void printCart() {
        System.out.println("\n──── Cart #" + cartId + " ────");
        items.values().forEach(System.out::println);
        System.out.println("  Subtotal: " + getSubtotal());
    }
}

// ============================================================
// 8. ORDER — BUILDER PATTERN (State machine)
// ============================================================
class Order {
    private static final AtomicLong idGen = new AtomicLong(500_000);

    private final  long          orderId;
    private final  String        orderRef;
    private final  long          userId;
    private final  long          darkStoreId;
    private final  GeoCoordinate deliveryAddress;
    private final  List<CartItem>items;
    private final  Money         subtotal;
    private final  Money         deliveryFee;
    private final  Money         totalAmount;
    private final  PaymentMethod paymentMethod;
    private        PaymentStatus paymentStatus;
    private        OrderStatus   status;
    private        long          assignedRiderId;
    private final  LocalDateTime placedAt;
    private        LocalDateTime updatedAt;
    private        LocalDateTime deliveredAt;
    private final  String        idempotencyKey;
    private        int           estimatedDeliveryMinutes;

    private Order(Builder b) {
        this.orderId          = idGen.getAndIncrement();
        this.orderRef         = "ORD-" + String.format("%08d", orderId);
        this.userId           = b.userId;
        this.darkStoreId      = b.darkStoreId;
        this.deliveryAddress  = b.deliveryAddress;
        this.items            = List.copyOf(b.items);
        this.subtotal         = b.subtotal;
        this.deliveryFee      = b.deliveryFee;
        this.totalAmount      = b.subtotal.add(b.deliveryFee);
        this.paymentMethod    = b.paymentMethod;
        this.paymentStatus    = PaymentStatus.PENDING;
        this.status           = OrderStatus.PENDING;
        this.idempotencyKey   = b.idempotencyKey;
        this.placedAt         = LocalDateTime.now();
        this.updatedAt        = LocalDateTime.now();
        this.estimatedDeliveryMinutes = 10;
    }

    // ---- State transitions (Req: order state machine) ----
    public void markPaymentPending()     { status = OrderStatus.PAYMENT_PENDING; update(); }
    public void markPaymentSuccess()     { paymentStatus = PaymentStatus.SUCCESS; update(); }
    public void markPaymentFailed()      { paymentStatus = PaymentStatus.FAILED;
                                           status = OrderStatus.FAILED; update(); }
    public void confirm()                { status = OrderStatus.CONFIRMED; update(); }
    public void startPicking()           { status = OrderStatus.PICKING; update(); }
    public void markPicked()             { status = OrderStatus.PICKED; update(); }
    public void startDelivery(long rid)  {
        status = OrderStatus.OUT_FOR_DELIVERY;
        assignedRiderId = rid;
        update();
    }
    public void markDelivered()          {
        status = OrderStatus.DELIVERED;
        deliveredAt = LocalDateTime.now();
        update();
        System.out.printf("[Order %s] DELIVERED in %d min%n",
            orderRef, minutesSincePlaced());
    }
    public void cancel(String reason)    {
        status = OrderStatus.CANCELLED;
        update();
        System.out.printf("[Order %s] CANCELLED: %s%n", orderRef, reason);
    }

    private void update()                { updatedAt = LocalDateTime.now(); }
    private int minutesSincePlaced() {
        return (int) java.time.Duration.between(placedAt, LocalDateTime.now()).toMinutes();
    }

    public boolean isSLABreached() {
        return status != OrderStatus.DELIVERED &&
               status != OrderStatus.CANCELLED &&
               minutesSincePlaced() > 10;
    }

    public long          getOrderId()          { return orderId; }
    public String        getOrderRef()         { return orderRef; }
    public long          getUserId()           { return userId; }
    public long          getDarkStoreId()      { return darkStoreId; }
    public GeoCoordinate getDeliveryAddress()  { return deliveryAddress; }
    public List<CartItem>getItems()            { return items; }
    public Money         getSubtotal()         { return subtotal; }
    public Money         getDeliveryFee()      { return deliveryFee; }
    public Money         getTotalAmount()      { return totalAmount; }
    public PaymentMethod getPaymentMethod()    { return paymentMethod; }
    public PaymentStatus getPaymentStatus()    { return paymentStatus; }
    public OrderStatus   getStatus()           { return status; }
    public long          getAssignedRider()    { return assignedRiderId; }
    public LocalDateTime getPlacedAt()         { return placedAt; }
    public String        getIdempotencyKey()   { return idempotencyKey; }
    public int           getEtaMinutes()       { return estimatedDeliveryMinutes; }
    public void          setEta(int min)       { estimatedDeliveryMinutes = min; }

    @Override public String toString() {
        return String.format("Order[%s | user=%d | %s | total=%s | %s | eta=%dmin]",
            orderRef, userId, status, totalAmount, paymentStatus, estimatedDeliveryMinutes);
    }

    static class Builder {
        private final long          userId;
        private final long          darkStoreId;
        private final GeoCoordinate deliveryAddress;
        private final List<CartItem>items;
        private final Money         subtotal;
        private       Money         deliveryFee   = Money.ofRupees(0);
        private       PaymentMethod paymentMethod = PaymentMethod.UPI;
        private       String        idempotencyKey= UUID.randomUUID().toString();

        public Builder(long userId, long darkStoreId, GeoCoordinate address,
                       List<CartItem> items, Money subtotal) {
            this.userId          = userId;
            this.darkStoreId     = darkStoreId;
            this.deliveryAddress = address;
            this.items           = items;
            this.subtotal        = subtotal;
        }
        public Builder deliveryFee(Money f)    { this.deliveryFee    = f;  return this; }
        public Builder payment(PaymentMethod p) { this.paymentMethod  = p;  return this; }
        public Builder idempotencyKey(String k){ this.idempotencyKey  = k;  return this; }
        public Order   build()                 { return new Order(this); }
    }
}

// ============================================================
// 9. PRICING STRATEGY — STRATEGY PATTERN (Req: extensible)
// ============================================================
interface PricingStrategy {
    String getName();
    Money computeDeliveryFee(Money subtotal, double distanceKm, LocalDateTime now);
}

class StandardPricingStrategy implements PricingStrategy {
    @Override public String getName() { return "Standard"; }

    @Override
    public Money computeDeliveryFee(Money subtotal, double distKm, LocalDateTime now) {
        if (subtotal.getPaise() >= 19900) return Money.ofRupees(0); // free above ₹199
        if (distKm <= 1.5)               return Money.ofRupees(15);
        if (distKm <= 3.0)               return Money.ofRupees(25);
        return Money.ofRupees(40);
    }
}

class SurgePricingStrategy implements PricingStrategy {
    private final double surgeMultiplier;

    public SurgePricingStrategy(double multiplier) { this.surgeMultiplier = multiplier; }
    @Override public String getName() { return "Surge(x" + surgeMultiplier + ")"; }

    @Override
    public Money computeDeliveryFee(Money subtotal, double distKm, LocalDateTime now) {
        Money base = new StandardPricingStrategy().computeDeliveryFee(subtotal, distKm, now);
        return new Money((long)(base.getPaise() * surgeMultiplier));
    }
}

// ============================================================
// 10. RIDER ASSIGNMENT STRATEGY — STRATEGY PATTERN
// ============================================================
interface RiderAssignmentStrategy {
    String getName();
    Optional<Rider> assign(List<Rider> candidates, DarkStore store, GeoCoordinate delivery);
}

/** Nearest-first: minimises time to reach the store. */
class NearestFirstStrategy implements RiderAssignmentStrategy {
    @Override public String getName() { return "NearestFirst"; }

    @Override
    public Optional<Rider> assign(List<Rider> candidates, DarkStore store, GeoCoordinate delivery) {
        return candidates.stream()
            .filter(Rider::isAvailable)
            .min(Comparator.comparingDouble(r -> r.distanceTo(store.getLocation())));
    }
}

/** Load-balanced: prefers riders with fewest active orders. */
class LoadBalancedStrategy implements RiderAssignmentStrategy {
    @Override public String getName() { return "LoadBalanced"; }

    @Override
    public Optional<Rider> assign(List<Rider> candidates, DarkStore store, GeoCoordinate delivery) {
        return candidates.stream()
            .filter(Rider::isAvailable)
            // Score: activeOrders × 2 + distance (low score wins)
            .min(Comparator.comparingDouble(r ->
                r.getActiveOrderCount() * 2.0 + r.distanceTo(store.getLocation())));
    }
}

// ============================================================
// 11. OBSERVER — ORDER EVENTS (Req 8 notifications + SLA)
// ============================================================
interface OrderEventObserver {
    void onOrderPlaced(Order order);
    void onOrderConfirmed(Order order);
    void onPickingStarted(Order order);
    void onOrderPicked(Order order);
    void onRiderAssigned(Order order, Rider rider);
    void onOrderOutForDelivery(Order order, Rider rider);
    void onOrderDelivered(Order order);
    void onOrderCancelled(Order order, String reason);
    void onSLABreach(Order order);
}

class NotificationObserver implements OrderEventObserver {
    @Override public void onOrderPlaced(Order o)          {
        System.out.printf("[Notif → user#%d] Order %s placed. Total: %s%n",
            o.getUserId(), o.getOrderRef(), o.getTotalAmount()); }

    @Override public void onOrderConfirmed(Order o)       {
        System.out.printf("[Notif → user#%d] ✅ Order confirmed! ETA: %d min%n",
            o.getUserId(), o.getEtaMinutes()); }

    @Override public void onPickingStarted(Order o)       {
        System.out.printf("[Notif → user#%d] 📦 We're picking your items!%n",
            o.getUserId()); }

    @Override public void onOrderPicked(Order o)          {
        System.out.printf("[Notif → user#%d] ✅ Items packed! Finding rider...%n",
            o.getUserId()); }

    @Override public void onRiderAssigned(Order o, Rider r) {
        System.out.printf("[Notif → user#%d] 🏍 Rider %s assigned! ETA: %d min%n",
            o.getUserId(), r.getName(), r.etaMinutes(o.getDeliveryAddress())); }

    @Override public void onOrderOutForDelivery(Order o, Rider r) {
        System.out.printf("[Notif → user#%d] 🚀 %s is on the way! Track live in app.%n",
            o.getUserId(), r.getName()); }

    @Override public void onOrderDelivered(Order o)       {
        System.out.printf("[Notif → user#%d] 🎉 Order delivered! Rate your experience.%n",
            o.getUserId()); }

    @Override public void onOrderCancelled(Order o, String reason) {
        System.out.printf("[Notif → user#%d] ❌ Order cancelled: %s. Refund in 2-4 hours.%n",
            o.getUserId(), reason); }

    @Override public void onSLABreach(Order o) {
        System.out.printf("[Notif → user#%d] ⚠ Sorry for the delay! ₹50 coupon applied.%n",
            o.getUserId()); }
}

class DarkStoreObserver implements OrderEventObserver {
    @Override public void onOrderConfirmed(Order o)  {
        System.out.printf("[DarkStore #%d] 📋 NEW ORDER %s — start picking now!%n",
            o.getDarkStoreId(), o.getOrderRef());
        o.getItems().forEach(i ->
            System.out.printf("   Pick: %-25s x%d%n", i.getName(), i.getQuantity())); }

    @Override public void onOrderPlaced(Order o)       {}
    @Override public void onPickingStarted(Order o)    {}
    @Override public void onOrderPicked(Order o)       {
        System.out.printf("[DarkStore #%d] ✅ %s packed, ready for rider pickup%n",
            o.getDarkStoreId(), o.getOrderRef()); }
    @Override public void onRiderAssigned(Order o, Rider r) {}
    @Override public void onOrderOutForDelivery(Order o, Rider r) {}
    @Override public void onOrderDelivered(Order o)    {}
    @Override public void onOrderCancelled(Order o, String reason) {
        System.out.printf("[DarkStore #%d] Order %s cancelled — restock items%n",
            o.getDarkStoreId(), o.getOrderRef()); }
    @Override public void onSLABreach(Order o) {
        System.out.printf("[DarkStore #%d] ⚠ SLA BREACH on %s — ops team alerted%n",
            o.getDarkStoreId(), o.getOrderRef()); }
}

class AnalyticsObserver implements OrderEventObserver {
    private long placed = 0, confirmed = 0, delivered = 0, cancelled = 0, breaches = 0;
    private long totalRevenueP = 0;

    @Override public synchronized void onOrderPlaced(Order o)     { placed++; }
    @Override public synchronized void onOrderConfirmed(Order o)  { confirmed++; }
    @Override public synchronized void onOrderDelivered(Order o)  {
        delivered++; totalRevenueP += o.getTotalAmount().getPaise(); }
    @Override public synchronized void onOrderCancelled(Order o, String r) { cancelled++; }
    @Override public synchronized void onSLABreach(Order o)       { breaches++; }
    @Override public void onPickingStarted(Order o)   {}
    @Override public void onOrderPicked(Order o)       {}
    @Override public void onRiderAssigned(Order o, Rider r) {}
    @Override public void onOrderOutForDelivery(Order o, Rider r) {}

    public void printReport() {
        System.out.printf("%n[Analytics] Placed=%d Confirmed=%d Delivered=%d Cancelled=%d " +
            "SLA-Breaches=%d Revenue=%s%n",
            placed, confirmed, delivered, cancelled, breaches,
            new Money(totalRevenueP));
    }
}

// ============================================================
// 12. PLACE ORDER COMMAND — COMMAND PATTERN
//     execute()  = reserve inventory → payment → confirm → notify
//     cancel()   = release inventory → refund → notify
// ============================================================
class PlaceOrderCommand {
    private final Order                       order;
    private final DarkStore                   darkStore;
    private final List<OrderEventObserver>    observers;
    private       boolean                     inventoryReserved = false;
    private       boolean                     executed          = false;

    public PlaceOrderCommand(Order order, DarkStore darkStore,
                              List<OrderEventObserver> observers) {
        this.order     = order;
        this.darkStore = darkStore;
        this.observers = observers;
    }

    public boolean execute() {
        observers.forEach(o -> o.onOrderPlaced(order));

        // Step 1: Hard inventory reservation (Layer 1 of 3)
        // All-or-nothing: if any item fails, release everything reserved so far
        List<CartItem> reserved = new ArrayList<>();
        for (CartItem item : order.getItems()) {
            if (!darkStore.hardReserve(item.getProductId(), item.getQuantity())) {
                System.out.printf("[PlaceOrderCmd] Out of stock: %s — rolling back%n",
                    item.getName());
                // Roll back items already reserved in this batch
                reserved.forEach(r -> darkStore.releaseReservation(
                    r.getProductId(), r.getQuantity()));
                order.cancel("Item out of stock: " + item.getName());
                observers.forEach(o -> o.onOrderCancelled(order, "Out of stock"));
                return false;
            }
            reserved.add(item);
        }
        inventoryReserved = true;

        // Step 2: Record PAYMENT_PENDING before calling gateway
        order.markPaymentPending();

        // Step 3: Simulate payment gateway call
        boolean paymentOk = simulatePayment(order);

        if (!paymentOk) {
            // Release inventory — compensating transaction
            order.getItems().forEach(item ->
                darkStore.releaseReservation(item.getProductId(), item.getQuantity()));
            inventoryReserved = false;
            order.markPaymentFailed();
            observers.forEach(o -> o.onOrderCancelled(order, "Payment failed"));
            return false;
        }

        order.markPaymentSuccess();
        order.confirm();
        executed = true;

        observers.forEach(o -> o.onOrderConfirmed(order));
        System.out.println("[PlaceOrderCmd] " + order);
        return true;
    }

    private boolean simulatePayment(Order order) {
        // COD always succeeds; simulate 95% success for digital payment
        if (order.getPaymentMethod() == PaymentMethod.CASH_ON_DELIVERY) return true;
        return Math.random() > 0.05;
    }

    /**
     * Cancel / compensate — safe to call at any point after execute().
     * Releases inventory and triggers refund notification.
     */
    public void cancel(String reason) {
        if (inventoryReserved) {
            order.getItems().forEach(item ->
                darkStore.releaseReservation(item.getProductId(), item.getQuantity()));
            inventoryReserved = false;
        }
        order.cancel(reason);
        observers.forEach(o -> o.onOrderCancelled(order, reason));
    }

    public Order getOrder()  { return order; }
    public boolean isExecuted() { return executed; }
}

// ============================================================
// 13. PRODUCT CATALOG ITERATOR — ITERATOR PATTERN (Req 2)
// ============================================================
class ProductCatalogIterator implements Iterator<Product> {
    private final List<Product> filtered;
    private       int           cursor;
    private final int           pageSize;

    public ProductCatalogIterator(Collection<Product> all,
                                   ProductCategory category,
                                   String searchQuery,
                                   int pageSize) {
        this.pageSize = pageSize;
        this.cursor   = 0;
        this.filtered = all.stream()
            .filter(p -> category == null || p.getCategory() == category)
            .filter(p -> searchQuery == null || searchQuery.isEmpty()
                         || p.matchesSearch(searchQuery))
            .sorted(Comparator.comparing(Product::getName))
            .collect(Collectors.toList());
    }

    @Override public boolean hasNext()  { return cursor < filtered.size(); }
    @Override public Product next()     { return filtered.get(cursor++); }

    public List<Product> nextPage() {
        int from = cursor;
        int to   = Math.min(cursor + pageSize, filtered.size());
        cursor   = to;
        return filtered.subList(from, to);
    }

    public int  totalResults()          { return filtered.size(); }
    public int  getOffset()             { return cursor; }
    public boolean hasMore()            { return cursor < filtered.size(); }
}

// ============================================================
// 14. ORDER FACTORY
// ============================================================
class OrderFactory {
    public static Order fromCart(Cart cart, GeoCoordinate deliveryAddr,
                                  PaymentMethod payment, Money deliveryFee,
                                  String idempotencyKey) {
        return new Order.Builder(
            cart.getUserId(), cart.getDarkStoreId(),
            deliveryAddr,
            new ArrayList<>(cart.getItems()),
            cart.getSubtotal())
            .deliveryFee(deliveryFee)
            .payment(payment)
            .idempotencyKey(idempotencyKey)
            .build();
    }
}

// ============================================================
// 15. INVENTORY SERVICE — SINGLETON
// ============================================================
class InventoryService {
    private static volatile InventoryService instance;

    private InventoryService() {}

    public static InventoryService getInstance() {
        if (instance == null) {
            synchronized (InventoryService.class) {
                if (instance == null) instance = new InventoryService();
            }
        }
        return instance;
    }

    /** Soft availability check — used on catalog/cart display. */
    public boolean isAvailable(DarkStore store, long productId, int qty) {
        return store.isSoftAvailable(productId, qty);
    }

    /** Get stock level (for UI badge "only 3 left"). */
    public int getStock(DarkStore store, long productId) {
        return store.getStock(productId);
    }

    /** Restock a product at a dark store. */
    public void restock(DarkStore store, long productId, int qty) {
        store.stockProduct(productId, store.getStock(productId) + qty);
        System.out.printf("[Inventory] Restocked %d units of SKU#%d at %s%n",
            qty, productId, store.getName());
    }
}

// ============================================================
// 16. DELIVERY SERVICE — SINGLETON
// ============================================================
class DeliveryService {
    private static volatile DeliveryService instance;

    private final ConcurrentHashMap<Long, Rider>        riders    = new ConcurrentHashMap<>();
    private       RiderAssignmentStrategy               strategy  = new NearestFirstStrategy();
    private final List<OrderEventObserver>              observers = new ArrayList<>();

    private DeliveryService() {}

    public static DeliveryService getInstance() {
        if (instance == null) {
            synchronized (DeliveryService.class) {
                if (instance == null) instance = new DeliveryService();
            }
        }
        return instance;
    }

    public void setStrategy(RiderAssignmentStrategy s) {
        this.strategy = s;
        System.out.println("[Delivery] Rider strategy: " + s.getName());
    }
    public void addObserver(OrderEventObserver o) { observers.add(o); }

    public Rider registerRider(Rider rider) {
        riders.put(rider.getRiderId(), rider);
        System.out.println("[Delivery] Rider registered: " + rider);
        return rider;
    }

    /**
     * Assign the best available rider to an order after picking is complete.
     * Waterfall: tries nearest, then load-balanced if unavailable.
     */
    public Optional<Rider> assignRider(Order order, DarkStore store) {
        List<Rider> storeCandidates = riders.values().stream()
            .filter(r -> r.getAssignedStore() == store.getStoreId())
            .collect(Collectors.toList());

        Optional<Rider> picked = strategy.assign(
            storeCandidates, store, order.getDeliveryAddress());

        picked.ifPresentOrElse(rider -> {
            rider.assign(order.getOrderId());
            order.startDelivery(rider.getRiderId());
            int eta = rider.etaMinutes(order.getDeliveryAddress());
            order.setEta(eta);
            observers.forEach(o -> o.onRiderAssigned(order, rider));
            System.out.printf("[Delivery] Rider #%d assigned to order %s | ETA: %d min%n",
                rider.getRiderId(), order.getOrderRef(), eta);
        }, () ->
            System.out.println("[Delivery] ⚠ No rider available for " + order.getOrderRef()));

        return picked;
    }

    /** Simulate rider updating their GPS location. */
    public void updateRiderLocation(long riderId, GeoCoordinate newLoc) {
        Rider rider = riders.get(riderId);
        if (rider != null) rider.updateLocation(newLoc);
    }

    /** Rider confirms delivery. */
    public void confirmDelivery(long riderId, Order order) {
        Rider rider = riders.get(riderId);
        if (rider == null) return;
        rider.completeDelivery();
        order.markDelivered();
        observers.forEach(o -> o.onOrderDelivered(order));
    }

    /** Compute live ETA for an order in transit. */
    public int getLiveEta(Order order) {
        Rider rider = riders.get(order.getAssignedRider());
        if (rider == null) return -1;
        return rider.etaMinutes(order.getDeliveryAddress());
    }

    public Rider getRider(long id) { return riders.get(id); }
    public Collection<Rider> getAllRiders() { return riders.values(); }
}

// ============================================================
// 17. QUICK COMMERCE SERVICE — SINGLETON (top-level facade)
// ============================================================
class QuickCommerceService {
    private static volatile QuickCommerceService instance;

    private final ConcurrentHashMap<Long, DarkStore>   darkStores  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Product>     products    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Cart>        carts       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Order>       orders      = new ConcurrentHashMap<>();
    // Req 10: idempotency — same key never places two orders
    private final ConcurrentHashMap<String, Long>      idempotency = new ConcurrentHashMap<>();
    // commandStore: orderId → command (for cancellation)
    private final ConcurrentHashMap<Long, PlaceOrderCommand> commandStore = new ConcurrentHashMap<>();

    private final InventoryService             inventoryService = InventoryService.getInstance();
    private final DeliveryService              deliveryService  = DeliveryService.getInstance();
    private final List<OrderEventObserver>     observers        = new ArrayList<>();
    private final AnalyticsObserver            analytics        = new AnalyticsObserver();
    private       PricingStrategy              pricingStrategy  = new StandardPricingStrategy();

    private QuickCommerceService() {
        NotificationObserver notif = new NotificationObserver();
        DarkStoreObserver    ds    = new DarkStoreObserver();
        observers.add(notif);
        observers.add(ds);
        observers.add(analytics);
        deliveryService.addObserver(notif);
        deliveryService.addObserver(analytics);
    }

    public static QuickCommerceService getInstance() {
        if (instance == null) {
            synchronized (QuickCommerceService.class) {
                if (instance == null) instance = new QuickCommerceService();
            }
        }
        return instance;
    }

    // ---- Store + product management ----
    public void addDarkStore(DarkStore store)  { darkStores.put(store.getStoreId(), store); }
    public void addProduct(Product product)    { products.put(product.getProductId(), product); }
    public void setPricingStrategy(PricingStrategy p) {
        this.pricingStrategy = p;
        System.out.println("[Service] Pricing: " + p.getName());
    }

    // ---- Req 1: Serviceability check ----
    public Optional<DarkStore> findDarkStore(GeoCoordinate userLocation) {
        return darkStores.values().stream()
            .filter(s -> s.services(userLocation))
            .min(Comparator.comparingDouble(s -> s.distanceTo(userLocation)));
    }

    // ---- Req 2: Browse / search ----
    public ProductCatalogIterator browseCategory(ProductCategory category, int pageSize) {
        return new ProductCatalogIterator(products.values(), category, null, pageSize);
    }

    public ProductCatalogIterator search(String query, int pageSize) {
        return new ProductCatalogIterator(products.values(), null, query, pageSize);
    }

    // ---- Req 3: Cart management ----
    public Cart getOrCreateCart(long userId, long darkStoreId) {
        return carts.computeIfAbsent(userId, k -> new Cart(userId, darkStoreId));
    }

    public boolean addToCart(long userId, long productId, int qty, DarkStore store) {
        Product product = products.get(productId);
        if (product == null) return false;

        // Soft availability check
        if (!inventoryService.isAvailable(store, productId, qty)) {
            int stock = inventoryService.getStock(store, productId);
            System.out.printf("[Cart] Not enough stock: %s (requested=%d, available=%d)%n",
                product.getName(), qty, stock);
            return false;
        }

        Cart cart = getOrCreateCart(userId, store.getStoreId());
        cart.addItem(product, qty);
        return true;
    }

    // ---- Req 5: Place order ----
    public Order placeOrder(long userId, GeoCoordinate deliveryAddress,
                             PaymentMethod payment, String idempotencyKey) {
        // Req 10: idempotency check
        Long existingOrderId = idempotency.get(idempotencyKey);
        if (existingOrderId != null) {
            System.out.println("[Service] Duplicate order request — returning existing order #" + existingOrderId);
            return orders.get(existingOrderId);
        }

        Cart cart = carts.get(userId);
        if (cart == null || cart.isEmpty()) {
            System.out.println("[Service] Cart is empty for user#" + userId);
            return null;
        }

        DarkStore store = darkStores.get(cart.getDarkStoreId());
        if (store == null || !store.isOpen()) {
            System.out.println("[Service] Dark store unavailable");
            return null;
        }

        double distKm   = store.getLocation().distanceKm(deliveryAddress);
        Money  dFee     = pricingStrategy.computeDeliveryFee(cart.getSubtotal(), distKm, LocalDateTime.now());
        Order  order    = OrderFactory.fromCart(cart, deliveryAddress, payment, dFee, idempotencyKey);

        PlaceOrderCommand cmd = new PlaceOrderCommand(order, store, observers);
        boolean ok = cmd.execute();

        if (ok) {
            orders.put(order.getOrderId(), order);
            commandStore.put(order.getOrderId(), cmd);
            idempotency.put(idempotencyKey, order.getOrderId());
            carts.remove(userId); // cart consumed
        }

        return ok ? order : null;
    }

    // ---- Simulate picking flow ----
    public void simulatePicking(long orderId) throws InterruptedException {
        Order order = orders.get(orderId);
        if (order == null || order.getStatus() != OrderStatus.CONFIRMED) return;

        order.startPicking();
        observers.forEach(o -> o.onPickingStarted(order));
        Thread.sleep(100); // simulate 4-min picking (compressed for demo)

        order.markPicked();
        observers.forEach(o -> o.onOrderPicked(order));

        // Assign rider
        DarkStore store = darkStores.get(order.getDarkStoreId());
        Optional<Rider> riderOpt = deliveryService.assignRider(order, store);

        riderOpt.ifPresent(rider -> {
            observers.forEach(o -> o.onOrderOutForDelivery(order, rider));
        });
    }

    // ---- Simulate delivery ----
    public void simulateDelivery(long orderId) throws InterruptedException {
        Order order = orders.get(orderId);
        if (order == null) return;
        Rider rider = deliveryService.getRider(order.getAssignedRider());
        if (rider == null) return;

        Thread.sleep(100); // simulate delivery
        deliveryService.confirmDelivery(rider.getRiderId(), order);
    }

    // ---- Cancel order ----
    public boolean cancelOrder(long orderId, String reason) {
        PlaceOrderCommand cmd = commandStore.get(orderId);
        Order order = orders.get(orderId);
        if (cmd == null || order == null) return false;

        if (order.getStatus() == OrderStatus.PICKING ||
            order.getStatus() == OrderStatus.PICKED  ||
            order.getStatus() == OrderStatus.OUT_FOR_DELIVERY) {
            System.out.println("[Service] Cannot cancel — order already being picked/delivered");
            return false;
        }
        cmd.cancel(reason);
        return true;
    }

    // ---- Check SLA ----
    public void checkSLA(long orderId) {
        Order order = orders.get(orderId);
        if (order != null && order.isSLABreached()) {
            observers.forEach(o -> o.onSLABreach(order));
        }
    }

    public Order     getOrder(long id) { return orders.get(id); }
    public int       getLiveEta(long orderId) {
        Order order = orders.get(orderId);
        return order == null ? -1 : deliveryService.getLiveEta(order);
    }
    public void      printAnalytics()  { analytics.printReport(); }
}

// ============================================================
// 18. MAIN — DRIVER CODE
// ============================================================
public class QuickCommerceSystem {
    public static void main(String[] args) throws InterruptedException {

        QuickCommerceService service  = QuickCommerceService.getInstance();
        DeliveryService      delivery = DeliveryService.getInstance();

        // ===== SETUP: Dark Stores =====
        System.out.println("=".repeat(60));
        System.out.println("SETUP: Dark Stores, Products, Riders");
        System.out.println("=".repeat(60));

        DarkStore kondapurStore = new DarkStore.Builder(
            "Kondapur Dark Store",
            new GeoCoordinate(17.4676, 78.3489))
            .serviceRadius(3.0).build();

        DarkStore hitechStore = new DarkStore.Builder(
            "Hitech City Dark Store",
            new GeoCoordinate(17.4504, 78.3808))
            .serviceRadius(3.0).build();

        service.addDarkStore(kondapurStore);
        service.addDarkStore(hitechStore);

        // ===== SETUP: Products =====
        Product milk   = new Product.Builder("DAIRY-001", "Amul Full Cream Milk 1L",
            "Amul", ProductCategory.DAIRY_EGGS, "1 Litre", Money.ofRupees(68))
            .tags("milk", "dairy", "fresh").build();

        Product eggs   = new Product.Builder("DAIRY-002", "Farm Fresh Eggs (6 pack)",
            "Country Delight", ProductCategory.DAIRY_EGGS, "6 Eggs", Money.ofRupees(65))
            .tags("eggs", "protein", "breakfast").build();

        Product bread  = new Product.Builder("BAKE-001", "Modern Bread Sliced",
            "Modern", ProductCategory.BAKERY, "400g", Money.ofRupees(40))
            .tags("bread", "breakfast", "bakery").build();

        Product chips  = new Product.Builder("SNAC-001", "Lay's Classic Salted",
            "Lay's", ProductCategory.SNACKS, "52g", Money.ofRupees(20))
            .tags("chips", "snacks", "namkeen").build();

        Product banana = new Product.Builder("FV-001", "Robusta Banana",
            "Fresh", ProductCategory.FRUITS_VEGETABLES, "1 dozen", Money.ofRupees(55))
            .tags("fruits", "banana", "healthy").build();

        Product cola   = new Product.Builder("BEV-001", "Coca-Cola 2L",
            "Coca-Cola", ProductCategory.BEVERAGES, "2 Litre", Money.ofRupees(95))
            .tags("cold-drink", "cola", "beverages").build();

        service.addProduct(milk);
        service.addProduct(eggs);
        service.addProduct(bread);
        service.addProduct(chips);
        service.addProduct(banana);
        service.addProduct(cola);

        // Stock the Kondapur store
        kondapurStore.stockProduct(milk.getProductId(),   10);
        kondapurStore.stockProduct(eggs.getProductId(),    5);
        kondapurStore.stockProduct(bread.getProductId(),   8);
        kondapurStore.stockProduct(chips.getProductId(),  15);
        kondapurStore.stockProduct(banana.getProductId(),  3);
        kondapurStore.stockProduct(cola.getProductId(),    6);

        hitechStore.stockProduct(milk.getProductId(),     20);
        hitechStore.stockProduct(cola.getProductId(),     10);

        // ===== SETUP: Riders =====
        Rider ravi = delivery.registerRider(
            new Rider.Builder("Ravi Kumar",  "+91-9999111111",
                new GeoCoordinate(17.4650, 78.3500))
                .darkStore(kondapurStore.getStoreId()).build());

        Rider priya = delivery.registerRider(
            new Rider.Builder("Priya Mehta", "+91-9999222222",
                new GeoCoordinate(17.4700, 78.3450))
                .darkStore(kondapurStore.getStoreId()).build());

        Rider arjun = delivery.registerRider(
            new Rider.Builder("Arjun Rao",   "+91-9999333333",
                new GeoCoordinate(17.4490, 78.3820))
                .darkStore(hitechStore.getStoreId()).build());

        // ===== SCENARIO 1: Req 1 — Serviceability check =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Serviceability Check (Req 1)");
        System.out.println("=".repeat(60));

        GeoCoordinate aliceAddr = new GeoCoordinate(17.4650, 78.3520); // near Kondapur
        GeoCoordinate outOfArea = new GeoCoordinate(17.3850, 78.4867); // LB Nagar — far

        Optional<DarkStore> aliceStore = service.findDarkStore(aliceAddr);
        Optional<DarkStore> noStore    = service.findDarkStore(outOfArea);

        System.out.println("Alice's store: " + aliceStore.map(DarkStore::getName).orElse("NOT SERVICEABLE"));
        System.out.println("Out of area:   " + noStore.map(DarkStore::getName).orElse("NOT SERVICEABLE"));

        // ===== SCENARIO 2: Req 2 — Browse and search =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Browse + Search (Req 2)");
        System.out.println("=".repeat(60));

        System.out.println("Browsing DAIRY_EGGS category:");
        ProductCatalogIterator dairyIter = service.browseCategory(ProductCategory.DAIRY_EGGS, 5);
        dairyIter.nextPage().forEach(System.out::println);

        System.out.println("\nSearch: 'cola'");
        ProductCatalogIterator searchIter = service.search("cola", 5);
        searchIter.nextPage().forEach(System.out::println);

        // ===== SCENARIO 3: Req 3 — Cart management =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Cart Management (Req 3)");
        System.out.println("=".repeat(60));

        long aliceId = 1001L;
        DarkStore aliceDS = aliceStore.get();

        service.addToCart(aliceId, milk.getProductId(),   2, aliceDS);
        service.addToCart(aliceId, eggs.getProductId(),   1, aliceDS);
        service.addToCart(aliceId, bread.getProductId(),  1, aliceDS);
        service.addToCart(aliceId, chips.getProductId(),  3, aliceDS);

        Cart aliceCart = service.getOrCreateCart(aliceId, aliceDS.getStoreId());
        aliceCart.printCart();

        // ===== SCENARIO 4: Req 4 — Soft vs Hard inventory check =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Soft vs Hard Inventory Check (Req 4)");
        System.out.println("=".repeat(60));

        System.out.println("Soft check banana (qty=5, stock=3): " +
            InventoryService.getInstance().isAvailable(aliceDS, banana.getProductId(), 5));
        System.out.println("Soft check milk (qty=2, stock=10): " +
            InventoryService.getInstance().isAvailable(aliceDS, milk.getProductId(), 2));

        // Try adding out-of-stock quantity
        boolean bananaFail = service.addToCart(aliceId, banana.getProductId(), 5, aliceDS);
        System.out.println("Add 5 bananas (only 3 in stock): " + bananaFail);

        // ===== SCENARIO 5: Req 5 — Place order =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Place Order (Req 5)");
        System.out.println("=".repeat(60));

        String idemKey = "ORDER-ALICE-" + System.currentTimeMillis();
        Order aliceOrder = service.placeOrder(aliceId, aliceAddr, PaymentMethod.UPI, idemKey);

        if (aliceOrder != null) {
            System.out.println("\nOrder placed: " + aliceOrder);
            System.out.println("Stock after reservation:");
            System.out.println("  milk:  " + aliceDS.getStock(milk.getProductId()));
            System.out.println("  bread: " + aliceDS.getStock(bread.getProductId()));
        }

        // ===== SCENARIO 6: Req 10 — Idempotency (duplicate request) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Idempotency — Retry with Same Key (Req 10)");
        System.out.println("=".repeat(60));

        // Re-add items since cart was consumed
        service.addToCart(aliceId, cola.getProductId(), 1, aliceDS);
        Order duplicateOrder = service.placeOrder(aliceId, aliceAddr, PaymentMethod.UPI, idemKey);
        System.out.println("Same key returns existing order: " +
            (duplicateOrder != null && duplicateOrder.getOrderId() == aliceOrder.getOrderId()));

        // ===== SCENARIO 7: Req 6 — Rider assignment + tracking =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Picking → Rider Assignment → Delivery (Req 6 + 7)");
        System.out.println("=".repeat(60));

        if (aliceOrder != null) {
            service.simulatePicking(aliceOrder.getOrderId());
            Thread.sleep(100);

            // Update rider location mid-delivery
            delivery.updateRiderLocation(ravi.getRiderId(),
                new GeoCoordinate(17.4655, 78.3515));
            System.out.println("Live ETA update: " + service.getLiveEta(aliceOrder.getOrderId()) + " min");

            service.simulateDelivery(aliceOrder.getOrderId());
        }

        // ===== SCENARIO 8: Concurrent orders — oversell protection =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Concurrent Orders — Zero Oversell (Req 10)");
        System.out.println("=".repeat(60));

        // banana has 3 units — 5 users try to buy 1 each concurrently
        System.out.println("Banana stock before: " + aliceDS.getStock(banana.getProductId()));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Boolean> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 5; i++) {
            final long uid = 2000 + i;
            final String key = "BANANA-ORDER-" + i;
            pool.submit(() -> {
                service.addToCart(uid, banana.getProductId(), 1, aliceDS);
                Cart c = service.getOrCreateCart(uid, aliceDS.getStoreId());
                if (!c.isEmpty()) {
                    Order o = service.placeOrder(uid, aliceAddr, PaymentMethod.UPI, key);
                    results.add(o != null);
                } else {
                    results.add(false);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long succeeded = results.stream().filter(r -> r).count();
        System.out.println("Banana orders succeeded: " + succeeded + "/5 (stock was 3)");
        System.out.println("Banana stock after: " + aliceDS.getStock(banana.getProductId()));

        // ===== SCENARIO 9: Bob's order — surge pricing =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Surge Pricing Strategy (Req: extensible)");
        System.out.println("=".repeat(60));

        service.setPricingStrategy(new SurgePricingStrategy(1.5));
        long bobId = 3001L;
        service.addToCart(bobId, milk.getProductId(), 1, aliceDS);
        service.addToCart(bobId, cola.getProductId(), 1, aliceDS);
        Cart bobCart = service.getOrCreateCart(bobId, aliceDS.getStoreId());
        bobCart.printCart();

        double bobDist = aliceDS.getLocation().distanceKm(aliceAddr);
        Money dFee = new SurgePricingStrategy(1.5).computeDeliveryFee(
            bobCart.getSubtotal(), bobDist, LocalDateTime.now());
        System.out.println("Surge delivery fee: " + dFee);
        service.setPricingStrategy(new StandardPricingStrategy()); // reset

        // ===== SCENARIO 10: Cancel order =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Cancel Order + Inventory Rollback");
        System.out.println("=".repeat(60));

        long carolId = 4001L;
        service.addToCart(carolId, chips.getProductId(), 2, aliceDS);
        int chipsBefore = aliceDS.getStock(chips.getProductId());
        System.out.println("Chips stock before order: " + chipsBefore);

        Order carolOrder = service.placeOrder(carolId, aliceAddr, PaymentMethod.UPI,
            "CAROL-ORDER-" + System.currentTimeMillis());

        if (carolOrder != null) {
            System.out.println("Chips stock after reservation: " + aliceDS.getStock(chips.getProductId()));
            boolean cancelled = service.cancelOrder(carolOrder.getOrderId(), "Changed my mind");
            System.out.println("Cancelled: " + cancelled);
            System.out.println("Chips stock after rollback: " + aliceDS.getStock(chips.getProductId()));
        }

        // ===== FINAL REPORT =====
        service.printAnalytics();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------------------
            Singleton  | QuickCommerceService, InventoryService, DeliveryService
            State      | OrderStatus: PENDING→CONFIRMED→PICKING→PICKED→OFD→DELIVERED
                       | RiderStatus: AVAILABLE↔ASSIGNED↔PICKING_UP↔DELIVERING
            Strategy   | PricingStrategy: Standard / Surge(multiplier)
                       | RiderAssignmentStrategy: NearestFirst / LoadBalanced
            Observer   | OrderEventObserver: Notification / DarkStore / Analytics
            Factory    | OrderFactory.fromCart() — single construction path
            Builder    | Order, Product, Rider, DarkStore (all fluent builders)
            Command    | PlaceOrderCommand: execute()=reserve+pay+confirm
                       |                   cancel()=release inventory+refund
            Iterator   | ProductCatalogIterator: paginated, category/search filtered
            """);

        System.out.println("===== CONCURRENCY SAFETY =====");
        System.out.println("""
            Class                    | Mechanism                  | Why
            -------------------------|----------------------------|--------------------------
            DarkStore.hardReserve()  | ReentrantLock(fair=true)   | Atomic check+decrement
            DarkStore.release()      | Same per-SKU lock          | Idempotent rollback
            QuickCommerceService     | ConcurrentHashMap          | Safe concurrent access
            Idempotency check        | putIfAbsent (atomic)       | No duplicate orders
            PlaceOrderCommand        | All-or-nothing reservation | No partial inventory hold
            """);

        System.out.println("\n===== KEY DESIGN DECISIONS =====");
        System.out.println("""
            1. Dark store pin at session start — every cart, inventory check, and order is
               scoped to ONE store's ID. Cross-store fulfilment is never attempted.

            2. Soft check at cart add — Redis GET (fast, may be stale). Hard atomic lock
               at order placement — Lua script or per-SKU ReentrantLock. Two completely
               separate mechanisms with different consistency guarantees.

            3. All-or-nothing batch reservation — if item 3 of 5 fails, items 1 and 2 are
               released before returning 409. No partial holds ever left dangling.

            4. Idempotency key guards every order placement — client retries on timeout
               return the same order, never create two orders charged to the same user.

            5. Command pattern carries the compensating transaction — cancel() always
               knows exactly what to undo regardless of which step the failure occurred at.
            """);
    }
}
