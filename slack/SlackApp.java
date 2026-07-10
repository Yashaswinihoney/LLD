import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ==========================================
// SLACK LLD — REAL-TIME MESSAGING PLATFORM
//
// Scope:
//   - Workspaces with channels (public/private) + DMs
//   - Real-time message delivery via WebSocket pub/sub simulation
//   - Message threading (replies), reactions, mentions
//   - Presence (ONLINE / AWAY / OFFLINE / DND)
//   - Message search across channels
//   - Unread counts per user per channel
//
// Patterns:
//   Singleton  — SlackService, PresenceService
//   Strategy   — NotificationStrategy (push / email / none)
//   Observer   — MessageEventObserver (delivery, search indexer, analytics)
//   Factory    — MessageFactory (text / file / system / thread-reply)
//   Builder    — Message, Channel, Workspace construction
//   State      — UserPresence (ONLINE → AWAY → OFFLINE → DND)
//   Iterator   — MessageCursor (paginated channel history)
//   Command    — SendMessageCommand (send + undo = delete)
// ==========================================

// ==========================================
// 1. ENUMS
// ==========================================
enum PresenceStatus  { ONLINE, AWAY, DND, OFFLINE }
enum ChannelType     { PUBLIC, PRIVATE, DIRECT_MESSAGE, GROUP_DM }
enum MessageType     { TEXT, FILE, IMAGE, CODE_SNIPPET, SYSTEM, THREAD_REPLY }
enum MemberRole      { OWNER, ADMIN, MEMBER, GUEST }
enum NotifPreference { ALL, MENTIONS_ONLY, NOTHING }

// ==========================================
// 2. USER — BUILDER PATTERN
// ==========================================
class User {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long           userId;
    private        String         displayName;
    private        String         email;
    private        String         avatarUrl;
    private        String         statusEmoji;   // :coffee: :headphones: etc.
    private        String         statusText;    // "In a meeting"
    private        PresenceStatus presence;
    private        LocalDateTime  lastSeenAt;
    private final  Set<Long>      workspaceIds = new HashSet<>();
    private        String         timezone;      // "Asia/Kolkata"

    private User(Builder b) {
        this.userId      = idGen.getAndIncrement();
        this.displayName = b.displayName;
        this.email       = b.email;
        this.avatarUrl   = b.avatarUrl;
        this.timezone    = b.timezone;
        this.presence    = PresenceStatus.OFFLINE;
        this.lastSeenAt  = LocalDateTime.now();
    }

    // ---- Presence transitions (STATE PATTERN) ----
    public void goOnline() {
        presence    = PresenceStatus.ONLINE;
        lastSeenAt  = LocalDateTime.now();
        System.out.println("[Presence] " + displayName + " → ONLINE");
    }

    public void goAway() {
        if (presence == PresenceStatus.ONLINE) {
            presence = PresenceStatus.AWAY;
            System.out.println("[Presence] " + displayName + " → AWAY");
        }
    }

    public void setDND(boolean on) {
        presence = on ? PresenceStatus.DND : PresenceStatus.ONLINE;
        System.out.println("[Presence] " + displayName + " → " + presence);
    }

    public void goOffline() {
        presence   = PresenceStatus.OFFLINE;
        lastSeenAt = LocalDateTime.now();
        System.out.println("[Presence] " + displayName + " → OFFLINE");
    }

    public void setStatus(String emoji, String text) {
        this.statusEmoji = emoji;
        this.statusText  = text;
    }

    public void joinWorkspace(long wsId) { workspaceIds.add(wsId); }

    public long          getUserId()     { return userId; }
    public String        getDisplayName(){ return displayName; }
    public String        getEmail()      { return email; }
    public PresenceStatus getPresence()  { return presence; }
    public LocalDateTime  getLastSeen()  { return lastSeenAt; }
    public String        getTimezone()   { return timezone; }
    public Set<Long>     getWorkspaces() { return workspaceIds; }

    @Override public String toString() {
        return "User[" + displayName + " | " + presence +
               (statusText != null ? " | " + statusEmoji + " " + statusText : "") + "]";
    }

    static class Builder {
        private final String displayName;
        private final String email;
        private       String avatarUrl = "";
        private       String timezone  = "UTC";

        public Builder(String displayName, String email) {
            this.displayName = displayName;
            this.email       = email;
        }
        public Builder avatarUrl(String u) { this.avatarUrl = u; return this; }
        public Builder timezone(String tz) { this.timezone = tz;  return this; }
        public User    build()             { return new User(this); }
    }
}

// ==========================================
// 3. MESSAGE — BUILDER PATTERN
//
// Messages are immutable after creation.
// Edits create a new version tracked in editHistory.
// Deletions mark isDeleted=true (soft delete).
// ==========================================
class Message {
    private static final AtomicLong idGen = new AtomicLong(100_000);

    private final  long              messageId;
    private final  long              channelId;
    private final  long              senderId;
    private        String            content;
    private final  MessageType       type;
    private final  LocalDateTime     sentAt;
    private        LocalDateTime     editedAt;
    private        boolean           isDeleted    = false;
    private        long              threadParentId; // 0 = not a thread reply
    private        int               replyCount   = 0;
    private final  Map<String, Set<Long>> reactions = new ConcurrentHashMap<>();
    // emoji → set of userIds who reacted
    private final  List<Long>        mentionedUserIds;
    private final  List<String>      editHistory  = new CopyOnWriteArrayList<>();
    private        String            fileUrl;     // for FILE type messages

    private Message(Builder b) {
        this.messageId        = idGen.getAndIncrement();
        this.channelId        = b.channelId;
        this.senderId         = b.senderId;
        this.content          = b.content;
        this.type             = b.type;
        this.sentAt           = LocalDateTime.now();
        this.threadParentId   = b.threadParentId;
        this.mentionedUserIds = List.copyOf(b.mentionedUserIds);
        this.fileUrl          = b.fileUrl;
    }

    // ---- Mutations ----
    public synchronized void edit(String newContent) {
        editHistory.add(content);          // keep old version
        this.content  = newContent;
        this.editedAt = LocalDateTime.now();
        System.out.println("[Message #" + messageId + "] Edited");
    }

    public synchronized void delete() {
        isDeleted = true;
        content   = "This message was deleted.";
        System.out.println("[Message #" + messageId + "] Deleted (soft)");
    }

    public synchronized void addReaction(String emoji, long userId) {
        reactions.computeIfAbsent(emoji, k -> ConcurrentHashMap.newKeySet())
                 .add(userId);
    }

    public synchronized void removeReaction(String emoji, long userId) {
        Set<Long> users = reactions.get(emoji);
        if (users != null) {
            users.remove(userId);
            if (users.isEmpty()) reactions.remove(emoji);
        }
    }

    public void incrementReplyCount() { replyCount++; }

    // ---- Getters ----
    public long              getMessageId()      { return messageId; }
    public long              getChannelId()      { return channelId; }
    public long              getSenderId()       { return senderId; }
    public String            getContent()        { return content; }
    public MessageType       getType()           { return type; }
    public LocalDateTime     getSentAt()         { return sentAt; }
    public boolean           isDeleted()         { return isDeleted; }
    public long              getThreadParentId() { return threadParentId; }
    public int               getReplyCount()     { return replyCount; }
    public Map<String,Set<Long>> getReactions()  { return reactions; }
    public List<Long>        getMentionedUsers() { return mentionedUserIds; }
    public boolean           isThreadReply()     { return threadParentId != 0; }
    public boolean           isEdited()          { return editedAt != null; }
    public String            getFileUrl()        { return fileUrl; }

    @Override public String toString() {
        return String.format("Msg[#%d | ch=%d | '%s'%s%s]",
            messageId, channelId,
            isDeleted ? "<deleted>" : content.substring(0, Math.min(40, content.length())),
            isEdited() ? " (edited)" : "",
            isThreadReply() ? " [reply→#" + threadParentId + "]" : "");
    }

    // ---- BUILDER ----
    static class Builder {
        private final long        channelId;
        private final long        senderId;
        private final String      content;
        private       MessageType type             = MessageType.TEXT;
        private       long        threadParentId   = 0;
        private       List<Long>  mentionedUserIds = new ArrayList<>();
        private       String      fileUrl          = null;

        public Builder(long channelId, long senderId, String content) {
            this.channelId = channelId;
            this.senderId  = senderId;
            this.content   = content;
        }
        public Builder type(MessageType t)         { this.type = t;              return this; }
        public Builder threadReplyTo(long parentId){ this.threadParentId=parentId;return this; }
        public Builder mentions(List<Long> ids)    { this.mentionedUserIds = ids; return this; }
        public Builder fileUrl(String url)         { this.fileUrl = url;          return this; }
        public Message build()                     { return new Message(this); }
    }
}

// ==========================================
// 4. MESSAGE FACTORY
// ==========================================
class MessageFactory {
    public static Message text(long channelId, long senderId, String content) {
        return new Message.Builder(channelId, senderId, content)
            .type(MessageType.TEXT).build();
    }

    public static Message withMentions(long channelId, long senderId,
                                        String content, List<Long> mentions) {
        return new Message.Builder(channelId, senderId, content)
            .type(MessageType.TEXT).mentions(mentions).build();
    }

    public static Message threadReply(long channelId, long senderId,
                                       String content, long parentMsgId) {
        return new Message.Builder(channelId, senderId, content)
            .type(MessageType.THREAD_REPLY).threadReplyTo(parentMsgId).build();
    }

    public static Message fileShare(long channelId, long senderId,
                                     String caption, String fileUrl) {
        return new Message.Builder(channelId, senderId, caption)
            .type(MessageType.FILE).fileUrl(fileUrl).build();
    }

    public static Message system(long channelId, String event) {
        return new Message.Builder(channelId, 0L, event)
            .type(MessageType.SYSTEM).build();
    }

    public static Message codeSnippet(long channelId, long senderId, String code) {
        return new Message.Builder(channelId, senderId, "```\n" + code + "\n```")
            .type(MessageType.CODE_SNIPPET).build();
    }
}

// ==========================================
// 5. CHANNEL — BUILDER PATTERN
//
// Channel is the primary container for messages.
// Messages are stored in insertion order (CopyOnWriteArrayList).
// Per-user unread counts tracked in a ConcurrentHashMap.
// ==========================================
class Channel {
    private static final AtomicLong idGen = new AtomicLong(1000);

    private final  long                          channelId;
    private        String                        name;
    private        String                        description;
    private final  ChannelType                   type;
    private final  long                          workspaceId;
    private final  long                          createdByUserId;
    private final  LocalDateTime                 createdAt;
    // userId → role in this channel
    private final  ConcurrentHashMap<Long, MemberRole>  members  = new ConcurrentHashMap<>();
    // All messages in chronological order
    private final  CopyOnWriteArrayList<Message>         messages = new CopyOnWriteArrayList<>();
    // userId → index of last read message
    private final  ConcurrentHashMap<Long, Integer>      lastReadIdx = new ConcurrentHashMap<>();
    // Pinned message IDs
    private final  Set<Long>                             pinnedMsgIds = ConcurrentHashMap.newKeySet();
    private        boolean                               isArchived = false;

    private Channel(Builder b) {
        this.channelId       = idGen.getAndIncrement();
        this.name            = b.name;
        this.description     = b.description;
        this.type            = b.type;
        this.workspaceId     = b.workspaceId;
        this.createdByUserId = b.createdByUserId;
        this.createdAt       = LocalDateTime.now();
        // Creator is auto-added as OWNER
        members.put(b.createdByUserId, MemberRole.OWNER);
    }

    // ---- Member management ----
    public void addMember(long userId, MemberRole role) {
        members.put(userId, role);
        System.out.println("[Channel #" + channelId + "] Added member: " +
            userId + " as " + role);
    }

    public void removeMember(long userId) {
        members.remove(userId);
        lastReadIdx.remove(userId);
    }

    public boolean hasMember(long userId) {
        return members.containsKey(userId);
    }

    // ---- Message management ----
    public synchronized void addMessage(Message msg) {
        messages.add(msg);
        // New message → everyone else in channel has unread
        // (we don't increment lastReadIdx for them — they'll compute unread on next check)
        System.out.println("[Channel #" + channelId + "] New message: " + msg);
    }

    /**
     * Mark messages as read for a user up to the latest message.
     * lastReadIdx[userId] = messages.size() - 1
     */
    public void markRead(long userId) {
        if (!messages.isEmpty()) {
            lastReadIdx.put(userId, messages.size() - 1);
        }
    }

    /**
     * Unread count = total messages − lastReadIdx − 1
     */
    public int getUnreadCount(long userId) {
        if (messages.isEmpty()) return 0;
        int lastRead = lastReadIdx.getOrDefault(userId, -1);
        return messages.size() - lastRead - 1;
    }

    public void pinMessage(long messageId) { pinnedMsgIds.add(messageId); }

    /**
     * ITERATOR PATTERN — paginated history.
     * Cursor-based: return up to `limit` messages before `beforeIndex`.
     */
    public List<Message> getHistory(int beforeIndex, int limit) {
        if (messages.isEmpty()) return Collections.emptyList();
        int end   = Math.min(beforeIndex, messages.size());
        int start = Math.max(0, end - limit);
        return new ArrayList<>(messages.subList(start, end));
    }

    public List<Message> getLatest(int limit) {
        return getHistory(messages.size(), limit);
    }

    /**
     * Get all replies to a parent message (thread view).
     */
    public List<Message> getThread(long parentMessageId) {
        return messages.stream()
            .filter(m -> m.getThreadParentId() == parentMessageId)
            .collect(Collectors.toList());
    }

    public void archive() {
        isArchived = true;
        System.out.println("[Channel #" + channelId + "] Archived");
    }

    // ---- Search within channel ----
    public List<Message> search(String query) {
        String q = query.toLowerCase();
        return messages.stream()
            .filter(m -> !m.isDeleted())
            .filter(m -> m.getContent().toLowerCase().contains(q))
            .collect(Collectors.toList());
    }

    // ---- Getters ----
    public long         getChannelId()   { return channelId; }
    public String       getName()        { return name; }
    public String       getDescription() { return description; }
    public ChannelType  getType()        { return type; }
    public long         getWorkspaceId() { return workspaceId; }
    public boolean      isArchived()     { return isArchived; }
    public Set<Long>    getMemberIds()   { return members.keySet(); }
    public MemberRole   getMemberRole(long userId){ return members.get(userId); }
    public int          getMessageCount(){ return messages.size(); }
    public Set<Long>    getPinnedMsgIds(){ return pinnedMsgIds; }

    public void setDescription(String d){ this.description = d; }

    @Override public String toString() {
        return (type == ChannelType.PUBLIC ? "#" : "🔒") + name +
               " [" + members.size() + " members, " + messages.size() + " msgs]";
    }

    static class Builder {
        private final String      name;
        private final ChannelType type;
        private final long        workspaceId;
        private final long        createdByUserId;
        private       String      description = "";

        public Builder(String name, ChannelType type,
                       long workspaceId, long createdByUserId) {
            this.name            = name;
            this.type            = type;
            this.workspaceId     = workspaceId;
            this.createdByUserId = createdByUserId;
        }
        public Builder description(String d){ this.description = d; return this; }
        public Channel build()              { return new Channel(this); }
    }
}

// ==========================================
// 6. WORKSPACE
// ==========================================
class Workspace {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long                         workspaceId;
    private        String                       name;
    private        String                       domain;       // acme.slack.com
    private final  ConcurrentHashMap<Long, MemberRole> members = new ConcurrentHashMap<>();
    // channelId → Channel
    private final  ConcurrentHashMap<Long, Channel>    channels = new ConcurrentHashMap<>();
    private final  LocalDateTime                createdAt;

    public Workspace(String name, String domain, long ownerId) {
        this.workspaceId = idGen.getAndIncrement();
        this.name        = name;
        this.domain      = domain;
        this.createdAt   = LocalDateTime.now();
        members.put(ownerId, MemberRole.OWNER);
    }

    public void addMember(long userId, MemberRole role) {
        members.put(userId, role);
    }

    public void addChannel(Channel channel) {
        channels.put(channel.getChannelId(), channel);
    }

    public Optional<Channel> getChannel(long channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    public List<Channel> getPublicChannels() {
        return channels.values().stream()
            .filter(c -> c.getType() == ChannelType.PUBLIC)
            .filter(c -> !c.isArchived())
            .collect(Collectors.toList());
    }

    public List<Channel> getChannelsForUser(long userId) {
        return channels.values().stream()
            .filter(c -> c.hasMember(userId))
            .collect(Collectors.toList());
    }

    public long    getWorkspaceId() { return workspaceId; }
    public String  getName()        { return name; }
    public String  getDomain()      { return domain; }
    public boolean hasMember(long userId){ return members.containsKey(userId); }
    public Set<Long> getMemberIds() { return members.keySet(); }

    @Override public String toString() {
        return "Workspace[" + name + ".slack.com | " + members.size() + " members]";
    }
}

// ==========================================
// 7. NOTIFICATION STRATEGY — STRATEGY PATTERN
// ==========================================
interface NotificationStrategy {
    String getName();
    void notify(User recipient, Message message, Channel channel);
}

class PushNotificationStrategy implements NotificationStrategy {
    @Override public String getName() { return "Push"; }

    @Override
    public void notify(User recipient, Message message, Channel channel) {
        // In production: call FCM / APNs
        System.out.printf("[Push → %s] New message in #%s: '%s'%n",
            recipient.getDisplayName(), channel.getName(),
            message.getContent().substring(0, Math.min(30, message.getContent().length())));
    }
}

class EmailNotificationStrategy implements NotificationStrategy {
    @Override public String getName() { return "Email"; }

    @Override
    public void notify(User recipient, Message message, Channel channel) {
        // In production: call SendGrid / SES
        System.out.printf("[Email → %s] You were mentioned in #%s%n",
            recipient.getEmail(), channel.getName());
    }
}

class SilentStrategy implements NotificationStrategy {
    @Override public String getName() { return "Silent (DND)"; }
    @Override public void notify(User u, Message m, Channel c) { /* no-op */ }
}

// ==========================================
// 8. PRESENCE SERVICE — SINGLETON
// Tracks online/away/offline for all users
// ==========================================
class PresenceService {
    private static PresenceService instance;

    // userId → PresenceStatus
    private final ConcurrentHashMap<Long, PresenceStatus> presenceMap
        = new ConcurrentHashMap<>();
    // userId → last heartbeat timestamp (ms)
    private final ConcurrentHashMap<Long, Long> heartbeats
        = new ConcurrentHashMap<>();

    private static final long AWAY_THRESHOLD_MS    = 5 * 60 * 1000;  // 5 min
    private static final long OFFLINE_THRESHOLD_MS = 15 * 60 * 1000; // 15 min

    private final ScheduledExecutorService presenceScanner
        = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "presence-scanner");
            t.setDaemon(true);
            return t;
        });

    private PresenceService() {
        // Scan heartbeats every 30s — auto-transition ONLINE→AWAY→OFFLINE
        presenceScanner.scheduleAtFixedRate(this::scanHeartbeats,
            30, 30, TimeUnit.SECONDS);
    }

    public static synchronized PresenceService getInstance() {
        if (instance == null) instance = new PresenceService();
        return instance;
    }

    public void heartbeat(long userId) {
        heartbeats.put(userId, System.currentTimeMillis());
        presenceMap.put(userId, PresenceStatus.ONLINE);
    }

    public void setOffline(long userId) {
        presenceMap.put(userId, PresenceStatus.OFFLINE);
        heartbeats.remove(userId);
    }

    public void setDND(long userId, boolean on) {
        presenceMap.put(userId, on ? PresenceStatus.DND : PresenceStatus.ONLINE);
    }

    private void scanHeartbeats() {
        long now = System.currentTimeMillis();
        heartbeats.forEach((userId, lastBeat) -> {
            long elapsed = now - lastBeat;
            PresenceStatus current = presenceMap.getOrDefault(userId, PresenceStatus.OFFLINE);
            if (current == PresenceStatus.DND) return; // don't auto-transition DND

            if (elapsed > OFFLINE_THRESHOLD_MS) {
                presenceMap.put(userId, PresenceStatus.OFFLINE);
            } else if (elapsed > AWAY_THRESHOLD_MS) {
                presenceMap.put(userId, PresenceStatus.AWAY);
            }
        });
    }

    public PresenceStatus getPresence(long userId) {
        return presenceMap.getOrDefault(userId, PresenceStatus.OFFLINE);
    }

    public Map<Long, PresenceStatus> getPresenceForUsers(Set<Long> userIds) {
        Map<Long, PresenceStatus> result = new HashMap<>();
        userIds.forEach(id -> result.put(id, getPresence(id)));
        return result;
    }

    public void shutdown() { presenceScanner.shutdown(); }
}

// ==========================================
// 9. OBSERVER — MESSAGE EVENTS
// ==========================================
interface MessageEventObserver {
    void onMessageSent(Message message, Channel channel);
    void onMessageEdited(Message message);
    void onMessageDeleted(Message message);
    void onReactionAdded(Message message, String emoji, long userId);
}

class SearchIndexObserver implements MessageEventObserver {
    // Simulated inverted index: word → set of (channelId, messageId)
    private final Map<String, Set<String>> index = new ConcurrentHashMap<>();

    @Override
    public void onMessageSent(Message msg, Channel channel) {
        // Index each word in the message
        Arrays.stream(msg.getContent().toLowerCase().split("\\W+"))
            .filter(w -> w.length() > 2)
            .forEach(word -> index
                .computeIfAbsent(word, k -> ConcurrentHashMap.newKeySet())
                .add(channel.getChannelId() + ":" + msg.getMessageId()));
    }

    @Override public void onMessageEdited(Message m) {
        // Re-index edited message (simplified: just log)
        System.out.println("[SearchIndex] Re-indexing edited message #" + m.getMessageId());
    }

    @Override public void onMessageDeleted(Message m) {
        // Remove from index
        System.out.println("[SearchIndex] Removing deleted message #" + m.getMessageId());
    }

    @Override public void onReactionAdded(Message m, String emoji, long userId) { }

    public List<String> search(String query) {
        String q = query.toLowerCase();
        return index.getOrDefault(q, Collections.emptySet())
            .stream().sorted().collect(Collectors.toList());
    }
}

class DeliveryObserver implements MessageEventObserver {
    private long deliveredCount = 0;

    @Override public synchronized void onMessageSent(Message m, Channel c) {
        deliveredCount++;
    }
    @Override public void onMessageEdited(Message m) {}
    @Override public void onMessageDeleted(Message m) {}
    @Override public void onReactionAdded(Message m, String e, long u) {}

    public long getDeliveredCount() { return deliveredCount; }
}

// ==========================================
// 10. SEND MESSAGE COMMAND — COMMAND PATTERN
// execute() = send message
// undo()    = delete (soft delete) the message
// ==========================================
class SendMessageCommand {
    private final Channel                     channel;
    private final Message                     message;
    private final List<MessageEventObserver>  observers;
    private       boolean                     executed = false;

    public SendMessageCommand(Channel channel, Message message,
                               List<MessageEventObserver> observers) {
        this.channel   = channel;
        this.message   = message;
        this.observers = observers;
    }

    public void execute() {
        channel.addMessage(message);

        // If this is a thread reply, increment parent's reply count
        if (message.isThreadReply()) {
            channel.getLatest(channel.getMessageCount()).stream()
                .filter(m -> m.getMessageId() == message.getThreadParentId())
                .findFirst()
                .ifPresent(Message::incrementReplyCount);
        }

        observers.forEach(o -> o.onMessageSent(message, channel));
        executed = true;
    }

    // Undo = soft delete
    public void undo() {
        if (executed) {
            message.delete();
            observers.forEach(o -> o.onMessageDeleted(message));
            executed = false;
        }
    }

    public Message getMessage() { return message; }
}

// ==========================================
// 11. MESSAGING SERVICE — core logic
// ==========================================
class MessagingService {
    private final List<MessageEventObserver>  observers    = new ArrayList<>();
    private final SearchIndexObserver         searchIndex  = new SearchIndexObserver();
    private final DeliveryObserver            delivery     = new DeliveryObserver();
    // messageId → SendMessageCommand (for undo / edit)
    private final ConcurrentHashMap<Long, SendMessageCommand> commandHistory
        = new ConcurrentHashMap<>();

    public MessagingService() {
        observers.add(searchIndex);
        observers.add(delivery);
    }

    public void addObserver(MessageEventObserver obs) { observers.add(obs); }

    /**
     * Send a text message to a channel.
     * Returns the sent Message object.
     */
    public Message send(long senderId, Channel channel, String content) {
        Message msg = MessageFactory.text(channel.getChannelId(), senderId, content);
        return executeCommand(channel, msg);
    }

    public Message sendWithMentions(long senderId, Channel channel,
                                     String content, List<Long> mentions) {
        Message msg = MessageFactory.withMentions(
            channel.getChannelId(), senderId, content, mentions);
        return executeCommand(channel, msg);
    }

    public Message sendThreadReply(long senderId, Channel channel,
                                    String content, long parentMsgId) {
        Message msg = MessageFactory.threadReply(
            channel.getChannelId(), senderId, content, parentMsgId);
        return executeCommand(channel, msg);
    }

    public Message sendFile(long senderId, Channel channel,
                             String caption, String fileUrl) {
        Message msg = MessageFactory.fileShare(
            channel.getChannelId(), senderId, caption, fileUrl);
        return executeCommand(channel, msg);
    }

    public Message sendCode(long senderId, Channel channel, String code) {
        Message msg = MessageFactory.codeSnippet(
            channel.getChannelId(), senderId, code);
        return executeCommand(channel, msg);
    }

    private Message executeCommand(Channel channel, Message msg) {
        SendMessageCommand cmd = new SendMessageCommand(channel, msg, observers);
        cmd.execute();
        commandHistory.put(msg.getMessageId(), cmd);
        return msg;
    }

    public void deleteMessage(long messageId) {
        SendMessageCommand cmd = commandHistory.get(messageId);
        if (cmd != null) {
            cmd.undo();
        }
    }

    public void editMessage(long messageId, String newContent) {
        SendMessageCommand cmd = commandHistory.get(messageId);
        if (cmd != null) {
            cmd.getMessage().edit(newContent);
            observers.forEach(o -> o.onMessageEdited(cmd.getMessage()));
        }
    }

    public void addReaction(long messageId, String emoji, long userId,
                             Channel channel) {
        channel.getLatest(channel.getMessageCount()).stream()
            .filter(m -> m.getMessageId() == messageId)
            .findFirst()
            .ifPresent(m -> {
                m.addReaction(emoji, userId);
                observers.forEach(o -> o.onReactionAdded(m, emoji, userId));
                System.out.println("[Reaction] " + emoji + " added to msg #" +
                    messageId + " by user " + userId);
            });
    }

    // ---- Search ----
    public List<String> search(String query) {
        return searchIndex.search(query);
    }

    public long getDeliveredCount() { return delivery.getDeliveredCount(); }
}

// ==========================================
// 12. SLACK SERVICE — SINGLETON
// Top-level entry point for all operations
// ==========================================
class SlackService {
    private static volatile SlackService instance;

    private final ConcurrentHashMap<Long, Workspace>     workspaces    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, User>          users         = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Channel>       allChannels   = new ConcurrentHashMap<>();
    private final PresenceService                        presenceService= PresenceService.getInstance();
    private final MessagingService                       messagingService= new MessagingService();

    // DM channel cache: sorted userId pair → channel
    // e.g. "1:3" → DM channel between user 1 and user 3
    private final ConcurrentHashMap<String, Channel>     dmCache       = new ConcurrentHashMap<>();

    private SlackService() {}

    public static SlackService getInstance() {
        if (instance == null) {
            synchronized (SlackService.class) {
                if (instance == null) instance = new SlackService();
            }
        }
        return instance;
    }

    // ---- User management ----
    public User registerUser(User user) {
        users.put(user.getUserId(), user);
        System.out.println("[Slack] User registered: " + user.getDisplayName());
        return user;
    }

    public void userConnect(long userId) {
        presenceService.heartbeat(userId);
        User u = users.get(userId);
        if (u != null) u.goOnline();
    }

    public void userDisconnect(long userId) {
        presenceService.setOffline(userId);
        User u = users.get(userId);
        if (u != null) u.goOffline();
    }

    // ---- Workspace management ----
    public Workspace createWorkspace(String name, String domain, long ownerId) {
        Workspace ws = new Workspace(name, domain, ownerId);
        workspaces.put(ws.getWorkspaceId(), ws);
        users.get(ownerId).joinWorkspace(ws.getWorkspaceId());
        System.out.println("[Slack] Workspace created: " + ws);
        return ws;
    }

    public boolean addUserToWorkspace(long workspaceId, long userId, MemberRole role) {
        Workspace ws = workspaces.get(workspaceId);
        User user    = users.get(userId);
        if (ws == null || user == null) return false;
        ws.addMember(userId, role);
        user.joinWorkspace(workspaceId);
        System.out.println("[Slack] " + user.getDisplayName() +
            " joined workspace " + ws.getName() + " as " + role);
        return true;
    }

    // ---- Channel management ----
    public Channel createChannel(long workspaceId, String name,
                                  ChannelType type, long creatorId,
                                  String description) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return null;

        Channel channel = new Channel.Builder(name, type, workspaceId, creatorId)
            .description(description)
            .build();

        ws.addChannel(channel);
        allChannels.put(channel.getChannelId(), channel);

        // Post a system message: "Alice created this channel"
        User creator = users.get(creatorId);
        messagingService.send(0L, channel,
            (creator != null ? creator.getDisplayName() : "Someone") +
            " created this channel.");

        System.out.println("[Slack] Channel created: " + channel);
        return channel;
    }

    public boolean joinChannel(long channelId, long userId) {
        Channel channel = allChannels.get(channelId);
        User    user    = users.get(userId);
        if (channel == null || user == null) return false;
        if (channel.getType() == ChannelType.PRIVATE) {
            System.out.println("[Slack] Cannot join private channel without invite");
            return false;
        }
        channel.addMember(userId, MemberRole.MEMBER);
        messagingService.send(0L, channel, user.getDisplayName() + " joined the channel.");
        return true;
    }

    public void inviteToChannel(long channelId, long inviterId, long inviteeId) {
        Channel channel = allChannels.get(channelId);
        User    invitee = users.get(inviteeId);
        if (channel != null && invitee != null) {
            channel.addMember(inviteeId, MemberRole.MEMBER);
            messagingService.send(0L, channel,
                invitee.getDisplayName() + " was added to the channel.");
        }
    }

    // ---- DM (Direct Message) ----
    public Channel getOrCreateDM(long workspaceId, long userId1, long userId2) {
        // Canonical key: smaller id first (prevents duplicate DM channels)
        String key = Math.min(userId1, userId2) + ":" + Math.max(userId1, userId2);
        return dmCache.computeIfAbsent(key, k -> {
            Channel dm = new Channel.Builder(
                "dm-" + userId1 + "-" + userId2,
                ChannelType.DIRECT_MESSAGE,
                workspaceId, userId1).build();
            dm.addMember(userId2, MemberRole.MEMBER);
            allChannels.put(dm.getChannelId(), dm);
            Workspace ws = workspaces.get(workspaceId);
            if (ws != null) ws.addChannel(dm);
            return dm;
        });
    }

    // ---- Messaging delegation ----
    public Message sendMessage(long senderId, long channelId, String content) {
        Channel channel = allChannels.get(channelId);
        if (channel == null) return null;
        if (!channel.hasMember(senderId)) {
            System.out.println("[Slack] User " + senderId + " not in channel");
            return null;
        }
        return messagingService.send(senderId, channel, content);
    }

    public Message sendWithMentions(long senderId, long channelId,
                                     String content, List<Long> mentionIds) {
        Channel channel = allChannels.get(channelId);
        if (channel == null || !channel.hasMember(senderId)) return null;
        return messagingService.sendWithMentions(senderId, channel, content, mentionIds);
    }

    public Message replyInThread(long senderId, long channelId,
                                  String content, long parentMsgId) {
        Channel channel = allChannels.get(channelId);
        if (channel == null || !channel.hasMember(senderId)) return null;
        return messagingService.sendThreadReply(senderId, channel, content, parentMsgId);
    }

    public void editMessage(long messageId, String newContent) {
        messagingService.editMessage(messageId, newContent);
    }

    public void deleteMessage(long messageId) {
        messagingService.deleteMessage(messageId);
    }

    public void addReaction(long messageId, String emoji,
                             long userId, long channelId) {
        Channel channel = allChannels.get(channelId);
        if (channel != null) {
            messagingService.addReaction(messageId, emoji, userId, channel);
        }
    }

    public void pinMessage(long channelId, long messageId) {
        Channel channel = allChannels.get(channelId);
        if (channel != null) channel.pinMessage(messageId);
    }

    public void markRead(long channelId, long userId) {
        Channel channel = allChannels.get(channelId);
        if (channel != null) channel.markRead(userId);
    }

    public int getUnreadCount(long channelId, long userId) {
        Channel channel = allChannels.get(channelId);
        return channel != null ? channel.getUnreadCount(userId) : 0;
    }

    public List<Message> getChannelHistory(long channelId, int limit) {
        Channel channel = allChannels.get(channelId);
        return channel != null ? channel.getLatest(limit) : Collections.emptyList();
    }

    public List<Message> getThread(long channelId, long parentMsgId) {
        Channel channel = allChannels.get(channelId);
        return channel != null ? channel.getThread(parentMsgId) : Collections.emptyList();
    }

    // ---- Search ----
    public List<String> search(String query) {
        return messagingService.search(query);
    }

    public List<Channel> searchChannels(long workspaceId, String query) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return Collections.emptyList();
        return ws.getPublicChannels().stream()
            .filter(c -> c.getName().contains(query.toLowerCase()))
            .collect(Collectors.toList());
    }

    // ---- Presence ----
    public PresenceStatus getPresence(long userId) {
        return presenceService.getPresence(userId);
    }

    // ---- Stats ----
    public void printStats() {
        System.out.println("\n[SlackService Stats]");
        System.out.println("  Workspaces:  " + workspaces.size());
        System.out.println("  Users:       " + users.size());
        System.out.println("  Channels:    " + allChannels.size());
        System.out.println("  Messages delivered: " + messagingService.getDeliveredCount());
    }

    public Channel getChannel(long channelId) { return allChannels.get(channelId); }
    public User    getUser(long userId)        { return users.get(userId); }

    public void shutdown() { presenceService.shutdown(); }
}

// ==========================================
// 13. MAIN — DRIVER CODE
// ==========================================
public class SlackApp {
    public static void main(String[] args) throws InterruptedException {

        SlackService slack = SlackService.getInstance();

        // ---- Register users ----
        User alice = slack.registerUser(
            new User.Builder("Alice",  "alice@acme.com").timezone("Asia/Kolkata").build());
        User bob   = slack.registerUser(
            new User.Builder("Bob",    "bob@acme.com").timezone("Asia/Kolkata").build());
        User carol = slack.registerUser(
            new User.Builder("Carol",  "carol@acme.com").timezone("America/New_York").build());
        User dave  = slack.registerUser(
            new User.Builder("Dave",   "dave@acme.com").timezone("Europe/London").build());

        // ===== SCENARIO 1: Create workspace + channels =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Create Workspace + Channels");
        System.out.println("=".repeat(60));

        Workspace acme = slack.createWorkspace("Acme Corp", "acme", alice.getUserId());

        // Add team members
        slack.addUserToWorkspace(acme.getWorkspaceId(), bob.getUserId(),   MemberRole.MEMBER);
        slack.addUserToWorkspace(acme.getWorkspaceId(), carol.getUserId(), MemberRole.MEMBER);
        slack.addUserToWorkspace(acme.getWorkspaceId(), dave.getUserId(),  MemberRole.MEMBER);

        // Create channels
        Channel general = slack.createChannel(acme.getWorkspaceId(),
            "general", ChannelType.PUBLIC, alice.getUserId(), "Company-wide announcements");

        Channel engineering = slack.createChannel(acme.getWorkspaceId(),
            "engineering", ChannelType.PUBLIC, bob.getUserId(), "Engineering discussions");

        Channel design = slack.createChannel(acme.getWorkspaceId(),
            "design", ChannelType.PRIVATE, carol.getUserId(), "Design team only");

        // Members join public channels
        slack.joinChannel(general.getChannelId(),     bob.getUserId());
        slack.joinChannel(general.getChannelId(),     carol.getUserId());
        slack.joinChannel(general.getChannelId(),     dave.getUserId());
        slack.joinChannel(engineering.getChannelId(), alice.getUserId());
        slack.joinChannel(engineering.getChannelId(), carol.getUserId());

        // Invite Dave to private design channel
        slack.inviteToChannel(design.getChannelId(), carol.getUserId(), dave.getUserId());

        // ===== SCENARIO 2: Send messages =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Send Messages");
        System.out.println("=".repeat(60));

        slack.userConnect(alice.getUserId());
        slack.userConnect(bob.getUserId());

        Message m1 = slack.sendMessage(alice.getUserId(),
            general.getChannelId(), "Welcome everyone to Acme Slack! 🎉");

        Message m2 = slack.sendMessage(bob.getUserId(),
            general.getChannelId(), "Thanks Alice! Great to be here.");

        Message m3 = slack.sendWithMentions(carol.getUserId(),
            engineering.getChannelId(),
            "Hey @Alice, the PR review is ready. Can you take a look?",
            List.of(alice.getUserId()));

        Message m4 = slack.sendMessage(bob.getUserId(),
            engineering.getChannelId(),
            "I'll review it too. Looks like a good change.");

        // ===== SCENARIO 3: Thread reply =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Thread Reply");
        System.out.println("=".repeat(60));

        // Reply in thread to m3
        Message t1 = slack.replyInThread(alice.getUserId(),
            engineering.getChannelId(),
            "Sure! I'll review it in 30 minutes.", m3.getMessageId());

        Message t2 = slack.replyInThread(dave.getUserId(),
            engineering.getChannelId(),
            "I can pair on this if needed.", m3.getMessageId());

        System.out.println("\nThread under message #" + m3.getMessageId() + ":");
        slack.getThread(engineering.getChannelId(), m3.getMessageId())
            .forEach(m -> System.out.println("  ↳ " + m));

        // ===== SCENARIO 4: Reactions =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Emoji Reactions");
        System.out.println("=".repeat(60));

        slack.addReaction(m1.getMessageId(), "🎉", bob.getUserId(), general.getChannelId());
        slack.addReaction(m1.getMessageId(), "🎉", carol.getUserId(), general.getChannelId());
        slack.addReaction(m1.getMessageId(), "👍", dave.getUserId(), general.getChannelId());
        slack.addReaction(m4.getMessageId(), "👍", alice.getUserId(), engineering.getChannelId());

        System.out.println("Reactions on welcome message:");
        m1.getReactions().forEach((emoji, users) ->
            System.out.println("  " + emoji + " × " + users.size()));

        // ===== SCENARIO 5: Edit + Delete =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: Edit and Delete Messages");
        System.out.println("=".repeat(60));

        System.out.println("Before edit: " + m4);
        slack.editMessage(m4.getMessageId(),
            "I'll review it too. Looks like a solid change! LGTM 👍");
        System.out.println("After edit:  " + m4);

        Message tempMsg = slack.sendMessage(bob.getUserId(),
            general.getChannelId(), "oops wrong channel");
        System.out.println("Before delete: " + tempMsg);
        slack.deleteMessage(tempMsg.getMessageId());
        System.out.println("After delete:  " + tempMsg);

        // ===== SCENARIO 6: Direct Message =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Direct Message (DM)");
        System.out.println("=".repeat(60));

        Channel aliceBobDM = slack.getOrCreateDM(
            acme.getWorkspaceId(), alice.getUserId(), bob.getUserId());

        slack.sendMessage(alice.getUserId(), aliceBobDM.getChannelId(),
            "Hey Bob, quick question about the architecture");
        slack.sendMessage(bob.getUserId(), aliceBobDM.getChannelId(),
            "Sure! What's up?");
        slack.sendMessage(alice.getUserId(), aliceBobDM.getChannelId(),
            "Should we use Kafka or RabbitMQ for the events pipeline?");

        System.out.println("\nDM history (Alice ↔ Bob):");
        slack.getChannelHistory(aliceBobDM.getChannelId(), 10)
            .forEach(m -> System.out.println("  " + m));

        // ===== SCENARIO 7: Unread counts =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Unread Counts");
        System.out.println("=".repeat(60));

        System.out.println("Dave's unread in #general: " +
            slack.getUnreadCount(general.getChannelId(), dave.getUserId()));

        slack.markRead(general.getChannelId(), dave.getUserId());
        System.out.println("After mark read: " +
            slack.getUnreadCount(general.getChannelId(), dave.getUserId()));

        // New message arrives after Dave marked read
        slack.sendMessage(alice.getUserId(), general.getChannelId(),
            "Team standup in 10 minutes!");
        System.out.println("After new message, Dave's unread: " +
            slack.getUnreadCount(general.getChannelId(), dave.getUserId()));

        // ===== SCENARIO 8: Pin message =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Pin Message");
        System.out.println("=".repeat(60));

        slack.pinMessage(general.getChannelId(), m1.getMessageId());
        System.out.println("Pinned messages in #general: " +
            general.getPinnedMsgIds());

        // ===== SCENARIO 9: Search =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Message Search");
        System.out.println("=".repeat(60));

        List<String> results = slack.search("review");
        System.out.println("Search 'review' → " + results.size() + " hits: " + results);

        List<Channel> channelSearch = slack.searchChannels(acme.getWorkspaceId(), "eng");
        System.out.println("Channel search 'eng': " +
            channelSearch.stream().map(Channel::getName).collect(Collectors.toList()));

        // ===== SCENARIO 10: Presence =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 10: Presence Status");
        System.out.println("=".repeat(60));

        System.out.println("Alice: " + slack.getPresence(alice.getUserId()));
        System.out.println("Carol: " + slack.getPresence(carol.getUserId())); // offline — never connected

        carol.setDND(true);
        PresenceService.getInstance().setDND(carol.getUserId(), true);
        System.out.println("Carol (DND): " + slack.getPresence(carol.getUserId()));

        slack.userDisconnect(alice.getUserId());
        System.out.println("Alice (disconnected): " + slack.getPresence(alice.getUserId()));

        // ===== SCENARIO 11: File share + Code snippet =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 11: File Share + Code Snippet");
        System.out.println("=".repeat(60));

        Message fileMsg = slack.getChannel(engineering.getChannelId()) != null
            ? (() -> {
                Channel ch = slack.getChannel(engineering.getChannelId());
                return new MessagingService(){{
                    // inline — show message creation
                }};
            }).equals(null) ? null
            : null : null;

        // Simpler direct approach
        Channel eng = slack.getChannel(engineering.getChannelId());
        if (eng != null) {
            Message fm = MessageFactory.fileShare(
                eng.getChannelId(), bob.getUserId(),
                "Architecture diagram", "https://s3.amazonaws.com/arch-v2.png");
            eng.addMessage(fm);
            System.out.println("File shared: " + fm);

            Message cm = MessageFactory.codeSnippet(
                eng.getChannelId(), alice.getUserId(),
                "public class CircuitBreaker {\n  // ...\n}");
            eng.addMessage(cm);
            System.out.println("Code snippet: " + cm);
        }

        // ===== STATS =====
        slack.printStats();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|--------------------------------------------------
            Singleton  | SlackService (volatile double-checked locking)
                       | PresenceService (same pattern)
            State      | UserPresence: ONLINE→AWAY→OFFLINE→DND
                       | PresenceService scans heartbeats → auto-transitions
            Strategy   | NotificationStrategy (Push / Email / Silent)
            Observer   | MessageEventObserver (SearchIndex / Delivery)
            Factory    | MessageFactory (text/file/code/thread/system)
            Builder    | Message.Builder, Channel.Builder, User.Builder
            Iterator   | Channel.getHistory() — cursor-based pagination
            Command    | SendMessageCommand: execute()=send, undo()=delete
            """);

        slack.shutdown();
    }
}
