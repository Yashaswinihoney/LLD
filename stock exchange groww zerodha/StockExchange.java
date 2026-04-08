import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// STOCK EXCHANGE LLD — GROWW / ZERODHA
// Patterns:
//   Singleton  — ExchangeEngine, PortfolioService
//   Strategy   — OrderMatchingStrategy (price-time priority)
//   Factory    — OrderFactory (market/limit/stop-loss/AMO)
//   Builder    — Order construction
//   Observer   — TradeEventObserver (portfolio, notification, ledger)
//   State      — OrderStatus (PENDING→PARTIAL→FILLED→CANCELLED)
//   Command    — Order as executable command (execute/cancel/modify)
//   Template   — OrderBook per instrument (bid/ask sorted trees)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum OrderType     { MARKET, LIMIT, STOP_LOSS, STOP_LOSS_MARKET, AMO }
enum OrderSide     { BUY, SELL }
enum OrderStatus   { PENDING, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED, EXPIRED }
enum ProductType   { CNC, MIS, NRML } // CNC=delivery, MIS=intraday, NRML=overnight
enum Exchange      { NSE, BSE }
enum TradeType     { EQUITY, FUTURES, OPTIONS }
enum SegmentType   { EQUITY, DERIVATIVES, CURRENCY, COMMODITY }

// ==========================================
// 2. MONEY — value object (paise precision)
// ==========================================
class Price implements Comparable<Price> {
    private final long    paise;   // 1 rupee = 100 paise

    public Price(double rupees)     { this.paise = Math.round(rupees * 100); }
    private Price(long paise)       { this.paise = paise; }

    public Price add(Price other)   { return new Price(paise + other.paise); }
    public Price multiply(long qty) { return new Price(paise * qty); }

    public boolean isGreaterThan(Price o) { return paise > o.paise; }
    public boolean isLessThan(Price o)    { return paise < o.paise; }
    public boolean isZero()               { return paise == 0; }

    public double  toRupees()       { return paise / 100.0; }
    public long    getPaise()       { return paise; }

    @Override public int compareTo(Price o) { return Long.compare(paise, o.paise); }
    @Override public boolean equals(Object o) {
        return o instanceof Price p && paise == p.paise;
    }
    @Override public String toString() {
        return "₹" + String.format("%.2f", toRupees());
    }
}

// ==========================================
// 3. INSTRUMENT (Stock/Futures/Options)
// ==========================================
class Instrument {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final long         id;
    private final String       symbol;        // RELIANCE, NIFTY50, etc.
    private final String       name;
    private final Exchange     exchange;
    private final TradeType    tradeType;
    private final SegmentType  segment;
    private       Price        lastTradedPrice;
    private       Price        openPrice;
    private       Price        highPrice;
    private       Price        lowPrice;
    private       Price        closePrice;    // previous day close
    private       long         totalVolume;
    private final Price        tickSize;      // min price movement
    private final long         lotSize;       // min order qty
    private       Price        upperCircuit;  // max price allowed today
    private       Price        lowerCircuit;  // min price allowed today

    public Instrument(String symbol, String name, Exchange exchange,
                      double lastPrice, double tickSize) {
        this.id             = idGen.getAndIncrement();
        this.symbol         = symbol;
        this.name           = name;
        this.exchange       = exchange;
        this.tradeType      = TradeType.EQUITY;
        this.segment        = SegmentType.EQUITY;
        this.lastTradedPrice = new Price(lastPrice);
        this.openPrice      = new Price(lastPrice);
        this.highPrice      = new Price(lastPrice);
        this.lowPrice       = new Price(lastPrice);
        this.closePrice     = new Price(lastPrice * 0.99); // simulate prev close
        this.tickSize       = new Price(tickSize);
        this.lotSize        = 1;
        this.upperCircuit   = new Price(lastPrice * 1.20); // 20% up
        this.lowerCircuit   = new Price(lastPrice * 0.80); // 20% down
    }

    public synchronized void updateLastTraded(Price price, long qty) {
        lastTradedPrice = price;
        totalVolume    += qty;
        if (price.isGreaterThan(highPrice)) highPrice = price;
        if (price.isLessThan(lowPrice))     lowPrice  = price;
    }

    public boolean isPriceWithinCircuit(Price price) {
        return !price.isLessThan(lowerCircuit) && !price.isGreaterThan(upperCircuit);
    }

    public long     getId()                { return id; }
    public String   getSymbol()            { return symbol; }
    public String   getName()              { return name; }
    public Exchange getExchange()          { return exchange; }
    public Price    getLastTradedPrice()   { return lastTradedPrice; }
    public Price    getOpenPrice()         { return openPrice; }
    public Price    getHighPrice()         { return highPrice; }
    public Price    getLowPrice()          { return lowPrice; }
    public Price    getClosePrice()        { return closePrice; }
    public long     getTotalVolume()       { return totalVolume; }
    public Price    getUpperCircuit()      { return upperCircuit; }
    public Price    getLowerCircuit()      { return lowerCircuit; }

    @Override public String toString() {
        double change = lastTradedPrice.toRupees() - closePrice.toRupees();
        double changePct = (change / closePrice.toRupees()) * 100;
        return String.format("%-12s LTP:%-10s H:%-10s L:%-10s Vol:%d %+.2f%%",
            symbol, lastTradedPrice, highPrice, lowPrice, totalVolume, changePct);
    }
}

// ==========================================
// 4. ORDER — BUILDER + STATE + COMMAND
// ==========================================
class Order {
    private static final AtomicLong idGen = new AtomicLong(1_000_000);

    private final  long          orderId;
    private final  String        userId;
    private final  Instrument    instrument;
    private final  OrderType     type;
    private final  OrderSide     side;
    private final  ProductType   product;
    private final  long          quantity;
    private final  Price         limitPrice;    // null for MARKET orders
    private final  Price         triggerPrice;  // for STOP_LOSS orders
    private        OrderStatus   status;
    private        long          filledQty;
    private        long          pendingQty;
    private        Price         avgTradePrice;
    private final  LocalDateTime placedAt;
    private        LocalDateTime updatedAt;
    private final  String        idempotencyKey; // prevent duplicate orders
    private        String        rejectReason;

    private Order(Builder b) {
        this.orderId        = idGen.getAndIncrement();
        this.userId         = b.userId;
        this.instrument     = b.instrument;
        this.type           = b.type;
        this.side           = b.side;
        this.product        = b.product;
        this.quantity       = b.quantity;
        this.limitPrice     = b.limitPrice;
        this.triggerPrice   = b.triggerPrice;
        this.status         = OrderStatus.PENDING;
        this.filledQty      = 0;
        this.pendingQty     = b.quantity;
        this.avgTradePrice  = new Price(0);
        this.placedAt       = LocalDateTime.now();
        this.updatedAt      = LocalDateTime.now();
        this.idempotencyKey = b.idempotencyKey != null
            ? b.idempotencyKey : UUID.randomUUID().toString();
    }

    // ---- State transitions ----
    public synchronized void fill(long qty, Price tradePrice) {
        filledQty  += qty;
        pendingQty -= qty;

        // Update weighted average trade price
        long totalPaise = avgTradePrice.getPaise() * (filledQty - qty) +
                          tradePrice.getPaise() * qty;
        avgTradePrice = new Price((double) totalPaise / filledQty);

        status    = pendingQty == 0
            ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        updatedAt = LocalDateTime.now();

        System.out.printf("[Order #%d] Filled %d @ %s | Total filled: %d/%d | Avg: %s%n",
            orderId, qty, tradePrice, filledQty, quantity, avgTradePrice);
    }

    public synchronized void cancel() {
        if (status == OrderStatus.PENDING || status == OrderStatus.PARTIALLY_FILLED) {
            status    = OrderStatus.CANCELLED;
            updatedAt = LocalDateTime.now();
            System.out.println("[Order #" + orderId + "] CANCELLED | Filled: " + filledQty);
        }
    }

    public synchronized void reject(String reason) {
        status         = OrderStatus.REJECTED;
        rejectReason   = reason;
        updatedAt      = LocalDateTime.now();
        System.out.println("[Order #" + orderId + "] REJECTED: " + reason);
    }

    // Is this limit order matchable against a given price?
    public boolean isMatchable(Price marketPrice) {
        if (type == OrderType.MARKET) return true;
        if (type == OrderType.LIMIT) {
            return side == OrderSide.BUY
                ? !marketPrice.isGreaterThan(limitPrice)   // buy: price ≤ limit
                : !marketPrice.isLessThan(limitPrice);     // sell: price ≥ limit
        }
        return false;
    }

    public long       getOrderId()       { return orderId; }
    public String     getUserId()        { return userId; }
    public Instrument getInstrument()    { return instrument; }
    public OrderType  getType()          { return type; }
    public OrderSide  getSide()          { return side; }
    public ProductType getProduct()      { return product; }
    public long       getQuantity()      { return quantity; }
    public long       getPendingQty()    { return pendingQty; }
    public long       getFilledQty()     { return filledQty; }
    public Price      getLimitPrice()    { return limitPrice; }
    public Price      getTriggerPrice()  { return triggerPrice; }
    public OrderStatus getStatus()       { return status; }
    public Price      getAvgTradePrice() { return avgTradePrice; }
    public LocalDateTime getPlacedAt()  { return placedAt; }
    public String     getIdempotencyKey(){ return idempotencyKey; }

    @Override public String toString() {
        return String.format("Order[#%d | %s %s %d %s @ %s | %s | %s]",
            orderId, side, instrument.getSymbol(), quantity, type,
            limitPrice != null ? limitPrice : "MARKET",
            product, status);
    }

    // ---- BUILDER ----
    static class Builder {
        private final String     userId;
        private final Instrument instrument;
        private final OrderSide  side;
        private final long       quantity;
        private       OrderType  type          = OrderType.LIMIT;
        private       ProductType product      = ProductType.CNC;
        private       Price      limitPrice;
        private       Price      triggerPrice;
        private       String     idempotencyKey;

        public Builder(String userId, Instrument instrument,
                       OrderSide side, long quantity) {
            this.userId     = userId;
            this.instrument = instrument;
            this.side       = side;
            this.quantity   = quantity;
        }
        public Builder type(OrderType t)        { this.type = t;           return this; }
        public Builder product(ProductType p)   { this.product = p;        return this; }
        public Builder limitPrice(double price) { this.limitPrice = new Price(price); return this; }
        public Builder triggerPrice(double p)   { this.triggerPrice = new Price(p); return this; }
        public Builder idempotencyKey(String k) { this.idempotencyKey = k; return this; }
        public Order   build()                  { return new Order(this); }
    }
}

// ==========================================
// 5. ORDER FACTORY
// ==========================================
class OrderFactory {
    public static Order marketBuy(String userId, Instrument inst, long qty) {
        return new Order.Builder(userId, inst, OrderSide.BUY, qty)
            .type(OrderType.MARKET).build();
    }

    public static Order marketSell(String userId, Instrument inst, long qty) {
        return new Order.Builder(userId, inst, OrderSide.SELL, qty)
            .type(OrderType.MARKET).build();
    }

    public static Order limitBuy(String userId, Instrument inst,
                                  long qty, double price) {
        return new Order.Builder(userId, inst, OrderSide.BUY, qty)
            .type(OrderType.LIMIT).limitPrice(price).build();
    }

    public static Order limitSell(String userId, Instrument inst,
                                   long qty, double price) {
        return new Order.Builder(userId, inst, OrderSide.SELL, qty)
            .type(OrderType.LIMIT).limitPrice(price).build();
    }

    public static Order stopLoss(String userId, Instrument inst,
                                  OrderSide side, long qty,
                                  double triggerPrice, double limitPrice) {
        return new Order.Builder(userId, inst, side, qty)
            .type(OrderType.STOP_LOSS)
            .triggerPrice(triggerPrice)
            .limitPrice(limitPrice)
            .build();
    }

    public static Order amo(String userId, Instrument inst,
                             OrderSide side, long qty, double price) {
        // After Market Order — placed outside trading hours
        return new Order.Builder(userId, inst, side, qty)
            .type(OrderType.AMO).limitPrice(price).build();
    }
}

// ==========================================
// 6. TRADE — result of a match
// ==========================================
class Trade {
    private static final AtomicLong idGen = new AtomicLong(500_000);
    private final long          tradeId;
    private final Order         buyOrder;
    private final Order         sellOrder;
    private final Instrument    instrument;
    private final long          quantity;
    private final Price         price;
    private final LocalDateTime executedAt;

    public Trade(Order buyOrder, Order sellOrder, long quantity, Price price) {
        this.tradeId    = idGen.getAndIncrement();
        this.buyOrder   = buyOrder;
        this.sellOrder  = sellOrder;
        this.instrument = buyOrder.getInstrument();
        this.quantity   = quantity;
        this.price      = price;
        this.executedAt = LocalDateTime.now();
    }

    public long       getTradeId()   { return tradeId; }
    public Order      getBuyOrder()  { return buyOrder; }
    public Order      getSellOrder() { return sellOrder; }
    public Instrument getInstrument(){ return instrument; }
    public long       getQuantity()  { return quantity; }
    public Price      getPrice()     { return price; }

    @Override public String toString() {
        return String.format("Trade[#%d | %s | %d @ %s | Buy#%d → Sell#%d]",
            tradeId, instrument.getSymbol(), quantity, price,
            buyOrder.getOrderId(), sellOrder.getOrderId());
    }
}

// ==========================================
// 7. ORDER BOOK — PRICE-TIME PRIORITY
// Core data structure of any exchange
// Bids: sorted descending (highest bid first)
// Asks: sorted ascending  (lowest ask first)
// ==========================================
class OrderBook {
    private final Instrument instrument;

    // BUY orders: sorted by price DESC, then by time ASC (price-time priority)
    // Key = Price, Value = Queue<Order> (FIFO at same price level)
    private final TreeMap<Price, Queue<Order>> bids =
        new TreeMap<>(Comparator.reverseOrder()); // highest bid first

    private final TreeMap<Price, Queue<Order>> asks =
        new TreeMap<>(); // lowest ask first

    // Stop loss orders waiting for trigger
    private final List<Order> stopOrders = new CopyOnWriteArrayList<>();

    public OrderBook(Instrument instrument) {
        this.instrument = instrument;
    }

    // Add order to the appropriate side
    public void addOrder(Order order) {
        if (order.getType() == OrderType.STOP_LOSS ||
            order.getType() == OrderType.STOP_LOSS_MARKET) {
            stopOrders.add(order);
            System.out.println("[OrderBook:" + instrument.getSymbol() +
                "] Stop order added: #" + order.getOrderId());
            return;
        }

        if (order.getSide() == OrderSide.BUY) {
            Price key = order.getType() == OrderType.MARKET
                ? new Price(Double.MAX_VALUE / 2) // market buy goes to front
                : order.getLimitPrice();
            bids.computeIfAbsent(key, k -> new ArrayDeque<>()).offer(order);
        } else {
            Price key = order.getType() == OrderType.MARKET
                ? new Price(0) // market sell goes to front (lowest price)
                : order.getLimitPrice();
            asks.computeIfAbsent(key, k -> new ArrayDeque<>()).offer(order);
        }
    }

    // Remove order (on cancel)
    public void removeOrder(Order order) {
        TreeMap<Price, Queue<Order>> book =
            order.getSide() == OrderSide.BUY ? bids : asks;
        Price key = order.getLimitPrice();
        if (key != null && book.containsKey(key)) {
            book.get(key).remove(order);
            if (book.get(key).isEmpty()) book.remove(key);
        }
    }

    // Check if stop orders should trigger
    public List<Order> checkStopTriggers(Price currentPrice) {
        List<Order> triggered = new ArrayList<>();
        Iterator<Order> it = stopOrders.iterator();
        while (it.hasNext()) {
            Order stop = it.next();
            Price trigger = stop.getTriggerPrice();
            boolean shouldTrigger =
                (stop.getSide() == OrderSide.SELL &&
                 !currentPrice.isGreaterThan(trigger)) ||  // sell stop: price fell below trigger
                (stop.getSide() == OrderSide.BUY  &&
                 !currentPrice.isLessThan(trigger));       // buy stop: price rose above trigger

            if (shouldTrigger) {
                triggered.add(stop);
                it.remove();
                System.out.println("[StopTrigger] " + stop.getOrderId() +
                    " triggered at " + currentPrice);
            }
        }
        return triggered;
    }

    public TreeMap<Price, Queue<Order>> getBids() { return bids; }
    public TreeMap<Price, Queue<Order>> getAsks() { return asks; }

    // Best bid = highest buy price
    public Optional<Price> getBestBid() {
        return bids.isEmpty() ? Optional.empty()
            : Optional.of(bids.firstKey());
    }

    // Best ask = lowest sell price
    public Optional<Price> getBestAsk() {
        return asks.isEmpty() ? Optional.empty()
            : Optional.of(asks.firstKey());
    }

    // Mid price (for market display)
    public Optional<Price> getMidPrice() {
        if (bids.isEmpty() || asks.isEmpty()) return Optional.empty();
        long mid = (bids.firstKey().getPaise() + asks.firstKey().getPaise()) / 2;
        return Optional.of(new Price((double) mid / 100));
    }

    public void printDepth(int levels) {
        System.out.println("\n[OrderBook: " + instrument.getSymbol() + "] Market Depth");
        System.out.println("  SELL (Asks) — top " + levels + ":");
        asks.entrySet().stream().limit(levels).forEach(e ->
            System.out.printf("    %-10s  qty=%d%n",
                e.getKey(), e.getValue().stream().mapToLong(Order::getPendingQty).sum()));
        System.out.println("  ---- Spread ----");
        System.out.println("  BUY (Bids) — top " + levels + ":");
        bids.entrySet().stream().limit(levels).forEach(e ->
            System.out.printf("    %-10s  qty=%d%n",
                e.getKey(), e.getValue().stream().mapToLong(Order::getPendingQty).sum()));
    }
}

// ==========================================
// 8. MATCHING ENGINE — STRATEGY PATTERN
// Price-Time Priority (standard exchange rule)
// ==========================================
interface MatchingStrategy {
    String getName();
    List<Trade> match(Order incoming, OrderBook book);
}

class PriceTimePriorityStrategy implements MatchingStrategy {
    @Override public String getName() { return "Price-Time Priority (FIFO)"; }

    @Override
    public List<Trade> match(Order incoming, OrderBook book) {
        List<Trade> trades = new ArrayList<>();

        // Get the opposite side of the book
        TreeMap<Price, Queue<Order>> oppositeBook =
            incoming.getSide() == OrderSide.BUY ? book.getAsks() : book.getBids();

        while (incoming.getPendingQty() > 0 && !oppositeBook.isEmpty()) {
            // Best opposite price
            Map.Entry<Price, Queue<Order>> bestLevel =
                incoming.getSide() == OrderSide.BUY
                    ? oppositeBook.firstEntry()   // lowest ask
                    : oppositeBook.firstEntry();  // highest bid (first since reversed)

            Price matchPrice = bestLevel.getKey();

            // Check if prices cross (can trade happen?)
            boolean pricesCross;
            if (incoming.getType() == OrderType.MARKET) {
                pricesCross = true; // market orders always match
            } else if (incoming.getSide() == OrderSide.BUY) {
                pricesCross = !incoming.getLimitPrice().isLessThan(matchPrice); // buy limit ≥ ask
            } else {
                pricesCross = !incoming.getLimitPrice().isGreaterThan(matchPrice); // sell limit ≤ bid
            }

            if (!pricesCross) break; // no more matches possible

            Queue<Order> ordersAtLevel = bestLevel.getValue();

            while (!ordersAtLevel.isEmpty() && incoming.getPendingQty() > 0) {
                Order resting = ordersAtLevel.peek();

                // Trade at resting order's price (price-time priority)
                Price tradePrice = (resting.getType() == OrderType.MARKET)
                    ? matchPrice : matchPrice;

                long tradeQty = Math.min(incoming.getPendingQty(),
                                         resting.getPendingQty());

                // Execute the trade
                incoming.fill(tradeQty, tradePrice);
                resting.fill(tradeQty, tradePrice);

                Trade trade = incoming.getSide() == OrderSide.BUY
                    ? new Trade(incoming, resting, tradeQty, tradePrice)
                    : new Trade(resting, incoming, tradeQty, tradePrice);

                trades.add(trade);

                System.out.println("[Engine] MATCHED: " + trade);

                // Remove fully filled resting order
                if (resting.getStatus() == OrderStatus.FILLED) {
                    ordersAtLevel.poll();
                }
            }

            // Clean up empty price level
            if (ordersAtLevel.isEmpty()) {
                oppositeBook.remove(matchPrice);
            }
        }

        return trades;
    }
}

// ==========================================
// 9. OBSERVER — TRADE EVENTS
// ==========================================
interface TradeEventObserver {
    void onTrade(Trade trade);
    void onOrderPlaced(Order order);
    void onOrderCancelled(Order order);
    // NEW: fired for all REJECTED orders — risk failure, margin block, or market no-liquidity
    void onOrderRejected(Order order, String reason);
}

class PortfolioUpdater implements TradeEventObserver {
    private final PortfolioService portfolioService;
    public PortfolioUpdater(PortfolioService ps) { this.portfolioService = ps; }

    @Override
    public void onTrade(Trade trade) {
        portfolioService.applyTrade(trade);
    }

    @Override public void onOrderPlaced(Order o) {}
    @Override public void onOrderCancelled(Order o) {}

    @Override
    public void onOrderRejected(Order o, String reason) {
        // For BUY orders: margin was never blocked if rejected at risk check.
        // For "Margin block failed" path: blockMargin() already returned false — nothing to release.
        // No-op here; the portfolio state is already consistent.
        System.out.println("[Portfolio] Order #" + o.getOrderId() +
            " rejected — no margin change needed.");
    }
}

class MarketDataPublisher implements TradeEventObserver {
    @Override
    public void onTrade(Trade trade) {
        trade.getInstrument().updateLastTraded(trade.getPrice(), trade.getQuantity());
        System.out.println("[MarketData] " + trade.getInstrument());
    }

    @Override public void onOrderPlaced(Order o) {}
    @Override public void onOrderCancelled(Order o) {}

    @Override
    public void onOrderRejected(Order o, String reason) {
        // Rejected orders don't affect LTP or market depth — log only.
        System.out.println("[MarketData] Order #" + o.getOrderId() +
            " rejected (" + reason + ") — no market data update.");
    }
}

class NotificationService implements TradeEventObserver {
    @Override
    public void onTrade(Trade trade) {
        System.out.println("[Notif → " + trade.getBuyOrder().getUserId() + "] " +
            "Your BUY order #" + trade.getBuyOrder().getOrderId() +
            " filled " + trade.getQuantity() + " @ " + trade.getPrice());
        System.out.println("[Notif → " + trade.getSellOrder().getUserId() + "] " +
            "Your SELL order #" + trade.getSellOrder().getOrderId() +
            " filled " + trade.getQuantity() + " @ " + trade.getPrice());
    }

    @Override
    public void onOrderPlaced(Order o) {
        System.out.println("[Notif → " + o.getUserId() + "] " +
            "Order #" + o.getOrderId() + " placed: " + o.getSide() +
            " " + o.getQuantity() + " " + o.getInstrument().getSymbol());
    }

    @Override
    public void onOrderCancelled(Order o) {
        System.out.println("[Notif → " + o.getUserId() + "] " +
            "Order #" + o.getOrderId() + " CANCELLED" +
            (o.getFilledQty() > 0
                ? " | Partial fill: " + o.getFilledQty() + "/" + o.getQuantity() + " @ " + o.getAvgTradePrice()
                : " | Nothing was filled"));
    }

    // NEW: push rejection reason to the user immediately
    @Override
    public void onOrderRejected(Order o, String reason) {
        System.out.println("[Notif → " + o.getUserId() + "] " +
            "Order #" + o.getOrderId() + " REJECTED — " + reason +
            " | " + o.getSide() + " " + o.getQuantity() +
            " " + o.getInstrument().getSymbol());
    }
}

// ==========================================
// 10. PORTFOLIO
// ==========================================
class Position {
    private final String     userId;
    private final Instrument instrument;
    private       long       quantity;
    private       Price      avgBuyPrice;
    private       Price      currentValue;
    private       long       totalBoughtQty;
    private       long       totalSoldQty;

    public Position(String userId, Instrument instrument) {
        this.userId     = userId;
        this.instrument = instrument;
        this.quantity   = 0;
        this.avgBuyPrice = new Price(0);
    }

    public synchronized void applyBuy(long qty, Price price) {
        long   newQty   = quantity + qty;
        long   newPaise = avgBuyPrice.getPaise() * quantity + price.getPaise() * qty;
        avgBuyPrice     = new Price((double) newPaise / newQty);
        quantity        = newQty;
        totalBoughtQty += qty;
    }

    public synchronized void applySell(long qty) {
        quantity      -= qty;
        totalSoldQty  += qty;
        if (quantity < 0) quantity = 0;
    }

    public Price getUnrealizedPnL() {
        Price ltp = instrument.getLastTradedPrice();
        long pnlPaise = (ltp.getPaise() - avgBuyPrice.getPaise()) * quantity;
        return new Price((double) pnlPaise / 100);
    }

    public long     getQuantity()   { return quantity; }
    public Price    getAvgBuyPrice(){ return avgBuyPrice; }
    public Instrument getInstrument(){ return instrument; }

    @Override public String toString() {
        return String.format("Position[%-12s | qty=%-5d | avgBuy=%-10s | LTP=%-10s | PnL=%s]",
            instrument.getSymbol(), quantity, avgBuyPrice,
            instrument.getLastTradedPrice(), getUnrealizedPnL());
    }
}

// ==========================================
// 11. PORTFOLIO SERVICE — SINGLETON
// ==========================================
class PortfolioService {
    private static PortfolioService instance;

    // userId → symbol → Position
    private final Map<String, Map<String, Position>> portfolios =
        new ConcurrentHashMap<>();

    // userId → cash balance (paise)
    private final Map<String, Long> cashBalances = new ConcurrentHashMap<>();

    // userId → blocked margin (for pending orders)
    private final Map<String, Long> blockedMargin = new ConcurrentHashMap<>();

    private PortfolioService() {}

    public static synchronized PortfolioService getInstance() {
        if (instance == null) instance = new PortfolioService();
        return instance;
    }

    public void addUser(String userId, double cash) {
        cashBalances.put(userId, Math.round(cash * 100));
        portfolios.put(userId, new ConcurrentHashMap<>());
        blockedMargin.put(userId, 0L);
        System.out.println("[Portfolio] User " + userId +
            " registered with ₹" + cash);
    }

    public boolean blockMargin(String userId, Price amount) {
        long available = cashBalances.getOrDefault(userId, 0L) -
                         blockedMargin.getOrDefault(userId, 0L);
        if (available < amount.getPaise()) {
            System.out.println("[Margin] Insufficient: needs " + amount +
                " available=" + new Price((double) available / 100));
            return false;
        }
        blockedMargin.merge(userId, amount.getPaise(), Long::sum);
        return true;
    }

    public void releaseMargin(String userId, Price amount) {
        blockedMargin.merge(userId, -amount.getPaise(), Long::sum);
    }

    public void applyTrade(Trade trade) {
        // Buy side
        String buyerId = trade.getBuyOrder().getUserId();
        Price  cost    = trade.getPrice().multiply(trade.getQuantity());
        cashBalances.merge(buyerId, -cost.getPaise(), Long::sum);
        releaseMargin(buyerId, cost);

        portfolios.get(buyerId)
            .computeIfAbsent(trade.getInstrument().getSymbol(),
                k -> new Position(buyerId, trade.getInstrument()))
            .applyBuy(trade.getQuantity(), trade.getPrice());

        // Sell side
        String sellerId  = trade.getSellOrder().getUserId();
        cashBalances.merge(sellerId, cost.getPaise(), Long::sum);

        Position sellPos = portfolios.getOrDefault(sellerId, new HashMap<>())
            .get(trade.getInstrument().getSymbol());
        if (sellPos != null) sellPos.applySell(trade.getQuantity());
    }

    public void printPortfolio(String userId) {
        System.out.println("\n[Portfolio: " + userId + "]");
        long cash = cashBalances.getOrDefault(userId, 0L);
        long blocked = blockedMargin.getOrDefault(userId, 0L);
        System.out.printf("  Cash: ₹%.2f | Blocked: ₹%.2f%n",
            cash / 100.0, blocked / 100.0);

        Map<String, Position> positions = portfolios.getOrDefault(userId, Map.of());
        if (positions.isEmpty()) {
            System.out.println("  No positions");
        } else {
            positions.values().forEach(p -> System.out.println("  " + p));
        }
    }

    public boolean hasPosition(String userId, String symbol, long qty) {
        Position pos = portfolios.getOrDefault(userId, Map.of()).get(symbol);
        return pos != null && pos.getQuantity() >= qty;
    }

    public long getAvailableCash(String userId) {
        long cash    = cashBalances.getOrDefault(userId, 0L);
        long blocked = blockedMargin.getOrDefault(userId, 0L);
        return cash - blocked;
    }
}

// ==========================================
// 12. RISK ENGINE — validates orders before acceptance
// ==========================================
class RiskEngine {
    private final PortfolioService portfolio;

    public RiskEngine(PortfolioService portfolio) { this.portfolio = portfolio; }

    public String validate(Order order) {
        Instrument inst = order.getInstrument();

        // Check circuit breaker
        if (order.getLimitPrice() != null &&
            !inst.isPriceWithinCircuit(order.getLimitPrice())) {
            return "Price " + order.getLimitPrice() +
                " outside circuit limits [" + inst.getLowerCircuit() +
                " - " + inst.getUpperCircuit() + "]";
        }

        // Quantity sanity check
        if (order.getQuantity() <= 0) return "Invalid quantity";
        if (order.getQuantity() > 100_000) return "Quantity exceeds max order size";

        // Margin check for BUY orders
        if (order.getSide() == OrderSide.BUY) {
            Price orderValue = order.getLimitPrice() != null
                ? order.getLimitPrice().multiply(order.getQuantity())
                : inst.getLastTradedPrice().multiply(order.getQuantity());

            long available = portfolio.getAvailableCash(order.getUserId());
            if (available < orderValue.getPaise()) {
                return "Insufficient margin: needs " + orderValue +
                    " available=" + new Price((double) available / 100);
            }
        }

        // Short sell check for CNC (delivery) orders
        if (order.getSide() == OrderSide.SELL &&
            order.getProduct() == ProductType.CNC) {
            if (!portfolio.hasPosition(order.getUserId(),
                    inst.getSymbol(), order.getQuantity())) {
                return "Insufficient holdings for CNC sell";
            }
        }

        return null; // null = no error = OK
    }
}

// ==========================================
// 13. EXCHANGE ENGINE — SINGLETON (core)
// ==========================================
class ExchangeEngine {
    private static ExchangeEngine instance;

    private final Map<String, OrderBook>         orderBooks   = new ConcurrentHashMap<>();
    private final Map<String, Instrument>        instruments  = new ConcurrentHashMap<>();
    private final Map<Long, Order>               allOrders    = new ConcurrentHashMap<>();
    private final Map<String, String>            idempotency  = new ConcurrentHashMap<>();
    private final List<Trade>                    tradeHistory = new CopyOnWriteArrayList<>();
    private final List<TradeEventObserver>       observers    = new ArrayList<>();
    private final PortfolioService               portfolio    = PortfolioService.getInstance();
    private final RiskEngine                     riskEngine   = new RiskEngine(portfolio);
    private       MatchingStrategy               matchingStrategy;

    // Single-threaded order processing per instrument (critical for correctness)
    private final Map<String, ExecutorService>   symbolExecutors = new ConcurrentHashMap<>();

    private ExchangeEngine() {
        this.matchingStrategy = new PriceTimePriorityStrategy();
        observers.add(new MarketDataPublisher());
        observers.add(new NotificationService());
        observers.add(new PortfolioUpdater(portfolio));
    }

    public static synchronized ExchangeEngine getInstance() {
        if (instance == null) instance = new ExchangeEngine();
        return instance;
    }

    // ---- Register instrument ----
    public void registerInstrument(Instrument instrument) {
        instruments.put(instrument.getSymbol(), instrument);
        orderBooks.put(instrument.getSymbol(), new OrderBook(instrument));
        // Single-threaded executor per symbol — critical for order book consistency
        symbolExecutors.put(instrument.getSymbol(),
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "matcher-" + instrument.getSymbol());
                t.setDaemon(true);
                return t;
            }));
        System.out.println("[Exchange] Registered: " + instrument.getSymbol());
    }

    // ---- PLACE ORDER (core flow) ----
    public Order placeOrder(Order order) {
        // 1. Idempotency check
        if (idempotency.containsKey(order.getIdempotencyKey())) {
            long existingId = Long.parseLong(idempotency.get(order.getIdempotencyKey()));
            System.out.println("[Exchange] Duplicate order — returning existing #" + existingId);
            return allOrders.get(existingId);
        }

        // 2. Risk validation
        String riskError = riskEngine.validate(order);
        if (riskError != null) {
            order.reject(riskError);
            allOrders.put(order.getOrderId(), order);
            // NEW: notify all observers — user gets push, portfolio logs no-op
            observers.forEach(o -> o.onOrderRejected(order, riskError));
            return order;
        }

        // 3. Block margin for BUY orders
        if (order.getSide() == OrderSide.BUY && order.getLimitPrice() != null) {
            Price required = order.getLimitPrice().multiply(order.getQuantity());
            if (!portfolio.blockMargin(order.getUserId(), required)) {
                order.reject("Margin block failed");
                allOrders.put(order.getOrderId(), order);
                // NEW: notify — blockMargin() already returned false so nothing was locked
                observers.forEach(o -> o.onOrderRejected(order, "Margin block failed"));
                return order;
            }
        }

        // 4. Store + notify
        allOrders.put(order.getOrderId(), order);
        idempotency.put(order.getIdempotencyKey(), String.valueOf(order.getOrderId()));
        observers.forEach(o -> o.onOrderPlaced(order));

        // 5. Submit to symbol-specific single-threaded executor
        String symbol = order.getInstrument().getSymbol();
        symbolExecutors.get(symbol).submit(() -> processOrder(order));

        return order;
    }

    // ---- PROCESS ORDER (inside single-threaded executor) ----
    private void processOrder(Order order) {
        String   symbol = order.getInstrument().getSymbol();
        OrderBook book  = orderBooks.get(symbol);
        if (book == null) return;

        System.out.println("\n[Engine:" + symbol + "] Processing: " + order);

        // Match against existing orders
        List<Trade> trades = matchingStrategy.match(order, book);

        // Publish all trades
        trades.forEach(trade -> {
            tradeHistory.add(trade);
            observers.forEach(o -> o.onTrade(trade));
            // Check stop-loss triggers after every trade
            Price newPrice = trade.getPrice();
            book.checkStopTriggers(newPrice).forEach(stopOrder -> {
                // Convert stop order to limit and re-process
                System.out.println("[StopTrigger] Activating stop order #" +
                    stopOrder.getOrderId());
                processOrder(stopOrder);
            });
        });

        // If order still has pending qty, add to book (resting order)
        if (order.getStatus() == OrderStatus.PENDING ||
            order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
            if (order.getType() != OrderType.MARKET) { // market orders don't rest
                book.addOrder(order);
                System.out.println("[Engine] Resting in book: #" +
                    order.getOrderId() + " pending=" + order.getPendingQty() +
                    (order.getStatus() == OrderStatus.PARTIALLY_FILLED
                        ? " (PARTIAL FILL — filled=" + order.getFilledQty() + ")"
                        : " (PENDING — no match yet)"));
            } else {
                // Market orders are fill-or-kill: remainder is rejected, never rests in book
                String noLiqReason = "Market order could not be fully filled — no liquidity";
                order.reject(noLiqReason);
                // NEW: observers notified so user gets push and portfolio logs no-op
                observers.forEach(o -> o.onOrderRejected(order, noLiqReason));
            }
        }
    }

    // ---- CANCEL ORDER ----
    public boolean cancelOrder(long orderId, String userId) {
        Order order = allOrders.get(orderId);
        if (order == null) {
            System.out.println("[Cancel] Order not found: #" + orderId);
            return false;
        }
        if (!order.getUserId().equals(userId)) {
            System.out.println("[Cancel] Unauthorized cancel attempt");
            return false;
        }
        if (order.getStatus() == OrderStatus.FILLED) {
            System.out.println("[Cancel] Cannot cancel filled order");
            return false;
        }

        String symbol = order.getInstrument().getSymbol();
        symbolExecutors.get(symbol).submit(() -> {
            orderBooks.get(symbol).removeOrder(order);
            order.cancel();

            // Release ONLY the remaining (pending) margin, not already-consumed fill margin.
            // For a partial fill: e.g. 50/100 filled → release only pendingQty(50) × limitPrice.
            // The 50 already filled had its margin consumed in applyTrade() at match time.
            if (order.getSide() == OrderSide.BUY && order.getLimitPrice() != null) {
                Price remaining = order.getLimitPrice().multiply(order.getPendingQty());
                portfolio.releaseMargin(userId, remaining);
                System.out.println("[Cancel] Released margin for pending qty=" +
                    order.getPendingQty() + " → " + remaining);
            }
            // SELL-side (MIS intraday): no margin was blocked in this implementation,
            // but in a real exchange short-sell margin would be released here.

            observers.forEach(o -> o.onOrderCancelled(order));
        });
        return true;
    }

    // ---- MARKET DEPTH ----
    public void printMarketDepth(String symbol) {
        OrderBook book = orderBooks.get(symbol);
        if (book != null) book.printDepth(5);
    }

    // ---- ORDER STATUS ----
    public Order getOrder(long orderId) { return allOrders.get(orderId); }

    public List<Trade> getRecentTrades(String symbol, int limit) {
        return tradeHistory.stream()
            .filter(t -> t.getInstrument().getSymbol().equals(symbol))
            .sorted(Comparator.comparingLong(Trade::getTradeId).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    public Instrument getInstrument(String symbol) { return instruments.get(symbol); }

    public void shutdown() {
        symbolExecutors.values().forEach(ExecutorService::shutdown);
    }
}

// ==========================================
// 14. MAIN — DRIVER CODE
// ==========================================
public class StockExchange {
    public static void main(String[] args) throws InterruptedException {

        ExchangeEngine  engine    = ExchangeEngine.getInstance();
        PortfolioService portfolio = PortfolioService.getInstance();

        // ---- Setup Instruments ----
        Instrument reliance = new Instrument("RELIANCE", "Reliance Industries",
            Exchange.NSE, 2950.00, 0.05);
        Instrument infosys  = new Instrument("INFY", "Infosys Ltd",
            Exchange.NSE, 1850.00, 0.05);
        Instrument tcs      = new Instrument("TCS", "Tata Consultancy Services",
            Exchange.NSE, 3900.00, 0.05);

        engine.registerInstrument(reliance);
        engine.registerInstrument(infosys);
        engine.registerInstrument(tcs);

        // ---- Setup Users ----
        portfolio.addUser("alice", 500_000); // ₹5 lakh
        portfolio.addUser("bob",   300_000); // ₹3 lakh
        portfolio.addUser("carol", 200_000); // ₹2 lakh
        portfolio.addUser("dave",  150_000); // ₹1.5 lakh

        // ===== SCENARIO 1: Limit order matching =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Limit Order Match (BUY meets SELL)");
        System.out.println("=".repeat(60));

        // Bob places sell limit at 2950
        Order s1 = engine.placeOrder(
            OrderFactory.limitSell("bob", reliance, 100, 2950.00));

        // Alice places buy limit at 2950 — should match
        Order b1 = engine.placeOrder(
            OrderFactory.limitBuy("alice", reliance, 50, 2950.00));

        Thread.sleep(200);
        System.out.println("\n" + s1);
        System.out.println(b1);

        // ===== SCENARIO 2: Market order =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Market Order (immediate fill at best price)");
        System.out.println("=".repeat(60));

        // Carol places market buy — matches against Bob's remaining 50
        Order b2 = engine.placeOrder(
            OrderFactory.marketBuy("carol", reliance, 30));

        Thread.sleep(200);
        System.out.println(b2);

        // ===== SCENARIO 3: Order book depth =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Order Book Depth (multiple price levels)");
        System.out.println("=".repeat(60));

        // Build up order book
        engine.placeOrder(OrderFactory.limitSell("bob", reliance, 100, 2955.00));
        engine.placeOrder(OrderFactory.limitSell("bob", reliance,  50, 2960.00));
        engine.placeOrder(OrderFactory.limitSell("bob", reliance,  75, 2965.00));
        engine.placeOrder(OrderFactory.limitBuy("dave", reliance, 200, 2945.00));
        engine.placeOrder(OrderFactory.limitBuy("dave", reliance, 100, 2940.00));

        Thread.sleep(200);
        engine.printMarketDepth("RELIANCE");

        // ===== SCENARIO 4: Partial fill =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Partial Fill");
        System.out.println("=".repeat(60));

        // Alice wants 200 shares at 2955 — only 100 available
        Order b3 = engine.placeOrder(
            OrderFactory.limitBuy("alice", reliance, 200, 2955.00));

        Thread.sleep(200);
        System.out.println("Order status: " + b3.getStatus() +
            " | Filled: " + b3.getFilledQty() + "/" + b3.getQuantity());

        // ===== SCENARIO 5: Stop loss order =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Stop Loss Order");
        System.out.println("=".repeat(60));

        // Give alice some Infosys first
        portfolio.addUser("alice2", 500_000);
        Order sl = engine.placeOrder(
            OrderFactory.stopLoss("alice", infosys, OrderSide.SELL,
                50, 1840.00, 1835.00)); // if price falls to 1840, sell at 1835

        System.out.println("Stop loss placed: #" + sl.getOrderId());

        // ===== SCENARIO 6: Risk rejection — insufficient margin =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Risk Rejection — Insufficient Margin");
        System.out.println("=".repeat(60));

        Order rejected = engine.placeOrder(
            OrderFactory.limitBuy("dave", tcs, 1000, 3900.00)); // ₹39 lakh, dave has ₹1.5L

        Thread.sleep(200);
        System.out.println("Result: " + rejected.getStatus());

        // ===== SCENARIO 7: Cancel order =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Cancel Pending Order");
        System.out.println("=".repeat(60));

        Order pending = engine.placeOrder(
            OrderFactory.limitBuy("alice", reliance, 10, 2800.00)); // far from market

        Thread.sleep(100);
        System.out.println("Before cancel: " + pending.getStatus());
        engine.cancelOrder(pending.getOrderId(), "alice");
        Thread.sleep(100);
        System.out.println("After cancel:  " + pending.getStatus());

        // ===== SCENARIO 8: Portfolio + PnL =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Portfolio Summary + P&L");
        System.out.println("=".repeat(60));

        Thread.sleep(200);
        portfolio.printPortfolio("alice");
        portfolio.printPortfolio("bob");

        // ===== SCENARIO 9: Recent trades =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Recent Trade History");
        System.out.println("=".repeat(60));

        System.out.println("Recent RELIANCE trades:");
        engine.getRecentTrades("RELIANCE", 5)
            .forEach(t -> System.out.println("  " + t));

        System.out.println("\nRELIANCE instrument summary:");
        System.out.println("  " + reliance);

        // ===== SCENARIO 10: Rejection notifications + partial cancel margin =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Rejection Notifications + Partial Cancel");
        System.out.println("=".repeat(60));

        // 10a: Risk rejection — circuit breaker (price too high)
        Order circuitBreak = engine.placeOrder(
            OrderFactory.limitBuy("alice", reliance, 10, 3600.00)); // above 20% upper circuit
        Thread.sleep(100);
        System.out.println("Circuit breach result: " + circuitBreak.getStatus());

        // 10b: Market order with empty book — no-liquidity rejection
        // First drain/skip — place a market buy against an empty ask side
        Instrument hdfc = new Instrument("HDFCBANK", "HDFC Bank", Exchange.NSE, 1700.00, 0.05);
        engine.registerInstrument(hdfc);
        portfolio.addUser("eve", 200_000);
        Order noLiq = engine.placeOrder(OrderFactory.marketBuy("eve", hdfc, 50));
        Thread.sleep(200);
        System.out.println("No-liquidity market order: " + noLiq.getStatus());

        // 10c: Partial fill → then cancel remaining → verify margin release
        System.out.println("\n-- Partial cancel margin release --");
        // bob sells 40 shares at 2970
        engine.placeOrder(OrderFactory.limitSell("bob", reliance, 40, 2970.00));
        // alice buys 100 at 2970 — only 40 available → PARTIALLY_FILLED, 60 rest
        Order partialThenCancel = engine.placeOrder(
            OrderFactory.limitBuy("alice", reliance, 100, 2970.00));
        Thread.sleep(300);
        System.out.println("After partial match: status=" + partialThenCancel.getStatus() +
            " filled=" + partialThenCancel.getFilledQty() + "/" + partialThenCancel.getQuantity());
        // Cancel the remaining 60
        engine.cancelOrder(partialThenCancel.getOrderId(), "alice");
        Thread.sleep(200);
        System.out.println("After cancel: status=" + partialThenCancel.getStatus() +
            " | Only pending qty margin was released");

        engine.shutdown();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | ExchangeEngine, PortfolioService
            Strategy   | MatchingStrategy (Price-Time Priority)
            Factory    | OrderFactory (market/limit/stop-loss/AMO)
            Builder    | Order.Builder
            Observer   | TradeEventObserver (Portfolio/MarketData/Notif)
            State      | OrderStatus (PENDING→PARTIAL→FILLED→CANCELLED)
            Command    | Order as executable command (place/cancel/modify)
            Template   | OrderBook per instrument (bid/ask sorted trees)
            """);
    }
}
