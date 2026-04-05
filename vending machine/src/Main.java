// ==========================================
// 5. MAIN
// ==========================================
public class Main {
    public static void main(String[] args) {
        VendingMachine vm = VendingMachine.getInstance();

        System.out.println("--- Transaction 1 ---");
        vm.insertCoin(20);
        vm.insertCoin(10);
        vm.selectProduct("A1");

        System.out.println("\n--- Transaction 2 (Cancel) ---");
        vm.insertCoin(50);
        vm.cancel();
    }
}