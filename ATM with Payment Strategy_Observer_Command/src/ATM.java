import java.util.*;

// =========================================================================
// 4. STATE PATTERN (Terminal Life Cycle & Context Machine)
// =========================================================================
interface ATMState {
    void insertCard(Account account);
    void authenticatePin(int pin);
    void withdrawCash(double amount);
    void ejectCard();
}

// --- Account Class containing Balance Context ---
class Account {
    private final String accountId;
    private double balance;
    private final WithdrawalStrategy withdrawalStrategy; // Strategy Reference

    public Account(String id, double initialBalance, WithdrawalStrategy strategy) {
        this.accountId = id;
        this.balance = initialBalance;
        this.withdrawalStrategy = strategy;
    }

    public synchronized boolean performWithdrawal(double amount) {
        return withdrawalStrategy.validateAndExecute(this, amount);
    }

    public synchronized void deposit(double amount) {
        this.balance += amount;
        System.out.println("[Account] Deposited/Reverted: " + amount + ". Current Balance: $" + balance);
    }

    public synchronized double getBalance() { return balance; }
    public synchronized void setBalance(double balance) { this.balance = balance; }
    public String getAccountId() { return accountId; }
}

class ATMMachine {
    private ATMState currentState;
    private double atmVaultBalance;
    private Account currentAccount;

    // Observer Registry List
    private final List<ATMListener> observers = new ArrayList<>();
    private final Object vaultLock = new Object();

    public ATMMachine(double initialCash) {
        this.atmVaultBalance = initialCash;
        this.currentState = new IdleState(this); // Machine boots to an Idle state
    }

    // Observer Pub/Sub Registries
    public void addObserver(ATMListener listener) { observers.add(listener); }
    public void notifyObservers(ATMEvent event, String message) {
        for (ATMListener observer : observers) {
            observer.onEvent(event, message);
        }
    }

    public void setState(ATMState newState) { this.currentState = newState; }
    public Account getAccount() { return currentAccount; }
    public void setAccount(Account account) { this.currentAccount = account; }

    public boolean deductVaultCash(double amount) {
        synchronized (vaultLock) {
            if (atmVaultBalance >= amount) {
                atmVaultBalance -= amount;
                System.out.println("[Hardware Vault] Vault remaining physical cash: $" + atmVaultBalance);

                if (atmVaultBalance <= 500.0) { // Telemetry trigger for low cash supplies
                    notifyObservers(ATMEvent.CASH_LOW, "ATM vault levels dropped to critical low: $" + atmVaultBalance);
                }
                return true;
            }
            return false;
        }
    }

    // Context Execution Layer Wrappers
    public void insertCard(Account account) { currentState.insertCard(account); }
    public void enterPin(int pin) { currentState.authenticatePin(pin); }
    public void withdraw(double amount) { currentState.withdrawCash(amount); }
    public void ejectCard() { currentState.ejectCard(); }
}

// --- Concrete States ---
class IdleState implements ATMState {
    private final ATMMachine atm;

    public IdleState(ATMMachine atm) { this.atm = atm; }

    @Override
    public void insertCard(Account account) {
        atm.setAccount(account);
        System.out.println("Card Accepted. Reading Account details...");
        atm.setState(new PinVerificationState(atm));
    }

    @Override public void authenticatePin(int pin) { System.out.println("Insert card first."); }
    @Override public void withdrawCash(double amount) { System.out.println("Insert card first."); }
    @Override public void ejectCard() { System.out.println("No card inserted."); }
}

class PinVerificationState implements ATMState {
    private final ATMMachine atm;

    public PinVerificationState(ATMMachine atm) { this.atm = atm; }

    @Override
    public void authenticatePin(int pin) {
        if (pin == 1234) { // Dummy pin match simulation
            System.out.println("PIN Authenticated successfully.");
            atm.setState(new AuthenticatedState(atm));
        } else {
            System.out.println("Invalid PIN. Try again.");
        }
    }

    @Override public void insertCard(Account account) { System.out.println("Card already in slot."); }
    @Override public void withdrawCash(double amount) { System.out.println("Authenticate PIN first."); }
    @Override public void ejectCard() {
        System.out.println("Card returned. Resetting terminal.");
        atm.setAccount(null);
        atm.setState(new IdleState(atm));
    }
}

class AuthenticatedState implements ATMState {
    private final ATMMachine atm;

    public AuthenticatedState(ATMMachine machine) { this.atm = machine; }

    @Override
    public void withdrawCash(double amount) {
        Account acc = atm.getAccount();
        if (acc == null) return;

        // Command pattern captures the operational transactions cleanly
        TransactionCommand withdrawTx = new WithdrawActionCommand(acc, atm, amount);
        boolean success = withdrawTx.execute();

        if (success) {
            System.out.println("Transaction Completed Successfully. Collect receipts.");
        } else {
            System.out.println("Transaction Aborted or Terminated by system safety boundaries.");
        }
        ejectCard();
    }

    @Override public void insertCard(Account account) { System.out.println("Card already in slot."); }
    @Override public void authenticatePin(int pin) { System.out.println("Already authenticated."); }
    @Override
    public void ejectCard() {
        System.out.println("Card ejected. Moving terminal context safely to Idle State.");
        atm.setAccount(null);
        atm.setState(new IdleState(atm));
    }
}


// =========================================================================
// 1. OBSERVER PATTERN (Hardware & Event Monitoring)
// =========================================================================
enum ATMEvent {
    CASH_LOW, TRANSACTION_SUCCESS, TRANSACTION_FAILED
}

interface ATMListener {
    void onEvent(ATMEvent event, String message);
}

/**
 * Concrete observer simulating an external monitoring dashboard
 * that bank technicians use to dispatch replenishment vans.
 */
class AlertMonitoringSystem implements ATMListener {
    @Override
    public void onEvent(ATMEvent event, String message) {
        System.out.println("[MONITORING ALERT] Received Event: " + event + " -> " + message);
        if (event == ATMEvent.CASH_LOW) {
            System.out.println("[MONITORING ALERT] CRITICAL: Scheduling cash replenishment van immediately!");
        }
    }
}

// =========================================================================
// 2. STRATEGY PATTERN (Account Rules, Overdraft limits, Fees)
// =========================================================================
interface WithdrawalStrategy {
    boolean validateAndExecute(Account account, double amount);
}

class StandardSavingsStrategy implements WithdrawalStrategy {
    private static final double DAILY_LIMIT = 500.0;

    @Override
    public boolean validateAndExecute(Account account, double amount) {
        if (amount > DAILY_LIMIT) {
            System.out.println("[Strategy: Savings] Rejected: Exceeds daily savings limit of $" + DAILY_LIMIT);
            return false;
        }
        if (account.getBalance() < amount) {
            System.out.println("[Strategy: Savings] Rejected: Insufficient balance.");
            return false;
        }
        account.setBalance(account.getBalance() - amount);
        return true;
    }
}

class PremiumCheckingStrategy implements WithdrawalStrategy {
    private static final double OVERDRAFT_LIMIT = 200.0;

    @Override
    public boolean validateAndExecute(Account account, double amount) {
        // Checking accounts support overdraft protection up to a specific limit
        if (account.getBalance() + OVERDRAFT_LIMIT < amount) {
            System.out.println("[Strategy: Checking] Rejected: Exceeds available funds + overdraft cushion.");
            return false;
        }
        account.setBalance(account.getBalance() - amount);
        return true;
    }
}

// =========================================================================
// 3. COMMAND PATTERN (Atomic Transactions & Structural Rollbacks)
// =========================================================================
interface TransactionCommand {
    boolean execute();
    void undo();
}

class WithdrawActionCommand implements TransactionCommand {
    private final Account account;
    private final ATMMachine atm;
    private final double amount;
    private boolean accountDebited = false;

    public WithdrawActionCommand(Account account, ATMMachine atm, double amount) {
        this.account = account;
        this.atm = atm;
        this.amount = amount;
    }

    @Override
    public boolean execute() {
        System.out.println("[Command] Beginning atomic processing for withdrawal...");

        // Step 1: Attempt to debit user account using its specific strategy rules
        if (account.performWithdrawal(amount)) {
            accountDebited = true;
            System.out.println("[Command] Account balance successfully debited dynamically.");

            // Step 2: Attempt physical hardware vault deployment
            if (atm.deductVaultCash(amount)) {
                atm.notifyObservers(ATMEvent.TRANSACTION_SUCCESS, "Successfully dispensed $" + amount + " to account " + account.getAccountId());
                return true;
            } else {
                System.out.println("[Command] HARDWARE WARNING: Cash dispensing mechanism failure or empty vault!");
                undo(); // Trigger explicit structural transactional rollback
                return false;
            }
        }

        atm.notifyObservers(ATMEvent.TRANSACTION_FAILED, "Withdrawal execution blocked by strategy rules.");
        return false;
    }

    @Override
    public void undo() {
        if (accountDebited) {
            System.out.println("[Command: Rollback] Initiating automated ledger correction...");
            account.deposit(amount); // Re-credit account balance
            System.out.println("[Command: Rollback] Ledger balance restored cleanly.");
            atm.notifyObservers(ATMEvent.TRANSACTION_FAILED, "Hardware failure rollback processed for account " + account.getAccountId());
        }
    }
}
