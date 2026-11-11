-- Module 7: reading, confirming and cancelling a hold find its seat by hold_id. Without an index
-- each of those queries scans every seat of every event.
CREATE INDEX IF NOT EXISTS seats_by_hold ON seats (hold_id);
