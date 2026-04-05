// ==========================================
// 5. DRIVER CODE
// ==========================================
public class Main {
    public static void main(String[] args) {
        BookMyShow system = new BookMyShow();
        Movie avengers = new Movie("Avengers", 180);

        system.addShow(1, avengers);
        Show avengersShow = system.getShow(1);

        // User A checks seats
        avengersShow.displayAvailableSeats();

        // User A books Seat 5
        avengersShow.bookSeat(5);

        // User B tries to book Seat 5 (Should fail)
        avengersShow.bookSeat(5);

        // User B books Seat 6 (Should pass)
        avengersShow.bookSeat(6);

        avengersShow.displayAvailableSeats();
    }
}