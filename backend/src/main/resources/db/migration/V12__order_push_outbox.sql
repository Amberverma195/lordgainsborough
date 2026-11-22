CREATE TABLE customer_push_devices (
 device_id VARCHAR(64) PRIMARY KEY,
 user_id VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 session_hash CHAR(64) NOT NULL,
 session_expires_at TIMESTAMPTZ NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE,
 encrypted_token TEXT NOT NULL,
 opted_in BOOLEAN NOT NULL DEFAULT TRUE,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE order_push_outbox (
 id BIGSERIAL PRIMARY KEY,
 device_id VARCHAR(64) NOT NULL REFERENCES customer_push_devices(device_id) ON DELETE CASCADE,
 order_id VARCHAR(64) NOT NULL REFERENCES customer_orders(id) ON DELETE CASCADE,
 status VARCHAR(50) NOT NULL,
 provider_ticket TEXT,
 receipt_checks INT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_until TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(device_id,order_id,status)
);
CREATE INDEX idx_order_push_due ON order_push_outbox(state,next_attempt_at);
CREATE FUNCTION enqueue_order_status_push() RETURNS TRIGGER AS $$
BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status IN ('ACCEPTED','READY','CANCELLED','REJECTED') THEN
  INSERT INTO order_push_outbox(device_id,order_id,status)
  SELECT d.device_id,NEW.id,NEW.status FROM customer_push_devices d JOIN users u ON u.id=d.user_id
  WHERE lower(u.email)=lower(NEW.customer_email) AND d.opted_in
  ON CONFLICT(device_id,order_id,status) DO NOTHING;
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER order_status_push AFTER UPDATE OF status ON customer_orders FOR EACH ROW EXECUTE FUNCTION enqueue_order_status_push();
