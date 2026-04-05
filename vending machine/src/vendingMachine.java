vending machinee java

import java.util.*;

// ==========================================
// 1. STATE INTERFACE
// ==========================================
interface State {
    void insertCoin(VendingMachine vm, int amount);
    void selectProduct(VendingMachine vm, String code);
    void dispense(VendingMachine vm, String code);
    void cancelRequest(VendingMachine vm);
}

// ==========================================
// 2. PRODUCT & INVENTORY
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
// 3. CONCRETE STATES
// ==========================================

class IdleState implements State {
    public void insertCoin(VendingMachine vm, int amount) {
        vm.addBalance(amount);
        System.out.println("Coin accepted: " + amount + ". Total: " + vm.getBalance());
        vm.setState(new HasMoneyState());
    }

    public void selectProduct(VendingMachine vm, String code) { System.out.println("Insert money first!"); }
    public void dispense(VendingMachine vm, String code) { System.out.println("Payment required."); }
    public void cancelRequest(VendingMachine vm) { System.out.println("Nothing to refund."); }
}

class HasMoneyState implements State {
    public void insertCoin(VendingMachine vm, int amount) {
        vm.addBalance(amount);
        System.out.println("Coin added. New Total: " + vm.getBalance());
    }

    public void selectProduct(VendingMachine vm, String code) {
        Product p = vm.getInventory().getProduct(code);
        if (p == null || !vm.getInventory().isAvailable(code)) {
            System.out.println("Product unavailable.");
            return;
        }
        if (vm.getBalance() < p.price) {
            System.out.println("Insufficient funds. Price: " + p.price);
            return;
        }
        vm.setState(new DispensingState());
        vm.triggerDispense(code);
    }

    public void cancelRequest(VendingMachine vm) {
        System.out.println("Refunding " + vm.getBalance());
        vm.resetBalance();
        vm.setState(new IdleState());
    }

    public void dispense(VendingMachine vm, String code) { System.out.println("Select a product first."); }
}

class DispensingState implements State {
    public void insertCoin(VendingMachine vm, int amount) { System.out.println("Wait, dispensing in progress..."); }
    public void selectProduct(VendingMachine vm, String code) { System.out.println("Already dispensing."); }

    public void dispense(VendingMachine vm, String code) {
        Product p = vm.getInventory().getProduct(code);
        vm.getInventory().reduceStock(code);
        int change = vm.getBalance() - p.price;

        System.out.println(">>> DISPENSING: " + p.name + " <<<");
        if (change > 0) System.out.println("Change returned: " + change);

        vm.resetBalance();
        vm.setState(new IdleState());
    }

    public void cancelRequest(VendingMachine vm) { System.out.println("Cannot cancel, item already dispensing!"); }
}

// ==========================================
// 4. VENDING MACHINE (Context - Singleton)
// ==========================================
class VendingMachine {
    private State currentState;
    private int balance = 0;
    private final Inventory inventory;
    private static VendingMachine instance;

    private VendingMachine() {
        currentState = new IdleState();
        inventory = new Inventory();
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

    public void setState(State state) { this.currentState = state; }
    public int getBalance() { return balance; }
    public void addBalance(int amount) { this.balance += amount; }
    public void resetBalance() { this.balance = 0; }
    public Inventory getInventory() { return inventory; }

    public void insertCoin(int amount) { currentState.insertCoin(this, amount); }
    public void selectProduct(String code) { currentState.selectProduct(this, code); }
    public void cancel() { currentState.cancelRequest(this); }
    public void triggerDispense(String code) { currentState.dispense(this, code); }
}
