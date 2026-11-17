# Desktop code signing

Status: **configuration and CI wiring are in place. No certificate has been purchased and no signed artefact has ever been produced by this repository.** Everything below the "Operator actions" heading is unstarted work that only a human with a payment method and an Apple Developer account can do.

Audit reference: `odd/tasks/desktop-security-checklist-audit.md` item #16 ("Zero code signing"). Before this change `bundle.windows`, `bundle.macOS` and `bundle.linux` were all `null`, and CI carried only `TAURI_SIGNING_PRIVATE_KEY` — which signs the **updater manifest**, not the binary. Those are two unrelated trust chains:

| Chain | What it proves | Where it lives |
| --- | --- | --- |
| Updater signature (`TAURI_SIGNING_PRIVATE_KEY`) | "this update payload came from the holder of the minisign key" | `plugins.updater.pubkey` in `tauri.conf.json` |
| OS code signature (Authenticode / Developer ID) | "this binary came from the registered publisher NEXO" | Certificate + `bundle.windows` / `bundle.macOS` |

Signing the updater manifest does not remove a single SmartScreen or Gatekeeper prompt.

## What was configured

### `desktop/src-tauri/tauri.conf.json`

```json
"windows": {
  "digestAlgorithm": "sha256",
  "timestampUrl": "http://timestamp.digicert.com",
  "tsp": true
},
"macOS": {
  "signingIdentity": "-",
  "hardenedRuntime": true,
  "entitlements": "entitlements.plist"
}
```

Key names verified against the vendored config struct, **not** guessed: `tauri-utils 2.9.3` (`desktop/src-tauri/Cargo.lock:4971`) at `src/config.rs`, `struct WindowsConfig` (`certificate_thumbprint`, `digest_algorithm`, `timestamp_url`, `tsp`, `sign_command`) and `struct MacConfig` (`signing_identity`, `hardened_runtime`, `entitlements`, `provider_short_name`). Both structs are `#[serde(deny_unknown_fields)]`, so a misspelled key is a **build failure**, not a silent no-op — verified by deliberately typing `certificateThumprint` and watching `cargo check` fail with `unknown field ... expected one of ...`.

`signingIdentity: "-"` is not a placeholder to change by hand. It is the literal Tauri uses for "sign ad-hoc" (equivalent to `codesign -s -`). Locally that is all that is available without a certificate; in CI the `APPLE_SIGNING_IDENTITY` environment variable **overrides** the config value, which is why the committed file can stay certificate-free while release builds sign for real. Hardened runtime is the same story: it is on so local builds practice the configuration that release builds use.

`certificateThumbprint` is deliberately **absent** from the committed config. The certificate does not exist yet, and a thumbprint committed today would be stale the first time the certificate is renewed — and a thumbprint that matches no imported certificate makes `signtool` fail mid-build. The release workflow imports the `.pfx`, reads the thumbprint, and passes it to Tauri with `--config` (`release-builds.yml`, step `Prepare the Windows signing config patch`).

### `desktop/src-tauri/entitlements.plist`

The app needs **no sandbox entitlements**: it is not distributed through the Mac App Store, it is not sandboxed, and its network access (Supabase, Google OAuth, Project Gutenberg, Open Library) and file access (user-chosen EPUB imports via the dialog plugin) are unconstrained outside a sandbox. Writing `com.apple.security.network.client` or `com.apple.security.files.user-selected.read-write` into the file would be cargo-culting — those are *sandbox* entitlements and do nothing on a hardened-runtime, non-sandboxed app.

Three hardened-runtime exceptions **are** needed, because the webview is a JavaScript engine:

| Entitlement | Why |
| --- | --- |
| `com.apple.security.cs.allow-jit` | WKWebView's JavaScriptCore allocates executable pages. Without it the app dies on the first script. |
| `com.apple.security.cs.allow-unsigned-executable-memory` | Some JIT paths map memory that is not signed. |
| `com.apple.security.cs.disable-library-validation` | Tauri loads the bundled webview process and dynamically linked libraries; library validation rejects anything not signed by the same Team ID. |

These are the standard hardened-runtime set for any Electron- or WKWebView-based app on a Developer ID build. They weaken hardened runtime slightly (unsigned executable memory is what malware wants too), which is exactly why they belong in a reviewed file rather than scattered across build flags.

If the entitlement file turns out not to be required, the failure is loud and immediate (`codesign` fails, or the app crashes on first paint with a `EXC_BAD_ACCESS` in JavaScriptCore) — not a silent degradation.

## Which certificate to buy

Three real options, in ascending order of cost and descending order of friction:

### Windows — Azure Trusted Signing (recommended)

Microsoft's managed signing service. No certificate file to store, no hardware token, no expiry cliff to discover at 2 a.m. You create a Trusted Signing account, a certificate profile, and grant a service principal the `Trusted Signing Certificate Profile Signer` role. Signing happens through `signtool` with a `/dlib` plugin.

- Cost: roughly $10/month for the basic tier (individual or organisation validation).
- Reputation: SmartScreen treats Azure Trusted Signing certificates the **same** as OV certificates — no instant reputation. There is no free pass.
- Fit for this repo: it is the only option that does not require a secret on disk, and the `sign_command` key exists for it. **Not wired here**, because it needs the account to exist before the command can be written, and this task cannot create one. Wiring it later is one `bundle.windows.signCommand` block plus three secrets (`AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`), and the whole `WINDOWS_CERTIFICATE` path in the workflow goes away.

### Windows — OV certificate (what the workflow currently expects)

A standard Organisation Validation Authenticode certificate from DigiCert, Sectigo, GlobalSign or SSL.com. Since June 2023 these **must** be stored on a FIPS 140-2 Level 2 hardware token (a USB dongle) — you cannot get a plain `.pfx` in an email any more. That is a hard constraint, not a vendor quirk: the CA is required by the CA/Browser Forum baseline requirements to enforce it.

Practical consequence: **CI cannot use the dongle.** The `.pfx` this workflow expects must be exported from the token, which the CA's tooling only permits into a non-exportable keystore unless you buy the "cloud HSM" tier. So an OV certificate with GitHub-hosted runners is genuinely awkward, and the honest recommendation is Azure Trusted Signing or a self-hosted runner with the token attached. Documented here so nobody discovers it after paying.

- Cost: roughly $250–400/year, plus a hardware token.
- Reputation: SmartScreen warns until the certificate accumulates download volume (weeks to months, no fixed number, and Microsoft does not publish the threshold). Timestamping is what keeps already-signed binaries valid after the certificate expires — hence `timestampUrl` and `tsp: true` above (DigiCert's RFC 3161 endpoint, which every mainstream CA accepts).

### Windows — EV certificate (do not buy for this)

Extended Validation is the only Windows certificate with **immediate** SmartScreen reputation. It also requires the private key to live on a FIPS 140-2 Level 2 HSM that you cannot export — for CI that means a cloud HSM (Azure Key Vault, DigiCert Secure App Service), so you are paying EV prices *and* a service fee.

Recommendation: **Azure Trusted Signing.** It is cheaper than EV, has no hardware, and gives the same SmartScreen treatment as OV. Only buy EV if a corporate procurement requirement demands the "EV" label.

### macOS — Developer ID Application (the only option)

Requires a paid Apple Developer Program membership ($99/year, organisation recommended so the publisher name is the company and not an individual).

1. Create a **Developer ID Application** certificate in the Apple Developer portal (Certificates, Identifiers & Profiles). Not "Apple Development", not "Mac Installer Distribution" — the updater and Gatekeeper both want `Developer ID Application`.
2. Download and install it into the login keychain, then export it **with its private key** as a `.p12` protected by a strong password.
3. Base64 the `.p12` (`base64 -i cert.p12 | pbcopy`) — the secret value is one enormous line, which is expected.
4. Notarisation needs an app-specific password (not your Apple ID password): appleid.apple.com → Sign-In and Security → App-Specific Passwords.

Notarisation is not optional. A Developer ID signature without a notarisation ticket still trips Gatekeeper's "Apple could not verify this software is free of malware" wall on first launch. The workflow supplies `APPLE_ID` + `APPLE_PASSWORD` + `APPLE_TEAM_ID`, which is the signal Tauri's bundler uses to submit to `notarytool` and staple the ticket.

### Linux — nothing

`.AppImage`, `.deb` and `.rpm` are shipped unsigned. There is no comparable gatekeeper; the verification story is the `SHA256SUMS.txt` asset the `desktop-checksums` fan-in job publishes. This is a decision, not an omission, and the workflow's preflight explicitly skips the Linux leg so it does not pretend otherwise.

## Secrets to create

Repository → Settings → Secrets and variables → Actions. Names are exact; Tauri's bundler reads the macOS ones by name, and the workflow reads the Windows ones by name.

| Secret | Platform | Value |
| --- | --- | --- |
| `APPLE_CERTIFICATE` | macOS | base64 of the exported Developer ID `.p12` (single line) |
| `APPLE_CERTIFICATE_PASSWORD` | macOS | the `.p12` export password |
| `APPLE_SIGNING_IDENTITY` | macOS | `Developer ID Application: <Company Name> (<TEAMID>)` — copy it verbatim from `security find-identity -v -p codesigning` |
| `APPLE_ID` | macOS | Apple ID email of the developer account |
| `APPLE_PASSWORD` | macOS | app-specific password from appleid.apple.com |
| `APPLE_TEAM_ID` | macOS | 10-character Team ID from the Apple Developer portal |
| `WINDOWS_CERTIFICATE` | Windows | base64 of the `.pfx` **with private key** |
| `WINDOWS_CERTIFICATE_PASSWORD` | Windows | the `.pfx` password |

Never commit any of these. Never paste a `.pfx`, `.p12`, or password into an issue, a task file, or a chat. The workflow passes them through `env:` only; `WINDOWS_CERTIFICATE` is written to `$RUNNER_TEMP` and deleted immediately after import.

Notarisation output worth keeping: `xcrun notarytool log <submission-id>` — run it if notarisation fails, it names the exact rejection reason (missing hardened runtime, unsigned nested binary, invalid entitlement).

## What the CI guard does

`release-builds.yml` → step **Verify desktop code-signing secrets** runs before the Rust toolchain is even set up:

- On `macOS`: requires all six `APPLE_*` secrets. Fails with `::error::Secret APPLE_X is missing or empty. ...`, naming what to set and why.
- On `Windows`: requires both `WINDOWS_CERTIFICATE*` secrets.
- On `linux`: prints a one-line notice and exits 0.

Why fail rather than warn: an unsigned `.dmg` or `.exe` uploads to the release perfectly. The job goes green, the release looks complete, the download page links to it — and every Windows user gets "Windows protected your PC" and every macOS user gets "damaged and can't be opened", while the macOS **updater** silently cannot install the update at all. That is a false green, and the audit found exactly that state. Same rationale as the existing `ANDROID_KEYSTORE_BASE64` guard.

## Verifying a signed artefact

Run these on the **downloaded artefact**, not the build output — that is the file a user gets.

### Windows

```powershell
# The signature must be present, valid, and chain to a trusted root.
signtool verify /pa /v "Nexo.Desktop_0.7.0_x64-setup.exe"
signtool verify /pa /v "Nexo.Desktop_0.7.0_x64_en-US.msi"

# Who signed it, and when.
Get-AuthenticodeSignature "Nexo.Desktop_0.7.0_x64-setup.exe" |
  Format-List Status, StatusMessage, SignerCertificate, TimeStamperCertificate
```

Expected: `Status: Valid`, `SignerCertificate.Subject` containing the publisher organisation, and **a non-empty `TimeStamperCertificate`** (an un-timestamped signature dies with the certificate and users start seeing "unknown publisher" on an unchanged binary).

### macOS

```bash
# Signature, authority chain, hardened runtime, timestamp.
codesign -dv --verbose=4 "Nexo Desktop.app"
# Expected: Authority=Developer ID Application: <Company> (<TEAMID>)
#           Authority=Developer ID Certification Authority
#           Authority=Apple Root CA
#           flags=0x10000(runtime)          <- hardened runtime
#           Timestamp=<a date>              <- not the "none" that means un-timestamped

# Gatekeeper verdict, as the OS would apply it on a fresh machine.
spctl -a -vv --type execute "Nexo Desktop.app"
# Expected: accepted   source=Notarized Developer ID
#
# NOT "accepted source=Developer ID" — that string means signed but NOT
# notarized, which still warns on first launch.

# Notarisation ticket is actually stapled to the .dmg users download.
xcrun stapler validate "Nexo.Desktop_0.7.0_universal.dmg"
# Expected: The validate action worked!

# Deep structural check: catches unsigned nested binaries that a shallow
# check misses.
codesign --verify --deep --strict --verbose=2 "Nexo Desktop.app"
```

The updater downloads a `.app.tar.gz`, not the `.dmg`. Check that too — it is the file `plugin-updater` installs, and it is signed separately by the bundler.

## Expected user-visible behaviour

| When | Windows SmartScreen | macOS Gatekeeper |
| --- | --- | --- |
| Certificates absent (today) | "Windows protected your PC — Unknown publisher". Requires "More info" → "Run anyway". | "NEXO Desktop is damaged and can't be opened" (translocated quarantine) or "cannot be opened because the developer cannot be verified". Right-click → Open does not fix the "damaged" variant. |
| Certificates present, first weeks | "Windows protected your PC" **still shown**, but the publisher line now reads the company name instead of "Unknown publisher". Reputation is per-certificate and starts at zero. | First launch: "NEXO Desktop was downloaded from the internet. Are you sure you want to open it?" — one confirmation, then clean. `spctl` says `accepted`. |
| Reputation built up (weeks–months, volume-dependent) | Prompt disappears entirely. Microsoft publishes no threshold. | Unchanged — already clean from the first notarised build. |
| Certificate expires, binary was **not** timestamped | Prompt returns on the *unchanged* binary. | Signature fails verification on binaries signed after expiry. |
| Certificate expires, binary **was** timestamped | Prompt does not return. | Notarised signatures stay valid indefinitely. |

The practical read: **macOS signing is a binary switch** — buy the certificate and the wall is gone. **Windows signing is a reputation ramp** — buying the certificate changes the publisher name on the warning, then the warning takes weeks to disappear. Do not schedule a "signing done" celebration for Windows on release day; schedule a check three months later.

## Operator actions (none of this is done)

Brutally: **not one line of real signing happens until the following is completed by a human with access to a payment method and an Apple account.**

1. Buy a certificate. Windows: Azure Trusted Signing account + certificate profile (recommended). macOS: Apple Developer Program membership + Developer ID Application certificate.
2. Create the eight repository secrets listed above. The workflow fails at the preflight until every one exists for its platform.
3. If Azure Trusted Signing is chosen over an OV `.pfx`, replace the `Prepare the Windows signing config patch` step and the two `WINDOWS_CERTIFICATE*` secrets with a `bundle.windows.signCommand` — the current workflow expects a `.pfx` and will keep failing until it gets one.
4. Cut a release tag and confirm the artefacts verify with the commands above. Until that happens, `docs/desktop-installers.md` stays full of `not run`.
5. Watch Windows SmartScreen over the following months and record when the prompt stops appearing.
