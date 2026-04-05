import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

// ==========================================
// 1. ENUMS
// ==========================================
enum OrderStatus {
    PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP,
    PICKED_UP, DELIVERED, CANCELLED
}
enum DeliveryAgentStatus { AVAILABLE, BUSY }
enum PaymentMethod { UPI, CARD, CASH, WALLET }
enum PaymentStatus { PENDING, SUCCESS, FAILED, REFUNDED }
enum FoodCategory { NORTH_INDIAN, SOUTH_INDIAN, CHINESE, PIZZA, BURGER, DESSERT, BEVERAGES }

// ==========================================
// 2. CORE ENTITIES
// ==========================================
class User {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;
    private final String email;
    private final String phone;
    private final String address;

    public User(String name, String email, String phone, String address) {
        this.id = idGen.getAndIncrement();
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.address = address;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getAddress() { return address; }

    @Override public String toString() { return "User[" + name + "]"; }
}

class MenuItem {
    private static final AtomicInteger idGen = new AtomicInteger(100);
    private final int id;
    private final String name;
    private final double price;
    private final FoodCategory category;
    private boolean available;
    private final String description;

    public MenuItem(String name, double price, FoodCategory category, String description) {
        this.id = idGen.getAndIncrement();
        this.name = name;
        this.price = price;
        this.category = category;
        this.description = description;
        this.available = true;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public FoodCategory getCategory() { return category; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    @Override public String toString() {
        return "MenuItem[" + name + ", Rs" + price + "]";
    }
}

class OrderItem {
    private final MenuItem menuItem;
    private final int quantity;

    public OrderItem(MenuItem menuItem, int quantity) {
        this.menuItem = menuItem;
        this.quantity = quantity;
    }

    public MenuItem getMenuItem() { return menuItem; }
    public int getQuantity() { return quantity; }
    public double getTotalPrice() { return menuItem.getPrice() * quantity; }

    @Override public String toString() {
        return menuItem.getName() + " x" + quantity + " = Rs" + getTotalPrice();
    }
}

class Restaurant {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;
    private final String cuisine;
    private final String address;
    private final double rating;
    private final List<MenuItem> menu = new ArrayList<>();
    private boolean isOpen;

    public Restaurant(String name, String cuisine, String address, double rating) {
        this.id = idGen.getAndIncrement();
        this.name = name;
        this.cuisine = cuisine;
        this.address = address;
        this.rating = rating;
        this.isOpen = true;
    }

    public void addMenuItem(MenuItem item) { menu.add(item); }

    public List<MenuItem> searchMenu(FoodCategory category) {
        return menu.stream()
            .filter(m -> m.getCategory() == category && m.isAvailable())
            .collect(Collectors.toList());
    }

    public List<MenuItem> getAllAvailableItems() {
        return menu.stream().filter(MenuItem::isAvailable).collect(Collectors.toList());
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getCuisine() { return cuisine; }
    public double getRating() { return rating; }
    public boolean isOpen() { return isOpen; }
    public void setOpen(boolean open) { isOpen = open; }

    @Override public String toString() {
        return "Restaurant[" + name + ", " + cuisine + ", " + rating + " stars]";
    }
}

class DeliveryAgent {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;
    private final String phone;
    private DeliveryAgentStatus status;
    private double currentLat;
    private double currentLng;

    public DeliveryAgent(String name, String phone, double lat, double lng) {
        this.id = idGen.getAndIncrement();
        this.name = name;
        this.phone = phone;
        this.status = DeliveryAgentStatus.AVAILABLE;
        this.currentLat = lat;
        this.currentLng = lng;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public DeliveryAgentStatus getStatus() { return status; }
    public void setStatus(DeliveryAgentStatus s) { this.status = s; }
    public double getCurrentLat() { return currentLat; }
    public double getCurrentLng() { return currentLng; }

    public void updateLocation(double lat, double lng) {
        this.currentLat = lat;
        this.currentLng = lng;
    }

    @Override public String toString() { return "Agent[" + name + ", " + status + "]"; }
}

// ==========================================
// 3. PAYMENT
// ==========================================
class Payment {
    private static final AtomicInteger idGen = new AtomicInteger(7000);
    private final int id;
    private final double amount;
    private final PaymentMethod method;
    private PaymentStatus status;

    public Payment(double amount, PaymentMethod method) {
        this.id = idGen.getAndIncrement();
        this.amount = amount;
        this.method = method;
        this.status = PaymentStatus.PENDING;
    }

    public boolean process() {
        System.out.println("[Payment] Processing Rs" + amount + " via " + method);
        // Simulate gateway call
        this.status = PaymentStatus.SUCCESS;
        System.out.println("[Payment] SUCCESS — ID: " + id);
        return true;
    }

    public void refund() {
        this.status = PaymentStatus.REFUNDED;
        System.out.println("[Payment] Refunded Rs" + amount + " — Payment ID: " + id);
    }

    public int getId() { return id; }
    public double getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
}

// ==========================================
// 4. ORDER — STATE MACHINE
// ==========================================
class Order {
    private static final AtomicInteger idGen = new AtomicInteger(5000);
    private final int id;
    private final User user;
    private final Restaurant restaurant;
    private final List<OrderItem> items;
    private OrderStatus status;
    private Payment payment;
    private DeliveryAgent agent;
    private final double totalAmount;
    private final double deliveryFee;
    private final LocalDateTime placedAt;

    public Order(User user, Restaurant restaurant, List<OrderItem> items,
                 double deliveryFee) {
        this.id = idGen.getAndIncrement();
        this.user = user;
        this.restaurant = restaurant;
        this.items = items;
        this.deliveryFee = deliveryFee;
        this.totalAmount = items.stream().mapToDouble(OrderItem::getTotalPrice).sum() + deliveryFee;
        this.status = OrderStatus.PLACED;
        this.placedAt = LocalDateTime.now();
    }

    // State transitions — explicit, clean
    public void accept()         { transition(OrderStatus.ACCEPTED); }
    public void startPreparing() { transition(OrderStatus.PREPARING); }
    public void markReady()      { transition(OrderStatus.READY_FOR_PICKUP); }
    public void pickUp(DeliveryAgent agent) {
        this.agent = agent;
        transition(OrderStatus.PICKED_UP);
    }
    public void deliver() {
        transition(OrderStatus.DELIVERED);
        if (agent != null) agent.setStatus(DeliveryAgentStatus.AVAILABLE);
    }
    public void cancel() {
        transition(OrderStatus.CANCELLED);
        if (payment != null) payment.refund();
        if (agent != null) agent.setStatus(DeliveryAgentStatus.AVAILABLE);
    }

    private void transition(OrderStatus next) {
        System.out.println("[Order " + id + "] " + status + " → " + next);
        this.status = next;
    }

    public void setPayment(Payment p) { this.payment = p; }

    public int getId() { return id; }
    public User getUser() { return user; }
    public Restaurant getRestaurant() { return restaurant; }
    public List<OrderItem> getItems() { return items; }
    public OrderStatus getStatus() { return status; }
    public DeliveryAgent getAgent() { return agent; }
    public double getTotalAmount() { return totalAmount; }
    public double getDeliveryFee() { return deliveryFee; }

    @Override public String toString() {
        return "Order[ID:" + id + " | " + user.getName() + " | " +
               restaurant.getName() + " | Rs" + totalAmount + " | " + status + "]";
    }
}

// ==========================================
// 5. STRATEGY PATTERN — DELIVERY FEE
// ==========================================
interface DeliveryFeeStrategy {
    double calculate(double distanceKm, double orderAmount);
}

class StandardDeliveryFee implements DeliveryFeeStrategy {
    @Override
    public double calculate(double distanceKm, double orderAmount) {
        return distanceKm * 8; // Rs 8 per km
    }
}

class FreeDeliveryAboveThreshold implements DeliveryFeeStrategy {
    private final double threshold;
    public FreeDeliveryAboveThreshold(double threshold) { this.threshold = threshold; }

    @Override
    public double calculate(double distanceKm, double orderAmount) {
        return orderAmount >= threshold ? 0 : distanceKm * 8;
    }
}

class SurgeDeliveryFee implements DeliveryFeeStrategy {
    @Override
    public double calculate(double distanceKm, double orderAmount) {
        return distanceKm * 8 * 1.5; // 1.5x surge during peak hours
    }
}

// ==========================================
// 6. STRATEGY PATTERN — AGENT ASSIGNMENT
// ==========================================
interface AgentAssignmentStrategy {
    DeliveryAgent assign(List<DeliveryAgent> agents, Restaurant restaurant);
}

class NearestAgentStrategy implements AgentAssignmentStrategy {
    @Override
    public DeliveryAgent assign(List<DeliveryAgent> agents, Restaurant restaurant) {
        // In real system: use GeoHash/Haversine formula
        // Simplified: return first available agent
        return agents.stream()
            .filter(a -> a.getStatus() == DeliveryAgentStatus.AVAILABLE)
            .findFirst()
            .orElse(null);
    }
}

class HighestRatedAgentStrategy implements AgentAssignmentStrategy {
    @Override
    public DeliveryAgent assign(List<DeliveryAgent> agents, Restaurant restaurant) {
        // Return available agent with highest rating (simplified: just first available)
        return agents.stream()
            .filter(a -> a.getStatus() == DeliveryAgentStatus.AVAILABLE)
            .findFirst()
            .orElse(null);
    }
}

// ==========================================
// 7. OBSERVER PATTERN — NOTIFICATIONS
// ==========================================
interface OrderObserver {
    void onOrderStatusChanged(Order order);
}

class UserNotifier implements OrderObserver {
    @Override
    public void onOrderStatusChanged(Order order) {
        String msg = switch (order.getStatus()) {
            case ACCEPTED -> "Your order has been accepted by " + order.getRestaurant().getName();
            case PREPARING -> "Your food is being prepared!";
            case PICKED_UP -> "Your order is on the way with " +
                (order.getAgent() != null ? order.getAgent().getName() : "an agent");
            case DELIVERED -> "Order delivered! Enjoy your meal.";
            case CANCELLED -> "Your order has been cancelled. Refund initiated.";
            default -> "Order status: " + order.getStatus();
        };
        System.out.println("[UserNotification -> " + order.getUser().getName() + "] " + msg);
    }
}

class RestaurantNotifier implements OrderObserver {
    @Override
    public void onOrderStatusChanged(Order order) {
        if (order.getStatus() == OrderStatus.PLACED) {
            System.out.println("[RestaurantNotification -> " + order.getRestaurant().getName() +
                "] New order #" + order.getId() + " received! Items: " + order.getItems().size());
        }
    }
}

// ==========================================
// 8. CART
// ==========================================
class Cart {
    private final User user;
    private Restaurant restaurant;
    private final List<OrderItem> items = new ArrayList<>();

    public Cart(User user) { this.user = user; }

    public void addItem(Restaurant restaurant, MenuItem item, int quantity) {
        // Enforce single-restaurant cart
        if (this.restaurant != null && this.restaurant.getId() != restaurant.getId()) {
            System.out.println("[Cart] Cannot mix items from different restaurants. Clear cart first.");
            return;
        }
        if (!item.isAvailable()) {
            System.out.println("[Cart] Item " + item.getName() + " is currently unavailable.");
            return;
        }
        this.restaurant = restaurant;
        items.add(new OrderItem(item, quantity));
        System.out.println("[Cart] Added: " + item.getName() + " x" + quantity);
    }

    public void clear() {
        items.clear();
        restaurant = null;
    }

    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
    public Restaurant getRestaurant() { return restaurant; }
    public User getUser() { return user; }
    public boolean isEmpty() { return items.isEmpty(); }

    public double getSubtotal() {
        return items.stream().mapToDouble(OrderItem::getTotalPrice).sum();
    }
}

// ==========================================
// 9. FOOD DELIVERY SYSTEM (SINGLETON)
// ==========================================
class FoodDeliverySystem {
    private static FoodDeliverySystem instance;

    private final Map<Integer, Restaurant> restaurants = new ConcurrentHashMap<>();
    private final Map<Integer, Order> orders = new ConcurrentHashMap<>();
    private final List<DeliveryAgent> agents = new ArrayList<>();
    private final List<OrderObserver> observers = new ArrayList<>();

    private DeliveryFeeStrategy deliveryFeeStrategy = new StandardDeliveryFee();
    private AgentAssignmentStrategy agentStrategy = new NearestAgentStrategy();

    private FoodDeliverySystem() {
        observers.add(new UserNotifier());
        observers.add(new RestaurantNotifier());
    }

    public static synchronized FoodDeliverySystem getInstance() {
        if (instance == null) instance = new FoodDeliverySystem();
        return instance;
    }

    // ---- Setup ----
    public void addRestaurant(Restaurant r) {
        restaurants.put(r.getId(), r);
        System.out.println("[System] Restaurant registered: " + r);
    }

    public void addAgent(DeliveryAgent agent) {
        agents.add(agent);
        System.out.println("[System] Agent registered: " + agent);
    }

    public void setDeliveryFeeStrategy(DeliveryFeeStrategy s) { this.deliveryFeeStrategy = s; }
    public void setAgentStrategy(AgentAssignmentStrategy s) { this.agentStrategy = s; }

    // ---- Search ----
    public List<Restaurant> searchRestaurants(String cuisine) {
        return restaurants.values().stream()
            .filter(Restaurant::isOpen)
            .filter(r -> r.getCuisine().equalsIgnoreCase(cuisine))
            .sorted(Comparator.comparingDouble(Restaurant::getRating).reversed())
            .collect(Collectors.toList());
    }

    public List<Restaurant> getAllOpenRestaurants() {
        return restaurants.values().stream()
            .filter(Restaurant::isOpen)
            .sorted(Comparator.comparingDouble(Restaurant::getRating).reversed())
            .collect(Collectors.toList());
    }

    // ---- Place Order ----
    public Order placeOrder(Cart cart, PaymentMethod paymentMethod, double distanceKm) {
        if (cart.isEmpty()) {
            System.out.println("[Order] Cart is empty.");
            return null;
        }
        if (!cart.getRestaurant().isOpen()) {
            System.out.println("[Order] Restaurant is closed.");
            return null;
        }

        // 1. Calculate delivery fee using strategy
        double deliveryFee = deliveryFeeStrategy.calculate(distanceKm, cart.getSubtotal());

        // 2. Create order
        Order order = new Order(cart.getUser(), cart.getRestaurant(),
            new ArrayList<>(cart.getItems()), deliveryFee);

        // 3. Process payment
        Payment payment = new Payment(order.getTotalAmount(), paymentMethod);
        order.setPayment(payment);

        if (!payment.process()) {
            System.out.println("[Order] Payment failed. Order cancelled.");
            return null;
        }

        // 4. Save order
        orders.put(order.getId(), order);

        // 5. Notify observers (user + restaurant)
        notifyObservers(order);

        System.out.println("[Order] Placed: " + order);
        cart.clear();
        return order;
    }

    // ---- Order Lifecycle ----
    public void acceptOrder(int orderId) {
        Order order = orders.get(orderId);
        if (order == null) return;
        order.accept();
        notifyObservers(order);
    }

    public void startPreparing(int orderId) {
        Order order = orders.get(orderId);
        if (order == null) return;
        order.startPreparing();
        notifyObservers(order);
    }

    public void markReady(int orderId) {
        Order order = orders.get(orderId);
        if (order == null) return;
        order.markReady();

        // Assign delivery agent when order is ready
        DeliveryAgent agent = agentStrategy.assign(agents, order.getRestaurant());
        if (agent == null) {
            System.out.println("[Order] No agents available — order queued.");
            return;
        }
        agent.setStatus(DeliveryAgentStatus.BUSY);
        order.pickUp(agent);
        notifyObservers(order);
        System.out.println("[Dispatch] Agent " + agent.getName() + " assigned to Order #" + orderId);
    }

    public void deliverOrder(int orderId) {
        Order order = orders.get(orderId);
        if (order == null) return;
        order.deliver();
        notifyObservers(order);
        System.out.println("[Delivery] Order #" + orderId + " delivered to " +
            order.getUser().getAddress());
    }

    public void cancelOrder(int orderId) {
        Order order = orders.get(orderId);
        if (order == null) return;
        if (order.getStatus() == OrderStatus.PICKED_UP ||
            order.getStatus() == OrderStatus.DELIVERED) {
            System.out.println("[Cancel] Cannot cancel order that is already picked up/delivered.");
            return;
        }
        order.cancel();
        notifyObservers(order);
    }

    // ---- Observer management ----
    public void addObserver(OrderObserver o) { observers.add(o); }

    private void notifyObservers(Order order) {
        observers.forEach(o -> o.onOrderStatusChanged(order));
    }

    public Map<Integer, Order> getAllOrders() { return orders; }
}

// ==========================================
// 10. MAIN — DRIVER CODE
// ==========================================
public class FoodDelivery {
    public static void main(String[] args) {
        FoodDeliverySystem system = FoodDeliverySystem.getInstance();

        // ---- Setup Restaurants ----
        Restaurant biryaniHouse = new Restaurant("Biryani House", "North Indian", "Koramangala", 4.5);
        biryaniHouse.addMenuItem(new MenuItem("Chicken Biryani", 250, FoodCategory.NORTH_INDIAN, "Aromatic basmati rice"));
        biryaniHouse.addMenuItem(new MenuItem("Mutton Biryani", 320, FoodCategory.NORTH_INDIAN, "Slow cooked mutton"));
        biryaniHouse.addMenuItem(new MenuItem("Raita", 40,  FoodCategory.NORTH_INDIAN, "Cooling yogurt dip"));
        biryaniHouse.addMenuItem(new MenuItem("Gulab Jamun", 80, FoodCategory.DESSERT, "Soft milk dumplings"));
        system.addRestaurant(biryaniHouse);

        Restaurant pizzaPalace = new Restaurant("Pizza Palace", "Italian", "Indiranagar", 4.2);
        pizzaPalace.addMenuItem(new MenuItem("Margherita Pizza", 299, FoodCategory.PIZZA, "Classic tomato cheese"));
        pizzaPalace.addMenuItem(new MenuItem("Pepperoni Pizza", 399, FoodCategory.PIZZA, "Loaded pepperoni"));
        pizzaPalace.addMenuItem(new MenuItem("Garlic Bread",  99,  FoodCategory.PIZZA, "Toasted with herbs"));
        system.addRestaurant(pizzaPalace);

        // ---- Setup Agents ----
        DeliveryAgent rahul = new DeliveryAgent("Rahul", "9876543210", 12.934, 77.610);
        DeliveryAgent priya  = new DeliveryAgent("Priya",  "9123456789", 12.940, 77.615);
        system.addAgent(rahul);
        system.addAgent(priya);

        // ---- Users ----
        User alice = new User("Alice", "alice@gmail.com", "9000000001", "HSR Layout, Bangalore");
        User bob   = new User("Bob",   "bob@gmail.com",   "9000000002", "Whitefield, Bangalore");

        System.out.println("\n===== Scenario 1: Standard Order =====");
        system.setDeliveryFeeStrategy(new StandardDeliveryFee());

        // Alice searches and adds to cart
        List<Restaurant> northIndian = system.searchRestaurants("North Indian");
        System.out.println("Found: " + northIndian);

        Cart aliceCart = new Cart(alice);
        aliceCart.addItem(biryaniHouse, biryaniHouse.getAllAvailableItems().get(0), 2); // 2x Chicken Biryani
        aliceCart.addItem(biryaniHouse, biryaniHouse.getAllAvailableItems().get(3), 1); // 1x Gulab Jamun

        System.out.println("Cart subtotal: Rs" + aliceCart.getSubtotal());

        Order order1 = system.placeOrder(aliceCart, PaymentMethod.UPI, 3.5); // 3.5 km

        if (order1 != null) {
            system.acceptOrder(order1.getId());
            system.startPreparing(order1.getId());
            system.markReady(order1.getId()); // also assigns agent + picks up
            system.deliverOrder(order1.getId());
        }

        System.out.println("\n===== Scenario 2: Free Delivery Above Threshold =====");
        system.setDeliveryFeeStrategy(new FreeDeliveryAboveThreshold(500)); // free above Rs500

        Cart bobCart = new Cart(bob);
        bobCart.addItem(pizzaPalace, pizzaPalace.getAllAvailableItems().get(0), 1); // Margherita
        bobCart.addItem(pizzaPalace, pizzaPalace.getAllAvailableItems().get(1), 1); // Pepperoni
        bobCart.addItem(pizzaPalace, pizzaPalace.getAllAvailableItems().get(2), 2); // 2x Garlic Bread

        System.out.println("Cart subtotal: Rs" + bobCart.getSubtotal() + " (free delivery above Rs500)");
        Order order2 = system.placeOrder(bobCart, PaymentMethod.CARD, 5.0);

        System.out.println("\n===== Scenario 3: Cancel Order =====");
        system.setDeliveryFeeStrategy(new StandardDeliveryFee());
        Cart aliceCart2 = new Cart(alice);
        aliceCart2.addItem(biryaniHouse, biryaniHouse.getAllAvailableItems().get(1), 1); // Mutton Biryani
        Order order3 = system.placeOrder(aliceCart2, PaymentMethod.WALLET, 2.0);

        if (order3 != null) {
            system.acceptOrder(order3.getId());
            system.cancelOrder(order3.getId()); // Cancel after accept — refund triggered
        }

        System.out.println("\n===== Scenario 4: Cross-Restaurant Cart Block =====");
        Cart mixedCart = new Cart(alice);
        mixedCart.addItem(biryaniHouse, biryaniHouse.getAllAvailableItems().get(0), 1);
        mixedCart.addItem(pizzaPalace,  pizzaPalace.getAllAvailableItems().get(0),  1); // Should be blocked

        System.out.println("\n===== Scenario 5: Surge Delivery Fee =====");
        system.setDeliveryFeeStrategy(new SurgeDeliveryFee());
        Cart aliceCart3 = new Cart(alice);
        aliceCart3.addItem(biryaniHouse, biryaniHouse.getAllAvailableItems().get(0), 1);
        Order order5 = system.placeOrder(aliceCart3, PaymentMethod.UPI, 4.0);
        if (order5 != null) {
            System.out.println("Delivery fee with surge: Rs" + order5.getDeliveryFee());
        }
    }
}
