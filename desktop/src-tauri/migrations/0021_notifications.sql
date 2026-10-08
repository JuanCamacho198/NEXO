-- 0021_notifications: durable local notification history with per-item read
-- state (NOTIF-02). A dedicated table, not an `app_settings` JSON blob, because
-- history is queried (newest-first), ordered, updated per item (read_at) and
-- pruned by retention. `created_at`/`read_at` are epoch milliseconds; the
-- `i18n_params` and `target` columns hold opaque JSON text so localization keys
-- and navigation targets round-trip untouched until NOTIF-04/NOTIF-06 consume
-- them.
CREATE TABLE IF NOT EXISTS notifications (
  id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  source TEXT NOT NULL,
  category TEXT NOT NULL CHECK (category IN ('system', 'nudge')),
  severity TEXT NOT NULL CHECK (severity IN ('success', 'info', 'warning', 'error')),
  interruption TEXT NOT NULL CHECK (interruption IN ('silent', 'in-app', 'system')),
  i18n_key TEXT NOT NULL,
  i18n_params TEXT,
  target TEXT,
  read_at INTEGER,
  dedup_key TEXT
);

-- Newest-first listing is the only read pattern (tray + retention).
CREATE INDEX IF NOT EXISTS idx_notifications_created_at
  ON notifications(created_at DESC, id DESC);

-- Unread lookup for the badge; partial because read rows never need it.
CREATE INDEX IF NOT EXISTS idx_notifications_unread
  ON notifications(read_at)
  WHERE read_at IS NULL;

-- Dedup snapshot rebuild after a restart.
CREATE INDEX IF NOT EXISTS idx_notifications_dedup_key
  ON notifications(dedup_key)
  WHERE dedup_key IS NOT NULL;
