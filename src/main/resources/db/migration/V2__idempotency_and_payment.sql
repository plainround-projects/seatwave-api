-- Module 7: a retried write must not act twice, and a confirm now takes a payment (Module 2).
-- Expand only: new table, new nullable columns. Module 1 code would still run against this schema.

-- One row per (endpoint, key). The primary key is the invariant: one key, at most one effect.
-- The row is written in the same transaction as the effect it records.
CREATE TABLE IF NOT EXISTS idempotency_keys (
  endpoint VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  state VARCHAR(12) NOT NULL,
  response_status INT,
  response_location VARCHAR(80),
  response_body VARCHAR(2048),
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  CONSTRAINT one_effect_per_key PRIMARY KEY (endpoint, idempotency_key),
  CONSTRAINT known_key_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
  CONSTRAINT completed_key_has_response
    CHECK (state <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);

-- The purge deletes by expiry; without this index it would scan every key.
CREATE INDEX IF NOT EXISTS idempotency_keys_by_expiry ON idempotency_keys (expires_at);

-- While a confirm waits for the payment provider, the seat is CONFIRMING until this time.
-- After it, another attempt may take over; the provider's own key stops a second charge.
ALTER TABLE seats ADD COLUMN IF NOT EXISTS confirm_lease_until BIGINT;

ALTER TABLE seats ADD CONSTRAINT IF NOT EXISTS known_seat_status
  CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMING', 'CONFIRMED'));

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS hold_id VARCHAR(40);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS payment_id VARCHAR(64);
-- Money as an exact decimal, never a floating-point number.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS amount DECIMAL(12, 2);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS currency CHAR(3);
