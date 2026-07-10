import java.util.*;

// ==========================================
// 1. STRATEGY DESIGN PATTERN (PAYMENT)
// ==========================================
interface PaymentStrategy {
    /**
     * Processes the transaction.
     * @param amount The total cost of the product.
     * @param currentBalance The amount of money currently inserted/authorized.
     * @return true if payment is successful, false otherwise.
     */
    boolean processPayment(int amount, int currentBalance);
}

class CashPayment implements PaymentStrategy {
    @Override
    public boolean processPayment(int amount, int currentBalance) {
        if (currentBalance >= amount) {
            System.out.println("[Payment: Cash] Accepted physical currency. Sufficient funds.");
            return true;
        }
        System.out.println("[Payment: Cash] Insufficient physical currency inserted.");
        return false;
    }
}

class UPIPayment implements PaymentStrategy {
    @Override
    public boolean processPayment(int amount, int currentBalance) {
        System.out.println("[Payment: UPI] Initializing digital gateway ping for amount: " + amount);
        // Simulating third-party API gateway ping (e.g., Razorpay/UPI network)
        boolean gatewaySuccess = true;

        if (gatewaySuccess) {
            System.out.println("[Payment: UPI] Gateway callback received: SUCCESS.");
            return true;
        } else {
            System.out.println("[Payment: UPI] Gateway callback received: FAILED/TIMEOUT.");
            return false;
        }
    }
}

// ==========================================
// 2. STATE INTERFACE
// ==========================================
interface State {
    void insertCoin(VendingMachine vm, int amount);
    void selectProduct(VendingMachine vm, String code);
    void dispense(VendingMachine vm, String code);
    void cancelRequest(VendingMachine vm);
}

// ==========================================
// 3. PRODUCT & INVENTORY
// ==========================================
class Product {
    String name;
    int price;

    public Product(String name, int price) {
        this.name = name;
        this.price = price;
    }
}

class Inventory {
    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Integer> stock = new HashMap<>();

    public void addProduct(String code, Product p, int count) {
        products.put(code, p);
        stock.put(code, count);
    }

    public Product getProduct(String code) {
        return products.get(code);
    }

    public boolean isAvailable(String code) {
        return stock.containsKey(code) && stock.get(code) > 0;
    }

    public void reduceStock(String code) {
        stock.put(code, stock.get(code) - 1);
    }
}

// ==========================================
// 4. CONCRETE STATES
// ==========================================
class IdleState implements State {
    @Override
    public void insertCoin(VendingMachine vm, int amount) {
        vm.addBalance(amount);
        System.out.println("Coin accepted: " + amount + ". Total: " + vm.getBalance());
        vm.setState(new HasMoneyState());
    }

    @Override
    public void selectProduct(VendingMachine vm, String code) {
        System.out.println("Insert money first!");
    }

    @Override
    public void dispense(VendingMachine vm, String code) {
        System.out.println("Payment required.");
    }

    @Override
    public void cancelRequest(VendingMachine vm) {
        System.out.println("Nothing to refund.");
    }
}

class HasMoneyState implements State {
    @Override
    public void insertCoin(VendingMachine vm, int amount) {
        vm.addBalance(amount);
        System.out.println("Coin added. New Total: " + vm.getBalance());
    }

    @Override
    public void selectProduct(VendingMachine vm, String code) {
        Product p = vm.getInventory().getProduct(code);
        if (p == null || !vm.getInventory().isAvailable(code)) {
            System.out.println("Product unavailable.");
            return;
        }

        // Delegate checking and processing to the Strategy Pattern component
        PaymentStrategy strategy = vm.getPaymentStrategy();
        if (strategy.processPayment(p.price, vm.getBalance())) {
            vm.setState(new DispensingState());
            vm.triggerDispense(code);
        } else {
            System.out.println("Transaction declined by payment processor. Remaining Balance: " + vm.getBalance());
        }
    }

    @Override
    public void cancelRequest(VendingMachine vm) {
        System.out.println("Refunding " + vm.getBalance());
        vm.resetBalance();
        vm.setState(new IdleState());
    }

    @Override
    public void dispense(VendingMachine vm, String code) {
        System.out.println("Select a product first.");
    }
}

class DispensingState implements State {
    @Override
    public void insertCoin(VendingMachine vm, int amount) {
        System.out.println("Wait, dispensing in progress...");
    }

    @Override
    public void selectProduct(VendingMachine vm, String code) {
        System.out.println("Already dispensing.");
    }

    @Override
    public void dispense(VendingMachine vm, String code) {
        Product p = vm.getInventory().getProduct(code);
        vm.getInventory().reduceStock(code);
        int change = vm.getBalance() - p.price;

        System.out.println(">>> DISPENSING: " + p.name + " <<<");

        // Change logic applies mostly to physical currency flows
        if (vm.getPaymentStrategy() instanceof CashPayment && change > 0) {
            System.out.println("Change returned: " + change);
        }

        vm.resetBalance();
        vm.setState(new IdleState());
    }

    @Override
    public void cancelRequest(VendingMachine vm) {
        System.out.println("Cannot cancel, item already dispensing!");
    }
}

// ==========================================
// 5. VENDING MACHINE (Context - Singleton)
// ==========================================
class VendingMachine {
    private State currentState;
    private int balance = 0;
    private final Inventory inventory;
    private PaymentStrategy paymentStrategy; // Strategy Reference
    private static VendingMachine instance;

    private VendingMachine() {
        currentState = new IdleState();
        inventory = new Inventory();
        // Default Strategy setup as Cash
        paymentStrategy = new CashPayment();

        inventory.addProduct("A1", new Product("Coke", 25), 5);
        inventory.addProduct("B2", new Product("Chips", 15), 5);
    }

    // Thread-safe Singleton
    public static synchronized VendingMachine getInstance() {
        if (instance == null) {
            instance = new VendingMachine();
        }
        return instance;
    }

    // Mutators for State and Strategy
    public void setState(State state) { this.currentState = state; }
    public void setPaymentStrategy(PaymentStrategy strategy) { this.paymentStrategy = strategy; }
    public PaymentStrategy getPaymentStrategy() { return this.paymentStrategy; }

    public int getBalance() { return balance; }
    public void addBalance(int amount) { this.balance += amount; }
    public void resetBalance() { this.balance = 0; }
    public Inventory getInventory() { return inventory; }

    // Business Logic Endpoints
    public void insertCoin(int amount) { currentState.insertCoin(this, amount); }
    public void selectProduct(String code) { currentState.selectProduct(this, code); }
    public void cancel() { currentState.cancelRequest(this); }
    public void triggerDispense(String code) { currentState.dispense(this, code); }
}

// ==========================================
// 6. MAIN EXECUTION (Verifying out both flows)
// ==========================================
public class Main {
    public static void main(String[] args) {
        VendingMachine vm = VendingMachine.getInstance();

        System.out.println("--- Transaction 1: Physical Cash Purchase ---");
        vm.setPaymentStrategy(new CashPayment());
        vm.insertCoin(20);
        vm.insertCoin(10);
        vm.selectProduct("A1"); // Coke costs 25, total 30. Expect success + change.

        System.out.println("\n--- Transaction 2: Swapping Strategy to UPI ---");
        vm.setPaymentStrategy(new UPIPayment());
        // For digital UPI payment, user scans QR. Machine registers authorization.
        vm.insertCoin(25);
        vm.selectProduct("A1"); // Expect digital execution path
    }
}