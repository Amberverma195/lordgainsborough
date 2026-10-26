-- =============================================================================
-- Lord Gainsborough Restaurant API - PostgreSQL Relational Schema
-- Migration: V1__init_relational_schema.sql
-- Description: Complete initial relational schema migrated from MongoDB collections.
-- Target Database: Supabase PostgreSQL 15+
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Users & Authentication
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                               VARCHAR(64) PRIMARY KEY,
    name                             VARCHAR(255),
    email                            VARCHAR(255) NOT NULL,
    phone                            VARCHAR(50),
    phone_normalized                 VARCHAR(20),
    password                         VARCHAR(255),
    role                             VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
    member_since                     VARCHAR(50),
    created_at                       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_profile_update_at           TIMESTAMP WITHOUT TIME ZONE,
    token_version                    BIGINT NOT NULL DEFAULT 0,
    password_changed_at              TIMESTAMP WITH TIME ZONE,
    last_password_reset_operation_id VARCHAR(64),
    no_show_count                    INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_phone_normalized UNIQUE (phone_normalized),
    CONSTRAINT chk_users_token_version CHECK (token_version >= 0),
    CONSTRAINT chk_users_no_show_count CHECK (no_show_count >= 0)
);

-- Case-insensitive unique constraint on email.
-- Enforces that 'User@example.com' and 'user@example.com' collide at the database level.
CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));

CREATE INDEX idx_users_name ON users (name);
CREATE INDEX idx_users_role ON users (role);

-- -----------------------------------------------------------------------------
-- 2. Menu Catalog & Categories
-- -----------------------------------------------------------------------------
CREATE TABLE categories (
    id            VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    display_order INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT chk_categories_display_order CHECK (display_order >= 0)
);

CREATE INDEX idx_categories_display_order ON categories (display_order);

CREATE TABLE menu_items (
    id            VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    price         NUMERIC(10, 2) NOT NULL,
    category      VARCHAR(255) NOT NULL,
    image         VARCHAR(1024),
    available     BOOLEAN NOT NULL DEFAULT TRUE,
    is_most_loved BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_menu_items_price CHECK (price >= 0.00)
);

CREATE INDEX idx_menu_items_category ON menu_items (category);
CREATE INDEX idx_menu_items_name_lower ON menu_items (LOWER(name));
CREATE INDEX idx_menu_items_available ON menu_items (available);

CREATE TABLE catalog_migrations (
    id         VARCHAR(128) PRIMARY KEY,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- 3. Store Operational Settings
-- -----------------------------------------------------------------------------
CREATE TABLE store_settings (
    id                     VARCHAR(64) PRIMARY KEY DEFAULT 'default',
    tax_rate               NUMERIC(5, 4) NOT NULL DEFAULT 0.1300,
    default_ready_minutes  INT NOT NULL DEFAULT 25,
    store_name             VARCHAR(255) NOT NULL DEFAULT 'Lord Gainsborough',
    contact_phone          VARCHAR(50) DEFAULT '',
    contact_email          VARCHAR(255) DEFAULT '',
    pickup_orders_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    kds_allowed_ip         TEXT DEFAULT '',
    CONSTRAINT chk_store_settings_tax_rate CHECK (tax_rate >= 0.0000 AND tax_rate <= 1.0000),
    CONSTRAINT chk_store_settings_default_ready_minutes CHECK (default_ready_minutes > 0)
);

-- -----------------------------------------------------------------------------
-- 4. Customer Orders & Order Sub-Tables
-- -----------------------------------------------------------------------------
CREATE TABLE customer_orders (
    id                    VARCHAR(64) PRIMARY KEY,
    customer_name         VARCHAR(255) NOT NULL,
    customer_email        VARCHAR(255) NOT NULL,
    customer_phone        VARCHAR(50) NOT NULL,
    pickup_date           VARCHAR(20) NOT NULL,
    pickup_time           VARCHAR(20),
    special_instructions  TEXT,
    status                VARCHAR(50) NOT NULL DEFAULT 'NEW',
    average_ready_minutes INT NOT NULL DEFAULT 25,
    subtotal              NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    tax                   NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    total                 NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at           TIMESTAMP WITHOUT TIME ZONE,
    ready_at              TIMESTAMP WITHOUT TIME ZONE,
    picked_up_at          TIMESTAMP WITHOUT TIME ZONE,
    no_show_at            TIMESTAMP WITHOUT TIME ZONE,
    reviewed              BOOLEAN NOT NULL DEFAULT FALSE,
    tracking_token_hash   VARCHAR(255),
    CONSTRAINT chk_orders_subtotal CHECK (subtotal >= 0.00),
    CONSTRAINT chk_orders_tax CHECK (tax >= 0.00),
    CONSTRAINT chk_orders_total CHECK (total >= 0.00),
    CONSTRAINT chk_orders_average_ready_minutes CHECK (average_ready_minutes > 0)
);

CREATE INDEX idx_orders_pickup_date ON customer_orders (pickup_date);
CREATE INDEX idx_orders_status ON customer_orders (status);
CREATE INDEX idx_orders_created_at ON customer_orders (created_at DESC);
CREATE INDEX idx_orders_email_status ON customer_orders (LOWER(customer_email), status);
CREATE INDEX idx_orders_phone_status ON customer_orders (customer_phone, status);

CREATE TABLE order_items (
    id           VARCHAR(64) PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL,
    item_index   INT NOT NULL DEFAULT 0,
    menu_item_id VARCHAR(64),
    name         VARCHAR(255) NOT NULL,
    quantity     INT NOT NULL,
    unit_price   NUMERIC(10, 2) NOT NULL,
    total        NUMERIC(10, 2) NOT NULL,
    notes        TEXT,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id)
        REFERENCES customer_orders (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price CHECK (unit_price >= 0.00),
    CONSTRAINT chk_order_items_total CHECK (total >= 0.00),
    CONSTRAINT chk_order_items_item_index CHECK (item_index >= 0)
);

-- Note: (order_id, item_index) composite index efficiently covers both foreign key lookups
-- (leading column order_id) and ordered array retrieval, while avoiding non-deferred uniqueness issues during Hibernate @OrderColumn updates.
CREATE INDEX idx_order_items_order_index ON order_items (order_id, item_index);

CREATE TABLE order_messages (
    id          VARCHAR(64) PRIMARY KEY,
    order_id    VARCHAR(64) NOT NULL,
    item_index  INT NOT NULL DEFAULT 0,
    sender_role VARCHAR(50) NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    text        TEXT NOT NULL,
    created_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_messages_order FOREIGN KEY (order_id)
        REFERENCES customer_orders (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_messages_item_index CHECK (item_index >= 0)
);

-- Note: (order_id, created_at) composite index covers foreign key lookups on order_id
-- and chronological conversation history queries without a redundant single-column index.
CREATE INDEX idx_order_messages_created_at ON order_messages (order_id, created_at);

-- -----------------------------------------------------------------------------
-- 5. Persistent Carts & Cart Items
-- -----------------------------------------------------------------------------
CREATE TABLE carts (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_carts_user_id UNIQUE (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_carts_updated_at ON carts (updated_at);

CREATE TABLE cart_items (
    id           VARCHAR(64) PRIMARY KEY,
    cart_id      VARCHAR(64) NOT NULL,
    item_index   INT NOT NULL DEFAULT 0,
    menu_item_id VARCHAR(64),
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    price        NUMERIC(10, 2) NOT NULL,
    category     VARCHAR(255),
    image        VARCHAR(1024),
    quantity     INT NOT NULL,
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id)
        REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT chk_cart_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_cart_items_price CHECK (price >= 0.00),
    CONSTRAINT chk_cart_items_item_index CHECK (item_index >= 0)
);

-- Note: (cart_id, item_index) covers foreign key lookups on cart_id and preserves display order.
CREATE INDEX idx_cart_items_cart_index ON cart_items (cart_id, item_index);

-- -----------------------------------------------------------------------------
-- 6. Order Reviews & Review Items
-- -----------------------------------------------------------------------------
CREATE TABLE order_reviews (
    id             VARCHAR(64) PRIMARY KEY,
    order_id       VARCHAR(64) NOT NULL,
    customer_name  VARCHAR(255) NOT NULL,
    customer_email VARCHAR(255) NOT NULL,
    customer_phone VARCHAR(50) NOT NULL,
    status         VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_order_reviews_order_id UNIQUE (order_id),
    CONSTRAINT fk_order_reviews_order FOREIGN KEY (order_id)
        REFERENCES customer_orders (id) ON DELETE CASCADE
);

CREATE INDEX idx_order_reviews_status ON order_reviews (status);
CREATE INDEX idx_order_reviews_created_at ON order_reviews (created_at DESC);

CREATE TABLE order_review_items (
    id           VARCHAR(64) PRIMARY KEY,
    review_id    VARCHAR(64) NOT NULL,
    item_index   INT NOT NULL DEFAULT 0,
    menu_item_id VARCHAR(64),
    item_name    VARCHAR(255) NOT NULL,
    rating       INT NOT NULL,
    description  TEXT,
    CONSTRAINT fk_order_review_items_review FOREIGN KEY (review_id)
        REFERENCES order_reviews (id) ON DELETE CASCADE,
    CONSTRAINT chk_order_review_items_rating CHECK (rating >= 1 AND rating <= 5),
    CONSTRAINT chk_order_review_items_item_index CHECK (item_index >= 0)
);

-- Note: (review_id, item_index) covers foreign key lookups on review_id and preserves display order.
CREATE INDEX idx_order_review_items_review_index ON order_review_items (review_id, item_index);

-- -----------------------------------------------------------------------------
-- 7. Dining Room Reservations
-- -----------------------------------------------------------------------------
CREATE TABLE reservations (
    id               VARCHAR(64) PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    phone            VARCHAR(50) NOT NULL,
    date             VARCHAR(20) NOT NULL,
    time             VARCHAR(20) NOT NULL,
    party_size       INT NOT NULL,
    special_requests TEXT,
    status           VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_reservations_party_size CHECK (party_size > 0)
);

CREATE INDEX idx_reservations_date ON reservations (date);
CREATE INDEX idx_reservations_email ON reservations (email);
CREATE INDEX idx_reservations_phone ON reservations (phone);
CREATE INDEX idx_reservations_created_at ON reservations (created_at DESC);

-- -----------------------------------------------------------------------------
-- 8. Signup OTP Challenges
-- -----------------------------------------------------------------------------
CREATE TABLE signup_otp_challenges (
    email_key              VARCHAR(64) PRIMARY KEY,
    normalized_email       VARCHAR(255) NOT NULL,
    challenge_id           VARCHAR(64) NOT NULL,
    status                 VARCHAR(50) NOT NULL,
    otp_digest             VARCHAR(255) NOT NULL,
    resend_count           INT NOT NULL DEFAULT 0,
    failed_verify_attempts INT NOT NULL DEFAULT 0,
    next_resend_at         TIMESTAMP WITH TIME ZONE,
    otp_expires_at         TIMESTAMP WITH TIME ZONE,
    challenge_expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    send_operation_id      VARCHAR(64),
    send_status            VARCHAR(50),
    send_lease_until       TIMESTAMP WITH TIME ZONE,
    client_idempotency_key VARCHAR(128),
    last_send_was_initial  BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_signup_otp_resend_count CHECK (resend_count >= 0),
    CONSTRAINT chk_signup_otp_failed_verify CHECK (failed_verify_attempts >= 0)
);

CREATE INDEX idx_signup_otp_challenge_id ON signup_otp_challenges (challenge_id);
CREATE INDEX idx_signup_otp_challenge_expires ON signup_otp_challenges (challenge_expires_at);
CREATE INDEX idx_signup_otp_idempotency ON signup_otp_challenges (client_idempotency_key);

-- -----------------------------------------------------------------------------
-- 9. Password Reset OTP Challenges
-- -----------------------------------------------------------------------------
CREATE TABLE password_reset_otp_challenges (
    email_key                    VARCHAR(64) PRIMARY KEY,
    challenge_id                 VARCHAR(64) NOT NULL,
    user_id                      VARCHAR(64) NOT NULL,
    purpose                      VARCHAR(50) NOT NULL,
    status                       VARCHAR(50) NOT NULL,
    code_digest                  VARCHAR(255),
    code_expires_at              TIMESTAMP WITH TIME ZONE,
    challenge_expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    next_resend_at               TIMESTAMP WITH TIME ZONE,
    resend_count                 INT NOT NULL DEFAULT 0,
    failed_verification_attempts INT NOT NULL DEFAULT 0,
    client_idempotency_key       VARCHAR(128),
    send_operation_id            VARCHAR(64),
    send_status                  VARCHAR(50),
    send_lease_until             TIMESTAMP WITH TIME ZONE,
    reset_operation_id           VARCHAR(64),
    password_updated             BOOLEAN NOT NULL DEFAULT FALSE,
    consumed_at                  TIMESTAMP WITH TIME ZONE,
    created_at                   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_pwd_reset_challenge_id UNIQUE (challenge_id),
    CONSTRAINT fk_pwd_reset_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_pwd_reset_resend_count CHECK (resend_count >= 0),
    CONSTRAINT chk_pwd_reset_failed_verify CHECK (failed_verification_attempts >= 0)
);

CREATE INDEX idx_pwd_reset_challenge_expires ON password_reset_otp_challenges (challenge_expires_at);
CREATE INDEX idx_pwd_reset_user_id ON password_reset_otp_challenges (user_id);
CREATE INDEX idx_pwd_reset_idempotency ON password_reset_otp_challenges (client_idempotency_key);

-- -----------------------------------------------------------------------------
-- 10. Security & Abuse Protection
-- -----------------------------------------------------------------------------
CREATE TABLE signup_abuse_limits (
    id           VARCHAR(128) PRIMARY KEY,
    bucket_type  VARCHAR(50) NOT NULL,
    subject_hash VARCHAR(128) NOT NULL,
    count        BIGINT NOT NULL DEFAULT 1,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_signup_abuse_count CHECK (count >= 0)
);

CREATE INDEX idx_abuse_limits_expires_at ON signup_abuse_limits (expires_at);
CREATE INDEX idx_abuse_limits_bucket_subject ON signup_abuse_limits (bucket_type, subject_hash);

CREATE TABLE login_failure_states (
    id                VARCHAR(128) PRIMARY KEY,
    subject_hash      VARCHAR(128) NOT NULL,
    failure_count     INT NOT NULL DEFAULT 1,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until     TIMESTAMP WITH TIME ZONE,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_login_failure_count CHECK (failure_count >= 0)
);

CREATE INDEX idx_login_failure_expires_at ON login_failure_states (expires_at);
CREATE INDEX idx_login_failure_blocked_until ON login_failure_states (blocked_until);
