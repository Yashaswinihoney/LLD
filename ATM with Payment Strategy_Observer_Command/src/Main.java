// =========================================================================
// 5. APPLICATION MAIN RUNTIME
// =========================================================================
public class Main {
    public static void main(String[] args) {
        // Instantiate ATM Machine vault with low reserve cash to trigger observers ($1000)
        ATMMachine atm = new ATMMachine(1000.0);

        // Attach the telemetry monitor system subscriber
        AlertMonitoringSystem techCenter = new AlertMonitoringSystem();
        atm.addObserver(techCenter);

        // Define multiple account frameworks utilizing distinct runtime rules (Strategy)
        Account savingsUser = new Account("ACC-SAVINGS-99", 2000.0, new StandardSavingsStrategy());
        Account checkingUser = new Account("ACC-CHECKING-11", 100.0, new PremiumCheckingStrategy());

        System.out.println("--- Scenario 1: Standard Savings Account Strategy Enforcement ---");
        atm.insertCard(savingsUser);
        atm.enterPin(1234);
        // Savings daily single cap rules limit extraction up to $500. This should fail at strategy block.
        atm.withdraw(600.0);

        System.out.println("\n--- Scenario 2: Premium Overdraft Checking Strategy + Observer Level Alert ---");
        atm.insertCard(checkingUser);
        atm.enterPin(1234);
        // Checking has $100 base but strategy allows a $200 cushion limit. Total request is $250.
        // Vault tracks transaction deduction: Remaining drops from $1000 to $750 (triggers cash low observer info)
        atm.withdraw(250.0);

        System.out.println("\n--- Scenario 3: Hardware Failure Command Rollback Transaction ---");
        atm.insertCard(savingsUser);
        atm.enterPin(1234);
        // Request $800 withdrawal. Vault only has $750 left.
        // System successfully debits user balance -> discovers hardware shortage -> runs transaction undo rollback.
        atm.withdraw(800.0);
    }
}