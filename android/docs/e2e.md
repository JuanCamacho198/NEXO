# E2E — Maestro

## Local run (DEBUG APK, emulator)

```powershell
# 1. Build DEBUG APK (DEBUG tags: auth_google_mock_button, auth_dev_bypass)
.\gradlew.bat assembleDebug

# 2. Boot emulator and install
.\gradlew.bat installDebug
# or: adb install -r app\build\outputs\apk\debug\app-debug.apk

# 3. Run all auth journeys (id: selectors only, 15s waits)
maestro test maestro/flows/auth/

# 4. Run single journey
maestro test maestro/flows/auth/j01_google_mock.yaml
```

## CI runbook (DEBUG APK)

```powershell
# CI builds DEBUG variant so DEBUG-only tags exist
.\gradlew.bat assembleDebug --no-daemon --console=plain

# Upload app-debug.apk to emulator step, then:
maestro test maestro/flows/auth/ --include-tags auth
# or explicit appId from maestro/config.yaml (appId: com.nexo)
```

## Selector contract

- All flows use `id:` selectors only — no `text:` selectors. Verify:
  ```powershell
  # Should return 0 hits (inputText is allowed, selector text: is not)
  Select-String -Pattern '^\s+text:' android/maestro/flows/auth/*.yaml
  Select-String -Pattern 'text:' android/maestro/flows/auth/j*.yaml | Where-Object { $_.Line -notmatch 'inputText' }
  ```
- Every wait uses `extendedWaitUntil` with `timeout: 15000` (cold-start + Supabase mock token exchange).
- `maestro/config.yaml` holds `appId: com.nexo` — flows omit `appId`.

## Journeys J1-J7

| Journey | File | Scenario |
|---------|------|----------|
| J1 | j01_google_mock.yaml | DEBUG mock button `auth_google_mock_button` -> Home `bottom_nav_home` |
| J2 | j02_email_success.yaml | valid email/password -> Home, no error |
| J3 | j03_email_error.yaml | invalid creds -> `auth_error_text` visible |
| J4 | j04_navigation.yaml | Login <-> Register <-> Forgot via testTags |
| J5 | j05_register_validation.yaml | password <8 blocked (no loading), >=8 shows loading |
| J6 | j06_dev_bypass.yaml | `auth_dev_bypass` -> Home |
| J7 | j07_session_routing.yaml | cold-start with session (clearState:false) -> Home; disabled Google shows `auth_google_disabled_reason` |

## TestTags

Single source: `AuthTags`, `HomeTags`, `NavTags` in `presentation/screen/AuthTags.kt`. Common tags exist in all builds; `GOOGLE_MOCK` and `DEV_BYPASS` only when `BuildConfig.DEBUG`.

## Verification

```powershell
.\gradlew.bat verifyAuthScreenNoHardcodedStrings
.\gradlew.bat testDebugUnitTest --tests "*AuthViewModelTest*"
.\gradlew.bat detekt
```
