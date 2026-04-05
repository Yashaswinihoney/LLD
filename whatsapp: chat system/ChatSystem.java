import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// WHATSAPP / REAL-TIME CHAT SYSTEM LLD
// Patterns:
//   Singleton  — ChatServer (one messaging hub)
//   Observer   — MessageListener (real-time delivery)
//   Strategy   — NotificationStrategy (push / SMS / email)
//   Factory    — MessageFactory (text, image, audio, doc)
//   Builder    — Message construction
//   State      — MessageStatus (SENT → DELIVERED → READ)
//   Command    — Message as a command object
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum MessageType   { TEXT, IMAGE, AUDIO, VIDEO, DOCUMENT, LOCATION }
enum MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }
enum UserStatus    { ONLINE, OFFLINE, AWAY, TYPING }
enum ChatType      { DIRECT, GROUP }

// ==========================================
// 2. USER
// ==========================================
class User {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final  long          id;
    private final  String        name;
    private final  String        phone;
    private        UserStatus    status;
    private        LocalDateTime lastSeen;
    private        String        profilePhoto;

    public User(String name, String phone) {
        this.id          = idGen.getAndIncrement();
        this.name        = name;
        this.phone       = phone;
        this.status      = UserStatus.OFFLINE;
        this.lastSeen    = LocalDateTime.now();
    }

    public void setOnline()  {
        this.status = UserStatus.ONLINE;
        System.out.println("[User] " + name + " is now ONLINE");
    }
    public void setOffline() {
        this.status   = UserStatus.OFFLINE;
        this.lastSeen = LocalDateTime.now();
        System.out.println("[User] " + name + " went OFFLINE");
    }
    public void setTyping()  { this.status = UserStatus.TYPING; }

    public long        getId()       { return id; }
    public String      getName()     { return name; }
    public String      getPhone()    { return phone; }
    public UserStatus  getStatus()   { return status; }
    public LocalDateTime getLastSeen() { return lastSeen; }

    @Override public String toString() { return name + "(" + status + ")"; }
}

// ==========================================
// 3. MESSAGE — BUILDER + STATE PATTERN
// Immutable payload, mutable status (state transitions)
// ==========================================
class Message {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long          id;
    private final  String        chatId;
    private final  User          sender;
    private final  MessageType   type;
    private final  String        content;     // text or media URL
    private final  LocalDateTime sentAt;
    private        MessageStatus status;
    private        LocalDateTime deliveredAt;
    private        LocalDateTime readAt;
    private final  String        replyToId;   // null if not a reply
    private final  Map<String, List<User>> reactions = new ConcurrentHashMap<>();

    private Message(Builder b) {
        this.id        = idGen.getAndIncrement();
        this.chatId    = b.chatId;
        this.sender    = b.sender;
        this.type      = b.type;
        this.content   = b.content;
        this.sentAt    = LocalDateTime.now();
        this.status    = MessageStatus.SENDING;
        this.replyToId = b.replyToId;
    }

    // State transitions — explicit, ordered
    public synchronized void markSent() {
        if (status == MessageStatus.SENDING) {
            status = MessageStatus.SENT;
        }
    }

    public synchronized void markDelivered() {
        if (status == MessageStatus.SENT) {
            status      = MessageStatus.DELIVERED;
            deliveredAt = LocalDateTime.now();
        }
    }

    public synchronized void markRead() {
        if (status == MessageStatus.DELIVERED) {
            status  = MessageStatus.READ;
            readAt  = LocalDateTime.now();
        }
    }

    public synchronized void markFailed() { status = MessageStatus.FAILED; }

    public void addReaction(String emoji, User user) {
        reactions.computeIfAbsent(emoji, k -> new CopyOnWriteArrayList<>()).add(user);
        System.out.println("[Reaction] " + user.getName() + " reacted " + emoji +
            " to message #" + id);
    }

    public long          getId()        { return id; }
    public String        getChatId()    { return chatId; }
    public User          getSender()    { return sender; }
    public MessageType   getType()      { return type; }
    public String        getContent()   { return content; }
    public LocalDateTime getSentAt()    { return sentAt; }
    public MessageStatus getStatus()    { return status; }
    public String        getReplyToId() { return replyToId; }

    @Override public String toString() {
        String preview = content.length() > 40
            ? content.substring(0, 40) + "..." : content;
        return "Msg[#" + id + " | " + sender.getName() + " | " +
               type + " | \"" + preview + "\" | " + status + "]";
    }

    // ---- BUILDER ----
    static class Builder {
        private final String      chatId;
        private final User        sender;
        private       MessageType type    = MessageType.TEXT;
        private       String      content = "";
        private       String      replyToId = null;

        public Builder(String chatId, User sender) {
            this.chatId = chatId;
            this.sender = sender;
        }
        public Builder type(MessageType t)    { this.type = t;      return this; }
        public Builder content(String c)      { this.content = c;   return this; }
        public Builder replyTo(String msgId)  { this.replyToId = msgId; return this; }
        public Message build()                { return new Message(this); }
    }
}

// ==========================================
// 4. FACTORY — CREATE MESSAGES BY TYPE
// ==========================================
class MessageFactory {
    public static Message createText(String chatId, User sender, String text) {
        return new Message.Builder(chatId, sender)
            .type(MessageType.TEXT)
            .content(text)
            .build();
    }

    public static Message createImage(String chatId, User sender, String imageUrl) {
        return new Message.Builder(chatId, sender)
            .type(MessageType.IMAGE)
            .content(imageUrl)
            .build();
    }

    public static Message createAudio(String chatId, User sender, String audioUrl) {
        return new Message.Builder(chatId, sender)
            .type(MessageType.AUDIO)
            .content(audioUrl)
            .build();
    }

    public static Message createLocation(String chatId, User sender,
                                          double lat, double lng) {
        return new Message.Builder(chatId, sender)
            .type(MessageType.LOCATION)
            .content("geo:" + lat + "," + lng)
            .build();
    }

    public static Message createReply(String chatId, User sender,
                                       String replyToId, String text) {
        return new Message.Builder(chatId, sender)
            .type(MessageType.TEXT)
            .content(text)
            .replyTo(replyToId)
            .build();
    }
}

// ==========================================
// 5. CHAT (Direct + Group)
// ==========================================
class Chat {
    private static final AtomicLong idGen = new AtomicLong(1);
    private final  String       id;
    private final  ChatType     type;
    private final  String       name;          // group name or null for direct
    private final  List<User>   members;
    private final  List<Message> messages      = new CopyOnWriteArrayList<>();
    private        User         admin;         // group admin

    // Direct chat constructor
    public Chat(User user1, User user2) {
        this.id      = "chat-" + idGen.getAndIncrement();
        this.type    = ChatType.DIRECT;
        this.name    = user1.getName() + " & " + user2.getName();
        this.members = new CopyOnWriteArrayList<>(List.of(user1, user2));
    }

    // Group chat constructor
    public Chat(String groupName, User admin, List<User> members) {
        this.id      = "chat-" + idGen.getAndIncrement();
        this.type    = ChatType.GROUP;
        this.name    = groupName;
        this.admin   = admin;
        this.members = new CopyOnWriteArrayList<>(members);
        if (!this.members.contains(admin)) this.members.add(admin);
    }

    public synchronized void addMessage(Message m)   { messages.add(m); }

    public synchronized void addMember(User u, User requestedBy) {
        if (type == ChatType.GROUP && requestedBy.equals(admin)) {
            members.add(u);
            System.out.println("[Group] " + u.getName() + " added to " + name);
        } else {
            System.out.println("[Group] Only admin can add members");
        }
    }

    public synchronized void removeMember(User u, User requestedBy) {
        if (type == ChatType.GROUP && requestedBy.equals(admin)) {
            members.remove(u);
            System.out.println("[Group] " + u.getName() + " removed from " + name);
        }
    }

    public List<Message> getMessages()      { return Collections.unmodifiableList(messages); }
    public List<Message> getRecentMessages(int n) {
        int size = messages.size();
        return messages.subList(Math.max(0, size - n), size);
    }

    public String       getId()     { return id; }
    public ChatType     getType()   { return type; }
    public String       getName()   { return name; }
    public List<User>   getMembers(){ return members; }

    @Override public String toString() {
        return "Chat[" + id + ", " + type + ", " + name + ", members=" +
               members.size() + ", msgs=" + messages.size() + "]";
    }
}

// ==========================================
// 6. OBSERVER — MESSAGE LISTENER
// Registered per user, notified on new messages
// ==========================================
interface MessageListener {
    void onMessageReceived(Message message, String chatId);
    void onMessageStatusUpdated(Message message);
    void onTypingIndicator(User typer, String chatId, boolean isTyping);
    void onUserStatusChanged(User user);
}

class InAppMessageListener implements MessageListener {
    private final User subscriber;

    public InAppMessageListener(User subscriber) { this.subscriber = subscriber; }

    @Override
    public void onMessageReceived(Message message, String chatId) {
        System.out.println("[InApp -> " + subscriber.getName() + "] New message in " +
            chatId + ": " + message);
    }

    @Override
    public void onMessageStatusUpdated(Message message) {
        System.out.println("[Status -> " + subscriber.getName() + "] Msg #" +
            message.getId() + " is now " + message.getStatus());
    }

    @Override
    public void onTypingIndicator(User typer, String chatId, boolean isTyping) {
        if (!typer.equals(subscriber)) {
            System.out.println("[Typing -> " + subscriber.getName() + "] " +
                typer.getName() + (isTyping ? " is typing..." : " stopped typing") +
                " in " + chatId);
        }
    }

    @Override
    public void onUserStatusChanged(User user) {
        System.out.println("[Status -> " + subscriber.getName() + "] " +
            user.getName() + " is now " + user.getStatus());
    }
}

// ==========================================
// 7. STRATEGY — OFFLINE NOTIFICATION
// When user is offline, notify via push/SMS/email
// ==========================================
interface NotificationStrategy {
    String getName();
    void notify(User recipient, Message message);
}

class PushNotificationStrategy implements NotificationStrategy {
    @Override public String getName() { return "Push (FCM/APNs)"; }

    @Override
    public void notify(User recipient, Message message) {
        System.out.println("[PushNotif -> " + recipient.getName() + "] " +
            message.getSender().getName() + ": " +
            message.getContent().substring(0, Math.min(30, message.getContent().length())));
    }
}

class SMSNotificationStrategy implements NotificationStrategy {
    @Override public String getName() { return "SMS"; }

    @Override
    public void notify(User recipient, Message message) {
        System.out.println("[SMS -> " + recipient.getPhone() + "] " +
            "New message from " + message.getSender().getName());
    }
}

class EmailNotificationStrategy implements NotificationStrategy {
    @Override public String getName() { return "Email"; }

    @Override
    public void notify(User recipient, Message message) {
        System.out.println("[Email -> " + recipient.getName() + "] " +
            "You have a new message from " + message.getSender().getName());
    }
}

// ==========================================
// 8. CHAT SERVER — SINGLETON
// Central hub: manages chats, users, delivery
// ==========================================
class ChatServer {
    private static ChatServer instance;

    // Registries
    private final Map<String, Chat>               chats       = new ConcurrentHashMap<>();
    private final Map<Long, User>                 users       = new ConcurrentHashMap<>();
    private final Map<Long, MessageListener>      listeners   = new ConcurrentHashMap<>();
    // Offline message queue: userId → list of pending messages
    private final Map<Long, Queue<Message>>       offlineQueue= new ConcurrentHashMap<>();

    // Offline notification strategy (swappable)
    private NotificationStrategy notificationStrategy = new PushNotificationStrategy();

    // Message storage (in-memory; Redis + Cassandra in production)
    private final Map<Long, Message>              messageStore= new ConcurrentHashMap<>();

    // Async delivery thread pool
    private final ExecutorService deliveryPool =
        Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "delivery-worker");
            t.setDaemon(true);
            return t;
        });

    private ChatServer() {}

    public static synchronized ChatServer getInstance() {
        if (instance == null) instance = new ChatServer();
        return instance;
    }

    // ---- User management ----
    public void registerUser(User user) {
        users.put(user.getId(), user);
        System.out.println("[Server] Registered: " + user.getName());
    }

    public void connect(User user, MessageListener listener) {
        user.setOnline();
        listeners.put(user.getId(), listener);

        // Deliver queued offline messages
        Queue<Message> pending = offlineQueue.remove(user.getId());
        if (pending != null && !pending.isEmpty()) {
            System.out.println("[Server] Delivering " + pending.size() +
                " queued messages to " + user.getName());
            pending.forEach(msg -> deliverToUser(user, msg, listener));
        }

        // Notify contacts of online status
        notifyStatusChange(user);
    }

    public void disconnect(User user) {
        user.setOffline();
        listeners.remove(user.getId());
        notifyStatusChange(user);
    }

    // ---- Chat creation ----
    public Chat createDirectChat(User u1, User u2) {
        // Check if chat already exists
        String existingId = findDirectChat(u1, u2);
        if (existingId != null) {
            System.out.println("[Server] Direct chat already exists: " + existingId);
            return chats.get(existingId);
        }
        Chat chat = new Chat(u1, u2);
        chats.put(chat.getId(), chat);
        System.out.println("[Server] Direct chat created: " + chat);
        return chat;
    }

    public Chat createGroupChat(String name, User admin, List<User> members) {
        Chat chat = new Chat(name, admin, members);
        chats.put(chat.getId(), chat);
        System.out.println("[Server] Group chat created: " + chat);
        return chat;
    }

    private String findDirectChat(User u1, User u2) {
        for (Chat c : chats.values()) {
            if (c.getType() == ChatType.DIRECT &&
                c.getMembers().containsAll(List.of(u1, u2))) {
                return c.getId();
            }
        }
        return null;
    }

    // ---- SEND MESSAGE (core flow) ----
    public Message sendMessage(Message message) {
        Chat chat = chats.get(message.getChatId());
        if (chat == null) {
            System.out.println("[Server] Chat not found: " + message.getChatId());
            message.markFailed();
            return message;
        }

        // 1. Store message
        message.markSent();
        chat.addMessage(message);
        messageStore.put(message.getId(), message);
        System.out.println("[Server] Sent: " + message);

        // 2. Deliver to all recipients asynchronously
        List<User> recipients = chat.getMembers().stream()
            .filter(u -> u.getId() != message.getSender().getId())
            .collect(Collectors.toList());

        for (User recipient : recipients) {
            deliveryPool.submit(() -> deliver(message, recipient));
        }

        return message;
    }

    private void deliver(Message message, User recipient) {
        MessageListener listener = listeners.get(recipient.getId());

        if (listener != null && recipient.getStatus() != UserStatus.OFFLINE) {
            // Online: deliver in-app
            deliverToUser(recipient, message, listener);
        } else {
            // Offline: queue + push notification
            offlineQueue
                .computeIfAbsent(recipient.getId(), k -> new LinkedList<>())
                .add(message);
            notificationStrategy.notify(recipient, message);
            System.out.println("[Server] " + recipient.getName() +
                " offline — message queued + push sent");
        }
    }

    private void deliverToUser(User recipient, Message message,
                                MessageListener listener) {
        listener.onMessageReceived(message, message.getChatId());
        message.markDelivered();

        // Notify sender of delivery
        MessageListener senderListener = listeners.get(message.getSender().getId());
        if (senderListener != null) {
            senderListener.onMessageStatusUpdated(message);
        }
    }

    // ---- Read receipt ----
    public void markRead(User reader, String chatId) {
        Chat chat = chats.get(chatId);
        if (chat == null) return;

        chat.getMessages().stream()
            .filter(m -> m.getSender().getId() != reader.getId())
            .filter(m -> m.getStatus() == MessageStatus.DELIVERED)
            .forEach(m -> {
                m.markRead();
                // Notify sender
                MessageListener senderListener = listeners.get(m.getSender().getId());
                if (senderListener != null) senderListener.onMessageStatusUpdated(m);
            });

        System.out.println("[Server] " + reader.getName() +
            " read all messages in " + chatId);
    }

    // ---- Typing indicator ----
    public void sendTypingIndicator(User typer, String chatId, boolean isTyping) {
        Chat chat = chats.get(chatId);
        if (chat == null) return;

        if (isTyping) typer.setTyping();

        chat.getMembers().stream()
            .filter(u -> u.getId() != typer.getId())
            .forEach(u -> {
                MessageListener l = listeners.get(u.getId());
                if (l != null) l.onTypingIndicator(typer, chatId, isTyping);
            });
    }

    // ---- User status broadcast ----
    private void notifyStatusChange(User changedUser) {
        listeners.forEach((userId, listener) -> {
            if (userId != changedUser.getId()) {
                listener.onUserStatusChanged(changedUser);
            }
        });
    }

    // ---- Notification strategy swap ----
    public void setNotificationStrategy(NotificationStrategy strategy) {
        this.notificationStrategy = strategy;
        System.out.println("[Server] Notification strategy: " + strategy.getName());
    }

    // ---- Message history ----
    public List<Message> getHistory(String chatId, int limit) {
        Chat chat = chats.get(chatId);
        return chat == null ? Collections.emptyList() : chat.getRecentMessages(limit);
    }

    // ---- Delete message ----
    public boolean deleteMessage(long messageId, User requestedBy) {
        Message msg = messageStore.get(messageId);
        if (msg == null) return false;
        if (msg.getSender().getId() != requestedBy.getId()) {
            System.out.println("[Server] Cannot delete another user's message");
            return false;
        }
        messageStore.remove(messageId);
        System.out.println("[Server] Message #" + messageId + " deleted by " +
            requestedBy.getName());
        return true;
    }

    public Map<String, Chat> getChats()   { return chats; }
    public int getOnlineUserCount()        { return listeners.size(); }
}

// ==========================================
// 9. MAIN — DRIVER CODE
// ==========================================
public class ChatSystem {
    public static void main(String[] args) throws InterruptedException {

        ChatServer server = ChatServer.getInstance();

        // ---- Register Users ----
        User alice = new User("Alice", "+91-9000000001");
        User bob   = new User("Bob",   "+91-9000000002");
        User carol = new User("Carol", "+91-9000000003");
        User dave  = new User("Dave",  "+91-9000000004");

        server.registerUser(alice);
        server.registerUser(bob);
        server.registerUser(carol);
        server.registerUser(dave);

        // ===== SCENARIO 1: Direct chat =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 1: Direct Chat (Alice ↔ Bob)");
        System.out.println("=".repeat(55));

        // Both come online
        server.connect(alice, new InAppMessageListener(alice));
        server.connect(bob,   new InAppMessageListener(bob));

        Chat directChat = server.createDirectChat(alice, bob);

        // Typing indicator
        server.sendTypingIndicator(alice, directChat.getId(), true);

        // Alice sends text
        Message m1 = server.sendMessage(
            MessageFactory.createText(directChat.getId(), alice, "Hey Bob! How are you?"));

        Thread.sleep(100);

        // Bob reads and replies
        server.markRead(bob, directChat.getId());
        server.sendTypingIndicator(bob, directChat.getId(), true);

        Message m2 = server.sendMessage(
            MessageFactory.createText(directChat.getId(), bob, "I'm great! You?"));

        // Alice replies with a reply-to
        Message m3 = server.sendMessage(
            MessageFactory.createReply(directChat.getId(), alice,
                String.valueOf(m2.getId()), "Doing awesome! Let's catch up."));

        // Bob reacts to message
        m3.addReaction("❤️", bob);

        // Alice sends an image
        Message m4 = server.sendMessage(
            MessageFactory.createImage(directChat.getId(), alice,
                "https://cdn.example.com/photo123.jpg"));

        // Alice shares location
        Message m5 = server.sendMessage(
            MessageFactory.createLocation(directChat.getId(), alice, 12.9716, 77.5946));

        server.markRead(bob, directChat.getId());

        // ===== SCENARIO 2: Offline message delivery =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 2: Offline Message Queuing");
        System.out.println("=".repeat(55));

        // Bob goes offline
        server.disconnect(bob);

        // Alice sends messages while Bob is offline
        server.sendMessage(
            MessageFactory.createText(directChat.getId(), alice,
                "Are you there? Just checking in."));
        server.sendMessage(
            MessageFactory.createText(directChat.getId(), alice,
                "Ping me when you're back!"));

        Thread.sleep(100);

        // Bob comes back online — offline messages delivered
        System.out.println("\n[Bob reconnects — queued messages should flush:]");
        server.connect(bob, new InAppMessageListener(bob));

        // ===== SCENARIO 3: Group chat =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 3: Group Chat");
        System.out.println("=".repeat(55));

        server.connect(carol, new InAppMessageListener(carol));
        server.connect(dave,  new InAppMessageListener(dave));

        Chat group = server.createGroupChat(
            "Weekend Plans 🎉",
            alice,
            new ArrayList<>(List.of(alice, bob, carol, dave)));

        server.sendMessage(
            MessageFactory.createText(group.getId(), alice,
                "Hey everyone! Beach trip this weekend?"));

        server.sendMessage(
            MessageFactory.createText(group.getId(), bob, "Absolutely! Count me in 🏖️"));

        server.sendMessage(
            MessageFactory.createText(group.getId(), carol, "Sounds amazing! What time?"));

        // Dave goes offline mid-conversation
        server.disconnect(dave);

        server.sendMessage(
            MessageFactory.createText(group.getId(), alice,
                "Let's meet at 9am at Marine Drive!"));

        Thread.sleep(100);

        // ===== SCENARIO 4: Message read receipts =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 4: Read Receipts");
        System.out.println("=".repeat(55));

        server.markRead(carol, group.getId());
        server.markRead(bob,   group.getId());

        // ===== SCENARIO 5: Notification strategy swap =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 5: Switch Notification Strategy to SMS");
        System.out.println("=".repeat(55));

        server.setNotificationStrategy(new SMSNotificationStrategy());

        // Dave still offline — this message goes via SMS
        server.sendMessage(
            MessageFactory.createText(group.getId(), alice,
                "Dave, don't forget to come!"));

        // ===== SCENARIO 6: Message delete =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 6: Delete Message");
        System.out.println("=".repeat(55));

        server.deleteMessage(m1.getId(), alice); // authorized
        server.deleteMessage(m1.getId(), bob);   // unauthorized (already deleted)
        server.deleteMessage(m2.getId(), alice); // unauthorized (not sender)

        // ===== SCENARIO 7: Audio message =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 7: Audio Message");
        System.out.println("=".repeat(55));

        server.sendMessage(
            MessageFactory.createAudio(directChat.getId(), bob,
                "https://cdn.example.com/voice-note-456.ogg"));

        // ===== SCENARIO 8: Chat history =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("SCENARIO 8: Chat History (last 3 messages)");
        System.out.println("=".repeat(55));

        List<Message> history = server.getHistory(group.getId(), 3);
        System.out.println("Recent messages in group:");
        history.forEach(m -> System.out.println("  " + m));

        // ===== SUMMARY =====
        System.out.println("\n" + "=".repeat(55));
        System.out.println("STATS");
        System.out.println("=".repeat(55));
        System.out.println("Active chats: " + server.getChats().size());
        System.out.println("Online users: " + server.getOnlineUserCount());
        server.getChats().values().forEach(c ->
            System.out.println("  " + c));

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | ChatServer (one central hub)
            Observer   | MessageListener (InAppMessageListener)
            Strategy   | NotificationStrategy (Push / SMS / Email)
            Factory    | MessageFactory (text, image, audio, location)
            Builder    | Message.Builder
            State      | MessageStatus (SENDING→SENT→DELIVERED→READ)
            Command    | Message as a command object sent through server
            """);
    }
}
