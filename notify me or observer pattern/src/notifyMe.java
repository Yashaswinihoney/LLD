import java.util.ArrayList;
import java.util.List;

// 1. Observer Interface
interface NotificationAlertObserver {
    void update();
}

// 2. Observable (Subject) Interface
interface StockObservable {
    void add(NotificationAlertObserver observer);
    void remove(NotificationAlertObserver observer);
    void notifyObservers();
    void setStockCount(int newStockAdded);
    int getStockCount();
}

// 3. Concrete Observable
class IphoneObservableImpl implements StockObservable {
    private final List<NotificationAlertObserver> observerList = new ArrayList<>();
    private int stockCount = 0;

    @Override
    public void add(NotificationAlertObserver observer) {
        // Synchronized to prevent concurrent modification of the list
        synchronized (observerList) {
            observerList.add(observer);
        }
    }

    @Override
    public void remove(NotificationAlertObserver observer) {
        synchronized (observerList) {
            observerList.remove(observer);
        }
    }

    @Override
    public void notifyObservers() {
        // Create a snapshot of observers to avoid holding the lock during the update calls
        List<NotificationAlertObserver> snapshot;
        synchronized (observerList) {
            snapshot = new ArrayList<>(observerList);
        }

        for (NotificationAlertObserver observer : snapshot) {
            observer.update();
        }
    }

    @Override
    public void setStockCount(int newStockAdded) {
        // Synchronized on the object instance to ensure atomic stock updates
        synchronized (this) {
            if (this.stockCount == 0 && newStockAdded > 0) {
                this.stockCount += newStockAdded;
                notifyObservers();
            } else {
                this.stockCount += newStockAdded;
            }
        }
    }

    @Override
    public synchronized int getStockCount() {
        return stockCount;
    }
}

// 4. Concrete Observers
class EmailAlertObserverImpl implements NotificationAlertObserver {
    private final String emailId;
    private final StockObservable observable;

    public EmailAlertObserverImpl(String email, StockObservable obs) {
        this.emailId = email;
        this.observable = obs;
    }

    @Override
    public void update() {
        sendMail(emailId, "Product is back in stock! Current count: " + observable.getStockCount());
    }

    private void sendMail(String emailId, String msg) {
        System.out.println("Mail sent to " + emailId + ": " + msg);
    }
}

class MobileAlertObserverImpl implements NotificationAlertObserver {
    private final String userName;
    private final StockObservable observable;

    public MobileAlertObserverImpl(String user, StockObservable obs) {
        this.userName = user;
        this.observable = obs;
    }

    @Override
    public void update() {
        sendMessage(userName, "Mobile Alert: Product is back in stock!");
    }

    private void sendMessage(String user, String msg) {
        System.out.println("Mobile notification sent to " + user + ": " + msg);
    }
}

