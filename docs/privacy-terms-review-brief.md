# Privacy Policy and Terms — review brief

Written to make the legal review cheap. Everything in the two documents was
derived from what the code actually does, and each factual claim below says where
it can be verified. **Neither document has been reviewed by a lawyer.**

## What to review

| Document | Source of the copy | Public URL |
|---|---|---|
| Privacy Policy (ES) | `nexo-web/src/i18n/es.ts`, keys `privacy.*` | `/privacidad` |
| Privacy Policy (EN) | `nexo-web/src/i18n/en.ts`, keys `privacy.*` | `/en/privacidad` |
| Terms of Service (ES) | `nexo-web/src/i18n/es.ts`, keys `terms.*` | `/terminos` |
| Terms of Service (EN) | `nexo-web/src/i18n/en.ts`, keys `terms.*` | `/en/terminos` |

The page shells are `nexo-web/src/pages/[...locale]/privacidad.astro` and
`terminos.astro`; they render dictionary strings and contain no legal text of
their own. Both locales carry identical keys, so reviewing the English and
checking that the Spanish says the same thing is sufficient.

## What the app does with data — verified, with where to check it

| Data | Where it goes | How it was verified |
|---|---|---|
| Account (email + password, or Google sign-in) | Supabase Auth | `android/.../data/session/`, `data/remote/supabase/`; `supabase/migrations/` |
| Books, highlights, notes, reading progress | Supabase Postgres and Storage; mirrored locally | `data/repository/`, `data/local/` (Room), `desktop/src-tauri/migrations/` |
| Imported files (EPUB/PDF) | On device, plus Supabase Storage when signed in | `data/storage/`, `data/pdf/`, `data/epub/` |
| Google account access | Google OAuth for sign-in; Google Drive only when the user connects it, for library backup/sync | `data/remote/google/`, `data/remote/drive/`; `BuildConfig.GOOGLE_OAUTH_CLIENT_ID` |
| Drive layout | A `Nexo/Books` folder in the user's own Drive, plus `nexo_cold_backup.json` | `DriveCatalogContract.BOOKS_PATH`, `GoogleDriveConfig.DRIVE_BOOKS_PATH`, `DriveColdBackupService.COLD_BACKUP_FILE` |
| Crash and error reports | Sentry (Android and desktop) | `build.gradle.kts` `SENTRY_DSN`; `desktop/src-tauri/src/sentry_init.rs` |
| Third-party analytics or advertising | **None** | Audited: no analytics/ads SDK in any manifest; the only `segment`/`gtag` matches in the tree are string-splitting and an `existingTag` identifier, not those vendors |

**Processors to name:** Supabase (authentication, database, file storage),
Google (sign-in, and Drive only when connected), Sentry (crash reports).

## Claims in the copy and their status

- "Supabase hosts authentication, database and storage" — **verified** in code.
- "Google OAuth is used for sign-in and Drive access" — **verified**.
- "Sentry receives crash reports" — **verified**.
- "Data is stored locally and, when signed in, in the account's storage" — **verified**.
- "Not directed at children" — **asserted**, not enforced by any age gate in code.
- "Data is encrypted" — **asserted at the level of the transport and the
  providers' at-rest encryption.** The app does not add its own application-level
  encryption on top, so a blanket "your data is encrypted" is weaker than it
  sounds. Worth the reviewer's judgement.
- Support-only contact for data-subject requests — **asserted**; there is no
  self-service export or deletion flow in the app.

A separate overstated claim was found and fixed while writing this brief: the
landing FAQ answered "who can see my library?" with "never shared with third
parties", which is false as written because Supabase, Google and Sentry all
process the data. It now scopes that claim to the services the app needs.

## Open questions only a lawyer can settle

1. **Lawful basis and jurisdiction.** The copy names no jurisdiction and no
   governing law, and the operator is an individual, not a company. Terms
   normally need a governing-law and venue clause; the policy normally needs a
   lawful basis per processing purpose.
2. **Retention.** The copy states no retention periods. Real ones exist implicitly
   (data lives until the account is deleted) but are not documented.
3. **International transfers.** Supabase, Google and Sentry infrastructure
   locations are not stated, and no transfer mechanism is referenced.
4. **Whether a DPA is on file** with each processor, and whether its terms should
   be linked.
5. **Age threshold and children's data.** Currently a plain assertion.
6. **Data-subject rights mechanics.** What the process actually is when someone
   emails the support address, and how long it takes.
7. **Is "your data is encrypted" defensible** as written, or should it be scoped
   to "encrypted in transit and by our providers at rest".

## Known gaps, stated honestly

- No lawyer has read any of this.
- No retention schedule, no named jurisdiction, no DPO (individual operator), no
  stated breach-notification process.
- The policy describes what the code does today; a behavioural change in the app
  should trigger a review of the matching paragraph.
- The OAuth consent screen must not be submitted for verification with Drive
  scope until this review happens: Google checks the policy, and Drive is a
  restricted scope.
