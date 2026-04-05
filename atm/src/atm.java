// --- Account Class (Thread-safe via Method Synchronization) ---
class Account {
    private final String accountId;
    private double balance;

    public Account(String id, double initialBalance) {
        this.accountId = id;
        this.balance = initialBalance;
    }

    // The 'synchronized' keyword on the method locks the entire instance of this Account
    public synchronized boolean withdraw(double amount) {
        if (balance >= amount) {
            balance -= amount;
            System.out.println("[Account] Withdrawn: " + amount + ". New Balance: " + balance);
            return true;
        }
        System.out.println("[Account] Insufficient funds!");
        return false;
    }

    public synchronized double getBalance() {
        return balance;
    }
}

// --- ATM State Interface ---
interface ATMState {
    void insertCard();
    void authenticatePin(int pin);
    void withdrawCash(double amount);
    void ejectCard();
}

// --- Context Class (ATM Machine) ---
class ATMMachine {
    private ATMState currentState;
    private double atmVaultBalance;
    private Account currentAccount;

    // Using a dedicated object for locking is a best practice to avoid 
    // interference with other synchronized methods on the same class.
    private final Object vaultLock = new Object();

    public ATMMachine(double initialCash) {
        this.atmVaultBalance = initialCash;
    }

    public void setState(ATMState newState) {
        this.currentState = newState;
    }

    public void setAccount(Account acc) {
        this.currentAccount = acc;
    }

    public Account getAccount() {
        return currentAccount;
    }

    public boolean deductVaultCash(double amount) {
        // Block-level synchronization
        synchronized (vaultLock) {
            if (atmVaultBalance >= amount) {
                atmVaultBalance -= amount;
                return true;
            }
            return false;
        }
    }

    public void insertCard() { currentState.insertCard(); }
    public void enterPin(int pin) { currentState.authenticatePin(pin); }
    public void withdraw(double amount) { currentState.withdrawCash(amount); }
}

// --- Concrete State ---
class AuthenticatedState implements ATMState {
    private final ATMMachine atm;

    public AuthenticatedState(ATMMachine machine) {
        this.atm = machine;
    }

    @Override
    public void insertCard() {
        System.out.println("Card already inserted.");
    }

    @Override
    public void authenticatePin(int pin) {
        System.out.println("Already authenticated.");
    }

    @Override
    public void withdrawCash(double amount) {
        Account acc = atm.getAccount();
        if (acc == null) return;

        // Still thread-safe because the methods called are synchronized
        if (acc.withdraw(amount)) {
            if (atm.deductVaultCash(amount)) {
                System.out.println("Success! Please collect your cash.");
            } else {
                System.out.println("ATM Error: Out of physical cash. Reverting transaction...");
                // Transactional rollback would go here
            }
        }
    }

    @Override
    public void ejectCard() {
        System.out.println("Card ejected. Returning to Idle State.");
    }
}