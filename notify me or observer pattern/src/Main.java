//excecution : client
public class Main {
    public static void main(String[] args) {
        // Create the Subject
        StockObservable iphoneStockObservable = new IphoneObservableImpl();

        // Create Observers
        NotificationAlertObserver obs1 = new EmailAlertObserverImpl("xyz@gmail.com", iphoneStockObservable);
        NotificationAlertObserver obs2 = new EmailAlertObserverImpl("abc@gmail.com", iphoneStockObservable);
        NotificationAlertObserver obs3 = new MobileAlertObserverImpl("shrayansh_user", iphoneStockObservable);

        // Register Observers
        iphoneStockObservable.add(obs1);
        iphoneStockObservable.add(obs2);
        iphoneStockObservable.add(obs3);

        // Trigger notifications
        System.out.println("--- Setting stock to 10 ---");
        iphoneStockObservable.setStockCount(10);

        System.out.println("\n--- Adding 50 more stock (No notification expected) ---");
        iphoneStockObservable.setStockCount(50);
    }
}