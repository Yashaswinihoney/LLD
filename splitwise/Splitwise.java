import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

// ==========================================
// 1. ENUMS
// ==========================================
enum SplitType { EQUAL, EXACT, PERCENT }

// ==========================================
// 2. USER
// ==========================================
class User {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;
    private final String email;

    public User(String name, String email) {
        this.id = idGen.getAndIncrement();
        this.name = name;
        this.email = email;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}

// ==========================================
// 3. SPLIT MODELS
// ==========================================
abstract class Split {
    protected final User user;
    protected double amount; // each split's resolved share

    public Split(User user) {
        this.user = user;
    }

    public User getUser() { return user; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}

class EqualSplit extends Split {
    public EqualSplit(User user) {
        super(user);
    }
}

class ExactSplit extends Split {
    public ExactSplit(User user, double exactAmount) {
        super(user);
        this.amount = exactAmount;
    }
}

class PercentSplit extends Split {
    private final double percent;

    public PercentSplit(User user, double percent) {
        super(user);
        this.percent = percent;
    }

    public double getPercent() { return percent; }
}

// ==========================================
// 4. STRATEGY PATTERN — SPLIT CALCULATION
// ==========================================
interface SplitStrategy {
    /**
     * Resolves and sets the 'amount' field on each Split object.
     * Throws IllegalArgumentException if validation fails.
     */
    void calculateSplits(double totalAmount, List<Split> splits);
}

class EqualSplitStrategy implements SplitStrategy {
    @Override
    public void calculateSplits(double totalAmount, List<Split> splits) {
        double share = totalAmount / splits.size();
        for (Split s : splits) {
            s.setAmount(share);
        }
    }
}

class ExactSplitStrategy implements SplitStrategy {
    @Override
    public void calculateSplits(double totalAmount, List<Split> splits) {
        double sum = splits.stream().mapToDouble(Split::getAmount).sum();
        if (Math.abs(sum - totalAmount) > 0.01) {
            throw new IllegalArgumentException(
                "Exact amounts sum to " + sum + " but total is " + totalAmount);
        }
        // amounts already set on ExactSplit at construction — nothing to do
    }
}

class PercentSplitStrategy implements SplitStrategy {
    @Override
    public void calculateSplits(double totalAmount, List<Split> splits) {
        double totalPercent = splits.stream()
            .mapToDouble(s -> ((PercentSplit) s).getPercent()).sum();
        if (Math.abs(totalPercent - 100.0) > 0.01) {
            throw new IllegalArgumentException(
                "Percentages sum to " + totalPercent + ", must be 100");
        }
        for (Split s : splits) {
            double share = ((PercentSplit) s).getPercent() / 100.0 * totalAmount;
            s.setAmount(share);
        }
    }
}

// ==========================================
// 5. EXPENSE
// ==========================================
class Expense {
    private static final AtomicInteger idGen = new AtomicInteger(1000);
    private final int id;
    private final String description;
    private final double amount;
    private final User paidBy;
    private final List<Split> splits;
    private final SplitType splitType;

    public Expense(String description, double amount, User paidBy,
                   List<Split> splits, SplitType splitType) {
        this.id = idGen.getAndIncrement();
        this.description = description;
        this.amount = amount;
        this.paidBy = paidBy;
        this.splits = splits;
        this.splitType = splitType;
    }

    public int getId() { return id; }
    public double getAmount() { return amount; }
    public User getPaidBy() { return paidBy; }
    public List<Split> getSplits() { return splits; }
    public String getDescription() { return description; }
}

// ==========================================
// 6. GROUP
// ==========================================
class Group {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;
    private final List<User> members = new ArrayList<>();
    private final List<Expense> expenses = new ArrayList<>();

    public Group(String name) {
        this.id = idGen.getAndIncrement();
        this.name = name;
    }

    public void addMember(User u) { members.add(u); }
    public void addExpense(Expense e) { expenses.add(e); }
    public List<User> getMembers() { return members; }
    public List<Expense> getExpenses() { return expenses; }
    public String getName() { return name; }
}

// ==========================================
// 7. EXPENSE MANAGER (SINGLETON — core controller)
// ==========================================
class ExpenseManager {
    private static ExpenseManager instance;

    // balanceSheet[A][B] = X means A owes B ₹X (positive = owes, negative = is owed)
    private final Map<Integer, Map<Integer, Double>> balanceSheet = new HashMap<>();
    private final List<Group> groups = new ArrayList<>();

    private ExpenseManager() {}

    public static synchronized ExpenseManager getInstance() {
        if (instance == null) instance = new ExpenseManager();
        return instance;
    }

    // ---- Add Expense ----
    public Expense addExpense(String desc, double amount, User paidBy,
                              List<Split> splits, SplitType type) {
        // 1. Resolve amounts via strategy
        SplitStrategy strategy = getStrategy(type);
        strategy.calculateSplits(amount, splits);

        // 2. Create expense
        Expense expense = new Expense(desc, amount, paidBy, splits, type);

        // 3. Update balance sheet
        for (Split split : splits) {
            User debtor = split.getUser();
            if (debtor.getId() == paidBy.getId()) continue; // payer owes nothing

            // debtor owes paidBy
            updateBalance(debtor, paidBy, split.getAmount());
        }

        System.out.println("\n[Expense Added] " + desc + " | ₹" + amount
            + " paid by " + paidBy.getName());
        return expense;
    }

    private void updateBalance(User debtor, User creditor, double amount) {
        balanceSheet
            .computeIfAbsent(debtor.getId(), k -> new HashMap<>())
            .merge(creditor.getId(), amount, Double::sum);

        // Also mirror the negative side for easy lookup
        balanceSheet
            .computeIfAbsent(creditor.getId(), k -> new HashMap<>())
            .merge(debtor.getId(), -amount, Double::sum);
    }

    // ---- Show balances for a user ----
    public void showBalances(User user) {
        System.out.println("\n--- Balances for " + user.getName() + " ---");
        Map<Integer, Double> userBalances = balanceSheet.get(user.getId());

        if (userBalances == null || userBalances.isEmpty()) {
            System.out.println("All settled up!");
            return;
        }

        // Need a user registry to print names — using a simple lookup here
        userBalances.forEach((otherId, amt) -> {
            if (Math.abs(amt) < 0.01) return;
            if (amt > 0) {
                System.out.printf("  %s owes User#%d  ₹%.2f%n",
                    user.getName(), otherId, amt);
            } else {
                System.out.printf("  User#%d owes %s  ₹%.2f%n",
                    otherId, user.getName(), -amt);
            }
        });
    }

    // ---- Show all balances with readable names ----
    public void showAllBalances(List<User> allUsers) {
        Map<Integer, String> nameMap = new HashMap<>();
        for (User u : allUsers) nameMap.put(u.getId(), u.getName());

        System.out.println("\n===== All Balances =====");
        Set<String> printed = new HashSet<>();

        for (User u : allUsers) {
            Map<Integer, Double> row = balanceSheet.get(u.getId());
            if (row == null) continue;
            row.forEach((otherId, amt) -> {
                if (amt <= 0.01) return;
                String key = Math.min(u.getId(), otherId) + "-" + Math.max(u.getId(), otherId);
                if (printed.contains(key)) return;
                printed.add(key);
                System.out.printf("  %s owes %s  ₹%.2f%n",
                    u.getName(), nameMap.get(otherId), amt);
            });
        }
    }

    // ---- Settle up — simplify debts (greedy algorithm) ----
    public void settleUp(List<User> allUsers) {
        System.out.println("\n===== Settle Up (Simplified) =====");

        // Compute net balance for each user
        Map<Integer, Double> net = new HashMap<>();
        for (User u : allUsers) net.put(u.getId(), 0.0);

        Map<Integer, String> nameMap = new HashMap<>();
        for (User u : allUsers) nameMap.put(u.getId(), u.getName());

        balanceSheet.forEach((userId, row) -> {
            row.forEach((otherId, amt) -> {
                net.merge(userId, amt, Double::sum);
            });
        });

        // Split into creditors (net > 0) and debtors (net < 0)
        // Wait — our balance means "owes", so debtor has positive net (owes others)
        // Let's recompute: net credit = sum of what others owe you minus what you owe
        // Simpler: use the existing balanceSheet which already nets positives/negatives

        // Build fresh net from raw direction: payer is creditor
        Map<Integer, Double> netCredit = new HashMap<>();
        for (User u : allUsers) netCredit.put(u.getId(), 0.0);

        balanceSheet.forEach((userId, row) -> {
            row.forEach((otherId, amt) -> {
                // amt > 0 means userId owes otherId → userId is debtor
                if (amt > 0.01) {
                    netCredit.merge(userId, -amt, Double::sum);   // debtor
                    netCredit.merge(otherId, amt, Double::sum);   // creditor
                }
            });
        });

        // Deduplicate (each pair counted twice in our map)
        for (int id : netCredit.keySet()) {
            netCredit.put(id, netCredit.get(id) / 2.0);
        }

        // Greedy settle using two lists
        List<int[]> creditors = new ArrayList<>(); // [id, amount*100 as int]
        List<int[]> debtors = new ArrayList<>();

        netCredit.forEach((id, val) -> {
            int cents = (int) Math.round(val * 100);
            if (cents > 0) creditors.add(new int[]{id, cents});
            else if (cents < 0) debtors.add(new int[]{id, -cents});
        });

        creditors.sort((a, b) -> b[1] - a[1]);
        debtors.sort((a, b) -> b[1] - a[1]);

        int i = 0, j = 0;
        while (i < debtors.size() && j < creditors.size()) {
            int[] debtor = debtors.get(i);
            int[] creditor = creditors.get(j);
            int settle = Math.min(debtor[1], creditor[1]);

            System.out.printf("  %s pays %s  ₹%.2f%n",
                nameMap.get(debtor[0]), nameMap.get(creditor[0]), settle / 100.0);

            debtor[1] -= settle;
            creditor[1] -= settle;
            if (debtor[1] == 0) i++;
            if (creditor[1] == 0) j++;
        }
    }

    // ---- Group management ----
    public Group createGroup(String name, List<User> members) {
        Group g = new Group(name);
        for (User u : members) g.addMember(u);
        groups.add(g);
        return g;
    }

    private SplitStrategy getStrategy(SplitType type) {
        switch (type) {
            case EQUAL:   return new EqualSplitStrategy();
            case EXACT:   return new ExactSplitStrategy();
            case PERCENT: return new PercentSplitStrategy();
            default: throw new IllegalArgumentException("Unknown split type");
        }
    }
}

// ==========================================
// 8. MAIN — DRIVER CODE
// ==========================================
public class Splitwise {
    public static void main(String[] args) {
        ExpenseManager em = ExpenseManager.getInstance();

        // Users
        User alice = new User("Alice", "alice@gmail.com");
        User bob   = new User("Bob",   "bob@gmail.com");
        User carol = new User("Carol", "carol@gmail.com");
        User dave  = new User("Dave",  "dave@gmail.com");

        List<User> allUsers = List.of(alice, bob, carol, dave);

        // --- Scenario 1: Equal Split ---
        // Alice pays ₹300 for dinner, split equally among all 4
        List<Split> splits1 = List.of(
            new EqualSplit(alice),
            new EqualSplit(bob),
            new EqualSplit(carol),
            new EqualSplit(dave)
        );
        em.addExpense("Dinner", 300, alice, splits1, SplitType.EQUAL);

        // --- Scenario 2: Exact Split ---
        // Bob pays ₹500 for groceries — Alice: 200, Carol: 150, Dave: 150
        List<Split> splits2 = List.of(
            new ExactSplit(alice, 200),
            new ExactSplit(carol, 150),
            new ExactSplit(dave,  150)
        );
        em.addExpense("Groceries", 500, bob, splits2, SplitType.EXACT);

        // --- Scenario 3: Percent Split ---
        // Carol pays ₹400 for hotel — Alice: 50%, Bob: 30%, Dave: 20%
        List<Split> splits3 = List.of(
            new PercentSplit(alice, 50),
            new PercentSplit(bob,   30),
            new PercentSplit(dave,  20)
        );
        em.addExpense("Hotel", 400, carol, splits3, SplitType.PERCENT);

        // --- Show balances ---
        em.showAllBalances(allUsers);

        // --- Settle up ---
        em.settleUp(allUsers);
    }
}
