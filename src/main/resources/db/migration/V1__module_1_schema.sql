-- Module 1's three tables, unchanged: the API in front of them changes, the rule inside them does not.
CREATE TABLE IF NOT EXISTS events (
  event_id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  starts_at BIGINT NOT NULL
);

-- The status column is the single source of truth for a seat. Java never decides alone.
CREATE TABLE IF NOT EXISTS seats (
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  section VARCHAR(4) NOT NULL,
  status VARCHAR(12) NOT NULL,
  hold_id VARCHAR(40),
  hold_customer VARCHAR(64),
  hold_expires_at BIGINT,
  booking_id VARCHAR(40),
  PRIMARY KEY (event_id, seat_label)
);

-- Second guard: even a bug in the confirm logic cannot create two bookings for one seat.
CREATE TABLE IF NOT EXISTS bookings (
  booking_id VARCHAR(40) PRIMARY KEY,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer VARCHAR(64) NOT NULL,
  confirmed_at BIGINT NOT NULL,
  CONSTRAINT one_booking_per_seat UNIQUE (event_id, seat_label)
);

CREATE INDEX IF NOT EXISTS seats_by_section ON seats (event_id, section);
