import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// ==========================================
// GENERIC GAME FRAMEWORK
// Strategy  — GameStrategy (one per game type)
// Factory   — GameFactory  (creates the right strategy)
// Singleton — GameManager  (manages all active sessions)
// Context   — GameSession  (holds strategy, delegates all calls)
// ==========================================

// ==========================================
// 1. PLAYER (shared across all games)
// ==========================================
class Player {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final String name;

    public Player(String name) {
        this.id = idGen.getAndIncrement();
        this.name = name;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    @Override public String toString() { return name; }
}

// ==========================================
// 2. MOVE (generic — each strategy interprets it)
// ==========================================
class Move {
    private final Player player;
    private final int fromRow, fromCol; // used by Chess
    private final int toRow,   toCol;   // used by Chess + TicTacToe
    private final Map<String, Object> extra = new HashMap<>(); // extensible

    // Chess / grid move
    public Move(Player player, int fromRow, int fromCol, int toRow, int toCol) {
        this.player = player;
        this.fromRow = fromRow; this.fromCol = fromCol;
        this.toRow = toRow;     this.toCol = toCol;
    }

    // Single-cell move (TicTacToe)
    public Move(Player player, int row, int col) {
        this(player, -1, -1, row, col);
    }

    // No-position move (Snake & Ladders — just roll dice)
    public Move(Player player) {
        this(player, -1, -1, -1, -1);
    }

    public Player getPlayer()  { return player; }
    public int getFromRow()    { return fromRow; }
    public int getFromCol()    { return fromCol; }
    public int getToRow()      { return toRow; }
    public int getToCol()      { return toCol; }

    public void put(String key, Object value) { extra.put(key, value); }
    public Object get(String key)             { return extra.get(key); }

    @Override public String toString() {
        if (fromRow == -1 && toRow == -1) return player.getName() + " rolls dice";
        if (fromRow == -1) return player.getName() + " -> (" + toRow + "," + toCol + ")";
        return player.getName() + ": (" + fromRow + "," + fromCol + ") -> (" + toRow + "," + toCol + ")";
    }
}

// ==========================================
// 3. GAME STATUS
// ==========================================
enum GameStatus { WAITING, IN_PROGRESS, FINISHED }

// ==========================================
// 4. GAME RESULT (returned at end)
// ==========================================
class GameResult {
    private final Player winner; // null = draw
    private final String reason; // "checkmate", "reached 100", "board full"
    private final boolean isDraw;

    public GameResult(Player winner, String reason) {
        this.winner = winner;
        this.reason = reason;
        this.isDraw = (winner == null);
    }

    public Player getWinner() { return winner; }
    public String getReason() { return reason; }
    public boolean isDraw()   { return isDraw; }

    @Override public String toString() {
        return isDraw
            ? "Draw — " + reason
            : winner.getName() + " wins! (" + reason + ")";
    }
}

// ==========================================
// 5. STRATEGY INTERFACE — the core pattern
// ==========================================
interface GameStrategy {
    String getGameName();
    void   initialize(List<Player> players);
    boolean makeMove(Move move);         // returns true if move succeeded
    boolean isGameOver();
    GameResult getResult();              // null if game not over
    String  getBoardState();             // text representation of board
    Player  getCurrentPlayer();
    List<String> getValidMoveHints();    // helpful for UI / AI
}

// ==========================================
// 6A. STRATEGY: SNAKE & LADDERS
// ==========================================
class SnakeAndLaddersStrategy implements GameStrategy {
    private List<Player> players;
    private final Map<Integer, Integer> snakes  = new HashMap<>();
    private final Map<Integer, Integer> ladders = new HashMap<>();
    private final Map<Player, Integer>  positions = new LinkedHashMap<>();
    private int currentIndex = 0;
    private boolean gameOver = false;
    private GameResult result;
    private final Random random = new Random();
    private final int boardSize;

    public SnakeAndLaddersStrategy(int boardSize,
                                   Map<Integer, Integer> snakes,
                                   Map<Integer, Integer> ladders) {
        this.boardSize = boardSize;
        this.snakes.putAll(snakes);
        this.ladders.putAll(ladders);
    }

    @Override
    public String getGameName() { return "Snake & Ladders (" + boardSize + " cells)"; }

    @Override
    public void initialize(List<Player> players) {
        this.players = players;
        players.forEach(p -> positions.put(p, 0));
        System.out.println("[" + getGameName() + "] Initialized with players: " + players);
    }

    @Override
    public boolean makeMove(Move move) {
        if (gameOver) { System.out.println("Game is over."); return false; }

        Player current = getCurrentPlayer();
        if (!move.getPlayer().equals(current)) {
            System.out.println("[S&L] Not " + move.getPlayer().getName() + "'s turn.");
            return false;
        }

        int roll = random.nextInt(6) + 1;
        int pos  = positions.get(current) + roll;
        System.out.println("[S&L] " + current.getName() + " rolled " + roll);

        if (pos > boardSize) {
            System.out.println("[S&L] " + current.getName() + " needs exact roll. Stays at " + positions.get(current));
        } else {
            if (snakes.containsKey(pos)) {
                int tail = snakes.get(pos);
                System.out.println("[S&L] SNAKE at " + pos + " → slides to " + tail);
                pos = tail;
            } else if (ladders.containsKey(pos)) {
                int top = ladders.get(pos);
                System.out.println("[S&L] LADDER at " + pos + " → climbs to " + top);
                pos = top;
            }
            positions.put(current, pos);
            System.out.println("[S&L] " + current.getName() + " now at " + pos);

            if (pos >= boardSize) {
                gameOver = true;
                result = new GameResult(current, "reached position " + boardSize);
                System.out.println("[S&L] " + result);
                return true;
            }
        }

        currentIndex = (currentIndex + 1) % players.size();
        return true;
    }

    @Override public boolean    isGameOver()       { return gameOver; }
    @Override public GameResult getResult()        { return result; }
    @Override public Player     getCurrentPlayer() { return players.get(currentIndex); }

    @Override
    public String getBoardState() {
        StringBuilder sb = new StringBuilder("[Board] Positions: ");
        positions.forEach((p, pos) -> sb.append(p.getName()).append("@").append(pos).append("  "));
        return sb.toString();
    }

    @Override
    public List<String> getValidMoveHints() {
        return List.of(getCurrentPlayer().getName() + ": Roll dice (1-6)");
    }
}

// ==========================================
// 6B. STRATEGY: TIC TAC TOE
// ==========================================
class TicTacToeStrategy implements GameStrategy {
    private List<Player> players;
    private final char[][] board = new char[3][3];
    private int currentIndex = 0;
    private boolean gameOver = false;
    private GameResult result;
    private final char[] symbols = {'X', 'O'};

    @Override public String getGameName() { return "Tic Tac Toe (3x3)"; }

    @Override
    public void initialize(List<Player> players) {
        if (players.size() != 2) throw new IllegalArgumentException("TicTacToe needs exactly 2 players");
        this.players = players;
        for (char[] row : board) Arrays.fill(row, '.');
        System.out.println("[TicTacToe] Initialized: " + players.get(0) + "(X) vs " + players.get(1) + "(O)");
    }

    @Override
    public boolean makeMove(Move move) {
        if (gameOver) { System.out.println("Game is over."); return false; }

        Player current = getCurrentPlayer();
        if (!move.getPlayer().equals(current)) {
            System.out.println("[TicTacToe] Not " + move.getPlayer().getName() + "'s turn.");
            return false;
        }

        int row = move.getToRow(), col = move.getToCol();
        if (row < 0 || row > 2 || col < 0 || col > 2 || board[row][col] != '.') {
            System.out.println("[TicTacToe] Invalid cell (" + row + "," + col + ")");
            return false;
        }

        char symbol = symbols[currentIndex];
        board[row][col] = symbol;
        System.out.println("[TicTacToe] " + current.getName() + "(" + symbol + ") plays (" + row + "," + col + ")");

        if (checkWin(symbol)) {
            gameOver = true;
            result = new GameResult(current, "three in a row");
            System.out.println("[TicTacToe] " + result);
        } else if (isBoardFull()) {
            gameOver = true;
            result = new GameResult(null, "board full");
            System.out.println("[TicTacToe] " + result);
        } else {
            currentIndex = (currentIndex + 1) % players.size();
        }
        return true;
    }

    private boolean checkWin(char s) {
        for (int i = 0; i < 3; i++) {
            if (board[i][0] == s && board[i][1] == s && board[i][2] == s) return true;
            if (board[0][i] == s && board[1][i] == s && board[2][i] == s) return true;
        }
        return (board[0][0] == s && board[1][1] == s && board[2][2] == s) ||
               (board[0][2] == s && board[1][1] == s && board[2][0] == s);
    }

    private boolean isBoardFull() {
        for (char[] row : board) for (char c : row) if (c == '.') return false;
        return true;
    }

    @Override public boolean    isGameOver()       { return gameOver; }
    @Override public GameResult getResult()        { return result; }
    @Override public Player     getCurrentPlayer() { return players.get(currentIndex); }

    @Override
    public String getBoardState() {
        StringBuilder sb = new StringBuilder("\n  0 1 2\n");
        for (int r = 0; r < 3; r++) {
            sb.append(r).append(" ");
            for (int c = 0; c < 3; c++) sb.append(board[r][c]).append(" ");
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public List<String> getValidMoveHints() {
        List<String> hints = new ArrayList<>();
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                if (board[r][c] == '.') hints.add("(" + r + "," + c + ")");
        return hints;
    }
}

// ==========================================
// 6C. STRATEGY: CHESS (simplified — key moves)
// ==========================================
enum PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
enum Color { WHITE, BLACK }

class Piece {
    final PieceType type;
    final Color color;
    boolean hasMoved = false;

    public Piece(PieceType type, Color color) {
        this.type = type; this.color = color;
    }

    @Override public String toString() {
        String sym = switch (type) {
            case KING -> "K"; case QUEEN -> "Q"; case ROOK -> "R";
            case BISHOP -> "B"; case KNIGHT -> "N"; case PAWN -> "P";
        };
        return (color == Color.WHITE ? "W" : "B") + sym;
    }
}

class ChessStrategy implements GameStrategy {
    private List<Player> players; // index 0 = WHITE, index 1 = BLACK
    private final Piece[][] board = new Piece[8][8];
    private int currentIndex = 0;
    private boolean gameOver = false;
    private GameResult result;

    @Override public String getGameName() { return "Chess (8x8)"; }

    @Override
    public void initialize(List<Player> players) {
        if (players.size() != 2) throw new IllegalArgumentException("Chess needs exactly 2 players");
        this.players = players;
        setupBoard();
        System.out.println("[Chess] " + players.get(0) + "(WHITE) vs " + players.get(1) + "(BLACK)");
    }

    private void setupBoard() {
        // Back rank
        PieceType[] backRank = {PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP,
                                 PieceType.QUEEN, PieceType.KING, PieceType.BISHOP,
                                 PieceType.KNIGHT, PieceType.ROOK};
        for (int c = 0; c < 8; c++) {
            board[0][c] = new Piece(backRank[c], Color.WHITE);
            board[7][c] = new Piece(backRank[c], Color.BLACK);
            board[1][c] = new Piece(PieceType.PAWN, Color.WHITE);
            board[6][c] = new Piece(PieceType.PAWN, Color.BLACK);
        }
    }

    @Override
    public boolean makeMove(Move move) {
        if (gameOver) { System.out.println("Game is over."); return false; }

        Player current = getCurrentPlayer();
        Color  color   = currentIndex == 0 ? Color.WHITE : Color.BLACK;

        if (!move.getPlayer().equals(current)) {
            System.out.println("[Chess] Not " + move.getPlayer().getName() + "'s turn.");
            return false;
        }

        int fr = move.getFromRow(), fc = move.getFromCol();
        int tr = move.getToRow(),   tc = move.getToCol();

        Piece piece = board[fr][fc];
        if (piece == null || piece.color != color) {
            System.out.println("[Chess] No valid piece at (" + fr + "," + fc + ")");
            return false;
        }

        Piece captured = board[tr][tc];
        board[tr][tc] = piece;
        board[fr][fc] = null;
        piece.hasMoved = true;

        System.out.println("[Chess] " + current.getName() + " moves " + piece +
            ": (" + fr + "," + fc + ") → (" + tr + "," + tc + ")" +
            (captured != null ? " captures " + captured : ""));

        // Check if king captured (simplified checkmate detection)
        if (captured != null && captured.type == PieceType.KING) {
            gameOver = true;
            result = new GameResult(current, "captured the King (checkmate)");
            System.out.println("[Chess] " + result);
            return true;
        }

        currentIndex = (currentIndex + 1) % players.size();
        return true;
    }

    @Override public boolean    isGameOver()       { return gameOver; }
    @Override public GameResult getResult()        { return result; }
    @Override public Player     getCurrentPlayer() { return players.get(currentIndex); }

    @Override
    public String getBoardState() {
        StringBuilder sb = new StringBuilder("\n   a   b   c   d   e   f   g   h\n");
        for (int r = 7; r >= 0; r--) {
            sb.append(r + 1).append(" ");
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                sb.append(p == null ? "... " : p + " ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public List<String> getValidMoveHints() {
        return List.of("Move any " + (currentIndex == 0 ? "WHITE" : "BLACK") +
            " piece — format: (fromRow,fromCol) → (toRow,toCol)");
    }
}

// ==========================================
// 7. FACTORY PATTERN — creates the right strategy
// ==========================================
class GameFactory {
    public enum GameType { SNAKE_AND_LADDERS, SNAKE_AND_LADDERS_MINI, TIC_TAC_TOE, CHESS }

    public static GameStrategy create(GameType type) {
        return switch (type) {
            case SNAKE_AND_LADDERS -> new SnakeAndLaddersStrategy(
                100,
                Map.of(99,54, 70,55, 52,42, 25,2, 61,19, 87,24),
                Map.of(4,14,  9,31,  20,38, 28,84, 40,59, 63,81, 71,91)
            );
            case SNAKE_AND_LADDERS_MINI -> new SnakeAndLaddersStrategy(
                25,
                Map.of(24,5, 17,3),
                Map.of(2,10, 8,22)
            );
            case TIC_TAC_TOE -> new TicTacToeStrategy();
            case CHESS        -> new ChessStrategy();
        };
    }
}

// ==========================================
// 8. OBSERVER PATTERN — game event notifications
// ==========================================
interface GameObserver {
    void onMoveCompleted(GameSession session, Move move);
    void onGameOver(GameSession session, GameResult result);
}

class ConsoleGameLogger implements GameObserver {
    @Override
    public void onMoveCompleted(GameSession session, Move move) {
        System.out.println("[Log] Move in Game#" + session.getId() +
            " (" + session.getGameName() + "): " + move);
    }
    @Override
    public void onGameOver(GameSession session, GameResult result) {
        System.out.println("[Log] Game#" + session.getId() + " OVER: " + result);
    }
}

class LeaderboardUpdater implements GameObserver {
    @Override
    public void onMoveCompleted(GameSession session, Move move) { /* no-op */ }

    @Override
    public void onGameOver(GameSession session, GameResult result) {
        if (!result.isDraw()) {
            System.out.println("[Leaderboard] +1 win for " + result.getWinner().getName());
        } else {
            System.out.println("[Leaderboard] Draw recorded.");
        }
    }
}

// ==========================================
// 9. GAME SESSION — CONTEXT (holds the strategy)
// The key class — completely game-agnostic
// ==========================================
class GameSession {
    private static final AtomicInteger idGen = new AtomicInteger(1);
    private final int id;
    private final GameStrategy strategy;   // ← THE STRATEGY
    private final List<Player> players;
    private GameStatus status;
    private final List<Move> moveHistory = new ArrayList<>();
    private final List<GameObserver> observers;

    public GameSession(GameStrategy strategy, List<Player> players,
                       List<GameObserver> observers) {
        this.id = idGen.getAndIncrement();
        this.strategy = strategy;
        this.players = players;
        this.observers = observers;
        this.status = GameStatus.WAITING;
    }

    public void start() {
        strategy.initialize(players);
        status = GameStatus.IN_PROGRESS;
        System.out.println("\n[Session#" + id + "] Started: " + strategy.getGameName());
        System.out.println("[Session#" + id + "] Players: " + players);
    }

    // Single entry point — works for ALL game types
    public boolean play(Move move) {
        if (status != GameStatus.IN_PROGRESS) {
            System.out.println("[Session#" + id + "] Not in progress.");
            return false;
        }

        boolean success = strategy.makeMove(move);

        if (success) {
            moveHistory.add(move);
            observers.forEach(o -> o.onMoveCompleted(this, move));

            if (strategy.isGameOver()) {
                status = GameStatus.FINISHED;
                GameResult result = strategy.getResult();
                observers.forEach(o -> o.onGameOver(this, result));
            }
        }

        return success;
    }

    public void printBoard() {
        System.out.println(strategy.getBoardState());
    }

    public void printHints() {
        System.out.println("[Hints] " + strategy.getValidMoveHints());
    }

    // Convenience: auto-play until game ends (good for S&L)
    public void autoPlay(int maxTurns) {
        int turn = 0;
        while (!strategy.isGameOver() && turn < maxTurns) {
            Player current = strategy.getCurrentPlayer();
            play(new Move(current)); // no-position move (S&L)
            printBoard();
            turn++;
        }
        if (!strategy.isGameOver()) {
            System.out.println("[Session#" + id + "] Max turns reached.");
        }
    }

    public int getId()              { return id; }
    public String getGameName()     { return strategy.getGameName(); }
    public GameStatus getStatus()   { return status; }
    public GameResult getResult()   { return strategy.getResult(); }
    public List<Move> getMoveHistory() { return Collections.unmodifiableList(moveHistory); }
    public Player getCurrentPlayer(){ return strategy.getCurrentPlayer(); }
}

// ==========================================
// 10. GAME MANAGER — SINGLETON
// Creates sessions, manages lifecycle
// ==========================================
class GameManager {
    private static GameManager instance;
    private final Map<Integer, GameSession> sessions = new ConcurrentHashMap<>();
    private final List<GameObserver> defaultObservers;

    private GameManager() {
        defaultObservers = List.of(new ConsoleGameLogger(), new LeaderboardUpdater());
    }

    public static synchronized GameManager getInstance() {
        if (instance == null) instance = new GameManager();
        return instance;
    }

    // Single method to create ANY game type
    public GameSession createSession(GameFactory.GameType type, List<Player> players) {
        GameStrategy strategy = GameFactory.create(type);
        GameSession session = new GameSession(strategy, players, defaultObservers);
        sessions.put(session.getId(), session);
        System.out.println("[GameManager] Created Session#" + session.getId() +
            " → " + strategy.getGameName());
        return session;
    }

    public GameSession getSession(int id) { return sessions.get(id); }

    public boolean play(int sessionId, Move move) {
        GameSession s = sessions.get(sessionId);
        if (s == null) { System.out.println("Session not found: " + sessionId); return false; }
        return s.play(move);
    }

    public Map<Integer, GameSession> getAllSessions() { return sessions; }

    public void printStats() {
        System.out.println("\n[GameManager] Total sessions: " + sessions.size());
        sessions.forEach((id, s) ->
            System.out.println("  Session#" + id + " | " + s.getGameName() +
                " | " + s.getStatus() +
                (s.getResult() != null ? " | " + s.getResult() : "")));
    }
}

// ==========================================
// 11. MAIN — demonstrates Strategy swapping
// Same GameManager, same GameSession.play()
// Different strategies = different games
// ==========================================
public class GameFramework {
    public static void main(String[] args) {
        GameManager manager = GameManager.getInstance();

        Player alice = new Player("Alice");
        Player bob   = new Player("Bob");
        Player carol = new Player("Carol");

        // ===== GAME 1: TIC TAC TOE =====
        System.out.println("\n" + "=".repeat(50));
        System.out.println("GAME 1: TIC TAC TOE");
        System.out.println("=".repeat(50));

        GameSession ttt = manager.createSession(
            GameFactory.GameType.TIC_TAC_TOE, List.of(alice, bob));
        ttt.start();
        ttt.printBoard();

        ttt.play(new Move(alice, 0, 0)); // Alice: top-left
        ttt.play(new Move(bob,   1, 1)); // Bob:   center
        ttt.play(new Move(alice, 0, 1)); // Alice: top-middle
        ttt.play(new Move(bob,   2, 2)); // Bob:   bottom-right
        ttt.play(new Move(alice, 0, 2)); // Alice: top-right — WINS
        ttt.printBoard();

        // ===== GAME 2: SNAKE AND LADDERS =====
        System.out.println("\n" + "=".repeat(50));
        System.out.println("GAME 2: SNAKE & LADDERS (Mini Board)");
        System.out.println("=".repeat(50));

        GameSession snl = manager.createSession(
            GameFactory.GameType.SNAKE_AND_LADDERS_MINI, List.of(alice, bob, carol));
        snl.start();
        snl.autoPlay(200); // auto-plays all turns

        // ===== GAME 3: CHESS =====
        System.out.println("\n" + "=".repeat(50));
        System.out.println("GAME 3: CHESS (Opening Moves)");
        System.out.println("=".repeat(50));

        GameSession chess = manager.createSession(
            GameFactory.GameType.CHESS, List.of(alice, bob));
        chess.start();
        chess.printBoard();

        // e2-e4
        chess.play(new Move(alice, 1, 4, 3, 4));
        // e7-e5
        chess.play(new Move(bob,   6, 4, 4, 4));
        // Ng1-f3
        chess.play(new Move(alice, 0, 6, 2, 5));
        // Nb8-c6
        chess.play(new Move(bob,   7, 1, 5, 2));
        // Bf1-c4
        chess.play(new Move(alice, 0, 5, 3, 2));
        // Scholar's mate setup: Qd1-f3
        chess.play(new Move(bob,   7, 3, 5, 3));
        chess.play(new Move(alice, 0, 3, 2, 5));
        // Qf3xf7 — Scholar's mate
        chess.play(new Move(bob,   5, 3, 6, 5));
        chess.play(new Move(alice, 2, 5, 6, 5));
        chess.printBoard();

        // ===== GAME 4: INVALID MOVE TEST =====
        System.out.println("\n" + "=".repeat(50));
        System.out.println("GAME 4: WRONG TURN + INVALID CELL TESTS");
        System.out.println("=".repeat(50));

        GameSession ttt2 = manager.createSession(
            GameFactory.GameType.TIC_TAC_TOE, List.of(bob, carol));
        ttt2.start();
        ttt2.play(new Move(carol, 0, 0)); // Carol's turn? No — Bob goes first
        ttt2.play(new Move(bob,   0, 0));
        ttt2.play(new Move(carol, 0, 0)); // Already taken
        ttt2.play(new Move(carol, 1, 1));
        ttt2.play(new Move(bob,   9, 9)); // Out of bounds

        // ===== STATS =====
        manager.printStats();
    }
}
