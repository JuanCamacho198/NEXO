-- Ownership of an outbox row lived only inside `payload_json`, read back with
-- `json_extract(payload_json, '$.userId')`. The drain (`listSyncOutboxReady`)
-- selected EVERY ready row with no owner filter, so on a shared machine account
-- B's sign-in would flush account A's queued writes and push them to Supabase
-- authenticated as B — A's work misfiled, B's account written to.
--
-- `user_id` is the owner as a first-class column so the drain and the
-- coalescing write path can filter by it directly.
ALTER TABLE sync_outbox ADD COLUMN user_id TEXT;

-- Legacy rows carry their owner in the payload: backfill from there. A payload
-- with no usable `userId` leaves the row ownerless (NULL), which the drain
-- reports rather than drains.
UPDATE sync_outbox
SET user_id = json_extract(payload_json, '$.userId')
WHERE user_id IS NULL
  AND json_valid(payload_json)
  AND json_extract(payload_json, '$.userId') IS NOT NULL
  AND json_extract(payload_json, '$.userId') <> '';

-- The drain filters on owner + readiness; 0013 already indexes next_retry_at
-- alone, which cannot serve a per-owner scan.
CREATE INDEX IF NOT EXISTS idx_sync_outbox_user_next_retry
  ON sync_outbox(user_id, next_retry_at);
