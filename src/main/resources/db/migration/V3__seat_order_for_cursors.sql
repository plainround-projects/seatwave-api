-- Module 7: cursor pagination needs a stable, unique order that matches the seat map.
-- seat_label sorts as text (A-1, A-10, A-100, A-2), so a numeric column is added and filled
-- from the label (expand and migrate); new seats get it from the code that inserts them.
ALTER TABLE seats ADD COLUMN IF NOT EXISTS seat_number INT;

UPDATE seats SET seat_number = CAST(SUBSTRING(seat_label, 3) AS INT);

ALTER TABLE seats ALTER COLUMN seat_number SET NOT NULL;

-- The index the keyset query walks: equality on the event, then the order of the cursor.
CREATE INDEX IF NOT EXISTS seats_in_map_order ON seats (event_id, section, seat_number);

-- The event list is paged by start time, then id, so two events at one time keep an order.
CREATE INDEX IF NOT EXISTS events_in_start_order ON events (starts_at, event_id);
