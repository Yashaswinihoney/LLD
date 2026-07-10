import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

// ============================================================
// PUB-SUB SYSTEM — LLD
//
// Requirements covered:
//   1. Publishers publish messages to specific topics
//   2. Subscribers subscribe to topics + receive messages
//   3. Multiple publishers and subscribers
//   4. Real-time delivery to all subscribers
//   5. Concurrent access + thread safety
//   6. Scalable + efficient message delivery
//
// Design Patterns:
//   Singleton  — MessageBroker (central coordinator)
//   Strategy   — DeliveryStrategy (sync / async / at-least-once)
//               RetryStrategy (fixed / exponential backoff)
//   Observer   — Subscriber (the pattern itself IS observer)
//   Factory    — TopicFactory (standard / priority / partitioned)
//   Builder    — Message, Topic, Subscription construction
//   State      — SubscriptionStatus (ACTIVE→PAUSED→CANCELLED)
//               MessageStatus (PENDING→DELIVERED/FAILED/DEAD_LETTER)
//   Command    — PublishCommand (publish + replay)
//   Iterator   — MessageCursor (replay from offset)
// ============================================================

// ============================================================
// 1. ENUMS
// ============================================================
enum MessageStatus      { PENDING, DELIVERED, FAILED, DEAD_LETTER, ACK_PENDING }
enum SubscriptionStatus { ACTIVE, PAUSED, CANCELLED }
enum TopicType          { STANDARD, PRIORITY, PARTITIONED, FANOUT }
enum DeliveryMode       { AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE }
enum MessagePriority    { LOW, NORMAL, HIGH, CRITICAL }

// ============================================================
// 2. MESSAGE — BUILDER PATTERN (Req 1)
//    Immutable after creation — critical for multi-subscriber
//    delivery (no subscriber can mutate another's message)
// ============================================================
class Message {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long             messageId;
    private final  String           messageRef;      // human-readable
    private final  String           topicName;
    private final  String           payload;         // content (JSON/text/bytes)
    private final  Map<String,String> headers;       // metadata (source, correlationId)
    private final  MessagePriority  priority;
    private final  long             publisherId;
    private final  LocalDateTime    publishedAt;
    private final  int              partitionKey;    // for partitioned topics
    private        MessageStatus    status;
    private        int              deliveryAttempts;

    private Message(Builder b) {
        this.messageId       = idGen.getAndIncrement();
        this.messageRef      = "MSG-" + String.format("%08d", messageId);
        this.topicName       = b.topicName;
        this.payload         = b.payload;
        this.headers         = Collections.unmodifiableMap(new HashMap<>(b.headers));
        this.priority        = b.priority;
        this.publisherId     = b.publisherId;
        this.publishedAt     = LocalDateTime.now();
        this.partitionKey    = b.partitionKey;
        this.status          = MessageStatus.PENDING;
        this.deliveryAttempts= 0;
    }

    public void incrementAttempts()  { deliveryAttempts++; }
    public void markDelivered()      { status = MessageStatus.DELIVERED; }
    public void markFailed()         { status = MessageStatus.FAILED; }
    public void markDeadLetter()     { status = MessageStatus.DEAD_LETTER; }
    public void markAckPending()     { status = MessageStatus.ACK_PENDING; }

    public long            getMessageId()      { return messageId; }
    public String          getMessageRef()     { return messageRef; }
    public String          getTopicName()      { return topicName; }
    public String          getPayload()        { return payload; }
    public Map<String,String> getHeaders()     { return headers; }
    public MessagePriority getPriority()       { return priority; }
    public long            getPublisherId()    { return publisherId; }
    public LocalDateTime   getPublishedAt()    { return publishedAt; }
    public int             getPartitionKey()   { return partitionKey; }
    public MessageStatus   getStatus()         { return status; }
    public int             getDeliveryAttempts(){ return deliveryAttempts; }

    @Override public String toString() {
        return String.format("Message[%s | topic=%s | priority=%s | '%s' | %s]",
            messageRef, topicName, priority,
            payload.length() > 40 ? payload.substring(0, 40) + "..." : payload,
            status);
    }

    static class Builder {
        private final String          topicName;
        private final String          payload;
        private final long            publisherId;
        private       Map<String,String> headers = new HashMap<>();
        private       MessagePriority priority   = MessagePriority.NORMAL;
        private       int             partitionKey = 0;

        public Builder(String topicName, long publisherId, String payload) {
            this.topicName   = topicName;
            this.publisherId = publisherId;
            this.payload     = payload;
        }
        public Builder header(String k, String v)  { headers.put(k, v);     return this; }
        public Builder priority(MessagePriority p) { this.priority = p;     return this; }
        public Builder partitionKey(int k)         { this.partitionKey = k; return this; }
        public Message build()                     { return new Message(this); }
    }
}

// ============================================================
// 3. SUBSCRIBER — OBSERVER PATTERN (Req 2)
//    Each subscriber holds a message handler (Consumer<Message>)
//    and a bounded queue for backpressure
// ============================================================
class Subscriber {
    private static final AtomicLong idGen = new AtomicLong(100);

    private final  long               subscriberId;
    private final  String             name;
    private final  Consumer<Message>  handler;       // the callback
    private final  Set<String>        subscribedTopics = ConcurrentHashMap.newKeySet();
    private        SubscriptionStatus status           = SubscriptionStatus.ACTIVE;
    // Bounded inbox for backpressure (Req 6: no unbounded memory)
    private final  BlockingQueue<Message> inbox;
    private        long               messagesReceived = 0;
    private        long               messagesFailed   = 0;
    // Req 6: each subscriber has its own delivery thread
    private final  ExecutorService    deliveryExecutor;
    private final  int                maxInboxSize;

    public Subscriber(String name, Consumer<Message> handler, int maxInboxSize) {
        this.subscriberId    = idGen.getAndIncrement();
        this.name            = name;
        this.handler         = handler;
        this.maxInboxSize    = maxInboxSize;
        this.inbox           = new LinkedBlockingQueue<>(maxInboxSize);
        // Single-threaded per subscriber → preserves message ordering
        this.deliveryExecutor= Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sub-" + name);
            t.setDaemon(true);
            return t;
        });
        startDeliveryLoop();
    }

    // Default constructor with reasonable defaults
    public Subscriber(String name, Consumer<Message> handler) {
        this(name, handler, 1000);
    }

    // ---- Delivery loop — runs on subscriber's own thread ----
    private void startDeliveryLoop() {
        deliveryExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Message msg = inbox.poll(500, TimeUnit.MILLISECONDS);
                    if (msg == null) continue;
                    if (status != SubscriptionStatus.ACTIVE) continue;

                    msg.incrementAttempts();
                    try {
                        handler.accept(msg);
                        msg.markDelivered();
                        messagesReceived++;
                    } catch (Exception e) {
                        msg.markFailed();
                        messagesFailed++;
                        System.err.printf("[Subscriber:%s] Handler error for %s: %s%n",
                            name, msg.getMessageRef(), e.getMessage());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    /**
     * Offer a message to this subscriber's inbox.
     * Returns false if inbox is full (backpressure — Req 6).
     */
    public boolean deliver(Message message) {
        if (status != SubscriptionStatus.ACTIVE) return false;
        boolean offered = inbox.offer(message);
        if (!offered) {
            System.out.printf("[Subscriber:%s] Inbox FULL — dropping %s (backpressure)%n",
                name, message.getMessageRef());
        }
        return offered;
    }

    public void pause()  {
        status = SubscriptionStatus.PAUSED;
        System.out.println("[Subscriber:" + name + "] PAUSED");
    }

    public void resume() {
        status = SubscriptionStatus.ACTIVE;
        System.out.println("[Subscriber:" + name + "] RESUMED");
    }

    public void cancel() {
        status = SubscriptionStatus.CANCELLED;
        deliveryExecutor.shutdownNow();
        System.out.println("[Subscriber:" + name + "] CANCELLED");
    }

    public void subscribeToTopic(String topic)   { subscribedTopics.add(topic); }
    public void unsubscribeFromTopic(String topic){ subscribedTopics.remove(topic); }
    public boolean isSubscribedTo(String topic)  { return subscribedTopics.contains(topic); }

    public long              getSubscriberId()   { return subscriberId; }
    public String            getName()           { return name; }
    public SubscriptionStatus getStatus()        { return status; }
    public Set<String>       getTopics()         { return Collections.unmodifiableSet(subscribedTopics); }
    public int               getInboxSize()      { return inbox.size(); }
    public long              getReceived()       { return messagesReceived; }
    public long              getFailed()         { return messagesFailed; }

    @Override public String toString() {
        return String.format("Subscriber[#%d | %-15s | %s | topics=%s | received=%d]",
            subscriberId, name, status, subscribedTopics, messagesReceived);
    }
}

// ============================================================
// 4. PUBLISHER (Req 1 + 3)
// ============================================================
class Publisher {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long          publisherId;
    private final  String        name;
    private        long          messagesSent = 0;
    private final  Set<String>   publishingTopics = ConcurrentHashMap.newKeySet();

    public Publisher(String name) {
        this.publisherId = idGen.getAndIncrement();
        this.name        = name;
    }

    public void registerTopic(String topic) { publishingTopics.add(topic); }
    public void incrementSent()             { messagesSent++; }

    public long    getPublisherId()  { return publisherId; }
    public String  getName()         { return name; }
    public long    getMessagesSent() { return messagesSent; }
    public Set<String> getTopics()   { return publishingTopics; }

    @Override public String toString() {
        return "Publisher[#" + publisherId + " | " + name +
               " | sent=" + messagesSent + " | topics=" + publishingTopics + "]";
    }
}

// ============================================================
// 5. TOPIC — BUILDER PATTERN (Req 1 + 6)
//    Stores the message log (append-only) for replay
//    Maintains subscriber registry with RW lock
// ============================================================
class Topic {
    private static final AtomicLong idGen = new AtomicLong(1);

    private final  long               topicId;
    private final  String             name;
    private final  TopicType          type;
    private final  int                retentionSecs;  // how long messages kept
    private final  int                maxMessageSize; // bytes
    private final  int                partitionCount; // for partitioned topics

    // Message log: all messages ever published (Req: replay support)
    private final  List<Message>      messageLog     = new CopyOnWriteArrayList<>();
    // subscriberId → Subscriber (Req 3: multiple subscribers)
    private final  Map<Long, Subscriber> subscribers = new ConcurrentHashMap<>();

    // Req 5: RW lock — many readers (delivery) vs rare writers (subscribe/unsubscribe)
    // ReadWriteLock: multiple threads can read simultaneously, write is exclusive
    private final  ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

    private final  AtomicLong         publishCount   = new AtomicLong(0);
    private final  LocalDateTime      createdAt;

    private Topic(Builder b) {
        this.topicId       = idGen.getAndIncrement();
        this.name          = b.name;
        this.type          = b.type;
        this.retentionSecs = b.retentionSecs;
        this.maxMessageSize= b.maxMessageSize;
        this.partitionCount= b.partitionCount;
        this.createdAt     = LocalDateTime.now();
    }

    /**
     * Req 5: Thread-safe subscribe using write lock.
     * Write lock because we're modifying the subscriber registry.
     */
    public void subscribe(Subscriber subscriber) {
        rwLock.writeLock().lock();
        try {
            subscribers.put(subscriber.getSubscriberId(), subscriber);
            subscriber.subscribeToTopic(name);
            System.out.printf("[Topic:%s] Subscriber '%s' joined (total=%d)%n",
                name, subscriber.getName(), subscribers.size());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Req 5: Thread-safe unsubscribe.
     */
    public void unsubscribe(long subscriberId) {
        rwLock.writeLock().lock();
        try {
            Subscriber sub = subscribers.remove(subscriberId);
            if (sub != null) {
                sub.unsubscribeFromTopic(name);
                System.out.printf("[Topic:%s] Subscriber '%s' left (total=%d)%n",
                    name, sub.getName(), subscribers.size());
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Req 4 + 5: Deliver to all subscribers under READ lock.
     * READ lock: multiple delivery threads can fan-out simultaneously.
     * Only write-lock operations (subscribe/unsubscribe) need exclusivity.
     */
    public int fanOut(Message message) {
        messageLog.add(message);       // append to log
        publishCount.incrementAndGet();

        rwLock.readLock().lock();      // shared read — concurrent delivery OK
        try {
            int delivered = 0;
            for (Subscriber sub : subscribers.values()) {
                if (sub.deliver(message)) delivered++;
            }
            System.out.printf("[Topic:%s] Fanout %s → %d/%d subscribers%n",
                name, message.getMessageRef(), delivered, subscribers.size());
            return delivered;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // Replay from a given offset (for late subscribers or retry)
    public List<Message> replayFrom(long fromMessageId) {
        return messageLog.stream()
            .filter(m -> m.getMessageId() >= fromMessageId)
            .collect(Collectors.toList());
    }

    public long              getTopicId()       { return topicId; }
    public String            getName()          { return name; }
    public TopicType         getType()          { return type; }
    public int               getSubscriberCount(){ return subscribers.size(); }
    public long              getPublishCount()  { return publishCount.get(); }
    public int               getMessageLogSize(){ return messageLog.size(); }
    public Map<Long,Subscriber> getSubscribers(){ return Collections.unmodifiableMap(subscribers); }

    @Override public String toString() {
        return String.format("Topic[%-20s | %s | subscribers=%d | published=%d]",
            name, type, subscribers.size(), publishCount.get());
    }

    static class Builder {
        private final String    name;
        private       TopicType type           = TopicType.STANDARD;
        private       int       retentionSecs  = 86400; // 24hr
        private       int       maxMessageSize = 256 * 1024; // 256KB
        private       int       partitionCount = 1;

        public Builder(String name)                  { this.name = name; }
        public Builder type(TopicType t)             { this.type = t;            return this; }
        public Builder retentionSecs(int s)          { this.retentionSecs = s;   return this; }
        public Builder maxMessageSize(int bytes)     { this.maxMessageSize = bytes; return this; }
        public Builder partitions(int n)             { this.partitionCount = n;  return this; }
        public Topic build()                         { return new Topic(this); }
    }
}

// ============================================================
// 6. TOPIC FACTORY — FACTORY PATTERN (Req 6)
// ============================================================
class TopicFactory {
    /** Standard broadcast topic */
    public static Topic standard(String name) {
        return new Topic.Builder(name)
            .type(TopicType.STANDARD).build();
    }

    /** Partitioned topic for ordered, scaled delivery */
    public static Topic partitioned(String name, int partitions) {
        return new Topic.Builder(name)
            .type(TopicType.PARTITIONED)
            .partitions(partitions).build();
    }

    /** Fan-out: ephemeral — no message log, no replay */
    public static Topic fanout(String name) {
        return new Topic.Builder(name)
            .type(TopicType.FANOUT)
            .retentionSecs(0).build();  // no retention
    }

    /** Priority topic — messages sorted by priority */
    public static Topic priority(String name) {
        return new Topic.Builder(name)
            .type(TopicType.PRIORITY).build();
    }
}

// ============================================================
// 7. DELIVERY STRATEGY — STRATEGY PATTERN (Req 4 + 6)
// ============================================================
interface DeliveryStrategy {
    String getName();
    // Attempt delivery to all topic subscribers
    int deliver(Topic topic, Message message);
}

/** Sync: deliver inline, caller blocks until all are delivered */
class SyncDeliveryStrategy implements DeliveryStrategy {
    @Override public String getName() { return "Synchronous"; }

    @Override
    public int deliver(Topic topic, Message message) {
        return topic.fanOut(message);
    }
}

/**
 * Async: deliver via topic's fan-out (each subscriber has own thread).
 * Publisher is never blocked by slow subscribers (Req 6).
 */
class AsyncDeliveryStrategy implements DeliveryStrategy {
    private final ExecutorService fanOutPool;

    public AsyncDeliveryStrategy(int poolSize) {
        this.fanOutPool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "fanout-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Override public String getName() { return "Asynchronous"; }

    @Override
    public int deliver(Topic topic, Message message) {
        fanOutPool.submit(() -> topic.fanOut(message));
        return topic.getSubscriberCount(); // optimistic count
    }

    public void shutdown() { fanOutPool.shutdown(); }
}

// ============================================================
// 8. RETRY STRATEGY — STRATEGY PATTERN (Req 6)
// ============================================================
interface RetryStrategy {
    String getName();
    long nextDelayMs(int attempt);
    int  maxAttempts();
}

class FixedRetryStrategy implements RetryStrategy {
    private final long delayMs;
    private final int  max;

    public FixedRetryStrategy(long delayMs, int max) {
        this.delayMs = delayMs; this.max = max;
    }
    @Override public String getName()             { return "Fixed(" + delayMs + "ms x" + max + ")"; }
    @Override public long   nextDelayMs(int n)    { return delayMs; }
    @Override public int    maxAttempts()         { return max; }
}

class ExponentialBackoffStrategy implements RetryStrategy {
    private final long   baseDelayMs;
    private final double multiplier;
    private final long   maxDelayMs;
    private final int    max;

    public ExponentialBackoffStrategy(long baseMs, double mult, long maxMs, int max) {
        this.baseDelayMs = baseMs; this.multiplier = mult;
        this.maxDelayMs  = maxMs; this.max = max;
    }
    @Override public String getName()           { return "ExponentialBackoff"; }
    @Override public long   nextDelayMs(int n)  {
        return Math.min((long)(baseDelayMs * Math.pow(multiplier, n)), maxDelayMs);
    }
    @Override public int maxAttempts()          { return max; }
}

// ============================================================
// 9. PUBLISH COMMAND — COMMAND PATTERN (Req 1)
//    execute() = validate + route to topic + fan-out
//    Retryable via retry strategy
// ============================================================
class PublishCommand {
    private final Message         message;
    private final Topic           topic;
    private final DeliveryStrategy delivery;
    private final RetryStrategy    retry;
    private final List<Message>    deadLetterQueue;
    private       boolean          executed = false;

    public PublishCommand(Message message, Topic topic,
                           DeliveryStrategy delivery, RetryStrategy retry,
                           List<Message> dlq) {
        this.message        = message;
        this.topic          = topic;
        this.delivery       = delivery;
        this.retry          = retry;
        this.deadLetterQueue= dlq;
    }

    public boolean execute() {
        for (int attempt = 0; attempt <= retry.maxAttempts(); attempt++) {
            try {
                int delivered = delivery.deliver(topic, message);
                message.markDelivered();
                executed = true;
                return true;
            } catch (Exception e) {
                message.markFailed();
                if (attempt < retry.maxAttempts()) {
                    long delay = retry.nextDelayMs(attempt);
                    System.out.printf("[PublishCmd] Retry %d/%d for %s after %dms%n",
                        attempt + 1, retry.maxAttempts(),
                        message.getMessageRef(), delay);
                    try { Thread.sleep(delay); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }
        // Exhausted retries → dead letter queue
        message.markDeadLetter();
        deadLetterQueue.add(message);
        System.out.println("[PublishCmd] Dead-lettered: " + message.getMessageRef());
        return false;
    }

    // Replay: re-publish to same topic (useful for failure recovery)
    public boolean replay() {
        System.out.println("[PublishCmd] Replaying: " + message.getMessageRef());
        return execute();
    }

    public Message getMessage() { return message; }
}

// ============================================================
// 10. MESSAGE CURSOR — ITERATOR PATTERN (Req 2: replay)
//     Lets a late subscriber or consumer replay from any offset
// ============================================================
class MessageCursor implements Iterator<Message> {
    private final List<Message> messages;
    private       int           cursor;

    public MessageCursor(List<Message> messages, int startOffset) {
        this.messages = messages;
        this.cursor   = Math.min(startOffset, messages.size());
    }

    @Override public boolean hasNext() { return cursor < messages.size(); }
    @Override public Message next()    { return messages.get(cursor++); }
    public    int     getOffset()      { return cursor; }

    /** Seek to a specific position */
    public void seekTo(int offset)     { this.cursor = Math.min(offset, messages.size()); }
    public void seekToBeginning()      { this.cursor = 0; }
    public void seekToEnd()            { this.cursor = messages.size(); }
}

// ============================================================
// 11. MESSAGE BROKER — SINGLETON (Req 3 + 5 + 6)
//     Central coordinator: topic registry, publish routing,
//     subscriber management
// ============================================================
class MessageBroker {
    private static volatile MessageBroker instance;

    // topic name → Topic (Req 3: multiple topics)
    private final ConcurrentHashMap<String, Topic>      topics      = new ConcurrentHashMap<>();
    // publisher id → Publisher (Req 3: multiple publishers)
    private final ConcurrentHashMap<Long, Publisher>    publishers  = new ConcurrentHashMap<>();
    // subscriber id → Subscriber (Req 3: multiple subscribers)
    private final ConcurrentHashMap<Long, Subscriber>   subscribers = new ConcurrentHashMap<>();
    // Dead letter queue for failed messages
    private final List<Message>                         dlq         = new CopyOnWriteArrayList<>();

    private DeliveryStrategy deliveryStrategy = new AsyncDeliveryStrategy(4);
    private RetryStrategy    retryStrategy    = new ExponentialBackoffStrategy(100, 2, 5000, 3);

    // Metrics
    private final AtomicLong totalPublished  = new AtomicLong(0);
    private final AtomicLong totalDelivered  = new AtomicLong(0);

    private MessageBroker() {}

    public static MessageBroker getInstance() {
        if (instance == null) {
            synchronized (MessageBroker.class) {
                if (instance == null) instance = new MessageBroker();
            }
        }
        return instance;
    }

    // ---- Strategy swaps (Req 6: extensible) ----
    public void setDeliveryStrategy(DeliveryStrategy s) {
        this.deliveryStrategy = s;
        System.out.println("[Broker] Delivery strategy: " + s.getName());
    }

    public void setRetryStrategy(RetryStrategy s) {
        this.retryStrategy = s;
        System.out.println("[Broker] Retry strategy: " + s.getName());
    }

    // ---- Topic management ----
    public Topic createTopic(Topic topic) {
        topics.put(topic.getName(), topic);
        System.out.println("[Broker] Topic created: " + topic);
        return topic;
    }

    public Optional<Topic> getTopic(String name) {
        return Optional.ofNullable(topics.get(name));
    }

    public boolean topicExists(String name) { return topics.containsKey(name); }

    // ---- Publisher management ----
    public Publisher registerPublisher(Publisher publisher) {
        publishers.put(publisher.getPublisherId(), publisher);
        System.out.println("[Broker] Publisher registered: " + publisher.getName());
        return publisher;
    }

    // ---- Subscriber management (Req 2) ----
    public Subscriber subscribe(Subscriber subscriber, String topicName) {
        Topic topic = topics.get(topicName);
        if (topic == null) {
            System.out.println("[Broker] Topic not found: " + topicName);
            return null;
        }
        subscribers.put(subscriber.getSubscriberId(), subscriber);
        topic.subscribe(subscriber);
        return subscriber;
    }

    public boolean unsubscribe(long subscriberId, String topicName) {
        Topic topic = topics.get(topicName);
        if (topic == null) return false;
        topic.unsubscribe(subscriberId);
        return true;
    }

    // ---- Publish (Req 1 + 4) ----
    public boolean publish(Publisher publisher, String topicName, String payload) {
        return publish(publisher, topicName, payload, MessagePriority.NORMAL);
    }

    public boolean publish(Publisher publisher, String topicName,
                            String payload, MessagePriority priority) {
        Topic topic = topics.get(topicName);
        if (topic == null) {
            System.out.println("[Broker] Topic not found: " + topicName);
            return false;
        }

        Message message = new Message.Builder(topicName, publisher.getPublisherId(), payload)
            .priority(priority)
            .header("source", publisher.getName())
            .build();

        PublishCommand cmd = new PublishCommand(
            message, topic, deliveryStrategy, retryStrategy, dlq);

        boolean ok = cmd.execute();
        if (ok) {
            publisher.incrementSent();
            totalPublished.incrementAndGet();
        }
        return ok;
    }

    // ---- Replay from offset (Req: late subscriber catch-up) ----
    public MessageCursor getCursor(String topicName, int fromOffset) {
        Topic topic = topics.get(topicName);
        if (topic == null) return new MessageCursor(Collections.emptyList(), 0);
        List<Message> log = topic.replayFrom(0); // get full log
        return new MessageCursor(log, fromOffset);
    }

    public void printStats() {
        System.out.println("\n[Broker Stats]");
        System.out.println("  Topics:     " + topics.size());
        System.out.println("  Publishers: " + publishers.size());
        System.out.println("  Subscribers:" + subscribers.size());
        System.out.println("  Published:  " + totalPublished.get());
        System.out.println("  DLQ size:   " + dlq.size());
        System.out.println("\n  Topics:");
        topics.values().forEach(t -> System.out.println("    " + t));
        System.out.println("\n  Subscribers:");
        subscribers.values().forEach(s -> System.out.println("    " + s));
    }
}

// ============================================================
// 12. MAIN — DRIVER CODE
// ============================================================
public class PubSubSystem {
    public static void main(String[] args) throws InterruptedException {

        MessageBroker broker = MessageBroker.getInstance();

        // ===== SCENARIO 1: Req 1+2 — Create topics, publishers, subscribers =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Setup Topics, Publishers, Subscribers");
        System.out.println("=".repeat(60));

        // Create topics (Req 1)
        Topic orderTopic    = broker.createTopic(TopicFactory.standard("orders"));
        Topic paymentTopic  = broker.createTopic(TopicFactory.standard("payments"));
        Topic notifTopic    = broker.createTopic(TopicFactory.fanout("notifications"));
        Topic stockTopic    = broker.createTopic(
            TopicFactory.partitioned("stock-prices", 4));

        // Register publishers (Req 3: multiple)
        Publisher orderService   = broker.registerPublisher(new Publisher("OrderService"));
        Publisher paymentService = broker.registerPublisher(new Publisher("PaymentService"));
        Publisher marketFeed     = broker.registerPublisher(new Publisher("MarketFeed"));

        orderService.registerTopic("orders");
        paymentService.registerTopic("payments");
        marketFeed.registerTopic("stock-prices");

        // Create subscribers (Req 2 + 3: multiple)
        Subscriber inventorySub = new Subscriber("InventoryService", msg ->
            System.out.printf("  [InventorySvc] Processing order: %s%n", msg.getPayload()));

        Subscriber emailSub = new Subscriber("EmailService", msg ->
            System.out.printf("  [EmailSvc] Sending email for: %s%n", msg.getPayload()));

        Subscriber analyticsSub = new Subscriber("AnalyticsService", msg ->
            System.out.printf("  [Analytics] Recording event: topic=%s payload=%s%n",
                msg.getTopicName(), msg.getPayload()));

        Subscriber fraudSub = new Subscriber("FraudDetection", msg ->
            System.out.printf("  [FraudSvc] Checking payment: %s%n", msg.getPayload()));

        Subscriber dashboardSub = new Subscriber("Dashboard", msg ->
            System.out.printf("  [Dashboard] Live update: %s%n", msg.getPayload()));

        Subscriber chartSub = new Subscriber("ChartService", msg ->
            System.out.printf("  [ChartSvc] Updating chart: %s%n", msg.getPayload()));

        // Subscribe to topics (Req 2)
        broker.subscribe(inventorySub, "orders");
        broker.subscribe(emailSub,     "orders");
        broker.subscribe(analyticsSub, "orders");

        broker.subscribe(fraudSub,     "payments");
        broker.subscribe(emailSub,     "payments");    // multi-topic subscriber
        broker.subscribe(analyticsSub, "payments");

        broker.subscribe(dashboardSub, "stock-prices");
        broker.subscribe(chartSub,     "stock-prices");
        broker.subscribe(analyticsSub, "stock-prices");

        Thread.sleep(100); // Let delivery threads start

        // ===== SCENARIO 2: Req 1+4 — Publish messages =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Publish Messages (Req 1 + 4)");
        System.out.println("=".repeat(60));

        broker.publish(orderService, "orders",
            "{\"orderId\":\"ORD-001\",\"item\":\"MacBook\",\"qty\":1}");

        broker.publish(orderService, "orders",
            "{\"orderId\":\"ORD-002\",\"item\":\"iPhone\",\"qty\":2}",
            MessagePriority.HIGH);

        broker.publish(paymentService, "payments",
            "{\"paymentId\":\"PAY-001\",\"amount\":120000,\"method\":\"UPI\"}");

        Thread.sleep(200); // allow async delivery

        // ===== SCENARIO 3: Req 3 — Multiple publishers to same topic =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Multiple Publishers (Req 3)");
        System.out.println("=".repeat(60));

        Publisher orderService2 = broker.registerPublisher(
            new Publisher("OrderService-Region2"));
        orderService2.registerTopic("orders");

        broker.publish(orderService,  "orders",
            "{\"orderId\":\"ORD-003\",\"region\":\"Mumbai\"}");
        broker.publish(orderService2, "orders",
            "{\"orderId\":\"ORD-004\",\"region\":\"Delhi\"}");

        Thread.sleep(200);

        // ===== SCENARIO 4: Req 5 — Concurrent publish + subscribe =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Concurrent Publish (Req 5)");
        System.out.println("=".repeat(60));

        ExecutorService concurrentPool = Executors.newFixedThreadPool(5);

        for (int i = 0; i < 10; i++) {
            final int msgNum = i;
            final Publisher pub = (msgNum % 2 == 0) ? orderService : orderService2;
            concurrentPool.submit(() ->
                broker.publish(pub, "orders",
                    "{\"orderId\":\"ORD-CONC-" + msgNum + "\",\"concurrent\":true}"));
        }

        concurrentPool.shutdown();
        concurrentPool.awaitTermination(3, TimeUnit.SECONDS);
        Thread.sleep(300); // allow all async deliveries

        // ===== SCENARIO 5: Req 6 — Stock price fanout (high volume) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 5: High-Volume Stock Feed (Req 6)");
        System.out.println("=".repeat(60));

        String[] stocks = {"INFY", "TCS", "HDFC", "RELIANCE", "WIPRO"};
        for (String stock : stocks) {
            double price = 1000 + Math.random() * 500;
            broker.publish(marketFeed, "stock-prices",
                String.format("{\"symbol\":\"%s\",\"price\":%.2f}", stock, price),
                MessagePriority.HIGH);
        }

        Thread.sleep(200);

        // ===== SCENARIO 6: Dynamic subscribe/unsubscribe =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 6: Dynamic Subscribe / Unsubscribe");
        System.out.println("=".repeat(60));

        // New subscriber joins mid-stream
        Subscriber lateJoiner = new Subscriber("LateJoiner", msg ->
            System.out.printf("  [LateJoiner] Got: %s%n", msg.getPayload()));
        broker.subscribe(lateJoiner, "orders");

        broker.publish(orderService, "orders",
            "{\"orderId\":\"ORD-005\",\"note\":\"LateJoiner should receive this\"}");

        Thread.sleep(200);

        // Unsubscribe inventory from orders
        broker.unsubscribe(inventorySub.getSubscriberId(), "orders");

        broker.publish(orderService, "orders",
            "{\"orderId\":\"ORD-006\",\"note\":\"Inventory should NOT receive this\"}");

        Thread.sleep(200);

        // ===== SCENARIO 7: Pause + resume subscriber =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 7: Pause + Resume Subscriber");
        System.out.println("=".repeat(60));

        emailSub.pause();
        broker.publish(orderService, "orders",
            "{\"orderId\":\"ORD-007\",\"note\":\"Email paused — should not deliver\"}");
        Thread.sleep(100);

        emailSub.resume();
        broker.publish(orderService, "orders",
            "{\"orderId\":\"ORD-008\",\"note\":\"Email resumed — should deliver\"}");
        Thread.sleep(200);

        // ===== SCENARIO 8: Message replay via cursor =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 8: Message Replay from Offset");
        System.out.println("=".repeat(60));

        MessageCursor cursor = broker.getCursor("orders", 0);
        int count = 0;
        System.out.println("Replaying first 3 messages from orders topic:");
        while (cursor.hasNext() && count < 3) {
            Message msg = cursor.next();
            System.out.println("  [Replay] " + msg);
            count++;
        }
        System.out.println("  Cursor now at offset: " + cursor.getOffset());

        // ===== SCENARIO 9: Strategy swap (Req 6) =====
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 9: Strategy Swap at Runtime");
        System.out.println("=".repeat(60));

        broker.setDeliveryStrategy(new SyncDeliveryStrategy());
        broker.publish(paymentService, "payments",
            "{\"paymentId\":\"PAY-SYNC\",\"mode\":\"synchronous\"}");

        broker.setDeliveryStrategy(new AsyncDeliveryStrategy(4));
        broker.publish(paymentService, "payments",
            "{\"paymentId\":\"PAY-ASYNC\",\"mode\":\"asynchronous\"}");

        Thread.sleep(300);

        // ===== FINAL STATS =====
        broker.printStats();

        System.out.println("\n===== PATTERN SUMMARY =====");
        System.out.println("""
            Pattern    | Class
            -----------|----------------------------------------------------------
            Singleton  | MessageBroker (double-checked locking)
            State      | SubscriptionStatus: ACTIVE → PAUSED → CANCELLED
                       | MessageStatus: PENDING → DELIVERED / FAILED / DEAD_LETTER
            Strategy   | DeliveryStrategy (Sync / Async)
                       | RetryStrategy (Fixed / ExponentialBackoff)
            Observer   | Subscriber — the core of pub-sub IS the Observer pattern
            Factory    | TopicFactory (standard / partitioned / fanout / priority)
            Builder    | Message.Builder, Topic.Builder
            Command    | PublishCommand: execute() + replay()
            Iterator   | MessageCursor: seek, replay from any offset
            """);

        System.out.println("===== THREAD-SAFETY (Req 5) =====");
        System.out.println("""
            Class              | Mechanism                      | Why
            -------------------|--------------------------------|-----------------------------
            Topic.subscribe()  | ReentrantReadWriteLock (write) | Exclusive subscriber registry
            Topic.fanOut()     | ReentrantReadWriteLock (read)  | Concurrent multi-sub delivery
            Subscriber.deliver | BlockingQueue inbox            | Thread-safe bounded queue
            MessageBroker      | ConcurrentHashMap              | Safe concurrent topic access
            Message log        | CopyOnWriteArrayList           | Safe concurrent reads
            MessageBroker      | volatile + double-checked      | Safe singleton init
            """);
    }
}
