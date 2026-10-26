# Lord Gainsborough PostgreSQL Migration Plan and Progress Journal

> **Single Source of Truth**  
> **Repository:** `Amberverma195/lordgainsborough`  
> **Status:** Active Roadmap & Journal  
> **Last Updated:** 2026-10-25T21:43:00-04:00 (America/Toronto)  

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
| `PG-005` | Phase 6 | Create operational JPA entities (`CustomerOrder`, `OrderItem`, `OrderMessage`) | `COMPLETED` | 2026-10-25T21:37:01-04:00 | 2026-10-25T21:43:00-04:00 | Hibernate 7 `ddl-auto=validate` PASSED against Flyway V1 schema; 12/12 JPA integration tests passed |
| `PG-006` | Phase 6 | Create cart and review JPA entities (`Cart`, `CartItem`, `OrderReview`, `OrderReviewItem`, `Reservation`) | `NOT STARTED` | — | — | Unit tests for cart & review mappings |
| `PG-007` | Phase 7 | Create security and rate-limiting JPA entities (`SignupOtpChallenge`, `PasswordResetOtpChallenge`) | `NOT STARTED` | — | — | Entity validation tests |
| `PG-008` | Phase 7 | Create abuse and failure state JPA entities (`SignupAbuseLimit`, `LoginFailureState`) | `NOT STARTED` | — | — | Entity validation tests |
| `PG-009` | Phase 8 | Implement Spring Data JPA repositories for core and catalog models | `NOT STARTED` | — | — | Repository compilation and method checks |
| `PG-010` | Phase 8 | Implement Spring Data JPA repositories for orders, carts, reviews, and reservations | `NOT STARTED` | — | — | Repository query method tests |
| `PG-011` | Phase 8 | Implement Spring Data JPA repositories for security challenges and abuse limits | `NOT STARTED` | — | — | Repository query tests |
| `PG-012` | Phase 9 | Adapt `DataInitializer.java` to use JPA repositories and relational catalog synchronization | `NOT STARTED` | — | — | `DataInitializerTest` pass |
| `PG-013` | Phase 10 | Implement `PostgresAbuseCounterStore` using atomic PostgreSQL upsert queries | `NOT STARTED` | — | — | Atomic increment unit tests |
| `PG-014` | Phase 10 | Refactor `LoginRateLimiter` to use relational transactional locking | `NOT STARTED` | — | — | Concurrency and cooldown tests |
| `PG-015` | Phase 10 | Refactor `SignupOtpChallengePersistence` for PostgreSQL JPA persistence | `NOT STARTED` | — | — | Challenge lifecycle and idempotency tests |
| `PG-016` | Phase 10 | Refactor `PasswordResetOtpChallengePersistence` and `PasswordResetService` transaction management | `NOT STARTED` | — | — | Password reset transactional tests |
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
