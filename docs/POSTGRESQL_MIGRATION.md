# Lord Gainsborough PostgreSQL Migration Plan and Progress Journal

> **Single Source of Truth**  
> **Repository:** `Amberverma195/lordgainsborough`  
> **Status:** Active Roadmap & Journal  
> **Last Updated:** 2026-10-26T17:19:00-04:00 (America/Toronto)  

---

## Migration Objective

### Purpose of Migration
The Lord Gainsborough restaurant application is transitioning its primary persistence engine from **MongoDB to Supabase-hosted PostgreSQL**.

While MongoDB served as an initial document store during early prototyping, the operational demands of a commercial restaurant application—including strict ordering integrity, menu repricing validations, atomic rate-limiting and challenge state machines, no-show record accounting, and customer profile synchronization—are inherently relational and demand ACID guarantees.

Transitioning to Supabase PostgreSQL provides:
1. **Relational Data Integrity:** Enforced foreign key constraints (e.g., cascading deletions for order line items, reviews, and carts), unique constraints, and schema validations.
2. **ACID Transaction Guarantees:** Standard relational isolation across multi-table operations (e.g., password reset finalization, order creation with inventory/pricing verification, customer no-show counters).
3. **Structured Evolution with Flyway:** Database schema changes are committed to source control as versioned SQL migration scripts, ensuring deterministic deployments across development, staging, and production.
4. **Cloud Managed Resilience:** Supabase delivers automated backups, point-in-time recovery, connection pooling via PgBouncer/Supavisor, and native PostgreSQL features on dedicated cloud infrastructure.

### Target Technology Stack
- **Frontend:** Next.js 16 (React 19, TypeScript, TailwindCSS, Socket.IO client). Hosted on **Vercel**. *Retain existing implementation.*
- **Backend:** Spring Boot 4.0.6, Java 21. Hosted on **Railway**. *Retain existing architecture and endpoints.*
- **Persistence Framework:** Spring Data JPA, Hibernate ORM, and PostgreSQL JDBC Driver (`org.postgresql:postgresql`).
- **Database Engine:** Supabase-hosted PostgreSQL 15+.
- **Database Schema Migrations:** Flyway (`flyway-core`, `flyway-database-postgresql`).
- **Realtime Services:** Netty-SocketIO (`com.corundumstudio.socketio:netty-socketio:2.0.14`) server and Socket.IO client. *Retain all existing events, payloads, customer rooms, and KDS synchronization.*
- **Connection Configuration:** Railway environment variables: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.

### Components That Must Remain Unchanged
1. **Next.js Frontend Contract:** The frontend API client expects specific JSON DTO shapes, route endpoints (`/api/auth/*`, `/api/orders/*`, `/api/kds/*`, `/api/menu/*`, `/api/cart/*`, `/api/reviews/*`, `/api/reservations/*`), and HTTP status codes. No URL changes or payload schema breaks are permitted.
2. **Socket.IO Protocol & Event Semantics:** The Socket.IO server on port 9092, handshake authorization parameters (`role`, `orderId`), token extraction (`token`, `trackingToken`), customer rooms (`order:<id>`), kitchen room (`kds`), and events (`order.created`, `order.updated`, `chat.message`) must remain functionally identical.
3. **Security Model & Business Logic:**
   - Client IP resolution (`ClientIpResolver`) and trusted proxy headers (`X-Forwarded-For`, `X-Real-IP`).
   - Abuse prevention: IP hashing via keyed HMAC (`AbuseIpHasher`), fixed-window rate counters, login failure penalties, and cooldown enforcement.
   - Signup OTP and Password Reset challenge state machines with idempotency keys and send leases.
   - Order access control: dual authentication via customer JWT or secret tracking token header (`X-Order-Tracking-Token`).
   - KDS IP allowlist access gating (`store_settings.kds_allowed_ip`).
   - Three-strike customer pickup suspension (`no_show_count >= 3`).
4. **Email Delivery:** Brevo SMTP and Brevo REST API fallback (`EmailSenderService`, `BrevoApiEmailService`).

### Why JPA/Hibernate Instead of Prisma
- The Lord Gainsborough backend is built natively with **Java 21 and Spring Boot 4**.
- Prisma is an ORM designed specifically for Node.js/TypeScript runtimes. Adopting Prisma would require running a foreign Node.js sidecar or rewriting the entire Spring Boot backend into Next.js/Node.js, introducing significant latency, complexity, and security risk.
- **Spring Data JPA with Hibernate** is the native, battle-tested standard for Spring Boot. It provides seamless integration with Spring's declarative transaction manager (`PlatformTransactionManager` / `@Transactional`), repository abstractions, connection pooling (HikariCP), JPA entity lifecycle callbacks, and standard Flyway migration hooks.

### No Customer Data Migration Required
The Lord Gainsborough website has not launched to real customers. MongoDB currently holds exclusively synthetic development, testing, and menu mock data. No historical database dump, data transformation pipeline, or cutover replication is required. We provision a fresh, clean PostgreSQL relational schema via Flyway and initialize seed data (categories, menu catalog, and default store settings) using automated data initialization scripts.

---

## Repository Inspection Findings & Architecture Baseline

An exhaustive inspection of the existing codebase (`backend/pom.xml`, `application.properties`, models, repositories, services, controllers, and tests) revealed critical MongoDB-dependent mechanisms that must be carefully re-engineered for PostgreSQL:

### 1. Existing MongoDB Models and Collections
| Class Name | MongoDB Collection | Relational Mapping Strategy | Key Architectural Observations |
| :--- | :--- | :--- | :--- |
| `User` | `users` | `users` table | Contains functional unique index on `LOWER(email)` and unique constraint on `phone_normalized`. Primary key `id` is a `String`. |
| `CustomerOrder` | `orders` | `customer_orders`, `order_items`, `order_messages` | Contains embedded `List<OrderItem>` and `List<OrderMessage>` mapped to child tables with `item_index` ordering. |
| `Cart` | `carts` | `carts`, `cart_items` | Contains embedded `List<CartItem>` ordered by `item_index`. Relied on Mongo TTL index (7 days); replaced by scheduled cleanup. |
| `MenuItem` | `menuItems` | `menu_items` | Catalog entity with category string reference, `NUMERIC(10,2)` price, and availability flags. |
| `Category` | `categories` | `categories` | Display ordering and unique category definitions. |
| `OrderReview` | `orderReviews` | `order_reviews`, `order_review_items` | Child `List<ReviewItem>` ordered by `item_index`. Unique review per `order_id`. |
| `Reservation` | `reservations` | `reservations` | Table booking entity with party size check constraint (`party_size > 0`) and date/time strings. |
| `StoreSettings` | `store_settings` | `store_settings` | Singleton entity with fixed ID `'default'`. Stores tax rate (`NUMERIC(5,4)`), default ready minutes, and KDS allowed IP list. |
| `CatalogMigration`| `catalogMigrations`| `catalog_migrations` | Tracks applied catalog versions (e.g., `'2026-08-lord-gains-menu'`). |
| `SignupOtpChallenge` | `signup_otp_challenges` | `signup_otp_challenges` | Primary key `email_key` (SHA-256 hex). Expiration index on `challenge_expires_at`. Idempotency tracking. |
| `PasswordResetOtpChallenge` | `password_reset_otp_challenges` | `password_reset_otp_challenges` | Primary key `email_key`. Nullable `code_digest` cleared upon consumption. Leases, verify attempts, and idempotency key. |
| `SignupAbuseLimit` | `signup_abuse_limits` | `signup_abuse_limits` | Rate limit counters. Expiration index on `expires_at`. Composite index on `(bucket_type, subject_hash)`. |
| `LoginFailureState` | `login_failure_states` | `login_failure_states` | Tracking subject failure counts, block windows, and expirations. |

### 2. MongoDB-Specific Mechanics & PostgreSQL Replacement Strategy
1. **Embedded Collections & Order Preservation:**
   - In MongoDB, order line items (`OrderItem`), order messages (`OrderMessage`), cart items (`CartItem`), and review items (`ReviewItem`) are embedded sub-documents.
   - In PostgreSQL, these are modeled as child tables with foreign key constraints (`ON DELETE CASCADE`) and an explicit `item_index INT NOT NULL DEFAULT 0` column to deterministically preserve list ordering without race conditions during Hibernate `@OrderColumn` updates.
2. **MongoDB TTL Indexes vs. Relational Expiration:**
   - MongoDB automatically removes expired documents using background TTL index monitors.
   - PostgreSQL does not provide automatic background row deletion based on timestamps.
   - *Solution:* Application services perform explicit timestamp comparisons against `Clock.instant()` during query time (`isChallengeExpired()`, `cart.isExpired()`). B-tree indexes on expiration columns (`expires_at`, `challenge_expires_at`) optimize query filters. A Spring `@Scheduled` maintenance task periodically purges expired challenges and stale carts.
3. **Atomic Operations & `findAndModify`:**
   - `MongoAbuseCounterStore` uses `mongoTemplate.findAndModify` with `upsert(true)` and `.inc("count", 1)`.
   - *Solution:* In PostgreSQL, this maps to an atomic upsert:  
     `INSERT INTO signup_abuse_limits (...) VALUES (...) ON CONFLICT (id) DO UPDATE SET count = signup_abuse_limits.count + 1 RETURNING count;`
   - `LoginRateLimiter` executes an optimistic compare-and-swap (CAS) retry loop with up to 20 attempts.
   - *Solution:* In PostgreSQL, row-level locking (`SELECT ... FOR UPDATE` within a Spring `@Transactional` boundary) or an atomic conditional update eliminates CAS contention loops.
   - `SignupOtpChallengePersistence` and `PasswordResetOtpChallengePersistence` execute conditional `findAndModify` calls for leases, attempts, and state transitions.
   - *Solution:* Replaced with clean Spring Data JPA repository methods using `@Modifying @Query` or pessimistic write locks (`@Lock(LockModeType.PESSIMISTIC_WRITE)`).
4. **Transactions & Transaction Management:**
   - `PasswordResetService` currently instantiates `TransactionTemplate` using `MongoTransactionManager`.
   - *Solution:* Standardize on Spring's `PlatformTransactionManager` (specifically `JpaTransactionManager`), allowing declarative `@Transactional` boundaries.
5. **Primary Key Representation:**
   - MongoDB uses 24-character hexadecimal `ObjectId` strings for auto-generated IDs, while manually providing string IDs for challenges and settings.
   - The Next.js frontend and all DTOs treat IDs as strings (e.g., `order.id`, `item.id`).
   - *Solution:* PostgreSQL tables use `VARCHAR(64)` primary keys generated via `@GeneratedValue(strategy = GenerationType.UUID)` or `UUID.randomUUID().toString()`. This maintains 100% binary and serial compatibility with frontend TypeScript interfaces.
6. **Backend Tests:**
   - Existing integration tests utilize `testcontainers-mongodb` (`MongoDBContainer`).
   - *Solution:* Migrate integration test suites to `testcontainers-postgresql` (`PostgreSQLContainer`) with Flyway migration execution during Phase 13 (`PG-024`).

---

## Relational Schema Design (PostgreSQL / Flyway)

The authoritative relational schema is defined in `backend/src/main/resources/db/migration/V1__init_relational_schema.sql`.

```
                  +-------------------+
                  |       users       |
                  +-------------------+
                            | 1
                            |
                            | 0..*
                  +-------------------+
                  |       carts       |
                  +-------------------+
                            | 1
                            |
                            | 0..*
                  +-------------------+
                  |    cart_items     |
                  +-------------------+

+-------------------+       +-----------------------+       +-------------------+
|    categories     |       |    customer_orders    |       |   order_reviews   |
+-------------------+       +-----------------------+       +-------------------+
         |                             | 1                             | 1
         | 0..*                        |                               |
+-------------------+                  +---------------+               | 0..*
|    menu_items     |                  | 0..*          | 0..*   +-------------------+
+-------------------+         +-----------------+  +-----------------+  order_review_items
                              |   order_items   |  | order_messages  |  +-------------------+
                              +-----------------+  +-----------------+
```

### DDL Specification Summary

> Note: Refer to `backend/src/main/resources/db/migration/V1__init_relational_schema.sql` as the authoritative DDL definition.

1. **`users`**
   - `id VARCHAR(64) PRIMARY KEY`
   - `name VARCHAR(255)`, `email VARCHAR(255) NOT NULL`, `phone VARCHAR(50)`, `phone_normalized VARCHAR(20)`
   - `password VARCHAR(255)`, `role VARCHAR(50) NOT NULL DEFAULT 'MEMBER'`, `member_since VARCHAR(50)`
   - `created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP`, `last_profile_update_at TIMESTAMP WITHOUT TIME ZONE`
   - `token_version BIGINT NOT NULL DEFAULT 0`, `password_changed_at TIMESTAMP WITH TIME ZONE`
   - `last_password_reset_operation_id VARCHAR(64)`, `no_show_count INT NOT NULL DEFAULT 0`
   - Constraints: `uq_users_phone_normalized UNIQUE (phone_normalized)`, `chk_users_token_version CHECK (token_version >= 0)`, `chk_users_no_show_count CHECK (no_show_count >= 0)`
   - Indexes: Functional unique index `uq_users_email_lower ON users (LOWER(email))`, `idx_users_name`, `idx_users_role`

2. **`categories`**
   - `id VARCHAR(64) PRIMARY KEY`, `name VARCHAR(255) NOT NULL`, `description TEXT`, `display_order INT NOT NULL DEFAULT 0`
   - Constraints: `uq_categories_name UNIQUE (name)`, `chk_categories_display_order CHECK (display_order >= 0)`
   - Index: `idx_categories_display_order`

3. **`menu_items`**
   - `id VARCHAR(64) PRIMARY KEY`, `name VARCHAR(255) NOT NULL`, `description TEXT`
   - `price NUMERIC(10, 2) NOT NULL` (Java `BigDecimal`), `category VARCHAR(255) NOT NULL`, `image VARCHAR(1024)`
   - `available BOOLEAN NOT NULL DEFAULT TRUE`, `is_most_loved BOOLEAN NOT NULL DEFAULT FALSE`
   - Constraints: `chk_menu_items_price CHECK (price >= 0.00)`
   - Indexes: `idx_menu_items_category`, `idx_menu_items_name_lower ON menu_items (LOWER(name))`, `idx_menu_items_available`

4. **`catalog_migrations`**
   - `id VARCHAR(128) PRIMARY KEY`, `applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP`

5. **`store_settings`**
   - `id VARCHAR(64) PRIMARY KEY DEFAULT 'default'`, `tax_rate NUMERIC(5, 4) NOT NULL DEFAULT 0.1300`, `default_ready_minutes INT NOT NULL DEFAULT 25`
   - `store_name VARCHAR(255) NOT NULL DEFAULT 'Lord Gainsborough'`, `contact_phone VARCHAR(50) DEFAULT ''`, `contact_email VARCHAR(255) DEFAULT ''`
   - `pickup_orders_enabled BOOLEAN NOT NULL DEFAULT TRUE`, `kds_allowed_ip TEXT DEFAULT ''`
   - Constraints: `chk_store_settings_tax_rate CHECK (tax_rate >= 0.0000 AND tax_rate <= 1.0000)`, `chk_store_settings_default_ready_minutes CHECK (default_ready_minutes > 0)`

6. **`customer_orders`**
   - `id VARCHAR(64) PRIMARY KEY`, `customer_name VARCHAR(255) NOT NULL`, `customer_email VARCHAR(255) NOT NULL`, `customer_phone VARCHAR(50) NOT NULL`
   - `pickup_date VARCHAR(20) NOT NULL`, `pickup_time VARCHAR(20)`, `special_instructions TEXT`, `status VARCHAR(50) NOT NULL DEFAULT 'NEW'`, `average_ready_minutes INT NOT NULL DEFAULT 25`
   - Monetary (Java `BigDecimal`): `subtotal NUMERIC(10, 2) NOT NULL DEFAULT 0.00`, `tax NUMERIC(10, 2) NOT NULL DEFAULT 0.00`, `total NUMERIC(10, 2) NOT NULL DEFAULT 0.00`
   - Timestamps & Audit: `created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP`, `accepted_at`, `ready_at`, `picked_up_at`, `no_show_at TIMESTAMP WITHOUT TIME ZONE`, `reviewed BOOLEAN NOT NULL DEFAULT FALSE`, `tracking_token_hash VARCHAR(255)`
   - Constraints: `chk_orders_subtotal CHECK (subtotal >= 0.00)`, `chk_orders_tax CHECK (tax >= 0.00)`, `chk_orders_total CHECK (total >= 0.00)`, `chk_orders_average_ready_minutes CHECK (average_ready_minutes > 0)`
   - Indexes: `idx_orders_pickup_date`, `idx_orders_status`, `idx_orders_created_at (created_at DESC)`, composite `idx_orders_email_status (LOWER(customer_email), status)`, composite `idx_orders_phone_status (customer_phone, status)`

7. **`order_items`**
   - `id VARCHAR(64) PRIMARY KEY`, `order_id VARCHAR(64) NOT NULL`, `item_index INT NOT NULL DEFAULT 0`, `menu_item_id VARCHAR(64)`
   - `name VARCHAR(255) NOT NULL`, `quantity INT NOT NULL`, `unit_price NUMERIC(10, 2) NOT NULL`, `total NUMERIC(10, 2) NOT NULL`, `notes TEXT`
   - Constraints: FK `fk_order_items_order REFERENCES customer_orders(id) ON DELETE CASCADE`, `chk_order_items_quantity CHECK (quantity > 0)`, `chk_order_items_unit_price CHECK (unit_price >= 0.00)`, `chk_order_items_total CHECK (total >= 0.00)`, `chk_order_items_item_index CHECK (item_index >= 0)`
   - Index: Composite `idx_order_items_order_index (order_id, item_index)` (covers foreign key lookups and ordered retrieval)

8. **`order_messages`**
   - `id VARCHAR(64) PRIMARY KEY`, `order_id VARCHAR(64) NOT NULL`, `item_index INT NOT NULL DEFAULT 0`
   - `sender_role VARCHAR(50) NOT NULL`, `sender_name VARCHAR(255) NOT NULL`, `text TEXT NOT NULL`, `created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP`
   - Constraints: FK `fk_order_messages_order REFERENCES customer_orders(id) ON DELETE CASCADE`, `chk_order_messages_item_index CHECK (item_index >= 0)`
   - Index: Composite `idx_order_messages_created_at (order_id, created_at)` (covers foreign key lookups and chronological chat history)

9. **`carts`**
   - `id VARCHAR(64) PRIMARY KEY`, `user_id VARCHAR(64) NOT NULL`, `updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP`
   - Constraints: `uq_carts_user_id UNIQUE (user_id)`, FK `fk_carts_user REFERENCES users(id) ON DELETE CASCADE`
   - Index: `idx_carts_updated_at`

10. **`cart_items`**
    - `id VARCHAR(64) PRIMARY KEY`, `cart_id VARCHAR(64) NOT NULL`, `item_index INT NOT NULL DEFAULT 0`, `menu_item_id VARCHAR(64)`
    - `name VARCHAR(255) NOT NULL`, `description TEXT`, `price NUMERIC(10, 2) NOT NULL`, `category VARCHAR(255)`, `image VARCHAR(1024)`, `quantity INT NOT NULL`
    - Constraints: FK `fk_cart_items_cart REFERENCES carts(id) ON DELETE CASCADE`, `chk_cart_items_quantity CHECK (quantity > 0)`, `chk_cart_items_price CHECK (price >= 0.00)`, `chk_cart_items_item_index CHECK (item_index >= 0)`
    - Index: Composite `idx_cart_items_cart_index (cart_id, item_index)`

11. **`order_reviews`**
    - `id VARCHAR(64) PRIMARY KEY`, `order_id VARCHAR(64) NOT NULL`, `customer_name VARCHAR(255) NOT NULL`, `customer_email VARCHAR(255) NOT NULL`, `customer_phone VARCHAR(50) NOT NULL`
    - `status VARCHAR(50) NOT NULL DEFAULT 'PENDING'`, `created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP`
    - Constraints: `uq_order_reviews_order_id UNIQUE (order_id)`, FK `fk_order_reviews_order REFERENCES customer_orders(id) ON DELETE CASCADE`
    - Indexes: `idx_order_reviews_status`, `idx_order_reviews_created_at (created_at DESC)`

12. **`order_review_items`**
    - `id VARCHAR(64) PRIMARY KEY`, `review_id VARCHAR(64) NOT NULL`, `item_index INT NOT NULL DEFAULT 0`, `menu_item_id VARCHAR(64)`, `item_name VARCHAR(255) NOT NULL`, `rating INT NOT NULL`, `description TEXT`
    - Constraints: FK `fk_order_review_items_review REFERENCES order_reviews(id) ON DELETE CASCADE`, `chk_order_review_items_rating CHECK (rating >= 1 AND rating <= 5)`, `chk_order_review_items_item_index CHECK (item_index >= 0)`
    - Index: Composite `idx_order_review_items_review_index (review_id, item_index)`

13. **`reservations`**
    - `id VARCHAR(64) PRIMARY KEY`, `name VARCHAR(255) NOT NULL`, `email VARCHAR(255) NOT NULL`, `phone VARCHAR(50) NOT NULL`
    - `date VARCHAR(20) NOT NULL`, `time VARCHAR(20) NOT NULL`, `party_size INT NOT NULL`, `special_requests TEXT`, `status VARCHAR(50) NOT NULL DEFAULT 'PENDING'`, `created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP`
    - Constraints: `chk_reservations_party_size CHECK (party_size > 0)`
    - Indexes: `idx_reservations_date`, `idx_reservations_email`, `idx_reservations_phone`, `idx_reservations_created_at (created_at DESC)`

14. **`signup_otp_challenges`**
    - `email_key VARCHAR(64) PRIMARY KEY`, `normalized_email VARCHAR(255) NOT NULL`, `challenge_id VARCHAR(64) NOT NULL`, `status VARCHAR(50) NOT NULL`, `otp_digest VARCHAR(255) NOT NULL`
    - `resend_count INT NOT NULL DEFAULT 0`, `failed_verify_attempts INT NOT NULL DEFAULT 0`, `next_resend_at TIMESTAMP WITH TIME ZONE`, `otp_expires_at TIMESTAMP WITH TIME ZONE`, `challenge_expires_at TIMESTAMP WITH TIME ZONE NOT NULL`
    - `created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP`, `send_operation_id VARCHAR(64)`, `send_status VARCHAR(50)`, `send_lease_until TIMESTAMP WITH TIME ZONE`, `client_idempotency_key VARCHAR(128)`, `last_send_was_initial BOOLEAN NOT NULL DEFAULT TRUE`
    - Constraints: `chk_signup_otp_resend_count CHECK (resend_count >= 0)`, `chk_signup_otp_failed_verify CHECK (failed_verify_attempts >= 0)`
    - Indexes: `idx_signup_otp_challenge_id`, `idx_signup_otp_challenge_expires`, `idx_signup_otp_idempotency`

15. **`password_reset_otp_challenges`**
    - `email_key VARCHAR(64) PRIMARY KEY`, `challenge_id VARCHAR(64) NOT NULL`, `user_id VARCHAR(64) NOT NULL`, `purpose VARCHAR(50) NOT NULL`, `status VARCHAR(50) NOT NULL`
    - `code_digest VARCHAR(255)` (Nullable; set to NULL upon consumption), `code_expires_at TIMESTAMP WITH TIME ZONE`, `challenge_expires_at TIMESTAMP WITH TIME ZONE NOT NULL`, `next_resend_at TIMESTAMP WITH TIME ZONE`
    - `resend_count INT NOT NULL DEFAULT 0`, `failed_verification_attempts INT NOT NULL DEFAULT 0`, `client_idempotency_key VARCHAR(128)`, `send_operation_id VARCHAR(64)`, `send_status VARCHAR(50)`, `send_lease_until TIMESTAMP WITH TIME ZONE`
    - `reset_operation_id VARCHAR(64)`, `password_updated BOOLEAN NOT NULL DEFAULT FALSE`, `consumed_at TIMESTAMP WITH TIME ZONE`, `created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP`
    - Constraints: `uq_pwd_reset_challenge_id UNIQUE (challenge_id)`, FK `fk_pwd_reset_user REFERENCES users(id) ON DELETE CASCADE`, `chk_pwd_reset_resend_count CHECK (resend_count >= 0)`, `chk_pwd_reset_failed_verify CHECK (failed_verification_attempts >= 0)`
    - Indexes: `idx_pwd_reset_challenge_expires`, `idx_pwd_reset_user_id`, `idx_pwd_reset_idempotency`

16. **`signup_abuse_limits`**
    - `id VARCHAR(128) PRIMARY KEY`, `bucket_type VARCHAR(50) NOT NULL`, `subject_hash VARCHAR(128) NOT NULL`, `count BIGINT NOT NULL DEFAULT 1`, `window_start TIMESTAMP WITH TIME ZONE NOT NULL`, `expires_at TIMESTAMP WITH TIME ZONE NOT NULL`
    - Constraints: `chk_signup_abuse_count CHECK (count >= 0)`
    - Indexes: `idx_abuse_limits_expires_at`, `idx_abuse_limits_bucket_subject (bucket_type, subject_hash)`

17. **`login_failure_states`**
    - `id VARCHAR(128) PRIMARY KEY`, `subject_hash VARCHAR(128) NOT NULL`, `failure_count INT NOT NULL DEFAULT 1`, `window_started_at TIMESTAMP WITH TIME ZONE NOT NULL`, `blocked_until TIMESTAMP WITH TIME ZONE`, `updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP`, `expires_at TIMESTAMP WITH TIME ZONE NOT NULL`
    - Constraints: `chk_login_failure_count CHECK (failure_count >= 0)`
    - Indexes: `idx_login_failure_expires_at`, `idx_login_failure_blocked_until`

---

## Complete Migration Roadmap

The migration is organized into 15 dependency-ordered phases. Each phase defines clear objectives, dependencies, deliverables, and verification criteria.

### Phase 1: Repository Inspection and Architectural Baseline
- **Objective:** Fully inspect the existing codebase, identify MongoDB-specific idioms, assess dependencies, and establish the permanent documentation roadmap and progress journal.
- **Dependencies:** None.
- **Expected Deliverables:** Complete `docs/POSTGRESQL_MIGRATION.md` single source of truth.
- **Verification Criteria:** Verified documentation of all models, repositories, atomic operations, TTL indexes, and Socket.IO dependencies.

### Phase 2: PostgreSQL Relational Schema Design & Validation
- **Objective:** Finalize the normalized PostgreSQL DDL script and validate against an active PostgreSQL engine.
- **Dependencies:** Phase 1.
- **Expected Deliverables:** Versioned migration `V1__init_relational_schema.sql` covering all 17 tables, foreign keys with cascading deletions, unique constraints, business `CHECK` constraints, ordered child collections with `item_index`, and non-redundant search indexes.
- **Verification Criteria:** DDL passes direct PostgreSQL engine validation with automated assertion harness testing table creation, unique constraint enforcement, cascade deletions, nullability state changes, and check constraints.

### Phase 3: Spring Data JPA, PostgreSQL JDBC Driver & Flyway Dependency Configuration
- **Objective:** Add Maven dependencies for JPA, PostgreSQL JDBC driver, and Flyway migration engine in `backend/pom.xml` while keeping existing MongoDB dependencies intact to enable a non-breaking dual-configuration transition.
- **Dependencies:** Phase 2.
- **Expected Deliverables:** Updated `pom.xml` containing `spring-boot-starter-data-jpa`, `org.postgresql:postgresql`, `org.flywaydb:flyway-core`, and `org.flywaydb:flyway-database-postgresql` (or `spring-boot-starter-flyway`).
- **Verification Criteria:** Maven clean compile succeeds (`mvn clean compile -DskipTests`).

### Phase 4: Database Connection, HikariCP & Early Flyway Smoke Test Harness
- **Objective:** Configure HikariCP connection pool, Flyway configuration (`spring.flyway.enabled=true`, `spring.flyway.locations=classpath:db/migration`), and PostgreSQL properties in `application.properties`. For a fresh database, do NOT enable `spring.flyway.baseline-on-migrate=true` by default (as baselining is reserved for pre-existing non-Flyway schemas). Keep `spring.jpa.hibernate.ddl-auto=none` to avoid premature global validation failures while JPA entities are incomplete. Execute an early Flyway migration smoke test (`PG-028`) to confirm Flyway runs `V1__init_relational_schema.sql` and populates `flyway_schema_history` cleanly. (Note: Comprehensive test-suite Testcontainers integration remains scheduled in Phase 13 under `PG-024`).
- **Dependencies:** Phase 3 (`PG-001`), Phase 2 (`PG-003`).
- **Expected Deliverables:** Configured `application.properties`, working HikariCP connection pool harness, and verified Flyway schema execution.
- **Verification Criteria:** Flyway executes `V1__init_relational_schema.sql` against PostgreSQL, creates `flyway_schema_history`, and reports version 1 applied.

### Phase 5: Core Domain JPA Entity Mapping
- **Objective:** Create JPA entities for core catalog and administrative models: `User`, `Category`, `MenuItem`, `CatalogMigration`, and `StoreSettings`.
- **Dependencies:** Phase 4 (`PG-028`).
- **Expected Deliverables:** Java entity classes annotated with `@Entity`, `@Table`, `@Id`, `@Column`, and `@GeneratedValue`. Monetary amounts mapped to `BigDecimal`.
- **Verification Criteria:** Entity mappings pass targeted Hibernate validation against `V1__init_relational_schema.sql`.

### Phase 6: Operational & Relational Entities (Orders, Carts, Reviews, Reservations)
- **Objective:** Implement JPA entities for `CustomerOrder`, `OrderItem`, `OrderMessage`, `Cart`, `CartItem`, `OrderReview`, `ReviewItem`, and `Reservation`.
- **Dependencies:** Phase 5.
- **Expected Deliverables:** Parent-child relational entities with `@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)`, `@OrderColumn(name = "item_index")`, and foreign key references.
- **Verification Criteria:** Unit tests verify cascading persist, update, and delete behavior across orders, carts, and reviews.

### Phase 7: Security & Rate-Limiting Relational Persistence
- **Objective:** Implement JPA entities for `SignupOtpChallenge`, `PasswordResetOtpChallenge`, `SignupAbuseLimit`, and `LoginFailureState`.
- **Dependencies:** Phase 6.
- **Expected Deliverables:** JPA entities supporting high-concurrency rate limiting and challenge state machines.
- **Verification Criteria:** Entities validate against schema and support atomic updates without data truncation.

### Phase 8: Spring Data JPA Repositories & Custom Query Methods
- **Objective:** Implement `JpaRepository` interfaces mirroring all existing `MongoRepository` methods (`UserRepository`, `CustomerOrderRepository`, `CartRepository`, `CategoryRepository`, `MenuItemRepository`, `OrderReviewRepository`, `ReservationRepository`, `StoreSettingsRepository`, `CatalogMigrationRepository`).
- **Dependencies:** Phases 5, 6, 7.
- **Expected Deliverables:** Typed `JpaRepository` interfaces with equivalent finder and existence query methods.
- **Verification Criteria:** Method signatures match existing call sites in controllers and services; repository unit tests execute successfully.

### Phase 9: Catalog Seeding & DataInitializer Adaptation
- **Objective:** Adapt `DataInitializer.java` to synchronize categories and menu items using JPA repositories and transactional semantics (`@Transactional`).
- **Dependencies:** Phase 8.
- **Expected Deliverables:** Updated `DataInitializer` that checks `CatalogMigrationRepository.existsById()` and idempotently seeds categories and menu items without duplicates.
- **Verification Criteria:** `DataInitializerTest` passes; menu and category catalogs are populated into PostgreSQL upon startup.

### Phase 10: Security & Persistence Services Migration
- **Objective:** Rewrite `MongoAbuseCounterStore`, `LoginRateLimiter`, `SignupOtpChallengePersistence`, and `PasswordResetOtpChallengePersistence` to use PostgreSQL JPA/JDBC and `PlatformTransactionManager`.
- **Dependencies:** Phases 7, 8.
- **Expected Deliverables:**
  - `PostgresAbuseCounterStore` using atomic `INSERT ... ON CONFLICT DO UPDATE`.
  - `LoginRateLimiter` using relational transactions instead of CAS loops.
  - `SignupOtpChallengePersistence` and `PasswordResetOtpChallengePersistence` using JPA repositories and `@Transactional`.
  - Transaction manager modernized in `PasswordResetService`.
- **Verification Criteria:** Concurrency tests confirm atomic counter increments, cooldown blocking, and challenge state transitions under load.

### Phase 11: Application Controllers & Services Verification
- **Objective:** Verify and adapt `OrderController`, `CartController`, `KdsController`, `ReviewController`, `ReservationController`, `AdminCustomerController`, and `AuthController` to ensure complete compatibility with PostgreSQL repositories.
- **Dependencies:** Phases 9, 10.
- **Expected Deliverables:** All REST endpoints operate on PostgreSQL entities with zero change to JSON contracts or frontend expectations.
- **Verification Criteria:** Integration tests verify order placement, repricing, cart updates, KDS status transitions, reviews, and reservations.

### Phase 12: Realtime Socket.IO Verification & Integration
- **Objective:** Verify that `SocketIoConfig` and `OrderRealtimeService` seamlessly work with PostgreSQL-backed orders, customer rooms, and KDS broadcasts.
- **Dependencies:** Phase 11.
- **Expected Deliverables:** Fully functional Socket.IO server emitting `order.created`, `order.updated`, and `chat.message` events backed by PostgreSQL data.
- **Verification Criteria:** Socket.IO client connections to `kds` and `order:<id>` receive valid ISO-8601 timestamps and serialized order payloads.

### Phase 13: Test Suite Modernization & PostgreSQL Testcontainers
- **Objective:** Replace MongoDB testcontainers dependencies with PostgreSQL testcontainers (`org.testcontainers:postgresql`) across all integration tests (`PG-024`).
- **Dependencies:** Phase 12.
- **Expected Deliverables:** Updated test suite executing against containerized PostgreSQL running Flyway migrations.
- **Verification Criteria:** `mvn clean test` runs with 100% test pass rate across all controller, service, and security test classes.

### Phase 14: Supabase Live Connection & Deployment Validation
- **Objective:** Verify live Spring Boot connectivity to Supabase PostgreSQL using Railway environment variables (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`).
- **Dependencies:** Phase 13.
- **Expected Deliverables:** Railway deployment connecting via SSL (`sslmode=require`) and executing Flyway migrations against Supabase.
- **Verification Criteria:** Actuator health endpoint (`/actuator/health`) reports database `UP`.

### Phase 15: Decommissioning MongoDB Configuration & Final Acceptance
- **Objective:** Safely remove obsolete MongoDB dependencies (`spring-boot-starter-data-mongodb`, `testcontainers-mongodb`), `MongoConfig.java`, and unused Mongo properties after PostgreSQL is 100% verified.
- **Dependencies:** Phase 14.
- **Expected Deliverables:** Clean codebase free of MongoDB dependencies.
- **Verification Criteria:** Clean build, all tests pass, and end-to-end user workflows (browsing, ordering, tracking, auth, KDS) verified.

---

## Task Tracker

| Task ID | Phase | Task | Status | Started At | Completed At | Verification |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `PG-001` | Phase 3 | Add Spring Data JPA, PostgreSQL JDBC, and Flyway dependencies to `pom.xml` | `COMPLETED` | 2026-10-25T19:56:53-04:00 | 2026-10-25T19:58:10-04:00 | `mvn clean compile -DskipTests`: BUILD SUCCESS (99 files compiled cleanly) |
| `PG-002` | Phase 4 | Configure DataSource, HikariCP, and Flyway in `application.properties` | `COMPLETED` | 2026-10-25T20:07:10-04:00 | 2026-10-25T20:19:30-04:00 | Profile isolation verified; `mvn clean compile -DskipTests` SUCCESS; HikariCP live test passed (3/3 tests) |
| `PG-003` | Phase 2 | Create `V1__init_relational_schema.sql` with full relational DDL, constraints, and indexes | `COMPLETED` | 2026-10-25T19:14:28-04:00 | 2026-10-25T19:44:21-04:00 | 17 tables, 55 indexes, 72/72 assertions passed on PostgreSQL 17.11 |
| `PG-004` | Phase 5 | Create core domain JPA entities (`JpaUser`, `JpaCategory`, `JpaMenuItem`, `JpaCatalogMigration`, `JpaStoreSettings`) | `COMPLETED` | 2026-10-25T21:02:53-04:00 | 2026-10-25T21:08:45-04:00 | Hibernate 7 `ddl-auto=validate` PASSED against Flyway V1 schema; 7/7 JPA integration tests passed |
| `PG-005` | Phase 6 | Create operational JPA entities (`CustomerOrder`, `OrderItem`, `OrderMessage`) | `COMPLETED` | 2026-10-25T21:37:01-04:00 | 2026-10-25T21:43:00-04:00 | Hibernate 7 `ddl-auto=validate` PASSED against Flyway V1 schema; 18/18 JPA integration tests passed |
| `PG-006` | Phase 6 | Create cart and review JPA entities (`Cart`, `CartItem`, `OrderReview`, `OrderReviewItem`, `Reservation`) | `COMPLETED` | 2026-10-25T22:04:44-04:00 | 2026-10-25T22:12:00-04:00 | Hibernate 7 `ddl-auto=validate` PASSED against Flyway V1 schema; 26/26 JPA integration tests passed |
| `PG-007` | Phase 7 | Create security and rate-limiting JPA entities (`SignupOtpChallenge`, `PasswordResetOtpChallenge`) | `COMPLETED` | 2026-10-25T22:25:30-04:00 | 2026-10-25T22:31:30-04:00 | Hibernate 7 `ddl-auto=validate` PASSED against Flyway V1 schema; 26/26 security integration tests passed |
| `PG-008` | Phase 7 | Create abuse and failure state JPA entities (`SignupAbuseLimit`, `LoginFailureState`) | `COMPLETED` | 2026-10-25T22:51:00-04:00 | 2026-10-25T22:56:00-04:00 | Hibernate 7 `ddl-auto=validate` PASSED (all 17 entities); 25/25 integration tests passed; full 7-suite regression passed (109/109 tests) |
| `PG-009` | Phase 8 | Implement Spring Data JPA repositories for core and catalog models | `COMPLETED` | 2026-10-25T23:05:00-04:00 | 2026-10-25T23:23:00-04:00 | 5 Spring Data JPA repos implemented; 25/25 repository integration tests passed; full 8-suite regression passed (134/134 tests) |
| `PG-010` | Phase 8 | Implement Spring Data JPA repositories for orders, carts, reviews, and reservations | `COMPLETED` | 2026-10-25T23:57:00-04:00 | 2026-10-26T00:15:00-04:00 | 4 Spring Data JPA repos implemented; 31/31 repository integration tests passed; full 9-suite regression passed (165/165 tests) |
| `PG-011` | Phase 8 | Implement Spring Data JPA repositories for security challenges and abuse limits | `COMPLETED` | 2026-10-26T00:35:00-04:00 | 2026-10-26T00:49:00-04:00 | 4 Spring Data JPA repos implemented; 34/34 security repository integration tests passed; full 10-suite regression passed (199/199 tests) |
| `PG-012` | Phase 9 | Adapt `DataInitializer.java` to use JPA repositories and relational catalog synchronization | `COMPLETED` | 2026-10-26T01:06:00-04:00 | 2026-10-26T02:18:00-04:00 | Shared `CatalogSeedSpecifications` extracted; `DataInitializerTest` passed (4/4); 41/41 PostgreSQL seeding & startup activation tests passed; full 12-suite regression passed (240/240 tests) |
| `PG-013` | Phase 10 | Implement `PostgresAbuseCounterStore` using atomic PostgreSQL upsert queries | `COMPLETED` | 2026-10-26T02:40:00-04:00 | 2026-10-26T03:26:00-04:00 | Concurrency-safe atomic `INSERT ... ON CONFLICT DO UPDATE RETURNING count`; simplified arithmetic increment without redundant CASE; BIGINT overflow handling; `getCount` exact boundaries; production-safe verified disposable db `clear()`; explicit backend selection via `@Primary`; independent fallback `transactionManager` bean; real Logback appender log verification; 40/40 tests passed; full 13-suite regression passed |
| `PG-014` | Phase 10 | Refactor `LoginRateLimiter` to use relational transactional locking | `COMPLETED` | 2026-10-26T03:30:00-04:00 | 2026-10-26T03:42:00-04:00 | Extracted `LoginFailureStateStore` SPI; implemented `PostgresLoginFailureStateStore` with atomic upsert + `SELECT ... FOR UPDATE` row-level locking; fail-closed `Integer.MAX_VALUE` overflow guard; exact millisecond active cooldown boundaries; targeted `clearFailures`; 38/38 new tests; 14-suite regression passed (318/318 tests) |
| `PG-015` | Phase 10 | Refactor `SignupOtpChallengePersistence` for PostgreSQL JPA persistence | `COMPLETED` | 2026-10-26T04:19:00-04:00 | 2026-10-26T05:35:00-04:00 | Extracted `SignupOtpChallengeStore` SPI; implemented `PostgresSignupOtpChallengeStore` with atomic SQL transitions; hardened with 5-finding resolution (atomic locking, deprecated unguarded overloads, fail-closed resend cooldown, Flyway disposable DB lifecycle with CI skip/fail controls, JPA timestamp integrity); 63/63 integration tests passed; full regression passed |
| `PG-016` | Phase 10 | Refactor `PasswordResetOtpChallengePersistence` and `PasswordResetService` transaction management | `COMPLETED` | 2026-10-26T06:35:00-04:00 | 2026-10-26T17:53:00-04:00 | Extracted `PasswordResetOtpChallengeStore` SPI; implemented `PostgresPasswordResetOtpChallengeStore` with atomic SQL transitions & single-transaction user update (`SELECT ... FOR UPDATE`); atomic 5th-attempt lock; cross-backend null `nextResendAt` fail-closed parity; opt-in configuration with fallback `transactionManager`; 48/48 integration tests in `PostgresPasswordResetOtpPersistenceIntegrationTest` executed against live disposable PostgreSQL; full 16-suite PostgreSQL regression passed (438/438 tests, 0 skipped); MongoDB unit & support tests passed (11/11) |
| `PG-017` | Phase 11 | Adapt `OrderController` and `OrderAccessService` for JPA persistence and repricing | `NOT STARTED` | — | — | `OrderControllerTest` pass |
| `PG-018` | Phase 11 | Adapt `CartController` for relational cart item persistence | `NOT STARTED` | — | — | Cart API endpoint tests |
| `PG-019` | Phase 11 | Adapt `KdsController` and `CustomerNoShowService` for JPA persistence | `NOT STARTED` | — | — | KDS order and status tests |
| `PG-020` | Phase 11 | Adapt `ReviewController` and `ReservationController` for JPA persistence | `NOT STARTED` | — | — | `ReviewControllerTest` pass |
| `PG-021` | Phase 11 | Adapt `AdminCustomerSearchService` and `AdminCustomerController` for JPA queries | `NOT STARTED` | — | — | `AdminCustomerSearchServiceTest` pass |
| `PG-022` | Phase 11 | Adapt `AuthController` user registration and authentication logic for JPA | `NOT STARTED` | — | — | `AuthControllerTest` pass |
| `PG-023` | Phase 12 | Verify `SocketIoConfig` and `OrderRealtimeService` realtime events with PostgreSQL orders | `NOT STARTED` | — | — | Socket.IO room and event verification |
| `PG-024` | Phase 13 | Migrate integration test suites to PostgreSQL Testcontainers | `NOT STARTED` | — | — | `mvn clean test` with testcontainers |
| `PG-025` | Phase 14 | Validate live Supabase PostgreSQL connection and Flyway migration execution | `NOT STARTED` | — | — | Actuator health endpoint `UP` |
| `PG-026` | Phase 15 | Remove MongoDB configuration (`MongoConfig.java`), properties, and dependencies | `NOT STARTED` | — | — | Clean build without Mongo libraries |
| `PG-027` | Phase 15 | Conduct end-to-end acceptance testing across Next.js frontend and Railway backend | `NOT STARTED` | — | — | E2E browser and API test pass |
| `PG-028` | Phase 4 | Execute early Flyway migration smoke test against clean PostgreSQL | `COMPLETED` | 2026-10-25T20:29:39-04:00 | 2026-10-25T20:36:00-04:00 | Flyway 11.14.1 on PG 17.11: 4/4 integration tests passed (initial V1 migration 17 tables, idempotency 0 applied, validation, minimal SB4 context) |

---

## Current Batch — Batch 1

The following implementation sequence establishes the foundational persistence configuration and schema without disrupting running code:

### 1. `PG-003` — Relational Schema Design & Validation
- **Status:** `COMPLETED`
- **Objective:** Finalize the complete PostgreSQL relational schema in `V1__init_relational_schema.sql` and validate directly against an active PostgreSQL engine.
- **Dependencies:** None (completed independently in Phase 2).

### 2. `PG-001` — Configure Spring Data JPA, PostgreSQL JDBC, and Flyway Dependencies in `pom.xml`
- **Status:** `COMPLETED`
- **Objective:** Add required Maven dependencies for Spring Data JPA, PostgreSQL JDBC driver, and Flyway migration engine to `backend/pom.xml`.
- **Implementation Scope:**
  - Add `org.springframework.boot:spring-boot-starter-data-jpa`.
  - Add `org.postgresql:postgresql` runtime driver.
  - Add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` (or `spring-boot-starter-flyway`).
  - Keep `spring-boot-starter-data-mongodb` intact to prevent compilation breakage of existing services.
- **Acceptance Criteria:**
  - All new dependencies resolve cleanly without version conflicts.
  - Existing dependencies remain unaffected.
- **Verification Commands:** `mvn clean compile -DskipTests`
- **Dependencies:** None.

### 3. `PG-002` — Configure Spring DataSource, HikariCP, and Flyway in `application.properties`
- **Status:** `COMPLETED`
- **Objective:** Set up PostgreSQL DataSource, HikariCP connection pool, and Flyway configuration in `application.properties`.
- **Implementation Scope:**
  - Configure DataSource properties (`spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`).
  - Configure HikariCP settings (`maximum-pool-size=10`, `minimum-idle=2`, `connection-timeout=30000`, `idle-timeout=600000`).
  - Configure Flyway: `spring.flyway.enabled=true`, `spring.flyway.locations=classpath:db/migration`. Do NOT enable `spring.flyway.baseline-on-migrate=true` by default for a fresh empty database.
  - Configure JPA settings: Keep `spring.jpa.hibernate.ddl-auto=none` to avoid premature validation failures while entity classes are incomplete.
  - Retain MongoDB properties until services are converted.
- **Acceptance Criteria:** Spring Boot configuration initializes PostgreSQL DataSource and Flyway without breaking active Mongo components.
- **Verification Commands:** `mvn compile -DskipTests`
- **Dependencies:** `PG-001`.

### 4. `PG-028` — Early Flyway Migration Execution Smoke Test
- **Status:** `COMPLETED`
- **Objective:** Execute an isolated Flyway migration smoke test against a clean PostgreSQL database to verify that `V1__init_relational_schema.sql` runs cleanly through the Flyway runner and correctly initializes `flyway_schema_history`.
- **Implementation Scope:** Verify Flyway runner lifecycle against target database without requiring full JPA entity or controller integration.
- **Acceptance Criteria:** All 17 tables are created and `flyway_schema_history` records state `SUCCESS`.
- **Verification Commands:** Isolated Flyway smoke test execution.
- **Dependencies:** `PG-001`, `PG-002`, `PG-003`.

### 5. `PG-004` — Create Core Domain JPA Entities (`User`, `Category`, `MenuItem`, `CatalogMigration`, `StoreSettings`)
- **Status:** `COMPLETED`
- **Objective:** Implement the initial set of JPA entities for catalog, user, and store settings, and verify entity mappings against `V1__init_relational_schema.sql`.
- **Implementation Scope:**
  - Add `@Entity`, `@Table`, `@Id`, `@Column` annotations.
  - Map currency columns to Java `BigDecimal`.
  - Configure string ID generation (`@GeneratedValue(strategy = GenerationType.UUID)` or UUID string assignment).
- **Acceptance Criteria:** Core entities accurately match table definitions and compile cleanly.
- **Verification Commands:** JPA entity mapping unit tests.
- **Dependencies:** `PG-003`, `PG-028`.

---

## Chronological Progress Log

### [2026-10-25T07:24:34-04:00] — Architectural Inspection & Migration Roadmap Creation

**Action:**  
Conducted comprehensive code and dependency inspection across backend (`pom.xml`, `application.properties`, models, repositories, controllers, security services, Socket.IO config, and test classes) and frontend (`context/CartContext.tsx`, Socket.IO listeners, orders, auth). Designed target PostgreSQL relational schema and authored the complete migration plan and progress journal.

**Files:**  
- `docs/POSTGRESQL_MIGRATION.md` (created)

**Status:**  
COMPLETED

**Verification:**  
- Inspected all 16 model classes and identified embedded sub-documents, TTL indexes, and atomic `findAndModify` operations.
- Verified system timezone is `America/Toronto` (EDT, UTC-04:00) using system clock command.
- Verified existing Railway environment variable bindings (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`).
- Verified Socket.IO event contract and customer room subscription requirements.

**Notes/Blockers:**  
- No real customer data exists; migration does not require data export/import from MongoDB.
- MongoDB and PostgreSQL configurations will co-exist during the transitional phases to avoid breaking active services before replacement.
- Planned work for Batch 1 (`PG-001` through `PG-004`) is defined and awaiting execution instruction.

**Next Step:**  
Await user instruction to begin Batch 1 (`PG-001`).

### [2026-10-25T19:44:21-04:00] — Task PG-003: PostgreSQL Relational Schema Design and Validation

**Task:** PG-003 — PostgreSQL Relational Schema Design and Validation  
**Status:** COMPLETED  
**Started At:** 2026-10-25T19:14:28-04:00  
**Completed At:** 2026-10-25T19:44:21-04:00  

**Implementation Summary:**  
Authored the authoritative PostgreSQL V1 Flyway schema (`backend/src/main/resources/db/migration/V1__init_relational_schema.sql`), mapping all 13 MongoDB document models into 17 normalized relational tables. Maintained String primary keys (`VARCHAR(64)`) to preserve API contracts with the Next.js frontend. Implemented foreign keys with cascading deletes (`ON DELETE CASCADE`) across all parent-child hierarchies (`order_items`, `order_messages`, `cart_items`, `order_review_items`), preserving collection ordering with `item_index INT NOT NULL DEFAULT 0`. Enforced case-insensitive user email uniqueness using `LOWER(email)` via `uq_users_email_lower`. Standardized currency on PostgreSQL `NUMERIC(10,2)` (and `NUMERIC(5,4)` for tax rate), targeting Java `BigDecimal` for subsequent JPA entities. Incorporated strict business `CHECK` constraints on prices, quantities, ratings (1–5), party size, and token versions. Corrected password-reset challenge fields (`reset_operation_id`, `password_updated`, `consumed_at`, `failed_verification_attempts`, nullable `code_digest`). Optimized B-tree indexes by pruning redundant single-column indexes covered by leading composite indexes (`idx_order_items_order_index`, `idx_order_messages_created_at`, `idx_cart_items_cart_index`, `idx_order_review_items_review_index`, `idx_orders_email_status`, `idx_orders_phone_status`), achieving an optimal 55-index architecture.

**Verification:**  
Validated against a fresh disposable PostgreSQL 17.11 instance using automated PowerShell harness `scratch/validate_schema_v2.ps1`. All 17 tables created cleanly. Exactly 55 indexes confirmed with zero redundant structures. All 72 assertions passed with zero failures: confirmed mixed-case email insertion and duplicate collision rejection, single-row update to `code_digest IS NULL` on challenge consumption, complete cascading deletion across all 4 child tables on order removal, and representative `CHECK` constraint enforcement. Direct PostgreSQL DDL validation is complete; runtime Flyway migration execution and Hibernate entity validation remain pending later integration tasks.

### [2026-10-25T19:58:10-04:00] — Task PG-001: Spring Data JPA, PostgreSQL JDBC and Flyway Dependencies

**Task:** PG-001 — Spring Data JPA, PostgreSQL JDBC and Flyway Dependencies  
**Status:** COMPLETED  
**Started At:** 2026-10-25T19:56:53-04:00  
**Completed At:** 2026-10-25T19:58:10-04:00  

**Implementation Summary:**  
Configured Maven dependencies in `backend/pom.xml` to support the upcoming PostgreSQL persistence layer while preserving complete dual-configuration compatibility with existing MongoDB components. Added `org.springframework.boot:spring-boot-starter-data-jpa` (resolving Spring Data JPA 4.0.5, Hibernate Core 7.2.12.Final, and HikariCP 7.0.2), `org.postgresql:postgresql:42.7.10` (runtime scope), `org.springframework.boot:spring-boot-starter-flyway:4.0.6`, and `org.flywaydb:flyway-database-postgresql:11.14.1` (which transitively supplies `flyway-core:11.14.1`). Rely on Spring Boot 4 BOM dependency management without hardcoded library versions. Preserved `spring-boot-starter-data-mongodb:4.0.6` and all MongoDB models, repositories, transaction management, and tests intact.

**Verification:**  
Executed `mvn clean compile -DskipTests` (`.\mvnw.cmd clean compile -DskipTests`) resulting in `BUILD SUCCESS` with all 99 source files compiling without errors or regressions. Verified clean dependency tree resolution with zero library conflicts.

**Runtime Integration Risks Deferred to PG-002:**  
Co-existence of JPA and MongoDB starters on the classpath can trigger automatic dual-persistence auto-configuration, ambiguity in `@Transactional` transaction manager resolution, and repository scanning collisions if not explicitly segregated. In addition, `DataSourceAutoConfiguration` and `FlywayAutoConfiguration` will attempt runtime database connection initialization during Spring Boot startup. These risks are acknowledged and deferred to isolated configuration and pooling controls in PG-002.

**Next Task:**  
`PG-002` — Configure Spring DataSource, HikariCP, and Flyway in `application.properties`.

### [2026-10-25T20:19:30-04:00] — Task PG-002: Isolated PostgreSQL DataSource, HikariCP and Flyway Configuration

**Task:** PG-002 — Isolated PostgreSQL DataSource, HikariCP and Flyway Configuration  
**Status:** COMPLETED  
**Started At:** 2026-10-25T20:07:10-04:00  
**Completed At:** 2026-10-25T20:19:30-04:00  

**Implementation Summary:**  
Implemented strict environment isolation between the default MongoDB persistence layer and the newly introduced PostgreSQL relational stack in Spring Boot 4.0.6:
1. Updated `application.properties` (default profile) to explicitly exclude relational auto-configuration classes:
   - `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`
   - `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`
   - `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`
   - `org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration`
   Also explicitly set `spring.flyway.enabled=false`. This guarantees that the default MongoDB-backed application boots cleanly without requiring PostgreSQL environment variables or initiating relational connections.
2. Created `backend/src/main/resources/application-postgres-migration.properties` (activated via `--spring.profiles.active=postgres-migration`):
   - Overrides `spring.autoconfigure.exclude=` to un-exclude and activate relational auto-configurations.
   - Configures PostgreSQL JDBC URL `jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME:postgres}?sslmode=${DB_SSL_MODE:prefer}`, username `${DB_USER}`, password `${DB_PASSWORD}`, and driver `org.postgresql.Driver`.
   - Configures HikariCP connection pool: pool name `LordGainsboroughHikariPool`, max pool size `${DB_POOL_MAX_SIZE:10}`, min idle `${DB_POOL_MIN_IDLE:2}`, connection timeout `${DB_POOL_CONNECTION_TIMEOUT:30000}`, idle timeout `${DB_POOL_IDLE_TIMEOUT:600000}`, and max lifetime `${DB_POOL_MAX_LIFETIME:1800000}`.
   - Configures Flyway: `spring.flyway.enabled=true`, `spring.flyway.locations=classpath:db/migration`, `spring.flyway.baseline-on-migrate=false`, and `spring.flyway.clean-disabled=true`.
   - Configures JPA/Hibernate safe defaults: `spring.jpa.hibernate.ddl-auto=none`, `spring.jpa.open-in-view=false`, `spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect`, and `spring.jpa.show-sql=false`.
3. Created test harness `com.lordgainsborough.restaurant.config.PostgresConfigurationSmokeTest` verifying default isolation, profile property bindings, and live HikariCP socket connectivity.

**Verification:**  
- Executed `.\mvnw.cmd clean compile -DskipTests`: `BUILD SUCCESS` (99 source files compiled cleanly).
- Executed automated PowerShell harness initializing a temporary local PostgreSQL 17 instance on port 54333 and executing `PostgresConfigurationSmokeTest`:
  - `testDefaultApplicationProperties_excludesRelationalAutoConfig`: PASSED. Verified relational auto-configurations are excluded and Flyway is disabled in default profile.
  - `testMigrationProfileProperties_configuredCorrectly`: PASSED. Verified `postgres-migration` profile properties, pool sizing, and Flyway configuration.
  - `testHikariDataSource_liveConnectivityIfAvailable`: PASSED. Successfully established `LordGainsboroughHikariPool` connection against local PostgreSQL, verified `SELECT 1`, and shut down cleanly.
  - Tests run: 3, Failures: 0, Errors: 0, Skipped: 0.
- Live PostgreSQL connectivity verified without touching production Supabase credentials.

**Transaction Manager & Dual-Persistence Analysis:**  
- When running in default profile, relational auto-configurations are excluded; `MongoTransactionManager` configured in `MongoConfig.java` remains the exclusive transaction manager.
- When `postgres-migration` profile is activated, `JpaTransactionManager` is introduced. `MongoConfig` explicitly registers `mongoTransactionManager` (`MongoTransactionManager`). In subsequent phases (PG-016), when services transition to PostgreSQL, `@Transactional` boundaries will be explicitly qualified or transitioned to `JpaTransactionManager` as MongoDB services are retired.

**Next Task:**  
`PG-028` — Early Flyway Migration Execution Smoke Test.

### [2026-10-25T20:36:00-04:00] — Task PG-028: Early Flyway PostgreSQL Migration Integration Test

**Task:** PG-028 — Early Flyway PostgreSQL Migration Integration Test  
**Status:** COMPLETED  
**Started At:** 2026-10-25T20:29:39-04:00  
**Completed At:** 2026-10-25T20:36:00-04:00  

**Implementation Summary:**  
Implemented and executed comprehensive Flyway migration integration testing using Flyway 11.14.1 against isolated disposable PostgreSQL 17.11 instances on local loopback:
1. Created `com.lordgainsborough.restaurant.migration.FlywayMigrationIntegrationTest` covering direct Flyway Java API operations and Spring Boot 4 minimal context auto-configuration:
   - Initial Migration (`test1`): Flyway discovered `V1__init_relational_schema.sql` at `classpath:db/migration` and applied it cleanly. Verified creation of `flyway_schema_history` table with `installed_rank=1`, `version='1'`, `type='SQL'`, `success=true`, and non-zero checksum. Confirmed all 17 application tables exist in `public` schema (18 tables total including history), foreign keys (`fk_order_items_order`, `fk_order_messages_order`, `fk_cart_items_cart`, `fk_order_reviews_order`, `fk_order_review_items_review`, `fk_pwd_reset_user`), unique indexes (`uq_users_email_lower`), and 55 application table indexes.
   - Idempotency (`test2`): Re-ran `flyway.migrate()` against the already-migrated database. Confirmed zero migrations executed (`migrationsExecuted=0`), empty migrations list, `success=true`, schema history row count remaining exactly 1, and tables unchanged.
   - Schema Validation (`test3`): Executed `flyway.validateWithResult()`. Confirmed `validationSuccessful=true`, zero invalid migrations, and no validation errors or warnings.
   - Spring Boot Auto-Configuration (`test4`): Tested minimal Spring Boot 4 context via `ApplicationContextRunner` configured with `DataSourceAutoConfiguration`, `FlywayAutoConfiguration`, and properties matching `application-postgres-migration.properties`. Confirmed `DataSource` bean created as `HikariDataSource` with pool name `LordGainsboroughHikariPool`, `Flyway` bean created, and Flyway migration runner automatically executed on context refresh, bringing the clean test database to version 1.
2. Verified architecture preservation: Zero modifications to frontend code, API contracts, Socket.IO behavior, or production Supabase. No JPA entities introduced (`PG-004` remains untouched).
3. Documented transaction manager inspection: Verified `MongoConfig.java` defines bean `mongoTransactionManager` (not `transactionManager`), confirming the bean naming distinction for future `@Transactional` qualification in Phase 10.

**Verification:**  
- Test Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest -Dtest.postgres.port=54335`
- Outcome: `BUILD SUCCESS` (Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 in 7.866s).
  - `PostgresConfigurationSmokeTest`: 3/3 tests passed.
  - `FlywayMigrationIntegrationTest`: 4/4 tests passed.
- Clean compilation: `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 99 source files compiled cleanly).

**Next Task:**  
`PG-004` — Create Core Domain JPA Entities (`User`, `Category`, `MenuItem`, `CatalogMigration`, `StoreSettings`).

### [2026-10-25T21:08:45-04:00] — Task PG-004: Core JPA Entity Mapping

**Task:** PG-004 — Core JPA Entity Mapping  
**Status:** COMPLETED  
**Started At:** 2026-10-25T21:02:53-04:00  
**Completed At:** 2026-10-25T21:08:45-04:00  

**Implementation Summary:**  
Created the five foundational PostgreSQL JPA entities in dedicated package `com.lordgainsborough.restaurant.persistence.jpa.entity` mapping to the authoritative Flyway V1 schema:
1. `JpaUser` (`users` table): String PK (`id`), `name`, `email` (without `@Column(unique=true)` to defer to PostgreSQL functional unique index `uq_users_email_lower`), `phone`, `phone_normalized`, `password`, `role` (default `'MEMBER'`), `member_since`, `created_at` (`LocalDateTime`), `last_profile_update_at` (`LocalDateTime`), `token_version` (`long`), `password_changed_at` (`Instant`), `last_password_reset_operation_id`, and `no_show_count` (`int`).
2. `JpaCategory` (`categories` table): String PK (`id`), unique `name`, `description` (`TEXT`), and `display_order` (`int`).
3. `JpaMenuItem` (`menu_items` table): String PK (`id`), `name`, `description` (`TEXT`), `price` (`BigDecimal` precision 10, scale 2), `category`, `image`, `available` (`boolean`), and `is_most_loved` (`boolean`).
4. `JpaCatalogMigration` (`catalog_migrations` table): String PK (`id` length 128) preserving explicit assigned IDs without UUID generation, and `applied_at` (`Instant`).
5. `JpaStoreSettings` (`store_settings` table): String PK (`id` default `'default'`), `tax_rate` (`BigDecimal` precision 5, scale 4), `default_ready_minutes` (`int`), `store_name`, `contact_phone`, `contact_email`, `pickup_orders_enabled` (`boolean`), and `kds_allowed_ip` (`TEXT`).
6. Preserved 100% MongoDB compatibility: Existing MongoDB models (`User`, `Category`, `MenuItem`, `CatalogMigration`, `StoreSettings`), repositories, controllers, and services remain untouched.

**Verification:**  
- Executed `CoreJpaEntityIntegrationTest` against disposable PostgreSQL 17.11 instance initialized with Flyway V1:
  - `test1_hibernateMetadataValidation_succeedsAgainstV1Schema`: PASSED. All 5 entity mappings validated cleanly with Hibernate 7 `hibernate.hbm2ddl.auto=validate`.
  - `test2_userEntity_lifecycleAndCaseInsensitiveEmailUniqueness`: PASSED. Persisted, reloaded, updated security tokens/timestamps, and verified PostgreSQL `uq_users_email_lower` rejects case-insensitive duplicate emails (`User.One@...` vs `user.one@...`).
  - `test3_categoryEntity_lifecycleAndDisplayOrder`: PASSED. Lifecycle, reload, and display order update verified.
  - `test4_menuItemEntity_bigDecimalPrecisionAndBooleanMappings`: PASSED. Verified exact `BigDecimal(10,2)` round-trip (`24.95`, `26.50`), availability flag, and `is_most_loved` boolean mapping.
  - `test5_catalogMigrationEntity_explicitIdAndInstantTimestamp`: PASSED. Explicit string ID preserved (`2026-10-lord-gains-autumn-menu`), `applied_at` `Instant` verified.
  - `test6_storeSettingsEntity_singletonIdAndTaxRatePrecision`: PASSED. Singleton ID (`default`), exact `BigDecimal(5,4)` tax rate precision (`0.1300`, `0.1425`) verified.
  - `test7_springBootMinimalContext_bootsAndValidatesEntities`: PASSED. Spring Boot 4 minimal context with `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration`, and `@EntityScan` boots and passes `ddl-auto=validate`.
- Executed full test suite: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest -Dtest.postgres.port=54337` (`BUILD SUCCESS`, Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 in 10.091s).
- Executed `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 104 source files compiled cleanly).

**Next Task:**  
`PG-005` — Create operational JPA entities (`CustomerOrder`, `OrderItem`, `OrderMessage`).

### [2026-10-25T21:43:00-04:00] — Task PG-005: Operational JPA Entity Mapping for Orders

**Task:** PG-005 — Operational JPA Entity Mapping for Orders  
**Status:** COMPLETED  
**Started At:** 2026-10-25T21:37:01-04:00  
**Completed At:** 2026-10-25T21:43:00-04:00  

**Implementation Summary:**  
Created the three operational PostgreSQL JPA entities in dedicated package `com.lordgainsborough.restaurant.persistence.jpa.entity` mapping directly to the Flyway V1 schema (`customer_orders`, `order_items`, `order_messages`):
1. `JpaCustomerOrder` (`customer_orders` table): String PK (`id` length 64), `customer_name`, `customer_email`, `customer_phone`, `pickup_date`, `pickup_time`, `special_instructions` (`TEXT`), `status` (default `'NEW'`), `average_ready_minutes` (default 25), `subtotal`, `tax`, `total` (all `BigDecimal` precision 10, scale 2), `created_at`, `accepted_at`, `ready_at`, `picked_up_at`, `no_show_at` (`LocalDateTime`), `reviewed` (`boolean` default `false`), and `tracking_token_hash` (`@JsonIgnore` protected from accidental serialization). Supported guest checkouts without mandatory user relationship.
2. `JpaOrderItem` (`order_items` table): String PK (`id`), `order` (`@ManyToOne` lazy to `JpaCustomerOrder`), read-only `item_index` (`insertable = false, updatable = false`), nullable snapshot reference `menu_item_id`, `name`, `quantity`, `unit_price`, `total` (`BigDecimal` precision 10, scale 2), and `notes` (`TEXT`).
3. `JpaOrderMessage` (`order_messages` table): String PK (`id`), `order` (`@ManyToOne` lazy to `JpaCustomerOrder`), read-only `item_index`, `sender_role`, `sender_name`, `text` (`TEXT`), and `created_at` (`LocalDateTime`).
4. **Parent-Child Ownership and Ordering Strategy:**
   - Designed bidirectional relationships on `JpaCustomerOrder` with `@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)` and `@OrderColumn(name = "item_index", nullable = false)` for both `items` and `messages`.
   - On the child entities (`JpaOrderItem` and `JpaOrderMessage`), the parent relationship is mapped via `@JoinColumn(name = "order_id", nullable = false)`. The `item_index` column is exposed read-only (`insertable = false, updatable = false`).
   - This bidirectional design satisfies the strict `NOT NULL` constraints on both `order_id` and `item_index` in the V1 schema. Hibernate 7 populates `order_id` and the zero-based `item_index` immediately during the child's initial `INSERT` statement, eliminating intermediate `UPDATE` statements or transient null constraint violations.
   - Child insertion, removal (orphan deletion), reordering (`Collections.swap`), and cascading order deletion are fully automated and verified via database queries.
5. Preserved 100% MongoDB compatibility: Existing MongoDB models (`CustomerOrder`, embedded `OrderItem`, `OrderMessage`), repositories (`CustomerOrderRepository`), controllers (`OrderController`, `KdsController`), DTOs, and Socket.IO services remain untouched.

**Verification:**  
- Executed `OrderJpaEntityIntegrationTest` against disposable PostgreSQL 17.11 instance on loopback port 54338 initialized with Flyway V1:
  - `test1_hibernateMetadataValidation_succeedsAgainstV1Schema`: PASSED. Verified all order entities pass Hibernate 7 `hibernate.hbm2ddl.auto=validate`.
  - `test2_guestOrder_insertedWithoutRegisteredUser`: PASSED. Confirmed guest order persistence without user FK.
  - `test3_cascadePersistenceAndOrdering_itemsAndMessages`: PASSED. Verified cascade insertion and list ordering preservation in fresh session and via direct JDBC query against `item_index`.
  - `test4_appendMessage_toExistingOrder`: PASSED. Appending messages increments `item_index` correctly.
  - `test5_orphanRemoval_removesItemRow`: PASSED. Removing child item deletes database row and reindexes siblings.
  - `test6_reorderItems_updatesItemIndex`: PASSED. Swapping elements in child collection updates `item_index` in PostgreSQL.
  - `test7_statusAndTimestamps_updatePersists`: PASSED. Verified lifecycle timestamp updates (`acceptedAt`, `readyAt`, `pickedUpAt`, `reviewed`).
  - `test8_explicitChildIds_preservedWithoutRegeneration`: PASSED. Verified explicitly supplied UUIDs are preserved.
  - `test9_bigDecimalPrecision_roundTripExact`: PASSED. Exact round-trip verification of `BigDecimal(10,2)` monetary fields.
  - `test10_cascadeDelete_removesChildren`: PASSED. Removing parent order cascades deletion to all child items and messages.
  - `test11_checkConstraints_rejectedByDatabase`: PASSED. Database rejects `quantity <= 0` and `subtotal < 0.00`.
  - `test12_springBootContext_bootsAndValidatesAllEntities`: PASSED. Minimal Spring Boot 4 context boots and validates all 8 entities (`JpaUser`, `JpaCategory`, `JpaMenuItem`, `JpaCatalogMigration`, `JpaStoreSettings`, `JpaCustomerOrder`, `JpaOrderItem`, `JpaOrderMessage`).
  - Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 in 5.886s.
- Executed full test suite: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest -Dtest.postgres.port=54337` (`BUILD SUCCESS`, Tests run: 26, Failures: 0, Errors: 0, Skipped: 0 in 10.738s).
- Executed `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 107 source files compiled cleanly).

**Next Task:**  
`PG-006` — Create cart and review JPA entities (`Cart`, `CartItem`, `OrderReview`, `OrderReviewItem`, `Reservation`).

### [2026-10-25T22:01:30-04:00] — PG-005 Follow-Up: Relationship Helper Hardening

**Task:** PG-005 Follow-Up — Relationship Helper Hardening  
**Status:** COMPLETED  
**Timestamp:** 2026-10-25T22:01:30-04:00  

**Implementation Summary:**  
Hardened relationship helper methods, collection replacement, identifier equality/hashing, and test isolation across the three operational order JPA entities:
1. **Safe Collection Replacement (`setItems` / `setMessages`):**
   - Added reference guard (`if (this.items == items) return;`) to ensure self-assignment never inadvertently clears collections or triggers orphan deletion.
   - Replaced naive `clear()` calls with an in-place collection synchronization algorithm: snapshots the incoming list, iterates through existing elements removing and detaching only those no longer present, and synchronizes positions/adds new items. This avoids clearing managed `PersistentCollection` instances in Hibernate while properly triggering orphan removal for removed items.
2. **Strict Child Detachment Guard (`removeItem` / `removeMessage`):**
   - Guarded parent detachment so that `item.setOrder(null)` only executes when `this.items.remove(item)` returns `true` and `item.getOrder() == this`. Prevents unintended detachment or corruption when attempting to remove an unrelated child entity belonging to another parent.
3. **Transient Equality and Stable Hash Codes:**
   - Corrected `equals()` across `JpaCustomerOrder`, `JpaOrderItem`, and `JpaOrderMessage` to require non-null IDs (`this.id != null && Objects.equals(this.id, that.id)`). Distinct transient entities with null IDs never compare equal solely because both IDs are null.
   - Stabilized `hashCode()` by returning `getClass().hashCode()`. Guarantees hash codes do not mutate across entity lifecycle states (transient → persisted), preserving `HashSet` and `HashMap` bucket integrity upon ID generation.
4. **Read-Only `itemIndex` Accessors:**
   - Restated package-private visibility for `setItemIndex()` on `JpaOrderItem` and `JpaOrderMessage`, removing it from the public API. Javadoc clearly specifies that collection ordering in `JpaCustomerOrder` is the sole authoritative source of database positions.
5. **Dynamic Test Fixture Isolation:**
   - Replaced all hardcoded child fixture IDs (`item-id-*`, `msg-id-*`, `item-remove-*`, `item-reorder-*`) with dynamic UUIDs in `OrderJpaEntityIntegrationTest` to eliminate primary key collision risks during repeated test execution.

**Verification:**  
- Executed `OrderJpaEntityIntegrationTest` against disposable PostgreSQL 17.11 instance on loopback port 54338:
  - 12 existing tests passed.
  - Added `test13_selfAssignment_preservesCollectionsWithoutClearing`: PASSED. Confirmed self-assignment preserves collections.
  - Added `test14_replacementOfManagedCollection_preservesRetainedAndRemovesOrphans`: PASSED. Confirmed retained items remain managed, new items are added, and dropped items are deleted as orphans.
  - Added `test15_attemptedRemovalOfUnrelatedChild_doesNotDetachChild`: PASSED. Confirmed attempting to remove an unrelated child does not detach its parent reference.
  - Added `test16_distinctTransientEntitiesWithNullIds_equalityAndHashCodeContract`: PASSED. Confirmed distinct new entities with null IDs do not compare equal, and hash codes remain stable in `Set` across persistence.
  - Added `test17_repeatedExecutionIsolation_multipleCyclesSucceed`: PASSED. Multi-cycle lifecycle runs cleanly without ID collisions.
  - Added `test18_readOnlyItemIndex_collectionOrderAuthoritative`: PASSED. Confirmed non-public `setItemIndex` and authoritative collection ordering.
  - Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 in 6.540s.
- Full regression suite: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest -Dtest.postgres.port=54337` (`BUILD SUCCESS`, Tests run: 32, Failures: 0, Errors: 0, Skipped: 0 in 11.548s).
- Clean compilation: `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 107 source files compiled cleanly).

**Next Task:**  
`PG-006` — Create cart and review JPA entities (`Cart`, `CartItem`, `OrderReview`, `OrderReviewItem`, `Reservation`).

### [2026-10-25T22:12:00-04:00] — Task PG-006: Cart, Review and Reservation JPA Entities

**Task:** PG-006 — Create cart and review JPA entities (`JpaCart`, `JpaCartItem`, `JpaOrderReview`, `JpaOrderReviewItem`, `JpaReservation`)  
**Status:** COMPLETED  
**Started At:** 2026-10-25T22:04:44-04:00  
**Completed At:** 2026-10-25T22:12:00-04:00  

**Implementation Summary:**  
Implemented the five operational JPA entities for Phase 6 in the dedicated package `com.lordgainsborough.restaurant.persistence.jpa.entity` matching the PostgreSQL V1 schema:
1. **`JpaCart` (`carts`):**
   - Mapped `id` (primary key `VARCHAR(64)`), `user` via `@OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false, unique = true)` referencing `JpaUser`, and `updatedAt` (`Instant` mapped to `TIMESTAMP WITH TIME ZONE`).
   - Modeled `@OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true) @OrderColumn(name = "item_index", nullable = false)` with safe collection synchronization (`setItems`), self-assignment guard, safe child removal (`removeItem`), non-null ID equality, and stable hash code.
   - Preserved 7-day TTL expiration semantics in the entity layer (`TTL_SECONDS = 7 * 24 * 60 * 60`, `isExpired()`, `isExpired(Instant)`), without automatic callbacks or eager scheduled cleanup.
2. **`JpaCartItem` (`cart_items`):**
   - Mapped `id`, `cart` (`@ManyToOne @JoinColumn(name = "cart_id", nullable = false)`), `menuItemId`, `name`, `description`, `price` (`BigDecimal` precision 10, scale 2), `category`, `image`, and `quantity`.
   - Maintained read-only `itemIndex` with package-private setter, strictly derived from parent collection ordering.
   - Omitted foreign key to `menu_items` to preserve historical cart item snapshots.
3. **`JpaOrderReview` (`order_reviews`):**
   - Mapped `id`, `order` via `@JsonIgnore @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id", nullable = false, unique = true)` referencing `JpaCustomerOrder` (enforcing single review per order and preventing circular JSON serialization).
   - Mapped `customerName`, `customerEmail`, `customerPhone`, `status` (defaulting to `"PENDING"`), `createdAt`, and `updatedAt` as `LocalDateTime`.
   - Modeled `@OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true) @OrderColumn(name = "item_index", nullable = false)` for review items with safe collection synchronization.
4. **`JpaOrderReviewItem` (`order_review_items`):**
   - Mapped `id`, `review` (`@ManyToOne @JoinColumn(name = "review_id", nullable = false)`), `menuItemId`, `itemName`, `rating` (`int` constrained 1–5), and `description`.
   - Maintained read-only `itemIndex` with package-private setter; omitted mandatory FK to `menu_items`.
5. **`JpaReservation` (`reservations`):**
   - Mapped `id`, `name`, `email`, `phone`, `date` (`String`), `time` (`String`), `partySize` (`int`), `specialRequests`, `status` (defaulting to `"PENDING"`), and `createdAt` (`LocalDateTime`).
   - Supports guest reservations without user foreign key; respects database check constraints (`party_size > 0`).

**Verification:**  
- Created integration test suite `backend/src/test/java/com/lordgainsborough/restaurant/persistence/jpa/CartReviewReservationJpaIntegrationTest.java` (20 tests) executed against a disposable PostgreSQL 17.11 instance initialized via Flyway V1:
  1. Hibernate `ddl-auto=validate` passed against the expanded 13-entity JPA metamodel.
  2. Cart persisted with an existing user.
  3. Unique user/cart relationship enforced (duplicate user cart rejected).
  4. Cart items cascade-persisted and reloaded in exact list order.
  5. Cart items appended, reordered, and removed with orphan deletion.
  6. Cart deletion cascaded to cart items.
  7. User deletion cascaded to cart and cart items (`ON DELETE CASCADE`).
  8. `updatedAt` persisted as `Instant` with UTC timestamp semantics.
  9. Seven-day expiration calculation verified against MongoDB equivalent semantics.
  10. Review persisted against an existing customer order.
  11. Second review for the same order rejected by unique constraint.
  12. Review items persisted and reloaded in list order.
  13. Review item orphan removal verified on collection replacement.
  14. Customer order deletion cascaded to review and review items (`ON DELETE CASCADE`).
  15. Invalid review ratings (< 1 or > 5) rejected by database check constraint `chk_review_items_rating`.
  16. Guest reservation persisted successfully without user reference.
  17. Reservation date and time string values round-tripped with exact fidelity.
  18. Invalid party size (<= 0) rejected by database check constraint `chk_reservations_party_size`.
  19. `BigDecimal` cart item price retained exact precision (10, 2).
  20. Spring Boot context booted with all 13 JPA entities validated against PostgreSQL schema.
- Full regression suite execution across all 5 test suites (`PostgresConfigurationSmokeTest`, `FlywayMigrationIntegrationTest`, `CoreJpaEntityIntegrationTest`, `OrderJpaEntityIntegrationTest`, `CartReviewReservationJpaIntegrationTest`):
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest -Dtest.postgres.port=54337`
  - Results: 52 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 15.329s).
- Clean compilation: `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 112 source files compiled cleanly).

**MongoDB Compatibility:**  
- Confirmed intact: existing MongoDB model classes (`Cart`, `OrderReview`, `Reservation`), repositories, and services remain completely untouched and operational.

**Next Task:**  
`PG-007` — Create security challenge JPA entities (`SignupOtpChallenge`, `PasswordResetOtpChallenge`).

### [2026-10-25T22:24:30-04:00] — PG-006 Follow-Up: Cart Expiration and Serialization Hardening

**Task:** PG-006 Follow-Up — Cart Expiration and Serialization Hardening  
**Status:** COMPLETED  
**Timestamp:** 2026-10-25T22:24:30-04:00  

**Implementation Summary:**  
Hardened cart timestamp tracking, entity lifecycle dirtying, Jackson serialization safeguards, and parent-child encapsulation across cart entities:
1. **Timestamp Touch Mechanism (`JpaCart.touch`):**
   - In JPA/Hibernate, modifying child entities (adding, removing, mutating, or reordering cart items) updates only child rows (`cart_items`) without dirtying the parent row (`carts`), leaving parent `@PreUpdate` callbacks unexecuted.
   - Introduced a documented `touch()` / `touch(Instant)` mechanism on `JpaCart` that updates `updatedAt = Instant.now()`, marking `JpaCart` as dirty in the persistence context and reliably issuing `UPDATE carts SET updated_at = ?` on transaction commit/flush.
   - Integrated automatic parent-touch into all cart collection mutations: `addItem()`, `removeItem()`, `setItems()`, and `reorderItems()`.
   - Wired child entity property setters in `JpaCartItem` (`setQuantity`, `setPrice`, `setName`, `setDescription`, `setCategory`, `setImage`) to automatically invoke `this.cart.touch()` when modified, ensuring child item changes advance the cart expiration timer without requiring manual scalar changes on the parent.
2. **Jackson Serialization Safeguards (`JpaCart` & `JpaCartItem`):**
   - Added `@JsonIgnore` to `JpaCart.user` and `getUser()` to prevent serializing sensitive user credentials (`password`, `tokenVersion`, `lastPasswordResetOperationId`) and prevent lazy loading issues outside of active transactions.
   - Added `@JsonProperty("userId") public String getUserId()` to serialize `userId` as a top-level string matching MongoDB's original REST JSON shape.
   - Added `@JsonIgnore` to `JpaCartItem.cart` (and `JpaOrderReviewItem.review`) to eliminate circular back-references and infinite serialization recursion.
3. **Regression Tests Added (`CartReviewReservationJpaIntegrationTest`):**
   - Test 21: Added item without changing parent scalar fields; verified via direct JDBC query that `carts.updated_at` advanced in PostgreSQL.
   - Test 22: Removed item; verified direct JDBC query that `carts.updated_at` advanced.
   - Test 23: Mutated existing item quantity; verified direct JDBC query that `quantity` updated and `carts.updated_at` advanced.
   - Test 24: Mutated existing item price; verified direct JDBC query that `price` updated and `carts.updated_at` advanced.
   - Test 25: Reordered items; verified direct JDBC query that `item_index` positions reordered and `carts.updated_at` advanced.
   - Test 26: Serialized `JpaCart` with Jackson; verified `userId` present while `password`, `tokenVersion`, `lastPasswordResetOperationId`, and circular `cart` references are excluded.

**Verification:**  
- `CartReviewReservationJpaIntegrationTest`: 26/26 tests passed in 6.686s against disposable PostgreSQL 17.11.
- Full regression suite across all 5 test suites (`PostgresConfigurationSmokeTest`, `FlywayMigrationIntegrationTest`, `CoreJpaEntityIntegrationTest`, `OrderJpaEntityIntegrationTest`, `CartReviewReservationJpaIntegrationTest`):
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest -Dtest.postgres.port=54337`
  - Result: 58 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 15.128s).
- Clean compilation: `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 112 source files compiled cleanly).

**Next Task:**  
`PG-007` — Create security challenge JPA entities (`SignupOtpChallenge`, `PasswordResetOtpChallenge`).

### [2026-10-25T22:31:30-04:00] — Task PG-007: PostgreSQL Security Challenge JPA Entities

**Task:** PG-007 — Create security challenge JPA entities (`JpaSignupOtpChallenge`, `JpaPasswordResetOtpChallenge`)  
**Status:** COMPLETED  
**Started At:** 2026-10-25T22:25:30-04:00  
**Completed At:** 2026-10-25T22:31:30-04:00  

**Implementation Summary:**  
Implemented the two operational security challenge JPA entities under `com.lordgainsborough.restaurant.persistence.jpa.entity` matching the PostgreSQL V1 schema:
1. **`JpaSignupOtpChallenge` (`signup_otp_challenges`):**
   - Mapped `emailKey` (primary key `VARCHAR(64)`), an assigned SHA-256 identifier preserving existing hex format without auto-generating UUIDs.
   - Mapped `normalizedEmail`, `challengeId`, `status` (`@Enumerated(EnumType.STRING)` for `OtpChallengeStatus`), `otpDigest` (BCrypt hash), `resendCount`, `failedVerifyAttempts`, `nextResendAt`, `otpExpiresAt`, `challengeExpiresAt`, `createdAt`, `updatedAt`, `sendOperationId`, `sendStatus` (`@Enumerated(EnumType.STRING)` for `OtpSendStatus`), `sendLeaseUntil`, `clientIdempotencyKey`, and `lastSendWasInitial`.
   - Applied defensive `@JsonIgnore` to `normalizedEmail`, `otpDigest`, `clientIdempotencyKey`, and `sendOperationId`. Custom `toString()` deliberately excludes contact details, digest material, operation identifiers (`sendOperationId`), and security identifiers (`emailKey`, `challengeId`) to prevent metadata leakage in routine application logs.
   - Model-level boundary helper methods: `isChallengeExpired()`, `isOtpExpired()`, `isSendLeaseActive()`, and `isResendAllowed()` (enforcing fail-closed semantics: missing or null expiry times evaluate to expired).
2. **`JpaPasswordResetOtpChallenge` (`password_reset_otp_challenges`):**
   - Mapped `emailKey` (primary key `VARCHAR(64)`), `challengeId` (`VARCHAR(64)` with `unique = true` matching `uq_pwd_reset_challenge_id`).
   - Mapped `user` via `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) @JsonIgnore` referencing `JpaUser` without cascade operations, ensuring challenge deletion does not remove the user while PostgreSQL `ON DELETE CASCADE` from user deletion is respected. Exposes safe `@JsonProperty("userId") public String getUserId()`.
   - Mapped `purpose` (`@Enumerated(EnumType.STRING)` for `PasswordResetOtpChallenge.Purpose`), `status` (`@Enumerated(EnumType.STRING)` for `PasswordResetChallengeStatus`), `codeDigest` (nullable `VARCHAR(255)`), `codeExpiresAt`, `challengeExpiresAt`, `nextResendAt`, `resendCount`, `failedVerificationAttempts`, `clientIdempotencyKey`, `sendOperationId`, `sendStatus`, `sendLeaseUntil`, `resetOperationId`, `passwordUpdated`, `consumedAt`, `createdAt`, and `updatedAt`.
   - Applied defensive `@JsonIgnore` to `user`, `codeDigest`, `resetOperationId`, `clientIdempotencyKey`, and `sendOperationId`. Custom `toString()` excludes digests, tokens, operation identifiers (`resetOperationId`, `sendOperationId`), user identifiers (`userId`, `user`), and security identifiers (`challengeId`, `emailKey`) to prevent metadata leakage in routine logs.
   - Model-level boundary helper methods: `isChallengeExpired()`, `isCodeExpired()`, `isSendLeaseActive()`, and `isResendAllowed()` (enforcing fail-closed semantics: missing or null expiry times evaluate to expired).

**Verification:**  
- Created integration test suite `backend/src/test/java/com/lordgainsborough/restaurant/persistence/jpa/SecurityChallengeJpaIntegrationTest.java` (26 tests) executed against a disposable PostgreSQL 17.11 instance initialized via Flyway V1:
  1. Hibernate `ddl-auto=validate` passed against the expanded 15-entity JPA metamodel.
  2. Signup challenge insertion and retrieval verified with all fields matching.
  3. Assigned SHA-256 `emailKey` preserved exactly in PostgreSQL without alteration.
  4. All `OtpChallengeStatus` enum values (`ACTIVE`, `VERIFIED`, `LOCKED`) round-trip as database strings.
  5. All `OtpSendStatus` enum values (`RESERVED`, `SENT`, `DEFINITELY_FAILED`, `DELIVERY_UNKNOWN`, `null`) round-trip correctly.
  6. OTP digest survives persistence internally but is completely excluded from Jackson JSON serialization.
  7. Resend counters and failed verification attempt counters persist accurately.
  8. Negative counter values are rejected by PostgreSQL check constraints (`chk_signup_otp_resend_count`, `chk_signup_otp_failed_verify`).
  9. Signup challenge expiry, OTP expiry, resend cooldown, and lease timestamps round-trip with Instant precision.
  10. Nullable signup challenge fields persist and reload as null.
  11. Client idempotency key persists without truncation or modification.
  12. Password-reset challenge insertion and retrieval verified against existing `JpaUser`.
  13. `JpaUser` relationship resolves lazily and `getUserId()` derives safely.
  14. Invalid or non-existent `user_id` is rejected by PostgreSQL foreign key constraint `fk_pwd_reset_user`.
  15. Deleting a password-reset challenge does not delete its referenced user.
  16. Deleting a user cascades to delete associated password-reset challenges via `ON DELETE CASCADE`.
  17. Unique `challenge_id` constraint `uq_pwd_reset_challenge_id` rejects duplicate challenge IDs.
  18. `PasswordResetChallengeStatus` enum values (`ACTIVE`, `PROCESSING`, `LOCKED`, `CONSUMED`) round-trip correctly.
  19. `Purpose` enum persists and reloads as exact string `"PASSWORD_RESET"`.
  20. Nullable `code_digest` supports consumed challenge state (`code_digest IS NULL`, `status = CONSUMED`).
  21. `password_updated` and `consumed_at` fields persist accurately.
  22. `reset_operation_id` and `send_operation_id` persist correctly.
  23. Challenge and code expiration boundaries are respected at exact instant edges, and null expiry values fail closed as expired.
  24. Sensitive fields (`codeDigest`, `resetOperationId`, `clientIdempotencyKey`, `user`, `password`, `tokenVersion`) are excluded from JSON output.
  25. Routine logging representations (`toString()`) exclude digests, operation secrets (`sendOperationId`, `resetOperationId`), and security identifiers (`challengeId`, `emailKey`, `userId`).
  26. Spring Boot context boots with all 15 JPA entities validated against PostgreSQL schema.
- Full regression suite execution across all 6 test suites (`PostgresConfigurationSmokeTest`, `FlywayMigrationIntegrationTest`, `CoreJpaEntityIntegrationTest`, `OrderJpaEntityIntegrationTest`, `CartReviewReservationJpaIntegrationTest`, `SecurityChallengeJpaIntegrationTest`):
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest -Dtest.postgres.port=54337`
  - Results: 84 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 20.855s).
- Clean compilation: `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 114 source files compiled cleanly).

**MongoDB Compatibility:**  
- Confirmed intact: existing MongoDB model classes (`SignupOtpChallenge`, `PasswordResetOtpChallenge`), repositories, security services, and controllers remain untouched and operational.

**Next Task:**  
`PG-008` — Create abuse and failure state JPA entities (`SignupAbuseLimit`, `LoginFailureState`).

---

### Task Progress: PG-007 Security Hardening Preflight (Fail-Closed Expiration & Logging Safeguards)
**Date/Time:** 2026-10-25T22:43:00-04:00 to 2026-10-25T22:50:30-04:00 (`America/Toronto`)  
**Status:** COMPLETED  
**Commit Baseline:** `6a99fb2` → commits `8a5318b`, `fd31891`  
**Description:**  
Applied security preflight hardening to the PG-007 operational challenge entities (`JpaSignupOtpChallenge` and `JpaPasswordResetOtpChallenge`) before commencing PG-008:
1. **Fail-Closed Missing Expiration Handling:**
   - Corrected `isChallengeExpired(Instant now)` in both challenge entities to `return challengeExpiresAt == null || !now.isBefore(challengeExpiresAt);`.
   - Prevents transient, unpersisted, or corrupted challenge entities with null expiry from being evaluated as valid.
   - Updated integration test `test23_challengeExpirationBoundariesRespected` to verify missing/null expiry fails closed as expired (`isChallengeExpired() == true`).
2. **Exclusion of Protected Identifiers from Routine Logging:**
   - Removed `sendOperationId`, `emailKey`, and `challengeId` from `JpaSignupOtpChallenge.toString()`.
   - Removed `challengeId`, `userId`, and `getUserId()` from `JpaPasswordResetOtpChallenge.toString()`.
   - Retained only non-identifying operational state flags (`status`, `resendCount`, `failedVerifyAttempts`, `sendStatus`, `purpose`, etc.).
   - Added integration test `test25_loggingSafeguards_toStringExcludesSensitiveIdentifiers` in `SecurityChallengeJpaIntegrationTest.java` verifying that synthetic marker strings for operation IDs, email keys, user IDs, and challenge IDs never appear in entity `toString()` logs.
3. **Cart Mutability Documentation:**
   - Added explicit Javadoc recommendations on `JpaCart.getItems()` documenting that direct collection mutation bypasses `touch()` and advising services to use dedicated helper methods (`addItem`, `removeItem`, `reorderItems`, `setItems`).
4. **Verification:**
   - `SecurityChallengeJpaIntegrationTest`: 26/26 tests passed against disposable PostgreSQL 17.11.
   - Full regression suite across 6 test classes: 84/84 tests passed (`BUILD SUCCESS`, 20.855s).

---

### Task Progress: PG-008 — Abuse Counter and Login Failure State JPA Entities
**Date/Time:** 2026-10-25T22:51:00-04:00 to 2026-10-25T22:56:00-04:00 (`America/Toronto`)  
**Status:** COMPLETED  
**Commit Baseline:** `fd31891`  
**Scope:** Phase 7 Finalization — Map the final two tables of the 17-table Flyway V1 relational schema into JPA entities.  

**Implementation Summary:**  
1. **`JpaSignupAbuseLimit` (`signup_abuse_limits`):**
   - Mapped `id` (`VARCHAR(128) PRIMARY KEY`), application-assigned using deterministic keyed cryptographic hashes without `@GeneratedValue` or UUID generation.
   - Mapped `bucketType` (`@Enumerated(EnumType.STRING)` for `SignupAbuseLimit.BucketType`, `VARCHAR(50) NOT NULL`).
   - Mapped `subjectHash` (`VARCHAR(128) NOT NULL`), protected with `@JsonIgnore` to prevent exposure of keyed IP or subject hashes.
   - Mapped `count` (`BIGINT NOT NULL DEFAULT 1`) using Java `long`, respecting check constraint `chk_signup_abuse_count CHECK (count >= 0)`.
   - Mapped `windowStart` and `expiresAt` (`TIMESTAMP WITH TIME ZONE NOT NULL`) using Java `Instant`.
   - Provided model-level boundary helper `isExpired(Instant now)` enforcing exact boundary (`!now.isBefore(expiresAt)`) and fail-closed null semantics (`expiresAt == null`).
   - Implemented custom `toString()` excluding `id` and `subjectHash` to safeguard internal security keys.
2. **`JpaLoginFailureState` (`login_failure_states`):**
   - Mapped `id` (`VARCHAR(128) PRIMARY KEY`), application-assigned string key without UUID generation.
   - Mapped `subjectHash` (`VARCHAR(128) NOT NULL`), protected with `@JsonIgnore` to prevent exposure of keyed subject hashes.
   - Mapped `failureCount` (`INT NOT NULL DEFAULT 1`) using Java `int`, respecting check constraint `chk_login_failure_count CHECK (failure_count >= 0)`.
   - Mapped `windowStartedAt` (`TIMESTAMP WITH TIME ZONE NOT NULL`), `blockedUntil` (`TIMESTAMP WITH TIME ZONE` nullable), `updatedAt` (`TIMESTAMP WITH TIME ZONE NOT NULL`), and `expiresAt` (`TIMESTAMP WITH TIME ZONE NOT NULL`) using Java `Instant`.
   - Provided model-level helper `isExpired(Instant now)` (fail-closed on null) and `isBlocked(Instant now)`:
     - Returns `false` if record is expired (preventing stale records from triggering blocks).
     - Returns `false` if `blockedUntil` is null.
     - Returns `true` if `now.isBefore(blockedUntil)`.
     - Returns `false` at exact boundary `now.equals(blockedUntil)`.
   - Kept `updatedAt` explicitly managed without automatic `@PreUpdate` overwriting to preserve injected Clock values from service layers.
   - Implemented custom `toString()` excluding `id` and `subjectHash`.
3. **Entity Identity & Lifecycle Equality:**
   - Both entities use safe primary key identity: distinct transient entities with null IDs never compare equal (`id != null && Objects.equals(id, that.id)`), with stable `hashCode()` (`getClass().hashCode()`).

**Verification:**  
- Created integration test suite `backend/src/test/java/com/lordgainsborough/restaurant/persistence/jpa/AbuseAndLoginFailureJpaIntegrationTest.java` (25 tests) executed against disposable PostgreSQL 17.11 initialized via Flyway V1:
  1. Flyway V1 schema initialization confirmed for `signup_abuse_limits` and `login_failure_states`.
  2. Hibernate `ddl-auto=validate` passed with all 17 JPA entities registered in metamodel.
  3. Signup abuse record persisted and reloaded successfully across all fields.
  4. Assigned `VARCHAR(128)` primary key preserved without alteration or truncation.
  5. All 10 `BucketType` enum values persisted and reloaded as exact database strings.
  6. `subjectHash` persisted without truncation up to 128 characters.
  7. `count` persisted as BIGINT above `Integer.MAX_VALUE` (e.g. 7,000,000,000L).
  8. `count = 0` accepted by PostgreSQL.
  9. Negative `count` rejected by PostgreSQL CHECK constraint `chk_signup_abuse_count`.
  10. `windowStart` and `expiresAt` round-trip with Instant precision.
  11. `isExpired()` boundary behavior and null fail-closed semantics verified.
  12. Required columns reject null values.
  13. Login failure state persisted and reloaded successfully.
  14. Login failure assigned primary key preserved unchanged.
  15. `failureCount` persisted accurately as INT.
  16. `failureCount = 0` accepted.
  17. Negative `failureCount` rejected by PostgreSQL CHECK constraint `chk_login_failure_count`.
  18. `blockedUntil` verified for both null and future timestamp states.
  19. Time-based blocking ends at exact `blockedUntil` boundary and expired records do not trigger active blocks.
  20. `updatedAt` and `windowStartedAt` round-trip correctly.
  21. `expiresAt` preserved without unintended modification during entity updates.
  22. Jackson serialization excludes sensitive hashes and internal identifiers.
  23. Routine `toString()` logging excludes internal IDs and subject hashes.
  24. Flyway V1 indexes confirmed in PostgreSQL `pg_indexes` catalog (`idx_abuse_limits_expires_at`, `idx_abuse_limits_bucket_subject`, `idx_login_failure_expires_at`, `idx_login_failure_blocked_until`).
  25. Spring Boot minimal context boots with all 17 JPA entities validated against schema.
- **Full PostgreSQL Regression Suite:**
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest,AbuseAndLoginFailureJpaIntegrationTest -Dtest.postgres.port=54337`
  - Results: **109 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 26.958s).
- **Clean Compilation:** `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 116 source files compiled cleanly).

**MongoDB Compatibility:**  
- Fully preserved: `SignupAbuseLimit`, `LoginFailureState`, `SignupAbuseLimitRepository`, `MongoAbuseCounterStore`, and `LoginRateLimiter` remain untouched and operational.

**Next Task:**  
Preflight Hardening & `PG-009` — Implement Spring Data JPA repositories for core domain and catalog entities (`UserRepository`, `CategoryRepository`, `MenuItemRepository`, `CatalogMigrationRepository`, `StoreSettingsRepository`).

---

### [2026-10-25T23:02:00-04:00] — PG-008 Hardening: Security Entity JSON Identifier Safeguards

**Task ID:** `PG-008-HARDENING`  
**Phase:** Phase 7: Operational Security & Rate Limiting  
**Status:** `COMPLETED`  
**Timestamp:** 2026-10-25T23:02:00-04:00 (Original PG-008 completion timestamp 2026-10-25T22:56:00-04:00 preserved)

**Objective:**  
Address code review Finding 1: internal security IDs on `JpaSignupAbuseLimit` and `JpaLoginFailureState` were exposed via public `getId()` without `@JsonIgnore`, presenting a potential accidental leak if returned via API JSON.

**Implementation Details:**
1. Applied `@JsonIgnore` to `getId()` in `JpaSignupAbuseLimit.java` and `JpaLoginFailureState.java`.
2. Preserved the underlying fields as JPA `@Id` primary keys with standard ORM persistence behavior.
3. Updated `AbuseAndLoginFailureJpaIntegrationTest.java` (`test22_jacksonSerialization_excludesSensitiveHashesAndIds`):
   - Asserted that serialized JSON contains neither `subjectHash` nor `id`.
   - Verified that both properties remain correctly persisted and retrievable via Hibernate EntityManager.
   - Verified that custom `toString()` implementations continue to omit both values.

**Verification:**
- Executed `AbuseAndLoginFailureJpaIntegrationTest`: 25/25 tests passed.

---

### [2026-10-25T23:04:00-04:00] — Test Infrastructure Hardening: Repeatable Core JPA Test Fixtures

**Task ID:** `TEST-INFRA-HARDENING`  
**Phase:** Phase 5/8: Test Infrastructure Hardening  
**Status:** `COMPLETED`  
**Timestamp:** 2026-10-25T23:04:00-04:00  

**Objective:**  
Address code review Finding 2: `CoreJpaEntityIntegrationTest` used hardcoded unique fixture values (`User.One@LordGainsborough.com`, `Signature Mains`, `2026-10-lord-gains-autumn-menu`), causing constraint collisions when re-executed against an existing database instance.

**Implementation Details:**
1. Replaced fixed values with randomized synthetic fixtures using UUID suffixes (`User.One.<uuid>@LordGainsborough.com`, `Signature Mains <uuid>`, `Prime Rib Roast <uuid>`, `2026-10-autumn-<uuid>`).
2. Preserved case-insensitive collision checks by using differently cased variants of the *same* dynamically generated unique email.
3. Added non-destructive database table cleanup in `setUpAll()` for `core_jpa_test`.
4. Maintained singleton `StoreSettings` behavior with `id = 'default'`.

**Verification:**
- Executed `CoreJpaEntityIntegrationTest` twice consecutively against the same PostgreSQL database instance.
- Both runs passed 7/7 tests cleanly with 0 failures, confirming full repeatability.

---

### [2026-10-25T23:23:00-04:00] — PG-009: Core & Catalog Spring Data JPA Repositories

**Task ID:** `PG-009`  
**Phase:** Phase 8: Data Access Layer & JPA Repositories  
**Status:** `COMPLETED`  
**Start Timestamp:** 2026-10-25T23:05:00-04:00  
**Completion Timestamp:** 2026-10-25T23:23:00-04:00  

**Objective:**  
Implement Spring Data JPA repositories in `com.lordgainsborough.restaurant.persistence.jpa.repository` for the 5 core domain and catalog entities mapped in PG-004:
1. `JpaUserRepository` (`JpaUser`, `String`)
2. `JpaCategoryRepository` (`JpaCategory`, `String`)
3. `JpaMenuItemRepository` (`JpaMenuItem`, `String`)
4. `JpaCatalogMigrationRepository` (`JpaCatalogMigration`, `String`)
5. `JpaStoreSettingsRepository` (`JpaStoreSettings`, `String`)

**Implementation Details:**
1. **`JpaUserRepository`:**
   - Extends `JpaRepository<JpaUser, String>`.
   - `Optional<JpaUser> findByEmail(String email)`: Exact email lookup.
   - `Optional<JpaUser> findByEmailIgnoreCase(String email)`: Case-insensitive email query supporting login and registration.
   - `boolean existsByEmail(String email)`: Exact email existence check.
   - `boolean existsByEmailIgnoreCase(String email)`: Fast case-insensitive email existence check.
   - `boolean existsByPhone(String phone)`: Exact raw phone existence check.
   - `boolean existsByPhoneNormalized(String phoneNormalized)`: Fast phone deduplication existence check.
   - `List<JpaUser> findByPhoneNormalized(String phoneNormalized)`: Phone lookup respecting the `uq_users_phone_normalized` constraint.
   - `List<JpaUser> findByPhoneNormalizedContaining(String phoneNormalized)`: Partial normalized phone search.
   - `List<JpaUser> findByPhoneIsNotNull()`: Phone presence query.
   - `List<JpaUser> findByNameContainingIgnoreCase(String name)`: Case-insensitive name substring search.
   - `List<JpaUser> findByEmailContainingIgnoreCase(String email)`: Case-insensitive email substring search.
   - `List<JpaUser> findByRole(String role)`: Query users by assigned role.
2. **`JpaCategoryRepository`:**
   - Extends `JpaRepository<JpaCategory, String>`.
   - `JpaCategory findByName(String name)`: Exact category name lookup returning `null` if not found, matching legacy MongoDB contract.
   - `Optional<JpaCategory> findByNameIgnoreCase(String name)`: Case-insensitive category lookup.
   - `boolean existsByName(String name)` & `boolean existsByNameIgnoreCase(String name)`: Deduplication checks.
   - `List<JpaCategory> findAllByOrderByDisplayOrderAsc()`: Ordered menu presentation query for frontend consumption.
3. **`JpaMenuItemRepository`:**
   - Extends `JpaRepository<JpaMenuItem, String>`.
   - `List<JpaMenuItem> findByCategory(String category)`: Exact category menu filtering.
   - `Optional<JpaMenuItem> findFirstByNameIgnoreCase(String name)`: Case-insensitive name match.
   - `Optional<JpaMenuItem> findFirstByNameIgnoreCaseOrderByIdAsc(String name)`: Deterministic first-match query for menu items when duplicate names exist.
   - `List<JpaMenuItem> findByAvailableTrue()`: Active ordering availability filter.
   - `List<JpaMenuItem> findByIsMostLovedTrue()`: Featured item filter.
   - `boolean existsByName(String name)` & `boolean existsByNameIgnoreCase(String name)`: Deduplication checks.
4. **`JpaCatalogMigrationRepository`:**
   - Extends `JpaRepository<JpaCatalogMigration, String>`.
   - `boolean existsById(String id)`: Checks whether catalog schema migration ID has already been applied.
   - Preserves explicit string migration identifiers (e.g., `2026-10-autumn-menu`).
5. **`JpaStoreSettingsRepository`:**
   - Extends `JpaRepository<JpaStoreSettings, String>`.
   - Provides singleton configuration management for `id = 'default'`.
   - Preserves tax rate `NUMERIC(5, 4)` precision.

**Verification:**
- Created comprehensive integration test suite `backend/src/test/java/com/lordgainsborough/restaurant/persistence/jpa/CoreJpaRepositoryIntegrationTest.java` (25 tests) executed against disposable PostgreSQL 17.11 initialized via Flyway V1:
  1. Req 1: All five JPA repository interfaces are instantiated in isolated Spring context.
  2. Req 2: No MongoDB repository is registered as a JPA repository.
  3. Req 3: Hibernate schema validation succeeds against Flyway V1 schema.
  4. Req 4: `JpaUserRepository` can save, load, update and delete a user.
  5. Req 5: Exact email finder matches exact casing and returns empty on mismatched case.
  6. Req 6: Case-insensitive email finder resolves mixed-case addresses.
  7. Req 7: Email existence queries preserve exact versus case-insensitive semantics.
  8. Req 8: PostgreSQL functional email uniqueness rejects mixed-case duplicates.
  9. Req 9: Exact phone and normalized-phone finders work.
  10. Req 10: Partial normalized-phone searches work.
  11. Req 11: Name and email substring searches work regardless of casing.
  12. Req 12: SQL wildcard characters are handled safely without unintended wildcard expansion.
  13. Req 13: Category repository finds by exact name.
  14. Req 14: Missing category returns null where required by the existing contract.
  15. Req 15: Category CRUD and displayOrder updates work.
  16. Req 16: Menu items can be found by category.
  17. Req 17: Case-insensitive menu name finder works.
  18. Req 18: Multiple matching menu names have stable, documented first-match behavior (`findFirstByNameIgnoreCaseOrderByIdAsc`).
  19. Req 19: Menu price BigDecimal precision remains exact (`scale = 2`).
  20. Req 20: CatalogMigration assigned IDs are preserved without alteration or UUID generation.
  21. Req 21: CatalogMigration `existsById` works correctly before and after save.
  22. Req 22: StoreSettings singleton can be inserted, updated and retrieved.
  23. Req 23: Tax rate precision (`precision 5, scale 4`) survives repository save and reload.
  24. Req 24: JPA repository beans do not collide with existing MongoDB repository names.
  25. Req 25: The default MongoDB configuration remains isolated from JPA repository activation.
- **Targeted Integration Test:** 25 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 6.149s).
- **Full PostgreSQL Regression Suite:**
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest,AbuseAndLoginFailureJpaIntegrationTest,CoreJpaRepositoryIntegrationTest -Dtest.postgres.port=54337`
  - Results: **134 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 28.661s across 8 suites).
- **Clean Compilation:** `mvn clean test-compile` (`BUILD SUCCESS`, 121 source files compiled cleanly).

**MongoDB Compatibility:**
- Fully preserved: All MongoDB document models, repositories, and services remain completely untouched and operational.

**Next Task:**
`PG-010` — Implement Spring Data JPA repositories for operational domain models (`JpaCustomerOrderRepository`, `JpaCartRepository`, `JpaOrderReviewRepository`, `JpaReservationRepository`).

---

### [2026-10-25T23:55:00-04:00] — Test Infrastructure Hardening: Safe Disposable DB Isolation & Repository Verification

**Task ID:** `TEST-INFRA-HARDENING-PREFLIGHT-A-B`  
**Phase:** Phase 8 Preflight Hardening  
**Status:** `COMPLETED`  
**Timestamp:** 2026-10-25T23:55:00-04:00 (`America/Toronto`)  
**Commit Baseline:** `9a80ccf` → Commit `142e413`  

**Objective:**  
Address code review findings prior to PG-010:
1. **Preflight A:** Eliminate unconditional `DELETE FROM ...` table-wide deletions in `CoreJpaEntityIntegrationTest` and `CoreJpaRepositoryIntegrationTest`. Replace with uniquely provisioned disposable databases, loopback connection safeguards, and per-test synthetic fixtures.
2. **Preflight B:** Strengthen wildcard escaping tests (literal `%` and `_`) and verify default MongoDB startup profile isolation without requiring PostgreSQL. Document deferred first-match behavior on duplicate menu item names.

**Implementation Details:**
1. **Safe Disposable Database Isolation:**
   - Modified `CoreJpaEntityIntegrationTest.java` and `CoreJpaRepositoryIntegrationTest.java` to provision dynamically generated disposable test databases (`core_jpa_test_<uuid>`, `core_jpa_repo_test_<uuid>`).
   - Implemented `assertSafeTestEnvironment(port, dbName)`:
     - Validates loopback execution (`127.0.0.1`).
     - Enforces expected test prefix and alphanumeric identifier pattern.
     - Strictly forbids operation against system, production, or shared database names (`postgres`, `template0`, `template1`, `public`, `lordgainsborough`, `restaurant`).
   - Implemented `dropDisposableDatabaseSafely(port, dbName)`:
     - Terminates active connections via `pg_terminate_backend(pid)` before executing safe `DROP DATABASE IF EXISTS`.
     - Ensures clean teardown without leaving orphaned databases or leaking connection pools.
   - Removed all table-wide `DELETE` statements (`DELETE FROM menu_items`, `DELETE FROM categories`, etc.).
2. **Wildcard Search Verification:**
   - Expanded `test12_sqlWildcardsHandledSafely_withoutUnintendedExpansion` in `CoreJpaRepositoryIntegrationTest.java`.
   - Verified that both literal `%` (`Percent%User`) and literal `_` (`Under_Score_User`) are matched as literal characters rather than SQL wildcards.
   - Asserted that decoy/unrelated records (`PercentXUser`, `UnderAScoreBUser`, `Percent User`) are strictly excluded from results.
3. **Default MongoDB Profile Isolation:**
   - Upgraded `test25_defaultMongoProfile_doesNotActivateJpaRepositories` in `CoreJpaRepositoryIntegrationTest.java` using Spring Boot's `ApplicationContextRunner`.
   - Verified that running with `spring.profiles.active=default` does not activate any JPA repository beans, does not require a PostgreSQL connection, and honors relational auto-configuration exclusions.
4. **Duplicate Menu Name Documentation:**
   - Added explicit Javadoc on `JpaMenuItemRepository.findFirstByNameIgnoreCase(String)` documenting deferred first-match behavior on non-unique names and recommending `findFirstByNameIgnoreCaseOrderByIdAsc(String)` for stable, deterministic ordering.

**Verification:**
- Executed `run_preflight_tests.ps1`:
  - `CoreJpaEntityIntegrationTest` executed twice consecutively against disposable PostgreSQL 17: both runs passed 7/7 tests with 0 failures.
  - `CoreJpaRepositoryIntegrationTest` executed twice consecutively against disposable PostgreSQL 17: both runs passed 25/25 tests with 0 failures.
- Clean compilation: `mvn clean test-compile` succeeded.

---

### [2026-10-26T00:15:00-04:00] — PG-010: Operational Spring Data JPA Repositories (Orders, Carts, Reviews, Reservations)

**Task ID:** `PG-010`  
**Phase:** Phase 8: Data Access Layer & JPA Repositories  
**Status:** `COMPLETED`  
**Start Timestamp:** 2026-10-25T23:57:00-04:00  
**Completion Timestamp:** 2026-10-26T00:15:00-04:00 (`America/Toronto`)  
**Commit Baseline:** `142e413`  

**Objective:**  
Implement Spring Data JPA repositories in `com.lordgainsborough.restaurant.persistence.jpa.repository` for the 4 operational domain entities mapped in Phase 6:
1. `JpaCustomerOrderRepository` (`JpaCustomerOrder`, `String`)
2. `JpaCartRepository` (`JpaCart`, `String`)
3. `JpaOrderReviewRepository` (`JpaOrderReview`, `String`)
4. `JpaReservationRepository` (`JpaReservation`, `String`)

**Implementation Details:**
1. **`JpaCustomerOrderRepository`:**
   - Extends `JpaRepository<JpaCustomerOrder, String>`.
   - `findByPickupDateOrderByCreatedAtDesc(String pickupDate)`: Lists orders for kitchen pickup scheduling ordered by creation time descending.
   - `findByCustomerEmailOrderByCreatedAtDesc(String customerEmail)`: Exact case customer order lookup.
   - `findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(String customerEmail)`: Case-insensitive email lookup supporting customer profile and order history views.
   - `findByCustomerPhoneOrderByCreatedAtDesc(String customerPhone)`: Phone lookup for customer lookup and SMS notifications.
   - `countByCustomerEmailIgnoreCase(String customerEmail)`: Aggregates order count by customer email.
   - `countByCustomerPhone(String customerPhone)`: Aggregates order count by customer phone.
   - `countByCustomerEmailIgnoreCaseAndStatus(String customerEmail, String status)`: Counts specific status orders (e.g. `NO_SHOW`) by email.
   - `countByCustomerPhoneAndStatus(String customerPhone, String status)`: Counts specific status orders (e.g. `NO_SHOW`) by phone.
   - `findWithItemsById(String id)` & `findWithMessagesById(String id)`: Targeted `@EntityGraph` query methods preventing N+1 queries when line items or chat messages are consumed detached.
   - Supports guest orders without requiring registered user joins. Excludes `trackingTokenHash` from JSON serialization.
2. **`JpaCartRepository`:**
   - Extends `JpaRepository<JpaCart, String>`.
   - `findByUserId(String userId)`: Explicit parameterized JPQL (`c.user.id = :userId`) traversing the `@OneToOne` association to `JpaUser`.
   - `findByUser_Id(String userId)`: Native Spring Data nested property traversal.
   - `deleteByUserId(String userId)`: `@Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional @Query("DELETE FROM JpaCart c WHERE c.user.id = :userId")`.
   - `deleteByUser_Id(String userId)`: Spring Data derived delete method.
   - Verified cascade semantics: Cart deletion cascades to delete all child `cart_items` rows (`fk_cart_items_cart ON DELETE CASCADE`), while the referenced `users` row is completely preserved.
   - Preserves one-cart-per-user constraint (`uq_carts_user_id`), list item order via `@OrderColumn(name = "item_index")`, and 7-day TTL expiration semantics.
3. **`JpaOrderReviewRepository`:**
   - Extends `JpaRepository<JpaOrderReview, String>`.
   - `findByOrderIdOrderByCreatedAtDesc(String orderId)`: Explicit JPQL (`r.order.id = :orderId ORDER BY r.createdAt DESC`).
   - `findByOrder_IdOrderByCreatedAtDesc(String orderId)`: Nested property traversal alternative.
   - `existsByOrderId(String orderId)`: Efficient boolean existence query (`SELECT CASE WHEN COUNT(r) > 0 THEN TRUE ELSE FALSE END FROM JpaOrderReview r WHERE r.order.id = :orderId`).
   - `existsByOrder_Id(String orderId)`: Derived existence check.
   - Respects unique constraint `uq_order_reviews_order_id` (at most one review per order).
   - Deleting a review removes child `order_review_items` rows via cascade while preserving the referenced `customer_orders` row.
4. **`JpaReservationRepository`:**
   - Extends `JpaRepository<JpaReservation, String>`.
   - Standard CRUD operations (`save`, `findById`, `findAll`, `deleteById`).
   - Supplementary finder queries: `findByDateOrderByTimeAsc(String date)`, `findByEmailIgnoreCaseOrderByCreatedAtDesc(String email)`, `findByStatus(String status)`.
   - Guest reservations supported without mandatory user reference.
   - Preserves exact date/time string values without time-zone distortion. Enforces check constraint `chk_reservations_party_size (party_size > 0)`.

**Verification:**
- Created integration test suite `OperationalJpaRepositoryIntegrationTest.java` (30 tests) executed against disposable PostgreSQL 17.11 initialized via Flyway V1:
  1. Req 1: All 4 repository proxies initialize in isolated Spring context.
  2. Req 2: No MongoDB repository is registered as a JPA repository.
  3. Req 3: All 17 JPA entities validate against Flyway V1 relational schema (`ddl-auto=validate`).
  4. Req 4: Customer order saved with ordered line items and messages.
  5. Req 5: Find orders by pickup date in `createdAt` descending order.
  6. Req 6: Find orders by exact customer email.
  7. Req 7: Find orders by case-insensitive customer email.
  8. Req 8: Find orders by phone.
  9. Req 9: Count orders by case-insensitive email.
  10. Req 10: Count orders by phone.
  11. Req 11: Count orders by email and status.
  12. Req 12: Count orders by phone and status.
  13. Req 13: Missing orders produce empty results and zero counts.
  14. Req 14: Order children persist and reload correctly through repository save (tested with targeted `@EntityGraph` and persistence session).
  15. Req 15: Find cart by associated user ID.
  16. Req 16: User without cart returns `Optional.empty()`.
  17. Req 17: One-cart-per-user uniqueness constraint is enforced by PostgreSQL.
  18. Req 18: Delete cart by associated user ID.
  19. Req 19: Cart deletion removes all child cart items.
  20. Req 20: Cart deletion does not remove user.
  21. Req 21: Cart item list order survives repository round-trip.
  22. Req 22: Cart `updatedAt` persists and item modifications refresh it through approved helpers (`touch()`, `isExpired()`).
  23. Req 23: Review lookup by order ID works.
  24. Req 24: Review existence query works.
  25. Req 25: Review deletion removes child review items but preserves underlying customer order.
  26. Req 26: One-review-per-order constraint is enforced.
  27. Req 27: Guest reservation CRUD works.
  28. Req 28: Reservation date/time strings round-trip exactly.
  29. Req 29: Invalid reservation party size (`<= 0`) is rejected by PostgreSQL check constraint.
  30. Req 30: No repository bean name collisions and default MongoDB profile isolates JPA repositories.
- **Targeted Integration Test Outcome:** 30 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 8.609s).
- **Three-Suite Targeted Run:** `CoreJpaEntityIntegrationTest,CoreJpaRepositoryIntegrationTest,OperationalJpaRepositoryIntegrationTest` (62 tests run, 0 failures, 0 errors, 0 skipped).
- **Complete PostgreSQL 9-Suite Regression Suite:**
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest,AbuseAndLoginFailureJpaIntegrationTest,CoreJpaRepositoryIntegrationTest,OperationalJpaRepositoryIntegrationTest -Dtest.postgres.port=54349`
  - Results: **164 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 37.465s across all 9 suites).
- **Clean Compilation:** `mvn clean compile -DskipTests` (`BUILD SUCCESS`, 125 source files compiled cleanly).

**MongoDB Compatibility:**
- Fully preserved: `CustomerOrderRepository`, `CartRepository`, `OrderReviewRepository`, `ReservationRepository`, operational controllers, and services remain completely untouched and operational.

**Next Task:**
`PG-011` — Implement Spring Data JPA repositories for security challenges and abuse limits (`JpaSignupOtpChallengeRepository`, `JpaPasswordResetOtpChallengeRepository`, `JpaSignupAbuseLimitRepository`, `JpaLoginFailureStateRepository`).

### [2026-10-26T00:34:00-04:00] — PG-010 Code Review Findings Resolution & Verification Hardening

**Task:** PG-010 Code Review Findings Resolution  
**Status:** COMPLETED  
**Timestamp:** 2026-10-26T00:34:00-04:00  

**Resolution Summary:**  
Resolved all three verification gaps and edge cases identified during the PG-010 code review:
1. **Cart Expiration Inclusive Boundary Hardening (`JpaCart`):**
   - Refactored `JpaCart.isExpired(Instant)` to calculate `expiresAt = this.updatedAt.plusSeconds(TTL_SECONDS)` and evaluate `!now.isBefore(expiresAt)`, ensuring exact instant expiration matching project-wide security challenge TTL semantics.
   - Enforced fail-closed behavior on `null` `updatedAt` timestamps (`return true`).
   - Hardened `OperationalJpaRepositoryIntegrationTest.test22` to assert exact boundary behavior: `-1ms` -> unexpired (`false`), exact instant -> expired (`true`), `+1ms` -> expired (`true`), and `null` -> expired (`true`).
2. **Supplementary Repository Execution Coverage (`JpaCartRepository` & `JpaReservationRepository`):**
   - Expanded `OperationalJpaRepositoryIntegrationTest.test18` to exercise both explicit JPQL `deleteByUserId()` and Spring Data derived `deleteByUser_Id()`, verifying that both successfully delete the cart and cascade to `cart_items` while preserving `users`.
   - Added `OperationalJpaRepositoryIntegrationTest.test29` exercising all three supplementary reservation queries:
     - `findByDateOrderByTimeAsc(date)`: strictly orders reservations for the target date ascending by time ("17:30", "19:15", "20:00").
     - `findByEmailIgnoreCaseOrderByCreatedAtDesc(email)`: case-insensitive email match, ordered descending by `createdAt`.
     - `findByStatus(status)`: accurately filters reservations by status ("CONFIRMED", "PENDING").
3. **Strengthened Default MongoDB Profile Isolation Test (`OperationalJpaRepositoryIntegrationTest.test31`):**
   - Replaced empty context check with a multi-layered Spring Boot auto-configuration isolation test:
     - Proves `DataJpaRepositoriesAutoConfiguration` with `@AutoConfigurationPackage` scanning backs off cleanly when `DataSource` / `EntityManagerFactory` beans are absent (as in default profile), registering 0 JPA repository beans.
     - Proves `DataSourceAutoConfiguration` without PostgreSQL connection properties fails immediately.
     - Proves `@EnableAutoConfiguration` configured with production `application.properties` exclusions (`spring.autoconfigure.exclude=...`) boots cleanly with zero relational infrastructure beans (`DataSource`, `EntityManagerFactory`, `Flyway`) and zero JPA repository beans.

**Verification:**  
- **Targeted Suite Run (`OperationalJpaRepositoryIntegrationTest`):** 31 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 10.60s).
- **Complete PostgreSQL 9-Suite Regression Suite:**
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest,AbuseAndLoginFailureJpaIntegrationTest,CoreJpaRepositoryIntegrationTest,OperationalJpaRepositoryIntegrationTest -Dtest.postgres.port=54349`
  - Results: **165 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 34.776s across all 9 suites).
- **Clean Compilation:** `mvn clean compile -DskipTests` (`BUILD SUCCESS`).

**MongoDB Compatibility:**  
- Fully preserved: existing MongoDB model classes, repositories, controllers, and services remain completely untouched and operational.

**Next Task:**  
`PG-011` — Implement Spring Data JPA repositories for security challenges and abuse limits (`JpaSignupOtpChallengeRepository`, `JpaPasswordResetOtpChallengeRepository`, `JpaSignupAbuseLimitRepository`, `JpaLoginFailureStateRepository`).

### [2026-10-26T00:49:00-04:00] — PG-011: Security Challenge and Abuse-Limit Spring Data JPA Repositories

**Task:** PG-011 — Security Challenge and Abuse-Limit Spring Data JPA Repositories  
**Status:** COMPLETED  
**Start Timestamp:** 2026-10-26T00:35:00-04:00  
**Completion Timestamp:** 2026-10-26T00:49:00-04:00  
**Baseline Commit:** `a3b30155bc22bb70008235fc4dd4b8e094156368`  
**Package:** `com.lordgainsborough.restaurant.persistence.jpa.repository`

**Implementation Summary:**  
Completed the final repository task of Phase 8, creating the four PostgreSQL security challenge, abuse limit, and login failure state Spring Data JPA repositories. This brings the total implemented JPA repository interfaces to **13**, covering all 17 mapped JPA entities across the relational schema.

1. **`JpaSignupOtpChallengeRepository`** (extends `JpaRepository<JpaSignupOtpChallenge, String>`):
   - Primary key: `emailKey` (`VARCHAR(64)` SHA-256 derived email hash).
   - Core CRUD: `save`, `saveAndFlush`, `findById`, `existsById`, `deleteById`. Assigned email key preserved without mutation or re-hashing.
   - Query methods:
     - `findByChallengeId(String challengeId)`: returns `List<JpaSignupOtpChallenge>` because `signup_otp_challenges.challenge_id` has a standard index in V1 but is not declared UNIQUE.
     - `findByStatus(OtpChallengeStatus status)`: filters challenges by status (`ACTIVE`, `VERIFIED`, `LOCKED`).
     - `findByChallengeExpiresAtLessThanEqual(Instant cutoff)`: finds expired challenges relative to an explicit instant.
     - `findByClientIdempotencyKey(String idempotencyKey)`: finds challenge records matching client dispatch idempotency keys.
2. **`JpaPasswordResetOtpChallengeRepository`** (extends `JpaRepository<JpaPasswordResetOtpChallenge, String>`):
   - Primary key: `emailKey` (`VARCHAR(64)`).
   - Core CRUD: `save`, `saveAndFlush`, `findById`, `existsById`, `deleteById`.
   - Query methods:
     - `findByChallengeId(String challengeId)`: returns `Optional<JpaPasswordResetOtpChallenge>` strictly guaranteed by the `uq_pwd_reset_challenge_id UNIQUE (challenge_id)` constraint in Flyway V1.
     - `findByUserId(String userId)` (parameterized JPQL query via `c.user.id`) and `findByUser_Id(String userId)` (derived traversal): look up challenges associated with a user without treating `userId` as a loose scalar.
     - `findByStatus(PasswordResetChallengeStatus status)`: filters challenges by status (`ACTIVE`, `PROCESSING`, `LOCKED`, `CONSUMED`).
     - `findByChallengeExpiresAtLessThanEqual(Instant cutoff)`: identifies expired password reset challenges.
     - `findByClientIdempotencyKey(String idempotencyKey)`: looks up challenges by client idempotency token.
   - Cascading and FK: verified that challenge deletion does not remove the user, and user deletion cascades to delete all associated password reset challenges (`ON DELETE CASCADE`).
3. **`JpaSignupAbuseLimitRepository`** (extends `JpaRepository<JpaSignupAbuseLimit, String>`):
   - Primary key: application-assigned String (`VARCHAR(128)`).
   - Core CRUD: `save`, `saveAndFlush`, `findById`, `existsById`, `deleteById`.
   - Precision: verified handling of `BIGINT` counters (`long` count) exceeding `Integer.MAX_VALUE` (tested up to 5,000,000,000L).
   - Check constraint: verified `chk_signup_abuse_count CHECK (count >= 0)` rejects negative counts.
   - Query methods:
     - `findByBucketTypeAndSubjectHash(BucketType bucketType, String subjectHash)`: returns `List<JpaSignupAbuseLimit>` because the composite index `idx_abuse_limits_bucket_subject` in V1 is non-unique.
     - `findBySubjectHash(String subjectHash)`: returns all buckets associated with a keyed subject hash.
     - `findByExpiresAtLessThanEqual(Instant cutoff)`: locates expired rate limit window buckets.
4. **`JpaLoginFailureStateRepository`** (extends `JpaRepository<JpaLoginFailureState, String>`):
   - Architectural distinction: Newly created PostgreSQL repository interface. The original MongoDB implementation (`LoginRateLimiter`) used `MongoTemplate` directly rather than a `MongoRepository` interface.
   - Primary key: application-assigned String (`VARCHAR(128)`).
   - Core CRUD: `save`, `saveAndFlush`, `findById`, `existsById`, `deleteById`.
   - Check constraint: verified `chk_login_failure_count CHECK (failure_count >= 0)` rejects negative counters.
   - Query methods:
     - `findBySubjectHash(String subjectHash)`: looks up state by keyed subject hash.
     - `findByExpiresAtLessThanEqual(Instant cutoff)`: locates expired failure states.
     - `findActiveBlocks(Instant now)` (custom JPQL) and `findByBlockedUntilAfterAndExpiresAtAfter(Instant now1, Instant now2)`: accurately queries actively blocked records where both `blockedUntil > now` and `expiresAt > now`, ensuring that stale expiration overrides obsolete block timestamps.

**Security & Concurrency Boundaries:**
- Concurrency logic strictly deferred: Ordinary JPA repository CRUD operations do NOT perform atomic increments or conditional state transitions. Atomic rate-limiter upserts and security state machines remain MongoDB-backed and will be transitioned in:
  - `PG-013`: `PostgresAbuseCounterStore` (atomic SQL upsert).
  - `PG-014`: `LoginRateLimiter` (transactional locking and cooldown migration).
  - `PG-015`: `SignupOtpChallengePersistence` (transactional OTP persistence).
  - `PG-016`: `PasswordResetOtpChallengePersistence` and `PasswordResetService` (transactional reset state management).
- Sensitive Data Protection: Verified Jackson serialization hides raw digests, secret operation IDs, idempotency keys, and user password hashes from JSON output across all security entities.
- Timestamp & Clock Caveat: Verified that `@PreUpdate` currently evaluates `Instant.now()`. Registered requirement to support injectable `Clock` during PG-015 and PG-016 without silently mutating JPA entity definitions during PG-011.
- Database Lifecycle & Teardown Hardening: Enforced strict test database ownership tracking (`databaseCreatedByTest`). Database creation failures are never swallowed; teardown only permits deletion when ownership was established; and drop failures are reported to `System.err` rather than silently suppressed.

**Verification Results:**
- **Targeted Integration Test Suite:** `com.lordgainsborough.restaurant.persistence.jpa.SecurityJpaRepositoryIntegrationTest`
  - Tests Run: **33**, Failures: **0**, Errors: **0**, Skipped: **0** (`BUILD SUCCESS`, 11.57s).
- **Complete PostgreSQL 10-Suite Regression Suite:**
  - Command: `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest,AbuseAndLoginFailureJpaIntegrationTest,CoreJpaRepositoryIntegrationTest,OperationalJpaRepositoryIntegrationTest,SecurityJpaRepositoryIntegrationTest -Dtest.postgres.port=54349`
  - Suites Executed:
    1. `PostgresConfigurationSmokeTest`
    2. `FlywayMigrationIntegrationTest`
    3. `CoreJpaEntityIntegrationTest`
    4. `OrderJpaEntityIntegrationTest`
    5. `CartReviewReservationJpaIntegrationTest`
    6. `SecurityChallengeJpaIntegrationTest`
    7. `AbuseAndLoginFailureJpaIntegrationTest`
    8. `CoreJpaRepositoryIntegrationTest`
    9. `OperationalJpaRepositoryIntegrationTest`
    10. `SecurityJpaRepositoryIntegrationTest`
  - Results: **198 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 49.992s across all 10 suites).
- **Clean Compilation:** `mvn clean test-compile` (`BUILD SUCCESS`).
- **Profile Isolation:** Verified that default MongoDB profile initializes cleanly with zero PostgreSQL connection requirements and zero JPA repository bean registrations.
  - *Verification Scope & Boundary Note:* Test 31 proves that Spring Boot auto-configuration exclusions prevent JPA repositories and datasource beans from activating when properties/beans are absent. However, it configures these exclusions directly in the test container context rather than booting the complete production application with the production properties file and live MongoDB services. Comprehensive full-application runtime verification remains required before the database cutover in Phase 15.

**MongoDB Compatibility:**  
Existing MongoDB models (`SignupOtpChallenge`, `PasswordResetOtpChallenge`, `SignupAbuseLimit`, `LoginFailureState`), repositories (`SignupOtpChallengeRepository`, `PasswordResetOtpChallengeRepository`, `SignupAbuseLimitRepository`), and services (`SignupOtpChallengePersistence`, `PasswordResetOtpChallengePersistence`, `MongoAbuseCounterStore`, `LoginRateLimiter`, `PasswordResetService`, `AuthController`) remain 100% untouched and operational.

**Next Task:**  
`PG-012` — Adapt `DataInitializer.java` to use JPA repositories and relational catalog synchronization (`NOT STARTED`).

---

### [2026-10-26T01:05:00-04:00] PG-011 Test Infrastructure Hardening: Disposable Database Lifecycle & Ownership Safety

**Overview & Rationale:**  
During post-implementation review of `SecurityJpaRepositoryIntegrationTest`, an unsafe database lifecycle pattern was identified where exceptions from `CREATE DATABASE` could be swallowed while teardown executed unconditional `DROP DATABASE ... WITH (FORCE)`. To guarantee complete isolation and prevent accidental deletion of existing databases:
- **`DisposableDatabaseSession` Helper:** Encapsulated database provisioning and teardown into an explicit lifecycle session. Successful database creation records verified ownership (`createdAndOwned = true`).
- **Conditional Destruction:** Teardown strictly refuses to terminate connections or execute `DROP DATABASE` unless test ownership was positively recorded.
- **Loopback Enforcement & Protected Database Rejection:** Connection configuration is strictly constrained to loopback (`127.0.0.1`), and database names are validated against regex `^[a-z0-9_]{5,63}$` with prefix `sec_jpa_repo_test_` while explicitly forbidding system/production databases (`postgres`, `template0`, `template1`, `public`, `lordgainsborough`, `restaurant`).
- **Error Transparency:** Database creation failures throw immediately and unswallowed; unexpected cleanup errors are reported to `System.err`.
- **Focused Verification:** Added `test32_databaseCreationFailureCannotCauseDeletionOfExistingDatabase` demonstrating that an unowned database survives cleanup attempts, creation failures over existing databases do not confer ownership, and protected database names are rejected.

**Verification Results:**  
- **Targeted Integration Test Suite:** `com.lordgainsborough.restaurant.persistence.jpa.SecurityJpaRepositoryIntegrationTest`
  - Tests Run: **34**, Failures: **0**, Errors: **0**, Skipped: **0** (`BUILD SUCCESS`, 9.487s).
- **Status:** PG-011 test infrastructure safety correction verified. Ready to proceed to PG-012.

---

### [2026-10-26T01:21:00-04:00] PG-012: PostgreSQL Catalog Initialization and Transactional Seeding

**Overview & Objective:**  
Implemented **PG-012: Adapt DataInitializer for PostgreSQL Catalog Synchronization** while keeping the original MongoDB `DataInitializer` intact and operational. Extracted a shared single-source-of-truth catalog definition (`CatalogSeedSpecifications`), implemented transactional opt-in PostgreSQL catalog synchronization (`PostgresCatalogSynchronizationService`), and configured conditional activation (`PostgresCatalogInitializerConfiguration`) protected against concurrent application startup via PostgreSQL transaction-scoped advisory locks.

**Design & Architectural Details:**
1. **Single Source of Truth (`CatalogSeedSpecifications`):**
   - Extracted immutable category and menu item specifications into `com.lordgainsborough.restaurant.config.CatalogSeedSpecifications`.
   - Preserves all 14 categories with exact names and `displayOrder` values (Appetizers, Pizzas, Salads, Sandwiches, Homemade Burgers, Classics, Pasta, Panzerotti, Kids Menu, Beverages, Hot Sandwiches, Sides, Gainsborough Melts, A Taste of Greece).
   - Preserves all 119 menu item specifications, including 3 independently orderable pizza sizes (Small 10", Medium 12", Large 16" with distinct prices), aliases (`Garlic Bread and Cheese` → `Cheesy Garlic Bread`), legacy items, and operational descriptions.
   - Refactored MongoDB `DataInitializer.java` to delegate to `CatalogSeedSpecifications.categories()` and `CatalogSeedSpecifications.menuCatalog()` while preserving its exact public/package API and MongoDB repository constructor.
2. **Transactional Synchronization Service (`PostgresCatalogSynchronizationService`):**
   - Annotated with Spring `@Transactional`.
   - **Advisory Lock Concurrency Control:** Executes `SELECT pg_advisory_xact_lock(5498725841443472718)` at transaction start. Holds until transaction commit or rollback, safely serializing concurrent multi-instance application startups without deadlock or duplicate key exceptions.
   - **Idempotency Check:** Checks `JpaCatalogMigrationRepository.existsById("2026-08-lord-gains-menu")`. If already present, skips synchronization entirely and returns `false`.
   - **Category Synchronization:** Upserts 14 categories by exact name, updates `description` and `displayOrder`, while strictly preserving preexisting database IDs.
   - **Menu Item Synchronization:**
     - Checks canonical name case-insensitively, followed by alias lookups.
     - Ambiguity detection: `findAllByNameIgnoreCase(name)` ensures that if multiple case-insensitive records match, synchronization fails safely with an explicit diagnostic rather than corrupting data.
     - Preserves operational fields on existing items (`id`, `image`, `available`, `isMostLoved`).
     - Inserts new items with UUID-style string IDs, default `available = true`, `isMostLoved = false`.
     - Monetary precision: converts prices via `BigDecimal.valueOf(price).setScale(2, RoundingMode.UNNECESSARY)` to avoid IEEE-754 floating point artifacts.
   - **Migration Marker:** Commits `JpaCatalogMigration("2026-08-lord-gains-menu", Instant.now())` in the same transaction upon completion.
3. **Controlled Conditional Activation (`PostgresCatalogInitializerConfiguration`):**
   - Strictly opt-in: requires `app.catalog.postgres-seed.enabled=true`. Default is `false`.
   - Gated by `@Profile({"postgres-migration", "test"})` and `@ConditionalOnBean({JpaCategoryRepository.class, JpaMenuItemRepository.class, JpaCatalogMigrationRepository.class})`.
   - Will NOT run in standard MongoDB runtime or default profiles.
4. **Safety & Non-Destructive Boundaries:**
   - No `deleteAll()` or destructive deletions; administrative menu items or categories created outside the seed specification remain untouched.
   - Stores settings, orders, users, reviews, carts, and challenges remain 100% untouched.

**Verification Results:**
- **MongoDB Initializer Regression:**
  - `com.lordgainsborough.restaurant.config.DataInitializerTest`
  - Result: **4 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 0.882s).
- **PostgreSQL Catalog Initializer Integration Suite:**
  - `com.lordgainsborough.restaurant.config.PostgresCatalogInitializerIntegrationTest`
  - Coverage: 34 tests exercising service initialization, profile/property gating, Flyway V1 schema validation, 14 categories sync, 119 menu items sync, 3 pizza sizes (10", 12", 16"), canonical/alias matching, operational field preservation (`available=false`, `isMostLoved=true`, custom image), `BigDecimal` scale 2 verification, idempotency on repeated run, rollback on category/menu/marker failure, ambiguity detection, and concurrent execution under multi-transaction advisory locking.
  - Result: **34 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 16.83s).
- **Complete PostgreSQL Regression Baseline (11 test suites from Surefire reports):**
  1. `PostgresConfigurationSmokeTest`: 3 tests
  2. `FlywayMigrationIntegrationTest`: 4 tests
  3. `CoreJpaEntityIntegrationTest`: 7 tests
  4. `OrderJpaEntityIntegrationTest`: 18 tests
  5. `CartReviewReservationJpaIntegrationTest`: 26 tests
  6. `SecurityChallengeJpaIntegrationTest`: 26 tests
  7. `AbuseAndLoginFailureJpaIntegrationTest`: 25 tests
  8. `CoreJpaRepositoryIntegrationTest`: 25 tests
  9. `OperationalJpaRepositoryIntegrationTest`: 31 tests
  10. `SecurityJpaRepositoryIntegrationTest`: 34 tests
  11. `PostgresCatalogInitializerIntegrationTest`: 34 tests
  - Total Tests Run: **233**, Failures: **0**, Errors: **0**, Skipped: **0** (`BUILD SUCCESS`, 50.815s).

---

### [2026-10-26T02:37:00-04:00] PG-012 Code Review Resolutions: Startup Activation Ordering, Transaction Manager Coexistence, Real Marker Rollback, Documentation Corrections, and Commit-Aware Logging

**Overview & Code Review Findings Addressed:**
1. **Startup Activation Order & Coexistence:**
   - Class-level `@ConditionalOnBean` evaluated before `DataJpaRepositoriesAutoConfiguration` ran, causing configuration parsing to back off prematurely during normal startup.
   - Spring Boot's JPA auto-configuration backed off from creating `transactionManager` due to existing `mongoTransactionManager`.
2. **Migration-Marker Rollback Incompleteness:**
   - `test28` previously verified late rollback by overriding `synchronizeCatalog()` after categories/menu sync, but did not exercise a failing real `JpaCatalogMigrationRepository.save()` or database flush operation.
3. **Pizza Dimensions & Suite Count Consistency:**
   - Corrected pizza dimensions in documentation from 10", 12", 14" to 10", 12", 16" matching `CatalogSeedSpecifications`.
   - Aligned individual suite test counts in the roadmap table and journal with actual Surefire report outputs.
4. **Transaction Commit Success Logging Hardening:**
   - `PostgresCatalogSynchronizationService.synchronizeCatalog()` previously logged that synchronization was successfully committed before returning from the `@Transactional` method, before the actual database commit took place.

**Implementation & Verification Details:**
1. **Startup Activation & Coexistence:**
   - Removed class-level `@ConditionalOnBean` from `PostgresCatalogInitializerConfiguration`. Startup activation is strictly gated by `@Profile({"postgres-migration", "test"})` and `@ConditionalOnProperty(name = "app.catalog.postgres-seed.enabled", havingValue = "true")`.
   - Added fallback `@Bean(name = "transactionManager") @ConditionalOnMissingBean(name = "transactionManager")` returning `new JpaTransactionManager(entityManager.getEntityManagerFactory())`.
   - Updated `PostgresCatalogSynchronizationService.synchronizeCatalog()` to explicitly specify `@Transactional(transactionManager = "transactionManager")`.
   - Added `PostgresCatalogStartupActivationIntegrationTest` (4 tests) verifying real repository auto-configuration startup, runner execution, idempotency, back-off, and non-relational coexistence.
2. **Real Migration Marker Save and Flush Rollback Integration Tests:**
   - In `PostgresCatalogInitializerIntegrationTest`:
     - Added `test35_realMigrationMarkerSaveFailureRollsBackCatalogChanges`: proxies `JpaCatalogMigrationRepository.save()` to throw `DataIntegrityViolationException` when `save()` is invoked after `syncCategories()` and `syncMenuItems()`. Verifies whole transaction rollback, asserting 0 categories, 0 menu items, and 0 migration markers in PostgreSQL.
     - Added `test36_migrationMarkerDatabaseFlushFailureRollsBackCatalogChanges`: installs a PostgreSQL table trigger (`fail_marker_insert_trg`) `BEFORE INSERT ON catalog_migrations` that raises an exception. Executes unmodified production `syncService.synchronizeCatalog()` with real repositories, verifying that SQL flush failure of the marker rolls back all catalog modifications, leaving 0 categories, 0 menu items, and 0 migration markers.
     - Updated `test28` display name and added explicit assertion verifying 0 migration markers on late application exception rollback.
     - `PostgresCatalogInitializerIntegrationTest` now contains **36 tests** (all passing).
3. **Transaction Commit-Aware Logging (`PostgresCatalogSynchronizationService`):**
   - Registered an after-commit synchronization callback via `TransactionSynchronizationManager.registerSynchronization(...)` when a transaction is active. The definitive log message (`"PostgreSQL catalog synchronization successfully committed for migration '{}'."`) is now logged strictly inside `afterCommit()`.
   - Prior to method return, logs: `"PostgreSQL catalog synchronization work complete; pending transaction commit for migration '{}'."`.
   - If executed outside transaction synchronization, falls back to logging work completion directly without claiming transaction commit.
4. **Surefire Verified Regression Breakdown (12 Suites):**
   - `PostgresConfigurationSmokeTest`: **3** tests
   - `FlywayMigrationIntegrationTest`: **4** tests
   - `CoreJpaEntityIntegrationTest`: **7** tests
   - `OrderJpaEntityIntegrationTest`: **18** tests
   - `CartReviewReservationJpaIntegrationTest`: **26** tests
   - `SecurityChallengeJpaIntegrationTest`: **26** tests
   - `AbuseAndLoginFailureJpaIntegrationTest`: **25** tests
   - `CoreJpaRepositoryIntegrationTest`: **25** tests
   - `OperationalJpaRepositoryIntegrationTest`: **31** tests
   - `SecurityJpaRepositoryIntegrationTest`: **34** tests
   - `PostgresCatalogInitializerIntegrationTest`: **36** tests
   - `PostgresCatalogStartupActivationIntegrationTest`: **4** tests
   - **Full 12-Suite PostgreSQL Regression Total:** **239 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 1m 22s).
   - **MongoDB Initializer:** `DataInitializerTest`: **4 tests run, 0 failures, 0 errors, 0 skipped** (0.870s).
   - **Combined Repository Total:** **243 tests run, 0 failures, 0 errors, 0 skipped**.

**Next Task:**  
`PG-013` — Implement `PostgresAbuseCounterStore` using atomic PostgreSQL upsert queries (`COMPLETED`).

---

### [2026-10-26T02:53:00-04:00] PG-013: Atomic PostgreSQL Abuse Counter Store

**Overview & Objective:**  
Implemented **PG-013: Atomic PostgreSQL Abuse Counter Store**, introducing `PostgresAbuseCounterStore` implementing the existing `AbuseCounterStore` interface. Provides atomic, concurrency-safe fixed-window rate-limiting counter operations backed by the PostgreSQL `signup_abuse_limits` table while preserving the existing `MongoAbuseCounterStore` completely intact.

**Design & Architectural Details:**
1. **Atomic Single-Statement Upsert (`incrementAndGet`):**
   - Implemented via a single atomic SQL statement with zero client-side locks or multi-query races:
     ```sql
     INSERT INTO signup_abuse_limits (id, bucket_type, subject_hash, count, window_start, expires_at)
     VALUES (?, ?, ?, 1, ?, ?)
     ON CONFLICT (id) DO UPDATE
     SET count = signup_abuse_limits.count + 1
     WHERE signup_abuse_limits.bucket_type = EXCLUDED.bucket_type
       AND signup_abuse_limits.subject_hash = EXCLUDED.subject_hash
       AND signup_abuse_limits.window_start = EXCLUDED.window_start
       AND signup_abuse_limits.expires_at = EXCLUDED.expires_at
     RETURNING count
     ```
   - **Conflicting Metadata Rejection:** If a primary key collision occurs with conflicting bucket type, subject hash, window start, or expires at, the `WHERE` condition in the `DO UPDATE` clause fails. PostgreSQL returns zero rows, which `PostgresAbuseCounterStore` detects immediately and raises an explicit `IllegalArgumentException`.
   - **BIGINT Overflow Protection:** When `count` reaches `Long.MAX_VALUE`, PostgreSQL raises `ERROR: bigint out of range`. This is caught and translated to `ArithmeticException("Abuse counter overflow for ID: ...")` failing closed without resetting or wrapping.
2. **Exact Boundary Semantics (`getCount`):**
   - Retrieves `count` and `expires_at` via `SELECT count, expires_at FROM signup_abuse_limits WHERE id = ?`.
   - Returns 0 if record does not exist or if `expires_at` is null.
   - Exact boundary evaluation: Returns 0 when `!now.isBefore(expiresAt)` (i.e. `now >= expiresAt`).
   - Retains expired rows in PostgreSQL without deleting them during query evaluation; returns active count only while `now.isBefore(expiresAt)`.
3. **Safe Clear Operation (`clear`):**
   - Guarded by constructor flag `allowClear`.
   - In production runtime (`allowClear = false`), throws `UnsupportedOperationException("clear() is disabled in production runtime for PostgreSQL abuse store")`.
   - In test harness (`allowClear = true`), executes `DELETE FROM signup_abuse_limits`.
4. **Data Privacy & SQL Injection Protection:**
   - Parameterized JDBC queries via `JdbcTemplate` prevent SQL injection across malicious IDs, subject hashes, or bucket types.
   - Sensitive subject hashes and raw client identifiers are never exposed in log outputs.
5. **Conditional Opt-In Configuration (`PostgresAbuseCounterStoreConfiguration`):**
   - Gated by `@Profile({"postgres-migration", "test"})` and `@ConditionalOnProperty(name = "app.security.postgres-abuse-store.enabled", havingValue = "true")`.
   - Leaves `MongoAbuseCounterStore` as the default bean in standard profiles and production runtime.

**Verification Results:**
- **Unit Tests (`SignupAbuseLimiterTest`, `PasswordResetSupportServicesTest`):**
  - **12 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`).
- **Targeted Integration Suite (`PostgresAbuseCounterStoreIntegrationTest`):**
  - 36 tests covering all requirements:
    - Initial insert count = 1
    - Monotonic atomic increment
    - All 10 `BucketType` enum variants
    - Non-existent record count = 0
    - Exact boundary semantics (`now = expiresAt - 1ms` active, `now = expiresAt` expired = 0, `now = expiresAt + 1ms` expired = 0)
    - Retained expired rows in database
    - Conflicting metadata detection & rejection (`IllegalArgumentException`)
    - Value scaling beyond `Integer.MAX_VALUE` (`Long.MAX_VALUE - 10`)
    - BIGINT overflow protection (`Long.MAX_VALUE` increment raises `ArithmeticException`)
    - Production safety guard on `clear()` (`UnsupportedOperationException`)
    - Test mode `clear()` execution
    - SQL injection resistance and log privacy
    - Profile and property gating
    - Multi-threaded concurrency:
      - Test A: 20 concurrent threads on single bucket (exact final count = 20)
      - Test B: 100 concurrent threads on single bucket (exact final count = 100)
      - Test C: High contention across multiple threads
      - Test D: Independent buckets concurrent increments without interference
      - Test E: Multi-transaction atomicity under parallel connections
      - Test F: Simultaneous first insert race condition
      - Test G: Boundary integrity under concurrency
  - Result: **36 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 23.41s).
- **Full 13-Suite PostgreSQL Regression Total (Surefire Reports):**
  1. `PostgresConfigurationSmokeTest`: **3** tests
  2. `FlywayMigrationIntegrationTest`: **4** tests
  3. `CoreJpaEntityIntegrationTest`: **7** tests
  4. `OrderJpaEntityIntegrationTest`: **18** tests
  5. `CartReviewReservationJpaIntegrationTest`: **26** tests
  6. `SecurityChallengeJpaIntegrationTest`: **26** tests
  7. `AbuseAndLoginFailureJpaIntegrationTest`: **25** tests
  8. `CoreJpaRepositoryIntegrationTest`: **25** tests
  9. `OperationalJpaRepositoryIntegrationTest`: **31** tests
  10. `SecurityJpaRepositoryIntegrationTest`: **34** tests
  11. `PostgresCatalogInitializerIntegrationTest`: **36** tests
  12. `PostgresCatalogStartupActivationIntegrationTest`: **4** tests
  13. `PostgresAbuseCounterStoreIntegrationTest`: **36** tests
  - Total Tests Run: **275**, Failures: **0**, Errors: **0**, Skipped: **0** (`BUILD SUCCESS`, 1m 10s).
- **MongoDB Initializer Regression:**
  - `DataInitializerTest`: **4 tests run, 0 failures, 0 errors, 0 skipped** (0.875s).
- **Combined Repository Total:** **291 tests run, 0 failures, 0 errors, 0 skipped**.

---

### [2026-10-26T03:10:00-04:00] PG-012/PG-013 Test Infrastructure Hardening: Disposable Database Lifecycle in Startup Activation Suite

**Overview & Code Review Finding Addressed:**
- **Finding:** `PostgresCatalogStartupActivationIntegrationTest.java` used fixed database names (`pg_seed_activation_test_db`, `pg_seed_coexist_test_db`) and executed unconditional `DROP DATABASE IF EXISTS ... WITH (FORCE)` prior to creation. This could delete a preexisting database or conflict with parallel test runs.
- **Resolution:**
  1. **Dynamic Identifier Generation:** Configured `TEST_DB_PREFIX = "pg_seed_act_test_"` and generated randomized, UUID-based unique database names for both the primary activation test database and coexisting transaction manager test database.
  2. **`DisposableDatabaseSession` Encapsulation:** Implemented lifecycle tracking with `createdAndOwned = true` strictly upon successful `CREATE DATABASE`.
  3. **Conditional Destruction & Protection:** `drop()` skips execution if ownership was not verified, terminates active connections cleanly, and strictly validates that database names match expected prefix/regex and are not protected/system databases (`postgres`, `template0`, `template1`, `public`, `lordgainsborough`, `restaurant`).
  4. **Dedicated Verification:** Added `test05_disposableDatabaseSession_unownedAndCreationFailureGuardrails` asserting that unowned sessions refuse drop, failed provisions do not grant ownership, and protected database names are rejected.

**Verification Results:**
- **Targeted Startup Activation Suite:**
  - `PostgresCatalogStartupActivationIntegrationTest`: **5 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 13.60s).
- **Full 13-Suite PostgreSQL Regression Breakdown:**
  1. `PostgresConfigurationSmokeTest`: **3** tests
  2. `FlywayMigrationIntegrationTest`: **4** tests
  3. `CoreJpaEntityIntegrationTest`: **7** tests
  4. `OrderJpaEntityIntegrationTest`: **18** tests
  5. `CartReviewReservationJpaIntegrationTest`: **26** tests
  6. `SecurityChallengeJpaIntegrationTest`: **26** tests
  7. `AbuseAndLoginFailureJpaIntegrationTest`: **25** tests
  8. `CoreJpaRepositoryIntegrationTest`: **25** tests
  9. `OperationalJpaRepositoryIntegrationTest`: **31** tests
  10. `SecurityJpaRepositoryIntegrationTest`: **34** tests
  11. `PostgresCatalogInitializerIntegrationTest`: **36** tests
  12. `PostgresCatalogStartupActivationIntegrationTest`: **5** tests
  13. `PostgresAbuseCounterStoreIntegrationTest`: **36** tests
  - **Full 13-Suite Regression Total:** **276 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 1m 16s).
- **MongoDB Initializer Regression:**
  - `DataInitializerTest`: **4 tests run, 0 failures, 0 errors, 0 skipped** (0.875s).
- **Combined Repository Total:** **292 tests run, 0 failures, 0 errors, 0 skipped**.

---

### [2026-10-26T03:13:00-04:00] PG-013 Spring Context Resolution: Explicit AbuseCounterStore Backend Selection & Coexistence

**Overview & Code Review Finding Addressed:**
- **Finding:** `MongoAbuseCounterStore` was an unconditional `@Component`. When `PostgresAbuseCounterStoreConfiguration` was enabled, Spring registered two implementations of `AbuseCounterStore`. Because existing consumers (`SignupAbuseLimiter`, `PasswordResetAbuseLimiter`, `LoginRateLimiter`) inject `AbuseCounterStore` without a qualifier, a combined application context resulted in ambiguous autowiring (`NoUniqueBeanDefinitionException`).
- **Resolution:**
  1. **Explicit Component Naming:** Annotated `MongoAbuseCounterStore` with `@Component("mongoAbuseCounterStore")`.
  2. **Explicit Primary Backend Selection:** Designated `postgresAbuseCounterStore` as `@Primary` and aliased with `@Bean(name = {"postgresAbuseCounterStore", "abuseCounterStore"})` in `PostgresAbuseCounterStoreConfiguration`. When opt-in PostgreSQL is activated, unqualified injection of `AbuseCounterStore` resolves explicitly and unambiguously to `PostgresAbuseCounterStore`.
  3. **Preserved Default MongoDB Behavior:** When `app.security.postgres-abuse-store.enabled` is missing or false (default runtime), `PostgresAbuseCounterStoreConfiguration` does not load, leaving `MongoAbuseCounterStore` as the sole active bean.
  4. **Combined Context Integration Tests:** Added `test37_combinedContextWithBothStoresResolvesUnambiguouslyToPostgres` and `test38_defaultContextWithMongoStoreResolvesToMongo` in `PostgresAbuseCounterStoreIntegrationTest` demonstrating that both stores coexist simultaneously in the same Spring context, unqualified injection resolves to `@Primary` PostgreSQL, named qualifiers resolve both implementations, and default context resolves to MongoDB.

---

### [2026-10-26T03:18:00-04:00] PG-013 Destructive Operation Guarding: Verified Disposable Database Tie for clear()

**Overview & Code Review Finding Addressed:**
- **Finding:** `PostgresAbuseCounterStore` allowed global deletion using a constructor flag or the JVM system property `app.postgres.abuse-store.allow-clear`. The permission was not intrinsically tied to a verified disposable database, posing a risk of catastrophic rate-limit data wipe if misconfigured against a live or production database.
- **Resolution:**
  1. **Removed System Property Override:** Default constructors `PostgresAbuseCounterStore(JdbcTemplate)` and `PostgresAbuseCounterStore(DataSource)` strictly set `allowClear = false`. The JVM property `app.postgres.abuse-store.allow-clear` has been completely eliminated.
  2. **Intrinsically Verified Disposable Database Check:** `clear()` executes `assertDisposableTestDatabase()`, executing `SELECT current_database()`:
     - Rejects system and production databases (`postgres`, `template0`, `template1`, `public`, `lordgainsborough`, `restaurant`) with `SecurityException`.
     - Validates that the active database matches recognized disposable test patterns (`pg_abuse_store_test_`, `*_test_*`, etc.).
     - Blocks global deletion even if `allowClear = true` was passed, unless the target database is positively confirmed to be a disposable test database.
  3. **Updated Verification Tests:**
     - Updated `test33` in `PostgresAbuseCounterStoreIntegrationTest` asserting that setting `-Dapp.postgres.abuse-store.allow-clear=true` does not enable `allowClear` and `clear()` fails with `UnsupportedOperationException`.
     - Added `test34b` asserting that even with `allowClear = true`, calling `clear()` against a protected database (e.g. `postgres`) throws `SecurityException: Refusing to execute clear() on protected database: postgres`.

---

### [2026-10-26T03:26:00-04:00] PG-013 Verification & Maintainability Hardening: Independent Fallback TransactionManager, SQL Simplification, and Emitted Log Capture

**Overview & Code Review Finding 4 Addressed:**
- **Finding 4 (Transaction manager and log tests need strengthening):**
  1. `PostgresAbuseCounterStore` explicitly requests `@Transactional(transactionManager = "transactionManager")`, but integration tests mostly constructed the store directly without exercising Spring transactional proxies. The JPA manager fallback added in PG-012 was conditional on catalog-seeding configuration (`app.catalog.postgres-seed.enabled=true`), so it was not proof the counter store works independently.
  2. The privacy logging test (`test25`) checked an exception message rather than capturing emitted logs.
  3. The SQL increment statement's overflow `CASE` expression had identical branches (`WHEN count >= 9223372036854775807 THEN count + 1 ELSE count + 1`), making the `CASE` structure redundant.
- **Resolution:**
  1. **Independent Fallback TransactionManager Bean:**
     - Added `@EnableTransactionManagement(proxyTargetClass = true)` to `PostgresAbuseCounterStoreConfiguration`.
     - Added `@Bean(name = "transactionManager") @ConditionalOnMissingBean(name = "transactionManager") PlatformTransactionManager transactionManager(ObjectProvider<EntityManagerFactory> emfProvider, DataSource dataSource)` in `PostgresAbuseCounterStoreConfiguration`.
     - When JPA `EntityManagerFactory` is present, it returns `JpaTransactionManager`; otherwise, it returns `DataSourceTransactionManager`. This guarantees Spring transactional proxies and `@Transactional(transactionManager = "transactionManager")` function independently of whether catalog seeding is active.
  2. **Simplified SQL Atomic Increment:**
     - Removed redundant `CASE` expression from `SQL_INCREMENT` in `PostgresAbuseCounterStore.java`, reducing to `SET count = signup_abuse_limits.count + 1`.
     - PostgreSQL integer arithmetic naturally raises `ERROR: bigint out of range` on `Long.MAX_VALUE`, cleanly caught by `isOverflowException` and translated to `ArithmeticException`.
  3. **Emitted Log Event Capture via Logback Appender:**
     - Updated `test25_noSecretIdsOrSubjectHashesLogged` in `PostgresAbuseCounterStoreIntegrationTest` to attach a `ListAppender<ILoggingEvent>` to the `PostgresAbuseCounterStore` logger.
     - Confirmed that a warning log is actively emitted on metadata conflict, and verified that neither the sensitive counter ID (`secretId`) nor the hashed subject (`secretHash`) is contained in any emitted log event message.
  4. **Spring Transactional Proxy & Independent TransactionManager Verification:**
     - Added `test39_springTransactionalProxyWithIndependentTransactionManagerWorks` in `PostgresAbuseCounterStoreIntegrationTest`.
     - Verifies Spring context creation with independent fallback `transactionManager` alongside `MongoTransactionManager`, confirms `AopUtils.isAopProxy(proxyStore) == true`, and executes transactional `incrementAndGet` and `getCount` through the proxy.

---

### [2026-10-26T03:42:00-04:00] PG-014: Concurrency-Safe PostgreSQL Login Rate Limiting With Transactional Row-Level Locking

**Overview & Objective:**
Implemented **PG-014: PostgreSQL Login Rate Limiter With Transactional Locking**, transitioning failed authentication attempt persistence from MongoDB compare-and-set loops (`findAndModify`) to PostgreSQL relational persistence with ACID transaction boundaries and row-level locking (`SELECT ... FOR UPDATE`), strictly preserving existing API contracts and default MongoDB runtime safety.

**Key Architecture & Implementation Decisions:**
1. **Separation of Concerns via `LoginFailureStateStore` SPI:**
   - Created `LoginFailureStateStore` interface defining persistence operations: `activeCooldown(id, now)`, `recordFailure(id, subjectHash, now, properties)`, and `clearFailures(id)`.
   - Extracted `MongoLoginFailureStateStore` implementing the interface using existing MongoDB compare-and-set logic, registered as `@Component("mongoLoginFailureStateStore")`.
   - Maintained full binary and source compatibility for `LoginRateLimiter`: retained existing constructors and added an SPI constructor `(counterStore, failureStore, properties, hasher, clock)`. `AuthController` injection remains untouched.
2. **PostgreSQL Relational Storage & Row-Level Locking (`PostgresLoginFailureStateStore`):**
   - Implemented an atomic insert-or-lock strategy to resolve the critical missing-row race condition:
     - `INSERT INTO login_failure_states (...) VALUES (...) ON CONFLICT (id) DO NOTHING`.
     - If insert succeeds (1 row affected), creates failure #1 immediately.
     - If row already exists (0 rows affected), executes `SELECT ... FOR UPDATE` to acquire an exclusive row lock.
     - Operates inside `TransactionTemplate` and `@Transactional(transactionManager = "transactionManager")`, guaranteeing the lock is held across the entire transition until transaction commit.
3. **Exact State Transition Parity (All 6 Cases):**
   - **Case A (Fresh Insert):** Sets `failure_count = 1`, `window_started_at = now`, `blocked_until = null`, `expires_at = now + failureWindow + 5m`. Returns `FailureResult(false, 0L)`.
   - **Case B (Expired Row):** If `!expiresAt.isAfter(now)`, resets failure count to 1, sets fresh timestamps and null cooldown.
   - **Case C (Active Cooldown):** If `blockedUntil != null && blockedUntil.isAfter(now)`, does not increment counter, returns `FailureResult(true, retryAfter)`.
   - **Case D (Active Window):** Increments `failureCount + 1`. If threshold reached (`>= identifierIpFailures`), sets `blockedUntil = now + cooldown`.
   - **Case E (Window Elapsed):** If `now >= windowStartedAt + failureWindow`, resets `failureCount = 1`, sets `windowStartedAt = now`.
   - **Case F (Expired Cooldown):** If `blockedUntil <= now`, resets `failureCount = 1` and initiates a fresh counting window.
   - **Threshold Contract:** The 5th (threshold-reaching) failed attempt returns `FailureResult(false, 0L)` (treated as invalid credentials); subsequent attempts observe and enforce the 30-second cooldown.
4. **Integer Overflow & Fail-Closed Hardening:**
   - Evaluates `failureCount >= Integer.MAX_VALUE`. Fails closed with `FailureResult(true, cooldown)` and logs an error without leaking IDs or hashes.
   - Catches database errors and connection interruptions, returning fail-closed `FailureResult(true, cooldown)`.
5. **Exact Cooldown Boundaries:**
   - Tested exact millisecond boundaries: 1ms before `blockedUntil` (active cooldown), exactly at `blockedUntil` (expired), and 1ms after (expired).
6. **Targeted Deletion (`clearFailures`):**
   - Deletes strictly `WHERE id = ?` inside a transaction; idempotent, safe on repeated calls, and leaves unrelated users and IPs untouched.
7. **Spring Opt-In Configuration (`PostgresLoginRateLimiterConfiguration`):**
   - Activates conditionally under `@Profile({"postgres-migration", "test"})` and `@ConditionalOnProperty("app.security.postgres-login-limiter.enabled")`.
   - Designates `PostgresLoginFailureStateStore` as `@Primary`, ensuring unambiguous resolution when coexisting with MongoDB.
   - Includes independent fallback `PlatformTransactionManager` bean.

**Verification Results:**
- **Compilation:** `mvn clean test-compile` passed cleanly with 0 errors.
- **Dedicated PG-014 Integration Suite (`PostgresLoginRateLimiterIntegrationTest`):**
  - **38 tests run, 0 failures, 0 errors, 0 skipped** (7.889s).
  - High contention: 20 concurrent threads under threshold (no lost increments, count = 20).
  - Extreme contention: 100 concurrent threads under high threshold (no lost increments, count = 100).
  - 30 concurrent active-cooldown threads (all denied, count remains 5).
  - Multi-user independence: 5 concurrent users with 10 threads each (all reached exactly 10).
  - Interleaved concurrent `recordFailure` and `clearFailures` (no deadlocks).
- **Existing Security Unit Tests:**
  - `SignupAbuseLimiterTest`: **9 tests run, 0 failures, 0 errors, 0 skipped** (0.023s).
  - `PasswordResetSupportServicesTest`: **3 tests run, 0 failures, 0 errors, 0 skipped** (0.039s).
  - `DataInitializerTest`: **4 tests run, 0 failures, 0 errors, 0 skipped** (0.756s).
- **Full 14-Suite PostgreSQL Regression:**
  1. `PostgresConfigurationSmokeTest`: **3** tests
  2. `FlywayMigrationIntegrationTest`: **4** tests
  3. `CoreJpaEntityIntegrationTest`: **7** tests
  4. `OrderJpaEntityIntegrationTest`: **18** tests
  5. `CartReviewReservationJpaIntegrationTest`: **26** tests
  6. `SecurityChallengeJpaIntegrationTest`: **26** tests
  7. `AbuseAndLoginFailureJpaIntegrationTest`: **25** tests
  8. `CoreJpaRepositoryIntegrationTest`: **25** tests
  9. `OperationalJpaRepositoryIntegrationTest`: **31** tests
  10. `SecurityJpaRepositoryIntegrationTest`: **34** tests
  11. `PostgresCatalogInitializerIntegrationTest`: **36** tests
  12. `PostgresCatalogStartupActivationIntegrationTest`: **5** tests
  13. `PostgresAbuseCounterStoreIntegrationTest`: **40** tests
  14. `PostgresLoginRateLimiterIntegrationTest`: **38** tests
  - **Full 14-Suite Regression Total:** **318 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 1m 20s).
- **Combined Test Total:** **334 tests passing cleanly**.

**Next Task:**  
`PG-014-FINAL-HARDENING` — Stage A Hardening & Code-Review Findings Resolution (`COMPLETED`).

---

### [2026-10-26T04:20:00-04:00] PG-014-FINAL-HARDENING: Login Rate Limiter & Abuse Counter Store Review Hardening

**Overview & Objective:**
Completed Stage A hardening resolving all code-review findings from PG-014, including Spring bean constructor ambiguity, transaction manager consistency, missing-row race conditions under concurrent deletion, linearizable clear/failure ordering semantics, and test database ownership guardrails.

**Key Hardening Measures:**
1. **LoginRateLimiter Spring Constructor Injection (Finding 1):**
   - Added `@Autowired` to the primary SPI-based constructor:
     `LoginRateLimiter(AbuseCounterStore, LoginFailureStateStore, LoginRateLimitProperties, AbuseIpHasher, Clock)`.
   - Kept the legacy `LoginRateLimiter(MongoTemplate, LoginRateLimitProperties, AbuseIpHasher, Clock)` constructor intact for backwards compatibility with direct test callers.
   - Added `test04b_springContextConstructorInjectionResolution` verifying that in both default MongoDB context and opt-in PostgreSQL context, Spring cleanly instantiates `LoginRateLimiter` with zero constructor ambiguity.
2. **Transaction-Manager Consistency Across JPA and Spring (Finding 2):**
   - Updated `PostgresLoginRateLimiterConfiguration` to inject `@Qualifier("transactionManager") PlatformTransactionManager` into `PostgresLoginFailureStateStore`.
   - `PostgresLoginFailureStateStore` stores this explicit manager and initializes its internal `TransactionTemplate` from it, ensuring both declarative `@Transactional(transactionManager = "transactionManager")` and programmatic `TransactionTemplate` operate on the exact same physical transaction and connection boundary.
   - Added `test29b_transactionalRollbackRestoresPreviousStateViaSpringProxy` and `test29c_rowLockHeldAcrossTransactionCommitViaSpringProxy` verifying real Spring AOP proxy transactional rollback and row-level lock blocking.
3. **Missing-Row Insert/Delete Race Handling (Finding 3):**
   - Hardened `PostgresLoginFailureStateStore.recordFailure()` with a bounded retry policy (`MAX_ROW_RACE_RETRIES = 20`).
   - Atomically attempts `INSERT ... ON CONFLICT (id) DO NOTHING`. Checks `inserted == 1`.
   - If `inserted == 0` (conflict with concurrent transaction), executes `SELECT ... FOR UPDATE`.
   - If the locked row is unexpectedly absent (due to concurrent `clearFailures` committing between conflict and select), loops cleanly to reattempt insertion.
   - For all updates, explicitly verifies `updated == 1`.
   - Fails closed with `FailureResult(true, cooldown)` if retries exhaust, preventing false-positive authorization or unrecorded failures.
   - Added `test35b_missingRowRaceUnderConcurrentDeleteAndReinsert` to verify bounded race recovery.
4. **Successful-Login Clearing Semantics & Linearization Policy (Finding 4):**
   - Formally documented the linearizability contract in `LoginFailureStateStore` and `PostgresLoginFailureStateStore`: final security state is strictly ordered by transaction commit timestamp.
   - If `clearFailures()` commits after `recordFailure()`, 0 rows remain (clean slate upon successful authentication).
   - If `recordFailure()` commits after `clearFailures()`, failure count resets to 1 (new failed attempt recorded, never leaking pre-clear counts like 5).
   - Strengthened `test35` with explicit final state assertions, and added `test35c_concurrentClearAndFailureStrictOrdering` to verify concurrent transaction ordering.
5. **Disposable Database Ownership Guard for Test Clearing (Finding 5):**
   - Removed reliance on database naming pattern (`contains("_test_")`).
   - Introduced `PostgresAbuseCounterStore.DisposableDatabaseSession` functional interface: `boolean isVerifiedOwner(String databaseName)`.
   - Replaced boolean constructor flag with explicit `DisposableDatabaseSession` instance. In production/default runtime, session is absent, completely disabling global table clearing.
   - Added `test34c_clearRejectsUnownedDatabaseEvenIfNameContainsTest` verifying that clearing an unowned database with a test-like name is strictly rejected with `IllegalStateException`.

**Verification Results:**
- `PostgresLoginRateLimiterIntegrationTest`: **43 tests run, 0 failures, 0 errors, 0 skipped** (8.423s).
- `PostgresAbuseCounterStoreIntegrationTest`: **41 tests run, 0 failures, 0 errors, 0 skipped** (1.815s).
- `AbuseAndLoginFailureJpaIntegrationTest`: **25 tests run, 0 failures, 0 errors, 0 skipped** (6.523s).
- `AuthControllerLoginSecurityTest`: **4 tests run, 0 failures, 0 errors, 0 skipped** (2.308s).
- Total tests passing in Stage A verification: **113 tests**.

**Next Task:**  
`PG-015` — PostgreSQL Signup OTP Challenge Persistence (`COMPLETED`).

---

### [2026-10-26T04:45:00-04:00] PG-015: Concurrency-Safe PostgreSQL Signup OTP Challenge Persistence

**Overview & Objective:**
Implemented **PG-015: PostgreSQL Signup OTP Challenge Persistence**, transitioning signup OTP challenge lifecycle and state transitions to a relational, atomic PostgreSQL store while preserving MongoDB as default runtime persistence and retaining 100% contract compatibility for `AuthController`, `SignupOtpChallengeService`, and existing unit test suites.

**Key Architecture & Implementation Decisions:**
1. **SPI Architecture & Clean Separation of Concerns:**
   - Introduced `SignupOtpChallengeStore` SPI defining challenge storage operations (`findById`, `tryInsert`, `tryAtomicReplaceExpired`, `save`, `deleteById`, `tryAtomicResend`, `tryMarkSendOutcome`, `expireAfterDefiniteInitialFailure`, `tryAtomicMarkVerified`, `tryAtomicRecordFailedVerify`, `tryAtomicLockAfterMaxFailures`).
   - Implemented `MongoSignupOtpChallengeStore` preserving existing MongoDB compare-and-set loops (`findAndModify`), registered as `@Component("mongoSignupOtpChallengeStore")`.
   - Refactored `SignupOtpChallengePersistence` to delegate all database interactions to the SPI while retaining all public helper contracts (`normalizeEmail`, `emailKeyFromNormalized`, `digestOtp`, `otpMatches`, `isChallengeExpired`, `isOtpExpired`, `now`).
   - Preserved the backwards-compatible constructor `SignupOtpChallengePersistence(repository, mongoTemplate, clock)` for direct callers and existing test suites, and added an `@Autowired SignupOtpChallengePersistence(store, clock)` constructor.
2. **PostgreSQL Atomic Conditional Persistence (`PostgresSignupOtpChallengeStore`):**
   - Utilizes real PostgreSQL relational transactions via `@Transactional(transactionManager = "transactionManager")` and `TransactionTemplate`.
   - **Atomic Create-Once Insert:** Executes `INSERT INTO signup_otp_challenges (...) VALUES (...) ON CONFLICT (email_key) DO NOTHING`. Returns `true` when 1 row is affected, `false` on conflict, ensuring that concurrent requests cannot overwrite each other.
   - **Atomic Expired Replacement:** Executes `UPDATE signup_otp_challenges SET ... WHERE email_key = ? AND challenge_expires_at <= ? RETURNING *`. Concurrently competing requests for expired rows yield exactly 1 winner; loser encounters updated `challenge_expires_at` and returns empty without race.
   - **Atomic Resend & OTP Digest Rotation:** Rotates `otp_digest`, refreshes `otp_expires_at` (10m), `next_resend_at` (30s cooldown), reserves a fresh `send_operation_id`, sets `send_status = 'RESERVED'`, refreshes `send_lease_until` (60s), and increments `resend_count` using:
     `UPDATE signup_otp_challenges SET resend_count = resend_count + 1, ... WHERE email_key = ? AND status = 'ACTIVE' AND challenge_expires_at > ? AND resend_count < 5 AND (next_resend_at IS NULL OR next_resend_at <= ?) RETURNING *`.
     Preserves absolute `challenge_expires_at` without extension.
   - **Send Outcome & Lease Handling:** Conditional update `WHERE email_key = ? AND send_operation_id = ? AND send_status = 'RESERVED'` prevents stale provider callbacks from corrupting newer resends.
   - **Initial Failure Expiration:** `expireAfterDefiniteInitialFailure` requires `last_send_was_initial = TRUE` and `DEFINITELY_FAILED`, preventing resend failures from inadvertently expiring the challenge.
   - **OTP Verification Security:** Evaluates `status = 'ACTIVE'`, unexpired timestamps, and direct SQL parameter comparison against `otp_digest = ?`. A rotated OTP or already verified challenge immediately fails atomic verification.
   - **Failed Verification Attempt Counting & Locking:** Increments `failed_verify_attempts` conditionally while `< 5`. At 5 attempts, `tryAtomicLockAfterMaxFailures` transitions status to `LOCKED`. Concurrent attempts cannot exceed the maximum count of 5.
3. **Injected Clock & Timestamp Integrity:**
   - Modified `JpaSignupOtpChallenge`'s `@PreUpdate` to only set `updatedAt = Instant.now()` if null, preventing JPA lifecycle callbacks from clobbering deterministic test clocks.
   - `PostgresSignupOtpChallengeStore` explicitly binds `updated_at` and transition timestamps using `Timestamp.from(now)`.
4. **Configuration Isolation:**
   - Introduced `PostgresSignupOtpPersistenceConfiguration` activated exclusively via `@Profile({"postgres-migration", "test"})` and `@ConditionalOnProperty("app.security.postgres-signup-otp.enabled", havingValue = "true")`.
   - Designates `PostgresSignupOtpChallengeStore` as `@Primary`, ensuring unambiguous resolution when both stores coexist.
   - Default application startup remains 100% on MongoDB.

**Verification Results:**
- **Dedicated PG-015 Integration Suite (`PostgresSignupOtpChallengePersistenceIntegrationTest`):**
  - **61 tests run, 0 failures, 0 errors, 0 skipped** (34.30s).
  - 20-thread simultaneous initial challenge creation: exactly 1 winner, 19 losers, 1 database row.
  - 20-thread simultaneous resend race: exactly 1 winner, `resendCount = 1`.
  - 100-thread concurrent failed verification race: atomic increments capped strictly at 5.
  - 2-thread valid verification race: exactly 1 transitions to VERIFIED.
  - Verification racing with resend digest rotation: old OTP verification cleanly fails.
  - Idempotency replay across SENT, DEFINITELY_FAILED, DELIVERY_UNKNOWN, and IN_FLIGHT.
  - Transactional rollback restoring previous security state.
  - Spring context DI: verified opt-in PostgreSQL selection and default MongoDB selection.
  - Log verification: confirmed no plain OTP codes, raw emails, or digests emitted in Logback logs.
- **Existing MongoDB Signup & Security Regression:**
  - `AuthControllerSignupOtpResponseTest`: **9 tests run, 0 failures, 0 errors, 0 skipped** (0.066s).
  - `SignupOtpChallengeDocumentTest`: **4 tests run, 0 failures, 0 errors, 0 skipped** (0.024s).
  - `SignupAbuseLimiterTest`: **9 tests run, 0 failures, 0 errors, 0 skipped** (0.063s).
  - `SignupOtpChallengeIdempotencyTest`: **6 tests run, 0 failures, 0 errors, 0 skipped** (2.217s).
  - `SignupOtpChallengePersistenceTest`: **19 tests run, 0 failures, 0 errors, 0 skipped** (2.374s).
  - `SignupOtpChallengeVerificationTest`: **8 tests run, 0 failures, 0 errors, 0 skipped** (4.674s).
  - `SignupOtpObservabilityTest`: **3 tests run, 0 failures, 0 errors, 0 skipped** (0.047s).
  - `AuthControllerLoginSecurityTest`: **4 tests run, 0 failures, 0 errors, 0 skipped** (2.225s).
  - Subtotal: **62 tests passing cleanly**.
- **Full 15-Suite PostgreSQL Regression:**
  1. `PostgresConfigurationSmokeTest`: **3** tests
  2. `FlywayMigrationIntegrationTest`: **4** tests
  3. `CoreJpaEntityIntegrationTest`: **7** tests
  4. `OrderJpaEntityIntegrationTest`: **18** tests
  5. `CartReviewReservationJpaIntegrationTest`: **26** tests
  6. `SecurityChallengeJpaIntegrationTest`: **26** tests
  7. `AbuseAndLoginFailureJpaIntegrationTest`: **25** tests
  8. `CoreJpaRepositoryIntegrationTest`: **25** tests
  9. `OperationalJpaRepositoryIntegrationTest`: **31** tests
  10. `SecurityJpaRepositoryIntegrationTest`: **34** tests
  11. `PostgresCatalogInitializerIntegrationTest`: **36** tests
  12. `PostgresCatalogStartupActivationIntegrationTest`: **5** tests
  13. `PostgresAbuseCounterStoreIntegrationTest`: **41** tests (+1 new test)
  14. `PostgresLoginRateLimiterIntegrationTest`: **43** tests (+5 new tests)
  15. `PostgresSignupOtpChallengePersistenceIntegrationTest`: **61** tests (new suite)
  - **Full 15-Suite Regression Total:** **385 tests run, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESS`, 2m 00s).

**Next Task:**  
`PG-015-HARDENING` — Post-PG-015 Security Hardening and Five-Finding Resolution (`COMPLETED`).

---

### [2026-10-26T05:35:00-04:00] PG-015-HARDENING: Post-PG-015 Security Hardening and Five-Finding Resolution

**Overview & Objective:**
Executed **PG-015 Post-Implementation Security Hardening**, addressing five architectural and concurrency findings across the signup OTP challenge state machine, the storage SPI, Flyway integration test lifecycle, and JPA update contracts.

**Five-Finding Resolution Summary:**
1. **Finding 1 — Atomic Failed-Attempt Threshold & Challenge Locking (State-Machine Atomicity across PostgreSQL & MongoDB):**
   - *Problem:* `SignupOtpChallengeService` previously incremented `failed_verify_attempts` to 5 and then invoked `tryAtomicLockAfterMaxFailures` as a separate operation, leaving an intermediate window where `status` remained `ACTIVE` while `failed_verify_attempts == 5`. A concurrent valid verification or resend could theoretically succeed before locking completed.
   - *Resolution:* Made the 5th failed attempt increment and `LOCKED` status transition fully atomic across both stores:
     - `PostgresSignupOtpChallengeStore.tryAtomicRecordFailedVerify` atomically updates `status = 'LOCKED'` within the exact same update statement on the 5th failed attempt (`status = CASE WHEN failed_verify_attempts + 1 >= ? THEN 'LOCKED' ELSE status END`).
     - `MongoSignupOtpChallengeStore.tryAtomicRecordFailedVerify` atomically attempts `lockCriteria` (`failedVerifyAttempts == 4`) with `inc("failedVerifyAttempts", 1).set("status", LOCKED)` so the 5th attempt immediately locks in a single atomic MongoDB operation.
     - Both `PostgresSignupOtpChallengeStore` and `MongoSignupOtpChallengeStore` now enforce `failed_verify_attempts < MAX_FAILED_VERIFY_ATTEMPTS` in both `tryAtomicMarkVerified()` and `tryAtomicResend()`.
     - In `SignupOtpChallengePersistence`, `diagnoseResendDenial` maps `failedVerifyAttempts >= MAX_FAILED_VERIFY_ATTEMPTS` directly to `CHALLENGE_LOCKED`.
     - Added concurrent 5th-failure vs. resend and valid OTP race tests across `SignupOtpChallengeVerificationTest` and `PostgresSignupOtpChallengePersistenceIntegrationTest`.
2. **Finding 2 — Legacy Unguarded Failed-Attempt Updates in Storage Interface:**
   - *Problem:* `SignupOtpChallengeStore.tryAtomicRecordFailedVerify(emailKey, now)` and `tryAtomicLockAfterMaxFailures(emailKey, now)` permitted unguarded updates without binding to challenge ID and OTP digest.
   - *Resolution:* Made guarded overloads mandatory for security operations. Formally deprecated (`@Deprecated`) the unguarded legacy overloads in `SignupOtpChallengeStore`, `SignupOtpChallengePersistence`, `PostgresSignupOtpChallengeStore`, and `MongoSignupOtpChallengeStore`. Migrated all callers across tests to the guarded overloads `tryAtomicRecordFailedVerify(emailKey, challengeId, otpDigest, now)` and `tryAtomicLockAfterMaxFailures(emailKey, challengeId, now)`, while retaining explicit backwards-compatibility test coverage (`test63_legacyUnguardedFailedAttemptAndLockCompatibility`).
3. **Finding 3 — Fail-Closed Resend Cooldown Parity with MongoDB:**
   - *Problem:* `PostgresSignupOtpChallengeStore` previously permitted `(next_resend_at IS NULL OR next_resend_at <= ?)`, which allowed a premature resend if `next_resend_at` was unexpectedly null before cooldown calculation.
   - *Resolution:* Removed `next_resend_at IS NULL`, requiring `next_resend_at <= ?` and establishing strict fail-closed resend cooldown parity with `MongoSignupOtpChallengeStore`.
4. **Finding 4 — Flyway Test Disposable Database Lifecycle and Safe Teardown:**
   - *Problem:* `FlywayMigrationIntegrationTest` previously dropped fixed test database names on setup and teardown, risked collisions under concurrent runs, and silently returned early when `test.postgres.port` was absent (producing false-positive passing tests without exercising PostgreSQL).
   - *Resolution:* Replaced fixed names with uniquely generated disposable databases (`DisposableDatabaseSession`), positive ownership tracking (`createdAndOwned`), safe environment check guardrails (`assertSafeTestEnvironment`), and introduced `getRequiredTestPort()` with JUnit 5 assumptions (`assumeTrue`) and explicit CI failure assertions (`test.postgres.mandatory=true` / `CI=true`), preventing unexercised tests from masquerading as passed.
5. **Finding 5 — Explicit JPA Entity Timestamp Contract:**
   - *Problem:* `JpaSignupOtpChallenge` previously contained conditional logic in `@PreUpdate` that avoided updating `updatedAt` if already set, which risked masking JPA update lifecycles.
   - *Resolution:* Made JPA entity lifecycle contract explicit by unconditionally setting `updatedAt = Instant.now()` on `@PreUpdate`, and verified JPA update behavior in `SecurityJpaRepositoryIntegrationTest`.

**Verification Commands & Results:**
- **Verification Commands:**
  - `mvn test -Dtest=PostgresSignupOtpChallengePersistenceIntegrationTest,SignupOtpChallengeVerificationTest,SignupOtpChallengePersistenceTest "-Dtest.postgres.port=54353"`
  - `mvn test -Dtest=FlywayMigrationIntegrationTest,SecurityJpaRepositoryIntegrationTest "-Dtest.postgres.port=54353"`
  - `mvn test -Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest,AbuseAndLoginFailureJpaIntegrationTest,CoreJpaRepositoryIntegrationTest,OperationalJpaRepositoryIntegrationTest,SecurityJpaRepositoryIntegrationTest,PostgresCatalogInitializerIntegrationTest,PostgresCatalogStartupActivationIntegrationTest,PostgresAbuseCounterStoreIntegrationTest,PostgresLoginRateLimiterIntegrationTest,PostgresSignupOtpChallengePersistenceIntegrationTest "-Dtest.postgres.port=54353"`
- **Results:**
  - `SignupOtpChallengeVerificationTest`: 12 tests run, 0 failures, 0 errors, 0 skipped (including concurrent 5th-attempt lock and resend races).
  - `SignupOtpChallengePersistenceTest`: 20 tests run, 0 failures, 0 errors, 0 skipped.
  - `PostgresSignupOtpChallengePersistenceIntegrationTest`: 64 tests run, 0 failures, 0 errors, 0 skipped (including test 64 concurrent resend vs. 5th failure race).
  - `FlywayMigrationIntegrationTest`: 5 tests run, 0 failures, 0 errors, 0 skipped (disposable database isolation & CI guardrails).
  - `SecurityJpaRepositoryIntegrationTest`: 34 tests run, 0 failures, 0 errors, 0 skipped.
  - Full 15-suite regression: 392 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`).

**Next Task:**  
`PG-015-HARDENING-DB-LIFECYCLE` — Failed-Database-Creation Ownership Guardrail Test (`COMPLETED`).

---

### [2026-10-26T06:30:00-04:00] PG-015-HARDENING-DB-LIFECYCLE: Failed-Database-Creation Ownership Guardrail Test

**Overview & Objective:**
Added explicit failed-database-creation ownership test coverage in `FlywayMigrationIntegrationTest` (`test6_disposableDatabaseSession_failedCreationDoesNotGrantOwnership`) prior to `PG-016` implementation. Verified that failed database provisioning (both offline guardrail rejection and live PostgreSQL duplicate database collisions) never transitions `createdAndOwned` to true, and that subsequent teardown (`drop()`) remains a strict safe NO-OP refusing to terminate or drop unowned/pre-existing databases. Standardized `isCreatedAndOwned()` and `getDbName()` accessors across disposable database sessions.

**Verification Results:**
- `FlywayMigrationIntegrationTest`: 6 tests run (2 run offline, 4 skipped without PostgreSQL port), 0 failures, 0 errors.
- `SignupOtpChallengePersistenceTest`: 20 tests run, 0 failures, 0 errors.
- `SignupOtpChallengeVerificationTest`: 12 tests run, 0 failures, 0 errors.
- Test suite compilation: `BUILD SUCCESS`.

**Next Task:**  
`PG-016` — Refactor `PasswordResetOtpChallengePersistence` and `PasswordResetService` transaction management (`COMPLETED`).

---

### [2026-10-26T06:54:12-04:00] PG-016: PostgreSQL Password Reset OTP Persistence and Transaction Management

**Overview & Architectural Strategy:**
Implemented real PostgreSQL password-reset OTP persistence and single-transaction password mutation coordination under PG-016, following the proven PG-015 storage SPI pattern while preserving 100% backward compatibility with existing MongoDB runtime and controller expectations.

1. **Storage SPI Extraction (`PasswordResetOtpChallengeStore`):**
   - Extracted clean storage interface declaring atomic operations: challenge lookup (`find`, `findByEmailKey`), insert with duplicate key rejection (`tryInsert`), atomic expired/consumed replacement (`tryAtomicReplaceExpired`), atomic resend slot reservation with OTP rotation (`tryReserveResend`), send lease outcome recording (`markSendOutcome`), failed attempt recording (`recordFailedVerification`), atomic 5th-attempt locking (`lockAfterFailures`), correct-code claiming (`tryClaimCorrectCode`), consumption finalization (`finalizeConsumed`), user email lookup (`findUserEmailById`), and atomic execution coordination (`executePasswordResetTransaction`).
   - Implemented `MongoPasswordResetOtpChallengeStore` as the default component with backward-compatible constructors and `ObjectProvider` dependencies.
   - Implemented `PostgresPasswordResetOtpChallengeStore` executing parameterized SQL against `password_reset_otp_challenges` and coordinated user password mutations against `users`.

2. **Opt-in Configuration (`PostgresPasswordResetPersistenceConfiguration`):**
   - Conditioned on `@Profile({"postgres-migration", "test"})` and `@ConditionalOnProperty(name = "app.security.postgres-password-reset.enabled", havingValue = "true")`.
   - Designates `PostgresPasswordResetOtpChallengeStore` as `@Primary`, cleanly overriding the default MongoDB store when active without ambiguous bean conflicts.
   - Provides an independent fallback `transactionManager` bean (`JpaTransactionManager` or `DataSourceTransactionManager`) to guarantee isolation in environments without a pre-existing transaction manager.

3. **High-Risk Single PostgreSQL Transaction Completion (`executePasswordResetTransaction`):**
   - Solved the highest-risk PG-016 requirement: coordinates password update and challenge consumption within a single database transaction managed by Spring's `PlatformTransactionManager`.
   - Execution Sequence:
     1. Acquires row-level write lock via `SELECT ... FROM password_reset_otp_challenges WHERE email_key = ? FOR UPDATE` to eliminate TOCTOU races and guarantee serializability.
     2. Validates challenge identity, purpose, expiration, and code digest.
     3. Claims challenge to `PROCESSING` with deterministic `resetOperationId`.
     4. Updates `users` table: updates `password` hash, increments `token_version` exactly once, sets `password_changed_at`, sets `last_password_reset_operation_id`.
     5. Finalizes `password_reset_otp_challenges` to `CONSUMED`: sets `password_updated = true`, `consumed_at = now`, clears sensitive `code_digest`.
     6. Commits transaction. If user update or challenge finalization fails, calls `status.setRollbackOnly()` ensuring complete rollback.

4. **Security Invariants & Parity Guarantees:**
   - **Cross-Backend Cooldown Parity:** Both MongoDB and PostgreSQL stores enforce strict fail-closed policy when `nextResendAt` is null or in the future (`next_resend_at <= now`), ensuring zero permissive windows.
   - **Atomic 5th Verification Lock:** Incrementing from 4 to 5 failed attempts atomically sets `status = LOCKED` in the same update, ensuring no intermediate ACTIVE window.
   - **Replay Idempotency:** Submitting an already-consumed challenge with the same `resetOperationId` returns `ALREADY_COMPLETED` without re-updating the password or incrementing `token_version` a second time. A different operation ID is rejected.
   - **JWT Revocation:** Password update increments `token_version`, immediately invalidating any prior JWT tokens upon subsequent requests.
   - **Sensitive Data Protection:** Verified that raw OTPs, plaintext passwords, raw IP addresses, and sensitive digests are never emitted in logs.
   - **User Store Isolation:** Preserved MongoDB user authentication in `AuthController` for default runtime until coordinated PG-022 transition; tested PostgreSQL reset paths against real PostgreSQL user records (`JpaUser` / `users` table).

**Files Created / Modified:**
- `backend/src/main/java/com/lordgainsborough/restaurant/service/PasswordResetExecutionResult.java` (created)
- `backend/src/main/java/com/lordgainsborough/restaurant/service/PasswordResetOtpChallengeStore.java` (created)
- `backend/src/main/java/com/lordgainsborough/restaurant/service/MongoPasswordResetOtpChallengeStore.java` (created)
- `backend/src/main/java/com/lordgainsborough/restaurant/service/PostgresPasswordResetOtpChallengeStore.java` (created)
- `backend/src/main/java/com/lordgainsborough/restaurant/config/PostgresPasswordResetPersistenceConfiguration.java` (created)
- `backend/src/main/java/com/lordgainsborough/restaurant/service/PasswordResetOtpChallengePersistence.java` (refactored to delegate to `PasswordResetOtpChallengeStore`)
- `backend/src/main/java/com/lordgainsborough/restaurant/service/PasswordResetService.java` (updated with `ObjectProvider` injection, `requestInitialForJpaUser`, user email resolution fallback, and single-transaction execution delegation)
- `backend/src/test/java/com/lordgainsborough/restaurant/service/PostgresPasswordResetOtpPersistenceIntegrationTest.java` (created 30-test comprehensive integration suite)
- `docs/POSTGRESQL_MIGRATION.md` (updated status table, last updated timestamp, and appended progress log)

**Verification Evidence:**
- Compilation:
  `.\mvnw.cmd test-compile` -> BUILD SUCCESS
- Integration & Unit Regression Suite:
  `.\mvnw.cmd test "-Dtest=PasswordResetSupportServicesTest,SignupOtpChallengeVerificationTest,SignupOtpChallengePersistenceTest,PostgresPasswordResetOtpPersistenceIntegrationTest,FlywayMigrationIntegrationTest"`
  Output:
  - `PostgresPasswordResetOtpPersistenceIntegrationTest`: 30 tests run (3 context/unit tests passed, 27 live DB tests skipped gracefully on missing PostgreSQL port), 0 failures, 0 errors.
  - `SignupOtpChallengePersistenceTest`: 20 tests run, 0 failures, 0 errors.
  - `SignupOtpChallengeVerificationTest`: 12 tests run, 0 failures, 0 errors.
  - `FlywayMigrationIntegrationTest`: 6 tests run (2 unit/offline passed, 4 skipped without port), 0 failures, 0 errors.
  - `PasswordResetSupportServicesTest`: 3 tests run, 0 failures, 0 errors.
  - Total: 71 tests run, 0 failures, 0 errors, 31 skipped (`BUILD SUCCESS`).

### [2026-10-26T17:19:00-04:00] Post-PG-016 Hardening: Rollback Integrity, Fail-Closed Security & Extended Integration Coverage

Following external preflight review, implemented 5 critical hardenings across PostgreSQL and MongoDB password reset stores:
1. **Schema-Compliant Rollback Testing:** Corrected `test23_transactionRollbackOnUserFailure` to inject controlled failure during user update without deleting the parent user (which previously triggered `fk_pwd_reset_user` `ON DELETE CASCADE`). Added dedicated post-update challenge finalization rollback test (`test44_transactionRollbackOnChallengeFinalizeFailure`).
2. **Mixed-Backend Startup Guard:** Implemented explicit startup validation in `PostgresPasswordResetPersistenceConfiguration` and `AuthController` rejecting unsupported mixed-backend states (`app.security.postgres-password-reset.enabled=true` with live MongoDB `UserRepository` authentication in `AuthController` before coordinated `app.security.postgres-user-auth.enabled=true` in PG-022).
3. **MongoDB Rollback-Only Enforcement:** Updated `MongoPasswordResetOtpChallengeStore.executePasswordResetTransaction()` to explicitly invoke `status.setRollbackOnly()` on all failure branches (including empty `finalizeConsumed()`), preventing partial commit anomalies. Introduced `app.auth.password-reset.require-transaction` property.
4. **Storage-Layer Fail-Closed Preconditions:** Enforced independent fail-closed validation on both PostgreSQL and MongoDB transaction stores, rejecting null digests, null expiration timestamps, and non-`PASSWORD_RESET` purposes.
5. **Comprehensive Integration Suite Expansion:** Expanded `PostgresPasswordResetOtpPersistenceIntegrationTest` from 30 to 47 tests (covering resend-vs-complete races, replacement races, forced finalization failure, full service completion, JWT invalidation with token version increment, and Spring DI `JpaTransactionManager` selection) and created `MongoPasswordResetOtpChallengeStoreUnitTest` (8 tests).

**Verification Evidence:**
- `.\mvnw.cmd test "-Dtest=MongoPasswordResetOtpChallengeStoreUnitTest,PostgresPasswordResetOtpPersistenceIntegrationTest,PasswordResetSupportServicesTest,PasswordResetMongoTest"`
  Output: 74 tests executed, 0 failures, 0 errors, 57 skipped (`BUILD SUCCESS`).

### [2026-10-26T17:38:00-04:00] Mixed-Backend Structural Enforcement & Isolation Guard Hardening

Addressed review finding regarding configuration flag bypass in the mixed-backend guard:
1. **Implementation-Level Validation:** Replaced superficial boolean flag check (`postgresUserAuthEnabled`) with actual persistence introspection (`isUsingMongoUserPersistence()`, `!hasPostgresUserAuthentication()`) in `AuthController` and `PostgresPasswordResetPersistenceConfiguration`.
2. **Strict Isolation Until PG-022:** Because `AuthController` injects the MongoDB `UserRepository` and delegates to the MongoDB user overload of `PasswordResetService` (which rejects PostgreSQL reset store), setting `app.security.postgres-user-auth.enabled=true` without actual PostgreSQL user authentication implementation will now be rejected at startup.
3. **Dedicated Test Opt-in:** Added `app.security.postgres-password-reset.allow-isolated-test:false` flag to allow isolated test fixtures while keeping production startup strictly fail-closed.
4. **Regression Verification:** Added `test33_startupGuardRejectsMixedBackendEvenWithPostgresUserAuthFlagEnabled` and `test34_startupGuardPermitsIsolatedTestOptIn`, verifying that starting a context with both flags enabled while MongoDB user authentication is injected fails with `IllegalStateException("Unsupported mixed-backend activation...")`.

**Verification Evidence:**
- `.\mvnw.cmd test "-Dtest=PostgresPasswordResetOtpPersistenceIntegrationTest#test33_startupGuardRejectsMixedBackendEvenWithPostgresUserAuthFlagEnabled,PostgresPasswordResetOtpPersistenceIntegrationTest#test34_startupGuardPermitsIsolatedTestOptIn,PostgresPasswordResetOtpPersistenceIntegrationTest#test36_authControllerEndpointsRejectMixedBackendInvocation"`
  Output: 3 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`).

### [2026-10-26T17:53:00-04:00] Real PostgreSQL Database Verification & 16-Suite Regression Validation

Executed mandatory verification of the full 48-test `PostgresPasswordResetOtpPersistenceIntegrationTest` suite and the full 16-suite PostgreSQL regression against a live disposable PostgreSQL database (`PostgreSQL 17.11` on port 54353) with `test.postgres.mandatory=true`:

1. **Resolution of Real Database Schema Discrepancies:**
   - **Unique Constraint vs Primary Key Conflict (`test05`):** Updated `PostgresPasswordResetOtpChallengeStore.tryInsert()` to use `INSERT ... ON CONFLICT (email_key) DO NOTHING` without catching general `DuplicateKeyException`. A duplicate primary key (`email_key`) returns `false` (0 rows inserted), while an attempt to insert a duplicate `challenge_id` across distinct emails correctly violates `CONSTRAINT uq_pwd_reset_challenge_id UNIQUE (challenge_id)` and throws `DataIntegrityViolationException`.
   - **Schema-Compliant Precondition Testing (`test39`):** Because Flyway V1 defines `challenge_expires_at TIMESTAMP WITH TIME ZONE NOT NULL`, attempting to update this column directly to `NULL` failed with a database constraint violation. Updated `test39` with a safe try/finally block dropping and restoring `NOT NULL` on the disposable database to isolate and verify the application-level fail-closed null expiration check.
   - **Smoke Test Database Provisioning:** Ensured `testdb` exists in the local PostgreSQL instance for `PostgresConfigurationSmokeTest.testHikariDataSource_liveConnectivityIfAvailable`.

2. **Verification Execution Results:**
   - **PostgreSQL Password Reset Suite (48 tests):**
     `.\mvnw.cmd test "-Dtest=PostgresPasswordResetOtpPersistenceIntegrationTest" "-Dtest.postgres.port=54353" "-Dtest.postgres.mandatory=true"`
     **Result:** 48 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 44.39s).
   - **MongoDB Password Reset Unit & Support Suites (11 tests):**
     `.\mvnw.cmd test "-Dtest=MongoPasswordResetOtpChallengeStoreUnitTest,PasswordResetSupportServicesTest"`
     **Result:** 11 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`).
   - **Full 16-Suite PostgreSQL Regression (438 tests):**
     `.\mvnw.cmd test "-Dtest=PostgresConfigurationSmokeTest,FlywayMigrationIntegrationTest,CoreJpaEntityIntegrationTest,OrderJpaEntityIntegrationTest,CartReviewReservationJpaIntegrationTest,SecurityChallengeJpaIntegrationTest,AbuseAndLoginFailureJpaIntegrationTest,CoreJpaRepositoryIntegrationTest,OperationalJpaRepositoryIntegrationTest,SecurityJpaRepositoryIntegrationTest,PostgresCatalogInitializerIntegrationTest,PostgresCatalogStartupActivationIntegrationTest,PostgresAbuseCounterStoreIntegrationTest,PostgresLoginRateLimiterIntegrationTest,PostgresSignupOtpChallengePersistenceIntegrationTest,PostgresPasswordResetOtpPersistenceIntegrationTest" "-Dtest.postgres.port=54353" "-Dtest.postgres.mandatory=true"`
     **Result:** 438 tests run, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 2m 38s).

**Next Task:**  
`PG-017` — Adapt `OrderController` and `OrderAccessService` for JPA persistence and repricing (`NOT STARTED`).

---

## Agent Execution Rules

1. **Single Source of Truth:** `docs/POSTGRESQL_MIGRATION.md` is the authoritative single source of truth for all migration planning, schema decisions, task tracking, and progress logs.
2. **Mandatory Pre-Execution Reading:** Before executing any future implementation batch, read this document to confirm current status, dependencies, and rules.
3. **Strict Batch Limiting:** Work on a maximum of four explicitly assigned tasks per implementation batch. Never exceed four tasks in a single turn or batch.
4. **Real Timestamps Only:** Record an actual system clock timestamp (`America/Toronto`, with correct UTC offset) when starting each task and when logging progress. Never fabricate timestamps.
5. **Atomic Progress Logging:** Update the task tracker table and append a formatted chronological progress log entry immediately after every completed, failed, or blocked task.
6. **No False Completion:** Never mark a task as `COMPLETED` without actually implementing the code, executing the appropriate verification commands, and confirming passing results.
7. **Document Verification Evidence:** Always document exact verification commands, test names, and execution outcomes in the progress log entry.
8. **Explicit Blocker Escalation:** If a task encounters unexpected failures or blockers, immediately mark it `BLOCKED` or `FAILED`, record full error context, and do not silently bypass it.
9. **Contract Preservation:** Retain the exact Next.js frontend contract, REST JSON shapes, HTTP response codes, and Socket.IO events unless explicitly authorized otherwise.
10. **Credential Protection:** Never hardcode database credentials, print raw secrets, or expose sensitive environment variables in logs or documentation.
11. **Technology Discipline:** Do not introduce Prisma, Supabase Auth, or alternative frameworks unless explicitly directed. Use Spring Data JPA, Hibernate, and Supabase PostgreSQL.
12. **Non-Destructive Transition:** Do not remove MongoDB configuration, dependencies, or repositories prematurely while dependent controllers or services still reference them.
13. **Scope Discipline:** Avoid unrelated refactoring, UI redesigns, styling modifications, or unauthorized feature additions during migration tasks.
14. **Dynamic Task Registration:** If implementation reveals necessary subtasks or missing requirements, register them in the roadmap and assign new task IDs (`PG-028`, etc.).
15. **Append-Only Progress History:** Progress log entries are strictly append-only to preserve a transparent audit trail. Historical entries must never be modified or overwritten, with the sole exception of explicit user-authorized consolidation milestones (such as the single consolidated PG-003 closeout entry authorized for final verification cleanup). Future completed tasks must each receive their own chronological entry.
16. **No Autonomous Batch Initiation:** Never mark future work as completed or automatically initiate a subsequent batch without explicit user instruction.
17. **Batch Conclusion Protocol:** When an assigned batch finishes, summarize completed tasks, provide test results, state any outstanding issues, and recommend the next batch.
