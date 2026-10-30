-- -----------------------------------------------------------------------------
-- V5: Order Idempotency Key
-- Prevents duplicate customer orders from network retries or connection drops.
-- -----------------------------------------------------------------------------

ALTER TABLE customer_orders
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_idempotency_key
    ON customer_orders (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
