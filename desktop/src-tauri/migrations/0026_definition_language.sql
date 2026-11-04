-- 0026_definition_language: the per-account language a definition is written in.
--
-- Why a column on `translation_preferences` and not a second table: the
-- preference is per-account, singular, and read on the same event as the
-- translation pair (every "Definir" and every "Traducir" off the selection
-- menu). Two tables would mean two locks, two upserts and a second place for
-- the account-scoping rule to be forgotten; a second row in the same table
-- would fight the UNIQUE index on `user_id`.
--
-- The ALTER arrives after 0024, so it is the migration that owns the column and
-- the row it lands on already carries the account. NULL means "infer": the
-- definition is written in the app's UI locale, which is what the feature did
-- before this column existed, so an existing row keeps its behaviour with no
-- backfill. See `repository/translation.rs::save_definition_language`.

ALTER TABLE translation_preferences ADD COLUMN definition_language TEXT;
