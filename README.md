# System Design & Low-Level Design (LLD) Portfolio

A comprehensive, curated collection of production-grade Low-Level Design (LLD) and High-Level Design (HLD) patterns. This repository deep-dives into object-oriented design principles (SOLID), concurrency control, structural blueprints, and architectural strategies for scaling modern software engineering applications.

---

## 🚀 Repository Blueprint

To make navigation seamless, the system components are divided into explicit domains:

### 🏢 Core Core Infrastructure & Utility Systems
These patterns focus on concurrency, resilience, and state management at the infrastructure level.

* [**Circuit Breaker**](./circuit%20breaker) — Fault tolerance, fallback strategies, and state transitions (Closed, Open, Half-Open).
* [**Distributed Cache**](./distributed%20cache) — Consistent hashing, eviction algorithms, and replication models.
* [**TTL Cache**](./ttl%20cache) — Expiry mechanics, background cleanup workers, and cache-aside eviction policies.
* [**Logging Framework**](./logging%20framework) — Chain of Responsibility pattern implementation supporting asynchronous decoupled appenders.
* [**Rate Limiter**](./rate%20limiter) — Distributed throttling algorithms (Token Bucket / Leaky Bucket / Sliding Window Log).

### 🛒 E-Commerce & Transaction-Heavy Ecosystems
High-concurrency systems featuring transactional state models and complex orchestration.

* [**Amazon Ecosystem**](./amazon) — Product catalog architecture, order state tracking, and customer lifecycles.
* [**Inventory Management**](./inventory%20management) — Multi-warehouse inventory synchronization and optimistic/pessimistic locking.
* [**Payment Systems**](./payment%20systems) — Idempotent transactions, payment gateways, and reconciliation engines.
* [**Shopping Cart**](./shopping%20cart) — In-memory session tracking, checkout pipelines, and discount engine hooks.
* [**Splitwise**](./splitwise) — Expense splitting mathematics, balance minimization graph algorithms, and ledger tracking.
* [**Vending Machine**](./vending%20machine) — State design pattern representing explicit coin validation, item selection, and change dispense workflows.

### 🚗 Mobility, Logistics & Booking Platforms
Real-time resource allocation engines handling dynamic pricing and geographical scheduling.

* [**Airbnb (Hotel Booking)**](./airbnb:%20hotel%20booking) — Inventory management, calendar blocking, and dynamic room pricing algorithms.
* [**Airline Booking System (Ixigo)**](./airline%20booking%20system(ixigo)) — Flight seating charts, multi-stop routing optimizations, and reservation locks.
* [**Car Rental**](./car%20rental) — Vehicle fleet logs, location-based tracking, and reservation state handlers.
* [**Food Delivery (Swiggy)**](./food%20delivery:%20swiggy) — Real-time tracking graphs, delivery partner assignment, and multi-restaurant order matching.
* [**Parking Lot**](./parking%20lot) — Extensible multi-vehicle slot allocation strategies and dynamic fee calculation.
* [**Uber (Ride Hailing)**](./uber) — Geospatial querying (Spatial Indexing/H3/S2), ETA computation, and driver matching.

### 💬 Social Media & Real-Time Interaction Systems
High-throughput read/write flows requiring eventual consistency and event-driven backends.

* [**Netflix**](./netflix) — Video content streaming schemas, personalized recommendations, and profile state managers.
* [**Search Auto-Complete**](./search%20auto%20complete) — Trie data structure indexing, prefix searching, and real-time query weight calculations.
* [**Truecaller**](./truecaller) — Massive global search indexes, contact book sync, and spam identification loops.
* [**Twitter Feed**](./twitter%20feed) — Fanout-on-write vs. fanout-on-read hybrid caching timelines.
* [**WhatsApp (Chat System)**](./whatsapp:%20chat%20system) — WebSockets connection handling, delivery tracking (sent/delivered/read states), and offline queues.
* [**YouTube**](./youtube) — Asynchronous ingestion workers, chunked processing pipelines, and CDN management.

### 🎮 Gaming & Automation Modules
* [**Elevator System**](./elevator) — Dispatch algorithms (SCAN/LOOK elevator algorithms) handling multi-car orchestration.
* [**Game Framework**](./game%20framework) — Extensible game loops, tick handlers, and entity-component architectures.
* [**Traffic Light Systems**](./traffic%20light%20systems) / [**Simple variant**](./traffic%20light%20system%20simple) — State pattern managing intersection timers and adaptive scheduling.

### 🗃️ Everyday Utilities & Internal Services
* [**ATM**](./atm) — Secure cash dispensing mechanics, session validation, and local hardware state mapping.
* [**BookMyShow**](./bookMyShow) — Seat selection locks and high-concurrency ticket booking states.
* [**Library Management**](./library%20management) — Fine tracking, catalog indexing, and transactional book borrowing.
* [**Meeting Scheduler**](./meeting%20scheduler) — Interval calendar overlapping algorithms, room matching, and conflict resolution.
* [**Stock Exchange (Groww/Zerodha)**](./stock%20exchange%20groww%20zerodha) — High-frequency matching engines, order books (Limit/Market orders), and ticker feeds.
* [**URL Shortener**](./url%20shortner) — Base62 encoding strategy, high-throughput redirect caches, and key generation services.
* [**Voting System**](./voting%20system) — Deduplicated secure ballot counting, region-aggregated metrics, and concurrency control.

---

## 🛠️ Architectural Foundations & Design Idioms

The code inside this repository focuses heavy emphasis on writing clean code that adheres to:
* **SOLID Principles:** Single Responsibility, Open/Closed architectures, Interface Segregation.
* **Behavioral Patterns:** Strategy (Pricing/Throttling), Observer Pattern (used heavily in the [Notify Me module](./notify%20me%20or%20observer%20pattern)), State, Command, and Chain of Responsibility.
* **Concurrency Models:** Multi-threaded synchronization primitives, optimistic locking strategies, and thread-safe collections.
* **Production Resiliency:** Distributed tracing points, structured event logs, and fault-tolerant [High-Resilience Patterns](./high%20Resilience%20application).

## 🚀 Getting Started

To explore or run any specific design module locally:

```bash
# Clone the repository
git clone [https://github.com/Yashaswinihoney/LLD.git](https://github.com/Yashaswinihoney/LLD.git)

# Navigate into a specific system directory
cd LLD/"parking lot"
