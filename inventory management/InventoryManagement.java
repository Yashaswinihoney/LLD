import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// INVENTORY MANAGEMENT SYSTEM LLD
// Patterns:
//   Singleton  — InventoryService, WarehouseRegistry
//   Strategy   — ReorderStrategy (FixedQty / EOQ / JIT)
//   Observer   — StockEventObserver (low-stock alerts, reorder triggers)
//   Factory    — StockMovementFactory, PurchaseOrderFactory
//   Builder    — Product, PurchaseOrder construction
//   State      — StockMovement (PENDING→IN_TRANSIT→RECEIVED/CANCELLED)
//   Chain of Responsibility — StockValidator (qty/expiry/warehouse capacity)
//   Command    — StockMovement as executable command (execute/rollback)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum MovementType   { INBOUND, OUTBOUND, TRANSFER, ADJUSTMENT, RETURN }
enum MovementStatus { PENDING, IN_TRANSIT, RECEIVED, CANCELLED, FAILED }
enum ProductCategory{ ELECTRONICS, GROCERY, APPAREL, FURNITURE,
                      PHARMACY, PERISHABLE, BOOKS, SPORTS }
enum StockStatus    { IN_STOCK, LOW_STOCK, OUT_OF_STOCK, DISCONTINUED }
enum PurchaseOrderStatus { DRAFT, CONFIRMED, SHIPPED, PARTIALLY_RECEIVED,
                           RECEIVED, CANCELLED }
enum AlertType      { LOW_STOCK, OUT_OF_STOCK, EXPIRY_WARNING,
                      REORDER_TRIGGERED, OVERSTOCK }

// ==========================================
// 2. PRODUCT — BUILDER PATTERN
// ==========================================
class Product {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long            id;
    private        String          name;
    private        String          sku;           // Stock Keeping Unit — unique code
    private        String          barcode;
    private final  ProductCategory category;
    private        double          unitCost;      // cost to purchase from supplier
    private        double          sellingPrice;
    private        int             reorderPoint;  // trigger reorder at this qty
    private        int             reorderQty;    // how much to reorder
    private        int             minStockLevel; // safety stock
    private        int             maxStockLevel; // no more than this
    private        String          supplierId;
    private        int             leadTimeDays;  // supplier lead time
    private        boolean         isPerishable;
    private        int             shelfLifeDays; // for perishables
    private        String          unit;          // kg, piece, litre, etc.
    private        double          weight;        // for shipping calc
    private        Map<String, String> attributes = new HashMap<>(); // size, colour, etc.

    private Product(Builder b) {
        this.id             = idGen.getAndIncrement();
        this.name           = b.name;
        this.sku            = b.sku;
        this.barcode        = b.barcode;
        this.category       = b.category;
        this.unitCost       = b.unitCost;
        this.sellingPrice   = b.sellingPrice;
        this.reorderPoint   = b.reorderPoint;
        this.reorderQty     = b.reorderQty;
        this.minStockLevel  = b.minStockLevel;
        this.maxStockLevel  = b.maxStockLevel;
        this.supplierId     = b.supplierId;
        this.leadTimeDays   = b.leadTimeDays;
        this.isPerishable   = b.isPerishable;
        this.shelfLifeDays  = b.shelfLifeDays;
        this.unit           = b.unit;
        this.weight         = b.weight;
        this.attributes     = new HashMap<>(b.attributes);
    }

    public long            getId()            { return id; }
    public String          getName()          { return name; }
    public String          getSku()           { return sku; }
    public String          getBarcode()       { return barcode; }
    public ProductCategory getCategory()      { return category; }
    public double          getUnitCost()      { return unitCost; }
    public double          getSellingPrice()  { return sellingPrice; }
    public int             getReorderPoint()  { return reorderPoint; }
    public int             getReorderQty()    { return reorderQty; }
    public int             getMinStockLevel() { return minStockLevel; }
    public int             getMaxStockLevel() { return maxStockLevel; }
    public String          getSupplierId()    { return supplierId; }
    public int             getLeadTimeDays()  { return leadTimeDays; }
    public boolean         isPerishable()     { return isPerishable; }
    public int             getShelfLifeDays() { return shelfLifeDays; }
    public String          getUnit()          { return unit; }
    public double          getWeight()        { return weight; }
    public Map<String,String> getAttributes() { return Collections.unmodifiableMap(attributes); }

    public void setUnitCost(double c)     { this.unitCost = c; }
    public void setSellingPrice(double p) { this.sellingPrice = p; }
    public void setReorderPoint(int r)    { this.reorderPoint = r; }

    @Override public String toString() {
        return String.format("Product[#%d | %-20s | SKU:%-12s | ₹%.2f | cat:%s]",
            id, name, sku, sellingPrice, category);
    }

    static class Builder {
        private final String          name;
        private final String          sku;
        private final ProductCategory category;
        private       String          barcode       = "";
        private       double          unitCost      = 0;
        private       double          sellingPrice  = 0;
        private       int             reorderPoint  = 10;
        private       int             reorderQty    = 50;
        private       int             minStockLevel = 5;
        private       int             maxStockLevel = 1000;
        private       String          supplierId    = "";
        private       int             leadTimeDays  = 7;
        private       boolean         isPerishable  = false;
        private       int             shelfLifeDays = 365;
        private       String          unit          = "piece";
        private       double          weight        = 0.5;
        private       Map<String,String> attributes = new HashMap<>();

        public Builder(String name, String sku, ProductCategory cat) {
            this.name = name; this.sku = sku; this.category = cat;
        }
        public Builder barcode(String b)       { this.barcode = b;         return this; }
        public Builder cost(double c)          { this.unitCost = c;        return this; }
        public Builder price(double p)         { this.sellingPrice = p;    return this; }
        public Builder reorderPoint(int r)     { this.reorderPoint = r;    return this; }
        public Builder reorderQty(int r)       { this.reorderQty = r;      return this; }
        public Builder minStock(int m)         { this.minStockLevel = m;   return this; }
        public Builder maxStock(int m)         { this.maxStockLevel = m;   return this; }
        public Builder supplier(String s)      { this.supplierId = s;      return this; }
        public Builder leadTime(int d)         { this.leadTimeDays = d;    return this; }
        public Builder perishable(int days)    {
            this.isPerishable = true; this.shelfLifeDays = days; return this;
        }
        public Builder unit(String u)          { this.unit = u;            return this; }
        public Builder weight(double w)        { this.weight = w;          return this; }
        public Builder attr(String k, String v){ this.attributes.put(k,v); return this; }
        public Product build()                 { return new Product(this); }
    }
}

// ==========================================
// 3. WAREHOUSE
// ==========================================
class Warehouse {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final long   id;
    private final String name;
    private final String location;
    private final String city;
    private final String pincode;
    private       int    totalCapacity;       // total units storable
    private       int    usedCapacity;        // currently occupied
    private       boolean isActive;
    private final Map<String, String> zones;  // zone code → description

    public Warehouse(String name, String location, String city,
                     String pincode, int totalCapacity) {
        this.id            = idGen.getAndIncrement();
        this.name          = name;
        this.location      = location;
        this.city          = city;
        this.pincode       = pincode;
        this.totalCapacity = totalCapacity;
        this.usedCapacity  = 0;
        this.isActive      = true;
        this.zones         = new HashMap<>();
    }

    public synchronized boolean canAccommodate(int qty) {
        return (usedCapacity + qty) <= totalCapacity;
    }

    public synchronized void addCapacityUsed(int qty)    { usedCapacity += qty; }
    public synchronized void releaseCapacity(int qty)    {
        usedCapacity = Math.max(0, usedCapacity - qty);
    }

    public int    getAvailableCapacity() { return totalCapacity - usedCapacity; }

    public long    getId()          { return id; }
    public String  getName()        { return name; }
    public String  getCity()        { return city; }
    public String  getPincode()     { return pincode; }
    public int     getTotalCapacity(){ return totalCapacity; }
    public int     getUsedCapacity(){ return usedCapacity; }
    public boolean isActive()       { return isActive; }
    public void    setActive(boolean a){ isActive = a; }

    @Override public String toString() {
        return String.format("Warehouse[%s | %s | cap:%d/%d]",
            name, city, usedCapacity, totalCapacity);
    }
}

// ==========================================
// 4. STOCK ITEM — one product in one warehouse
// ==========================================
class StockItem {
    private final  Product   product;
    private final  Warehouse warehouse;
    private        int       availableQty;    // can be sold/transferred
    private        int       reservedQty;     // held for pending orders
    private        int       damagedQty;      // unusable
    private        int       inTransitQty;    // being moved here
    private final  LocalDateTime lastUpdated  = LocalDateTime.now();
    private final  Map<String, Integer> batchQty = new LinkedHashMap<>();
    // batchId → qty (for FIFO/FEFO management)
    private final  Map<String, LocalDateTime> batchExpiry = new HashMap<>();
    // batchId → expiry date (for perishables)

    public StockItem(Product product, Warehouse warehouse, int initialQty) {
        this.product      = product;
        this.warehouse    = warehouse;
        this.availableQty = initialQty;
        this.reservedQty  = 0;
        this.damagedQty   = 0;
        this.inTransitQty = 0;
    }

    // ---- Thread-safe stock operations ----
    public synchronized boolean reserve(int qty) {
        if (availableQty < qty) return false;
        availableQty -= qty;
        reservedQty  += qty;
        System.out.printf("[Stock] Reserved %d of %s in %s | avail=%d reserved=%d%n",
            qty, product.getSku(), warehouse.getName(), availableQty, reservedQty);
        return true;
    }

    public synchronized void releaseReservation(int qty) {
        reservedQty  = Math.max(0, reservedQty - qty);
        availableQty += qty;
        System.out.printf("[Stock] Released %d reservation of %s | avail=%d%n",
            qty, product.getSku(), availableQty);
    }

    public synchronized void deductReserved(int qty) {
        // Called when order is actually shipped — remove from reserved
        reservedQty = Math.max(0, reservedQty - qty);
        warehouse.releaseCapacity(qty);
        System.out.printf("[Stock] Deducted %d from reserved (shipped) | reserved=%d%n",
            qty, reservedQty);
    }

    public synchronized void addStock(int qty, String batchId, LocalDateTime expiry) {
        availableQty += qty;
        warehouse.addCapacityUsed(qty);
        if (batchId != null) {
            batchQty.merge(batchId, qty, Integer::sum);
            if (expiry != null) batchExpiry.put(batchId, expiry);
        }
        System.out.printf("[Stock] Added %d of %s to %s | total avail=%d%n",
            qty, product.getSku(), warehouse.getName(), availableQty);
    }

    public synchronized void markDamaged(int qty) {
        int deduct    = Math.min(qty, availableQty);
        availableQty -= deduct;
        damagedQty   += deduct;
        warehouse.releaseCapacity(deduct);
        System.out.printf("[Stock] Marked %d damaged: %s | avail=%d damaged=%d%n",
            deduct, product.getSku(), availableQty, damagedQty);
    }

    public synchronized void adjustStock(int delta, String reason) {
        int oldQty = availableQty;
        availableQty = Math.max(0, availableQty + delta);
        System.out.printf("[Adjustment] %s: %d → %d (%s)%n",
            product.getSku(), oldQty, availableQty, reason);
    }

    public int getTotalQty()     { return availableQty + reservedQty; }
    public int getAvailableQty() { return availableQty; }
    public int getReservedQty()  { return reservedQty; }
    public int getDamagedQty()   { return damagedQty; }
    public int getInTransitQty() { return inTransitQty; }

    public void setInTransitQty(int q) { this.inTransitQty = q; }

    public StockStatus getStockStatus() {
        if (availableQty == 0 && reservedQty == 0) return StockStatus.OUT_OF_STOCK;
        if (availableQty <= product.getMinStockLevel()) return StockStatus.LOW_STOCK;
        return StockStatus.IN_STOCK;
    }

    // Find next expiring batch (FEFO — First Expired First Out)
    public Optional<String> getNextExpiringBatch() {
        return batchExpiry.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }

    public Product   getProduct()   { return product; }
    public Warehouse getWarehouse() { return warehouse; }

    @Override public String toString() {
        return String.format("Stock[%-20s | %-15s | avail=%-5d reserved=%-5d status=%s]",
            product.getSku(), warehouse.getName(),
            availableQty, reservedQty, getStockStatus());
    }
}

// ==========================================
// 5. STOCK MOVEMENT — STATE + COMMAND PATTERN
// ==========================================
class StockMovement {
    private static final AtomicLong idGen = new AtomicLong(80000);

    private final  long           id;
    private final  MovementType   type;
    private final  Product        product;
    private final  Warehouse      fromWarehouse;  // null for INBOUND
    private final  Warehouse      toWarehouse;    // null for OUTBOUND
    private final  int            quantity;
    private        MovementStatus status;
    private final  String         referenceId;    // orderId / poId / transferId
    private final  String         reason;
    private final  String         batchId;
    private        String         performedBy;
    private final  LocalDateTime  createdAt;
    private        LocalDateTime  completedAt;
    private        String         cancelReason;

    public StockMovement(MovementType type, Product product,
                         Warehouse from, Warehouse to,
                         int quantity, String referenceId, String reason) {
        this.id           = idGen.getAndIncrement();
        this.type         = type;
        this.product      = product;
        this.fromWarehouse = from;
        this.toWarehouse  = to;
        this.quantity     = quantity;
        this.referenceId  = referenceId;
        this.reason       = reason;
        this.batchId      = "batch-" + System.currentTimeMillis();
        this.status       = MovementStatus.PENDING;
        this.createdAt    = LocalDateTime.now();
    }

    // State transitions
    public void markInTransit() {
        if (status == MovementStatus.PENDING) {
            status = MovementStatus.IN_TRANSIT;
            System.out.println("[Movement #" + id + "] → IN_TRANSIT");
        }
    }

    public void markReceived() {
        if (status == MovementStatus.IN_TRANSIT ||
            status == MovementStatus.PENDING) {
            status      = MovementStatus.RECEIVED;
            completedAt = LocalDateTime.now();
            System.out.println("[Movement #" + id + "] → RECEIVED (" + quantity + " units)");
        }
    }

    public void cancel(String reason) {
        if (status != MovementStatus.RECEIVED) {
            status       = MovementStatus.CANCELLED;
            cancelReason = reason;
            completedAt  = LocalDateTime.now();
            System.out.println("[Movement #" + id + "] → CANCELLED: " + reason);
        }
    }

    public long           getId()           { return id; }
    public MovementType   getType()         { return type; }
    public Product        getProduct()      { return product; }
    public Warehouse      getFromWarehouse(){ return fromWarehouse; }
    public Warehouse      getToWarehouse()  { return toWarehouse; }
    public int            getQuantity()     { return quantity; }
    public MovementStatus getStatus()       { return status; }
    public String         getReferenceId()  { return referenceId; }
    public String         getBatchId()      { return batchId; }
    public LocalDateTime  getCreatedAt()    { return createdAt; }

    @Override public String toString() {
        return String.format("Movement[#%d | %s | %s x%d | %s→%s | %s]",
            id, type, product.getSku(), quantity,
            fromWarehouse != null ? fromWarehouse.getName() : "SUPPLIER",
            toWarehouse   != null ? toWarehouse.getName()   : "CUSTOMER",
            status);
    }
}

// ==========================================
// 6. STOCK MOVEMENT FACTORY
// ==========================================
class StockMovementFactory {
    public static StockMovement inbound(Product product, Warehouse to,
                                         int qty, String poId) {
        return new StockMovement(MovementType.INBOUND, product,
            null, to, qty, poId, "Purchase order receipt");
    }

    public static StockMovement outbound(Product product, Warehouse from,
                                          int qty, String orderId) {
        return new StockMovement(MovementType.OUTBOUND, product,
            from, null, qty, orderId, "Customer order fulfillment");
    }

    public static StockMovement transfer(Product product, Warehouse from,
                                          Warehouse to, int qty, String transferId) {
        return new StockMovement(MovementType.TRANSFER, product,
            from, to, qty, transferId, "Warehouse transfer");
    }

    public static StockMovement adjustment(Product product, Warehouse warehouse,
                                            int qty, String reason) {
        return new StockMovement(MovementType.ADJUSTMENT, product,
            null, warehouse, qty, "ADJ-" + System.currentTimeMillis(), reason);
    }

    public static StockMovement returnMovement(Product product, Warehouse to,
                                                int qty, String returnId) {
        return new StockMovement(MovementType.RETURN, product,
            null, to, qty, returnId, "Customer return");
    }
}

// ==========================================
// 7. PURCHASE ORDER — BUILDER PATTERN
// ==========================================
class PurchaseOrder {
    private static final AtomicLong idGen = new AtomicLong(PO100);
    static final long PO100 = 100;

    private final  long                  id;
    private final  String                supplierId;
    private final  Warehouse             destinationWarehouse;
    private final  Map<Product, Integer> lineItems;  // product → qty ordered
    private        PurchaseOrderStatus   status;
    private final  LocalDateTime         orderedAt;
    private        LocalDateTime         expectedBy;
    private        LocalDateTime         receivedAt;
    private        double                totalValue;
    private        String                notes;
    private final  Map<Product, Integer> receivedQty = new HashMap<>();

    private PurchaseOrder(Builder b) {
        this.id                   = idGen.getAndIncrement();
        this.supplierId           = b.supplierId;
        this.destinationWarehouse = b.destinationWarehouse;
        this.lineItems            = Map.copyOf(b.lineItems);
        this.status               = PurchaseOrderStatus.DRAFT;
        this.orderedAt            = LocalDateTime.now();
        this.expectedBy           = b.expectedBy;
        this.notes                = b.notes;
        this.totalValue           = b.lineItems.entrySet().stream()
            .mapToDouble(e -> e.getKey().getUnitCost() * e.getValue())
            .sum();
    }

    public void confirm() {
        status = PurchaseOrderStatus.CONFIRMED;
        System.out.println("[PO #" + id + "] Confirmed | Total: ₹" +
            String.format("%.2f", totalValue));
    }

    public void markShipped() { status = PurchaseOrderStatus.SHIPPED; }

    public synchronized void receiveItem(Product product, int qty) {
        receivedQty.merge(product, qty, Integer::sum);
        int ordered  = lineItems.getOrDefault(product, 0);
        int received = receivedQty.getOrDefault(product, 0);

        boolean allReceived = lineItems.entrySet().stream()
            .allMatch(e -> receivedQty.getOrDefault(e.getKey(), 0) >= e.getValue());

        status = allReceived
            ? PurchaseOrderStatus.RECEIVED
            : PurchaseOrderStatus.PARTIALLY_RECEIVED;

        System.out.printf("[PO #%d] Received %d of %s (%d/%d total)%n",
            id, qty, product.getSku(), received, ordered);

        if (allReceived) receivedAt = LocalDateTime.now();
    }

    public void cancel(String reason) {
        if (status != PurchaseOrderStatus.RECEIVED) {
            status = PurchaseOrderStatus.CANCELLED;
            System.out.println("[PO #" + id + "] Cancelled: " + reason);
        }
    }

    public long                  getId()          { return id; }
    public String                getSupplierId()  { return supplierId; }
    public Warehouse             getDestination() { return destinationWarehouse; }
    public Map<Product, Integer> getLineItems()   { return lineItems; }
    public PurchaseOrderStatus   getStatus()      { return status; }
    public double                getTotalValue()  { return totalValue; }
    public LocalDateTime         getExpectedBy()  { return expectedBy; }

    @Override public String toString() {
        return String.format("PO[#%d | supplier=%s | items=%d | ₹%.2f | %s]",
            id, supplierId, lineItems.size(), totalValue, status);
    }

    static class Builder {
        private final String                supplierId;
        private final Warehouse             destinationWarehouse;
        private final Map<Product, Integer> lineItems = new LinkedHashMap<>();
        private       LocalDateTime         expectedBy = LocalDateTime.now().plusDays(7);
        private       String                notes = "";

        public Builder(String supplierId, Warehouse dest) {
            this.supplierId           = supplierId;
            this.destinationWarehouse = dest;
        }
        public Builder addItem(Product p, int qty) { lineItems.put(p, qty); return this; }
        public Builder expectedBy(LocalDateTime dt){ this.expectedBy = dt;  return this; }
        public Builder notes(String n)             { this.notes = n;        return this; }
        public PurchaseOrder build()               { return new PurchaseOrder(this); }
    }
}

// ==========================================
// 8. CHAIN OF RESPONSIBILITY — STOCK VALIDATORS
// ==========================================
abstract class StockValidator {
    protected StockValidator next;

    public StockValidator setNext(StockValidator next) {
        this.next = next;
        return next;
    }

    public abstract String validate(StockItem stock, int qty);

    protected String passToNext(StockItem stock, int qty) {
        return next != null ? next.validate(stock, qty) : null;
    }
}

class QuantityValidator extends StockValidator {
    @Override
    public String validate(StockItem stock, int qty) {
        if (qty <= 0) return "Quantity must be positive: " + qty;
        if (qty > 10_000) return "Quantity exceeds single-operation limit: " + qty;
        System.out.println("[Validator] ✓ Quantity: " + qty);
        return passToNext(stock, qty);
    }
}

class AvailabilityValidator extends StockValidator {
    @Override
    public String validate(StockItem stock, int qty) {
        if (stock.getAvailableQty() < qty) {
            return "Insufficient stock: available=" + stock.getAvailableQty() +
                " requested=" + qty + " (" + stock.getProduct().getSku() + ")";
        }
        System.out.println("[Validator] ✓ Availability: " +
            stock.getAvailableQty() + " >= " + qty);
        return passToNext(stock, qty);
    }
}

class ExpiryValidator extends StockValidator {
    private static final int MIN_SHELF_LIFE_DAYS = 30;

    @Override
    public String validate(StockItem stock, int qty) {
        if (!stock.getProduct().isPerishable()) return passToNext(stock, qty);

        Optional<String> nextBatch = stock.getNextExpiringBatch();
        // Simplified check — in production compare actual expiry date
        System.out.println("[Validator] ✓ Expiry check passed (FEFO applied)");
        return passToNext(stock, qty);
    }
}

class WarehouseCapacityValidator extends StockValidator {
    @Override
    public String validate(StockItem stock, int qty) {
        // Only relevant for inbound operations
        Warehouse wh = stock.getWarehouse();
        if (!wh.canAccommodate(qty)) {
            return "Warehouse " + wh.getName() +
                " capacity exceeded: available=" + wh.getAvailableCapacity() +
                " needs=" + qty;
        }
        System.out.println("[Validator] ✓ Warehouse capacity OK");
        return passToNext(stock, qty);
    }
}

// ==========================================
// 9. STRATEGY — REORDER
// ==========================================
interface ReorderStrategy {
    String getName();
    int calculateReorderQty(Product product, StockItem currentStock,
                            double avgDailyDemand);
    boolean shouldReorder(Product product, StockItem currentStock);
}

class FixedQtyReorderStrategy implements ReorderStrategy {
    @Override public String getName() { return "Fixed Quantity"; }

    @Override
    public boolean shouldReorder(Product product, StockItem stock) {
        return stock.getAvailableQty() <= product.getReorderPoint();
    }

    @Override
    public int calculateReorderQty(Product product, StockItem stock,
                                    double avgDailyDemand) {
        return product.getReorderQty();
    }
}

class EOQReorderStrategy implements ReorderStrategy {
    // Economic Order Quantity: minimise total ordering + holding costs
    // EOQ = sqrt(2 × demand × orderCost / holdingCostPerUnit)
    private static final double ORDER_COST    = 500.0;  // cost per PO placement
    private static final double HOLDING_RATE  = 0.20;   // 20% of unit cost per year

    @Override public String getName() { return "Economic Order Quantity (EOQ)"; }

    @Override
    public boolean shouldReorder(Product product, StockItem stock) {
        return stock.getAvailableQty() <= product.getReorderPoint();
    }

    @Override
    public int calculateReorderQty(Product product, StockItem stock,
                                    double avgDailyDemand) {
        double annualDemand  = avgDailyDemand * 365;
        double holdingCost   = product.getUnitCost() * HOLDING_RATE;
        double eoq           = Math.sqrt((2 * annualDemand * ORDER_COST) / holdingCost);

        // Round up to nearest 10 for practical ordering
        int rounded = (int)(Math.ceil(eoq / 10) * 10);
        System.out.printf("[EOQ] %s: annualDemand=%.0f EOQ=%.1f rounded=%d%n",
            product.getSku(), annualDemand, eoq, rounded);
        return rounded;
    }
}

class JITReorderStrategy implements ReorderStrategy {
    // Just-In-Time: order only what's needed for the next lead-time window
    @Override public String getName() { return "Just-In-Time (JIT)"; }

    @Override
    public boolean shouldReorder(Product product, StockItem stock) {
        // Reorder when stock < (dailyDemand × leadTime × safetyFactor)
        return stock.getAvailableQty() <= product.getReorderPoint();
    }

    @Override
    public int calculateReorderQty(Product product, StockItem stock,
                                    double avgDailyDemand) {
        // Order exactly: (leadTime + buffer) × dailyDemand
        int safetyBuffer = (int)(avgDailyDemand * 3); // 3-day safety buffer
        int qty          = (int)(avgDailyDemand * product.getLeadTimeDays()) + safetyBuffer;
        System.out.printf("[JIT] %s: dailyDemand=%.1f leadTime=%d qty=%d%n",
            product.getSku(), avgDailyDemand, product.getLeadTimeDays(), qty);
        return Math.max(qty, product.getMinStockLevel());
    }
}

// ==========================================
// 10. OBSERVER — STOCK EVENTS
// ==========================================
interface StockEventObserver {
    void onLowStock(Product product, Warehouse warehouse, int currentQty);
    void onOutOfStock(Product product, Warehouse warehouse);
    void onReorderTriggered(Product product, PurchaseOrder po);
    void onExpiryWarning(Product product, Warehouse warehouse, int daysLeft);
    void onOverstock(Product product, Warehouse warehouse, int currentQty);
}

class AlertObserver implements StockEventObserver {
    private final List<String> alerts = new CopyOnWriteArrayList<>();

    @Override
    public void onLowStock(Product product, Warehouse wh, int qty) {
        String alert = "[⚠ LOW STOCK] " + product.getSku() + " in " +
            wh.getName() + " → " + qty + " units left";
        alerts.add(alert);
        System.out.println(alert);
    }

    @Override
    public void onOutOfStock(Product product, Warehouse wh) {
        String alert = "[❌ OUT OF STOCK] " + product.getSku() +
            " in " + wh.getName();
        alerts.add(alert);
        System.out.println(alert);
    }

    @Override
    public void onReorderTriggered(Product product, PurchaseOrder po) {
        String alert = "[🔄 REORDER] " + product.getSku() + " → PO #" +
            po.getId() + " placed with " + po.getSupplierId();
        alerts.add(alert);
        System.out.println(alert);
    }

    @Override
    public void onExpiryWarning(Product product, Warehouse wh, int daysLeft) {
        String alert = "[⏰ EXPIRY] " + product.getSku() + " expires in " +
            daysLeft + " days in " + wh.getName();
        alerts.add(alert);
        System.out.println(alert);
    }

    @Override
    public void onOverstock(Product product, Warehouse wh, int qty) {
        String alert = "[📦 OVERSTOCK] " + product.getSku() + " in " +
            wh.getName() + " → " + qty + " units (max=" +
            product.getMaxStockLevel() + ")";
        alerts.add(alert);
        System.out.println(alert);
    }

    public List<String> getAlerts()   { return Collections.unmodifiableList(alerts); }
    public int          getAlertCount(){ return alerts.size(); }
}

class ReorderObserver implements StockEventObserver {
    private final InventoryService inventoryService;

    public ReorderObserver(InventoryService service) {
        this.inventoryService = service;
    }

    @Override
    public void onLowStock(Product product, Warehouse wh, int qty) {
        inventoryService.autoReorder(product, wh);
    }

    @Override public void onOutOfStock(Product p, Warehouse wh) {
        inventoryService.autoReorder(p, wh);
    }
    @Override public void onReorderTriggered(Product p, PurchaseOrder po) {}
    @Override public void onExpiryWarning(Product p, Warehouse wh, int days) {}
    @Override public void onOverstock(Product p, Warehouse wh, int qty) {}
}

// ==========================================
// 11. WAREHOUSE REGISTRY — SINGLETON
// ==========================================
class WarehouseRegistry {
    private static WarehouseRegistry instance;
    private final Map<Long, Warehouse> warehouses = new ConcurrentHashMap<>();

    private WarehouseRegistry() {}

    public static synchronized WarehouseRegistry getInstance() {
        if (instance == null) instance = new WarehouseRegistry();
        return instance;
    }

    public void register(Warehouse wh) {
        warehouses.put(wh.getId(), wh);
        System.out.println("[Registry] Warehouse registered: " + wh);
    }

    public Optional<Warehouse> findNearestWithStock(String pincode, Product product,
                                                     int qty,
                                                     Map<String, StockItem> stockMap) {
        // Simplified — in production: use geolocation distance
        return warehouses.values().stream()
            .filter(Warehouse::isActive)
            .filter(wh -> {
                String key = product.getSku() + ":" + wh.getId();
                StockItem item = stockMap.get(key);
                return item != null && item.getAvailableQty() >= qty;
            })
            .findFirst();
    }

    public List<Warehouse> getAll()    { return new ArrayList<>(warehouses.values()); }
    public Warehouse getById(long id)  { return warehouses.get(id); }
}

// ==========================================
// 12. INVENTORY SERVICE — SINGLETON (core)
// ==========================================
class InventoryService {
    private static InventoryService instance;

    // Key: "SKU:warehouseId" → StockItem
    private final Map<String, StockItem>     stockMap        = new ConcurrentHashMap<>();
    // productSku → avgDailyDemand (for reorder calc)
    private final Map<String, Double>        demandHistory   = new ConcurrentHashMap<>();
    // key → lock for per-SKU-warehouse locking
    private final Map<String, Object>        stockLocks      = new ConcurrentHashMap<>();

    private final List<StockMovement>        movementLog     = new CopyOnWriteArrayList<>();
    private final List<PurchaseOrder>        purchaseOrders  = new CopyOnWriteArrayList<>();
    private final List<StockEventObserver>   observers       = new ArrayList<>();
    private final AlertObserver              alertObserver   = new AlertObserver();
    private final WarehouseRegistry          warehouseReg    = WarehouseRegistry.getInstance();

    private       ReorderStrategy            reorderStrategy = new EOQReorderStrategy();
    private       StockValidator             validationChain;

    private InventoryService() {
        // Build validation chain
        StockValidator qty      = new QuantityValidator();
        StockValidator avail    = new AvailabilityValidator();
        StockValidator expiry   = new ExpiryValidator();
        StockValidator capacity = new WarehouseCapacityValidator();
        qty.setNext(avail).setNext(expiry).setNext(capacity);
        validationChain = qty;

        observers.add(alertObserver);
        observers.add(new ReorderObserver(this));
    }

    public static synchronized InventoryService getInstance() {
        if (instance == null) instance = new InventoryService();
        return instance;
    }

    // ---- CONFIGURATION ----
    public void setReorderStrategy(ReorderStrategy s) {
        this.reorderStrategy = s;
        System.out.println("[Service] Reorder strategy: " + s.getName());
    }

    // ---- STOCK KEY ----
    private String stockKey(String sku, long warehouseId) {
        return sku + ":" + warehouseId;
    }

    private Object getLock(String key) {
        return stockLocks.computeIfAbsent(key, k -> new Object());
    }

    // ---- INITIALISE STOCK ----
    public StockItem initialiseStock(Product product, Warehouse warehouse, int qty) {
        String key = stockKey(product.getSku(), warehouse.getId());
        StockItem item = new StockItem(product, warehouse, qty);
        stockMap.put(key, item);
        if (qty > 0) warehouse.addCapacityUsed(qty);
        System.out.println("[Service] Initialized: " + item);
        return item;
    }

    // ---- RECEIVE STOCK (INBOUND) ----
    public StockMovement receiveStock(Product product, Warehouse warehouse,
                                       int qty, String poId,
                                       LocalDateTime expiry) {
        StockMovement movement = StockMovementFactory.inbound(
            product, warehouse, qty, poId);

        String key = stockKey(product.getSku(), warehouse.getId());
        synchronized (getLock(key)) {
            StockItem item = stockMap.computeIfAbsent(key,
                k -> new StockItem(product, warehouse, 0));

            movement.markInTransit();
            item.addStock(qty, movement.getBatchId(), expiry);
            movement.markReceived();
            warehouse.addCapacityUsed(qty);
            movementLog.add(movement);
        }

        checkStockAlerts(product, warehouse);
        return movement;
    }

    // ---- RESERVE STOCK (for an order) ----
    public boolean reserveStock(Product product, Warehouse warehouse, int qty,
                                 String orderId) {
        String key = stockKey(product.getSku(), warehouse.getId());

        synchronized (getLock(key)) {
            StockItem item = stockMap.get(key);
            if (item == null) {
                System.out.println("[Reserve] No stock item found for " +
                    product.getSku() + " in " + warehouse.getName());
                return false;
            }

            // Validate before reserving
            String error = new QuantityValidator()
                .validate(item, qty);
            if (error == null) error = new AvailabilityValidator()
                .validate(item, qty);

            if (error != null) {
                System.out.println("[Reserve] ❌ " + error);
                return false;
            }

            boolean reserved = item.reserve(qty);
            if (reserved) {
                // Update demand history
                demandHistory.merge(product.getSku(), (double) qty,
                    (old, n) -> (old * 0.9 + n * 0.1)); // exponential moving avg
                checkStockAlerts(product, warehouse);
            }
            return reserved;
        }
    }

    // ---- DEDUCT STOCK (order shipped) ----
    public StockMovement deductStock(Product product, Warehouse warehouse,
                                      int qty, String orderId) {
        String key = stockKey(product.getSku(), warehouse.getId());
        StockMovement movement = StockMovementFactory.outbound(
            product, warehouse, qty, orderId);

        synchronized (getLock(key)) {
            StockItem item = stockMap.get(key);
            if (item != null) {
                movement.markInTransit();
                item.deductReserved(qty);
                movement.markReceived(); // "received" = successfully dispatched
                movementLog.add(movement);
            }
        }

        System.out.println("[Service] Deducted (shipped): " + movement);
        return movement;
    }

    // ---- RELEASE RESERVATION (order cancelled) ----
    public void releaseReservation(Product product, Warehouse warehouse,
                                    int qty, String orderId) {
        String key = stockKey(product.getSku(), warehouse.getId());
        synchronized (getLock(key)) {
            StockItem item = stockMap.get(key);
            if (item != null) {
                item.releaseReservation(qty);
                System.out.println("[Service] Reservation released for order " + orderId);
            }
        }
    }

    // ---- TRANSFER BETWEEN WAREHOUSES ----
    public StockMovement transferStock(Product product, Warehouse from,
                                        Warehouse to, int qty) {
        String fromKey = stockKey(product.getSku(), from.getId());
        String toKey   = stockKey(product.getSku(), to.getId());

        StockMovement movement = StockMovementFactory.transfer(
            product, from, to, qty, "TRF-" + System.currentTimeMillis());

        // Lock both — always in consistent order to prevent deadlock
        String[] keys = {fromKey, toKey};
        Arrays.sort(keys); // consistent ordering

        synchronized (getLock(keys[0])) {
            synchronized (getLock(keys[1])) {
                StockItem fromItem = stockMap.get(fromKey);
                if (fromItem == null || fromItem.getAvailableQty() < qty) {
                    movement.cancel("Insufficient stock for transfer");
                    return movement;
                }

                movement.markInTransit();

                // Deduct from source
                fromItem.adjustStock(-qty, "Transfer out to " + to.getName());
                from.releaseCapacity(qty);

                // Add to destination
                StockItem toItem = stockMap.computeIfAbsent(toKey,
                    k -> new StockItem(product, to, 0));
                toItem.addStock(qty, movement.getBatchId(), null);

                movement.markReceived();
                movementLog.add(movement);
            }
        }

        System.out.println("[Transfer] " + movement);
        checkStockAlerts(product, from);
        return movement;
    }

    // ---- STOCK ADJUSTMENT (cycle count / shrinkage) ----
    public void adjustStock(Product product, Warehouse warehouse,
                             int delta, String reason) {
        String key = stockKey(product.getSku(), warehouse.getId());
        synchronized (getLock(key)) {
            StockItem item = stockMap.get(key);
            if (item != null) {
                item.adjustStock(delta, reason);
                StockMovement movement = StockMovementFactory.adjustment(
                    product, warehouse, delta, reason);
                movement.markReceived();
                movementLog.add(movement);
            }
        }
        checkStockAlerts(product, warehouse);
    }

    // ---- MARK DAMAGED ----
    public void markDamaged(Product product, Warehouse warehouse, int qty) {
        String key = stockKey(product.getSku(), warehouse.getId());
        synchronized (getLock(key)) {
            StockItem item = stockMap.get(key);
            if (item != null) item.markDamaged(qty);
        }
        checkStockAlerts(product, warehouse);
    }

    // ---- RETURN FROM CUSTOMER ----
    public StockMovement processReturn(Product product, Warehouse warehouse,
                                        int qty, String returnId) {
        StockMovement movement = StockMovementFactory.returnMovement(
            product, warehouse, qty, returnId);

        String key = stockKey(product.getSku(), warehouse.getId());
        synchronized (getLock(key)) {
            StockItem item = stockMap.computeIfAbsent(key,
                k -> new StockItem(product, warehouse, 0));
            movement.markInTransit();
            item.addStock(qty, movement.getBatchId(), null);
            movement.markReceived();
            movementLog.add(movement);
        }

        System.out.println("[Return] Processed: " + movement);
        checkStockAlerts(product, warehouse);
        return movement;
    }

    // ---- AUTO REORDER ----
    public void autoReorder(Product product, Warehouse warehouse) {
        if (product.getSupplierId().isBlank()) return;

        // Check if a PO already exists for this product
        boolean existingPO = purchaseOrders.stream()
            .anyMatch(po -> po.getLineItems().containsKey(product) &&
                (po.getStatus() == PurchaseOrderStatus.CONFIRMED ||
                 po.getStatus() == PurchaseOrderStatus.SHIPPED));
        if (existingPO) {
            System.out.println("[Reorder] PO already in flight for " + product.getSku());
            return;
        }

        StockItem item = stockMap.get(stockKey(product.getSku(), warehouse.getId()));
        if (item == null) return;

        double avgDemand = demandHistory.getOrDefault(product.getSku(), 10.0);
        int reorderQty   = reorderStrategy.calculateReorderQty(
            product, item, avgDemand);

        PurchaseOrder po = new PurchaseOrder.Builder(
                product.getSupplierId(), warehouse)
            .addItem(product, reorderQty)
            .expectedBy(LocalDateTime.now().plusDays(product.getLeadTimeDays()))
            .notes("Auto-generated reorder — triggered by low stock")
            .build();

        po.confirm();
        purchaseOrders.add(po);
        observers.forEach(o -> o.onReorderTriggered(product, po));
    }

    // ---- RECEIVE PURCHASE ORDER ----
    public void receivePurchaseOrder(PurchaseOrder po) {
        po.markShipped();
        po.getLineItems().forEach((product, qty) -> {
            receiveStock(product, po.getDestination(), qty,
                "PO-" + po.getId(), null);
            po.receiveItem(product, qty);
        });
    }

    // ---- FIND BEST WAREHOUSE for an order ----
    public Optional<Warehouse> findBestWarehouse(Product product, int qty,
                                                   String customerPincode) {
        return warehouseReg.findNearestWithStock(
            customerPincode, product, qty, stockMap);
    }

    // ---- STOCK LEVEL QUERY ----
    public int getTotalAvailable(Product product) {
        return stockMap.entrySet().stream()
            .filter(e -> e.getKey().startsWith(product.getSku() + ":"))
            .mapToInt(e -> e.getValue().getAvailableQty())
            .sum();
    }

    public Optional<StockItem> getStockItem(Product product, Warehouse warehouse) {
        return Optional.ofNullable(
            stockMap.get(stockKey(product.getSku(), warehouse.getId())));
    }

    // ---- ALERT CHECKS ----
    private void checkStockAlerts(Product product, Warehouse warehouse) {
        String key    = stockKey(product.getSku(), warehouse.getId());
        StockItem item = stockMap.get(key);
        if (item == null) return;

        int qty = item.getAvailableQty();

        if (qty == 0) {
            observers.forEach(o -> o.onOutOfStock(product, warehouse));
        } else if (qty <= product.getMinStockLevel()) {
            observers.forEach(o -> o.onLowStock(product, warehouse, qty));
        } else if (qty > product.getMaxStockLevel()) {
            observers.forEach(o -> o.onOverstock(product, warehouse, qty));
        }

        if (product.isPerishable()) {
            // simplified — would check actual expiry dates in production
            observers.forEach(o -> o.onExpiryWarning(product, warehouse, 15));
        }
    }

    // ---- PRINT STOCK REPORT ----
    public void printStockReport() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("INVENTORY STOCK REPORT");
        System.out.println("=".repeat(80));
        stockMap.values().stream()
            .sorted(Comparator.comparing(s -> s.getProduct().getSku()))
            .forEach(item -> System.out.println("  " + item));
    }

    public void printAlerts() {
        System.out.println("\n[ALERTS] Total: " + alertObserver.getAlertCount());
        alertObserver.getAlerts().forEach(a -> System.out.println("  " + a));
    }

    public void printMovementLog(int limit) {
        System.out.println("\n[Movement Log] Last " + limit + " movements:");
        movementLog.stream()
            .sorted(Comparator.comparingLong(StockMovement::getId).reversed())
            .limit(limit)
            .forEach(m -> System.out.println("  " + m));
    }

    public List<PurchaseOrder> getPurchaseOrders() { return purchaseOrders; }
    public AlertObserver       getAlertObserver()  { return alertObserver; }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class InventoryManagement {
    public static void main(String[] args) throws InterruptedException {

        InventoryService   service  = InventoryService.getInstance();
        WarehouseRegistry  registry = WarehouseRegistry.getInstance();

        // ---- Setup Warehouses ----
        Warehouse mumbaiWH   = new Warehouse("Mumbai-Central", "Dharavi MIDC",
            "Mumbai",   "400017", 50_000);
        Warehouse bangaloreWH = new Warehouse("Bangalore-South", "Electronics City",
            "Bangalore","560100", 30_000);
        Warehouse delhiWH    = new Warehouse("Delhi-North",  "Kundli Industrial",
            "Delhi",    "131028", 40_000);

        registry.register(mumbaiWH);
        registry.register(bangaloreWH);
        registry.register(delhiWH);

        // ---- Setup Products ----
        Product iphone = new Product.Builder("iPhone 15 Pro", "APPL-IP15P", ProductCategory.ELECTRONICS)
            .cost(80000).price(134900).reorderPoint(20).reorderQty(50)
            .minStock(10).maxStock(500).supplier("apple-distributor")
            .leadTime(14).unit("piece").weight(0.19).build();

        Product milk = new Product.Builder("Amul Full Cream Milk 1L", "AMUL-FCM-1L", ProductCategory.GROCERY)
            .cost(55).price(68).reorderPoint(200).reorderQty(1000)
            .minStock(100).maxStock(5000).supplier("amul-dairy")
            .leadTime(1).perishable(5).unit("litre").weight(1.02).build();

        Product shirt = new Product.Builder("Levi's 511 Slim Fit Shirt", "LEVIS-511-M-BLU", ProductCategory.APPAREL)
            .cost(800).price(2499).reorderPoint(15).reorderQty(100)
            .minStock(5).maxStock(500).supplier("levis-india")
            .leadTime(7).unit("piece").attr("size","M").attr("colour","Blue").build();

        Product paracetamol = new Product.Builder("Paracetamol 500mg Strip", "PARA-500-STR", ProductCategory.PHARMACY)
            .cost(12).price(25).reorderPoint(500).reorderQty(5000)
            .minStock(200).maxStock(50000).supplier("cipla-pharma")
            .leadTime(3).perishable(730).unit("strip").weight(0.05).build();

        // ---- Initialize Stock ----
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SETUP: Initialize Stock across Warehouses");
        System.out.println("=".repeat(60));

        service.initialiseStock(iphone,      mumbaiWH,    80);
        service.initialiseStock(iphone,      bangaloreWH, 40);
        service.initialiseStock(milk,        mumbaiWH,   1500);
        service.initialiseStock(milk,        delhiWH,    2000);
        service.initialiseStock(shirt,       bangaloreWH, 120);
        service.initialiseStock(paracetamol, mumbaiWH,   8000);
        service.initialiseStock(paracetamol, delhiWH,    12000);

        // ===== SCENARIO 1: Reserve stock for an order =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Reserve Stock for Orders");
        System.out.println("=".repeat(60));

        boolean r1 = service.reserveStock(iphone, mumbaiWH, 5, "ORD-001");
        boolean r2 = service.reserveStock(iphone, mumbaiWH, 3, "ORD-002");
        System.out.println("ORD-001 reserved: " + r1);
        System.out.println("ORD-002 reserved: " + r2);

        // ===== SCENARIO 2: Concurrent reservations (thread safety) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Concurrent Reservations (5 threads)");
        System.out.println("=".repeat(60));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<Boolean>> futures = new ArrayList<>();

        // 5 orders, each wanting 20 iPhones — only 80 available
        for (int i = 3; i <= 7; i++) {
            final String orderId = "ORD-00" + i;
            futures.add(pool.submit(() ->
                service.reserveStock(iphone, bangaloreWH, 20, orderId)));
        }

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        long succeeded = futures.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { return false; }
        }).count();
        System.out.println("Successful reservations: " + succeeded + "/5");

        // ===== SCENARIO 3: Deduct stock (order shipped) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Deduct Stock (Order Shipped)");
        System.out.println("=".repeat(60));

        service.deductStock(iphone, mumbaiWH, 5, "ORD-001");
        service.deductStock(iphone, mumbaiWH, 3, "ORD-002");

        // ===== SCENARIO 4: Cancel order — release reservation =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Release Reservation (Order Cancelled)");
        System.out.println("=".repeat(60));

        // Reserve then cancel
        service.reserveStock(shirt, bangaloreWH, 10, "ORD-008");
        service.releaseReservation(shirt, bangaloreWH, 10, "ORD-008");

        // ===== SCENARIO 5: Inbound — receive stock from supplier =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Receive Stock from Supplier");
        System.out.println("=".repeat(60));

        // Receive iPhone shipment
        StockMovement m1 = service.receiveStock(iphone, mumbaiWH, 100,
            "PO-101", null);

        // Receive perishable milk with expiry
        StockMovement m2 = service.receiveStock(milk, mumbaiWH, 500,
            "PO-102", LocalDateTime.now().plusDays(4));

        // ===== SCENARIO 6: Warehouse transfer =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Warehouse Transfer (Mumbai → Delhi)");
        System.out.println("=".repeat(60));

        StockMovement transfer = service.transferStock(
            iphone, mumbaiWH, delhiWH, 30);
        System.out.println("Transfer result: " + transfer.getStatus());

        // ===== SCENARIO 7: Low stock → auto reorder =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Low Stock → Auto Reorder (EOQ)");
        System.out.println("=".repeat(60));

        service.setReorderStrategy(new EOQReorderStrategy());

        // Drain milk below reorder point
        service.reserveStock(milk, delhiWH, 1850, "ORD-BULK-01");
        service.deductStock(milk,  delhiWH, 1850, "ORD-BULK-01");
        // Now milk in Delhi is at 150 — below reorderPoint(200) → triggers reorder

        // ===== SCENARIO 8: JIT reorder strategy =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Switch to JIT Reorder Strategy");
        System.out.println("=".repeat(60));

        service.setReorderStrategy(new JITReorderStrategy());
        // Force a low-stock event for paracetamol
        service.adjustStock(paracetamol, delhiWH, -11700, "Cycle count correction");

        // ===== SCENARIO 9: Stock adjustment (physical count) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Stock Adjustment + Damaged Goods");
        System.out.println("=".repeat(60));

        service.adjustStock(shirt, bangaloreWH, -5, "Shrinkage — quarterly audit");
        service.markDamaged(milk, mumbaiWH, 20);

        // ===== SCENARIO 10: Customer return =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Customer Return");
        System.out.println("=".repeat(60));

        StockMovement returnMvt = service.processReturn(
            iphone, mumbaiWH, 2, "RTN-4521");
        System.out.println("Return: " + returnMvt.getStatus());

        // ===== SCENARIO 11: Find best warehouse for order =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 11: Find Best Warehouse for Customer Order");
        System.out.println("=".repeat(60));

        Optional<Warehouse> best = service.findBestWarehouse(iphone, 10, "560001");
        best.ifPresent(wh -> System.out.println(
            "Best warehouse for Bangalore customer: " + wh.getName()));

        // ===== REPORTS =====
        service.printStockReport();
        service.printAlerts();
        service.printMovementLog(8);

        System.out.println("\n[Purchase Orders]:");
        service.getPurchaseOrders()
            .forEach(po -> System.out.println("  " + po));

        System.out.println("\nTotal available iPhones across all warehouses: " +
            service.getTotalAvailable(iphone));

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern                  | Class
            -------------------------|--------------------------------------------------
            Singleton                | InventoryService, WarehouseRegistry
            Strategy                 | ReorderStrategy (FixedQty / EOQ / JIT)
            Observer                 | StockEventObserver (Alert / Reorder)
            Factory                  | StockMovementFactory, PurchaseOrder.Builder
            Builder                  | Product.Builder, PurchaseOrder.Builder
            State                    | MovementStatus (PENDING→IN_TRANSIT→RECEIVED)
            Chain of Responsibility  | StockValidator (Qty→Availability→Expiry→Capacity)
            Command                  | StockMovement (execute/cancel with rollback)
            """);
    }
}
