//! DPAPI-backed secret protection (0.3.5 secrets encryption).
//!
//! Threat model: stolen laptop / disk removed. Secrets at rest are encrypted
//! with Windows DPAPI (`CryptProtectData`, `CurrentUser` scope), so the bytes
//! on disk are unreadable without this Windows user's logon credentials. The
//! ciphertext is hex-encoded for JSON storage; the JSON envelope itself
//! (`{ v, alg, data }`) is assembled on the TypeScript side (`secretVault.ts`).
//!
//! Malware running as the same user is explicitly OUT of scope: DPAPI by
//! design decrypts for any process running as the user, so no attempt is made
//! here to defend against it.
//!
//! Non-Windows builds ship as `.dmg`/`.AppImage`/`.deb`/`.rpm` and have no
//! DPAPI. They do NOT get a different sealing scheme here: the frontend
//! contract is "protectSecret returns the on-disk sealed bytes", and an OS
//! keyring would return a handle instead of ciphertext — which breaks the
//! legacy-migration path in `supabase.ts` that re-seals a plaintext session
//! file by feeding the bytes it just read back into `protectSecret`. So on
//! those platforms protection is declared UNAVAILABLE: `protectSecret` fails
//! with an explicit error, the frontend's seal-before-disk rule turns that into
//! "session not persisted", and [`startup_diagnostic`] gives the app a
//! user-visible reason at boot instead of a per-call runtime failure.

use super::CommandError;
use crate::error::AppError;

/// Service/account names a keyring backend would use, if one is ever wired in.
/// They are part of the stored-secret identity: changing them orphans every
/// secret already written.
pub const KEYRING_SERVICE: &str = "nexo-desktop";
pub const KEYRING_ACCOUNT: &str = "secret-vault";

/// Whether this build can seal secrets at rest, as seen by the frontend.
///
/// Read-only and argument-free: it reads the same [`secret_protection_available`]
/// flag that [`protectSecret`] consults, so the boot banner and the per-call
/// rejection can never disagree. `false` is not an error — the app runs fully,
/// only session persistence across launches is lost.
#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn secretProtectionAvailable() -> bool {
    secret_protection_available()
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn protectSecret(plaintext: String) -> Result<String, CommandError> {
    protect_secret_inner(&plaintext).map_err(CommandError::from)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn unprotectSecret(ciphertext: String) -> Result<String, CommandError> {
    unprotect_secret_inner(&ciphertext).map_err(CommandError::from)
}

fn protect_secret_inner(plaintext: &str) -> Result<String, AppError> {
    let cipher = platform::protect(plaintext.as_bytes())?;
    Ok(hex_encode(&cipher))
}

fn unprotect_secret_inner(ciphertext: &str) -> Result<String, AppError> {
    let cipher = hex_decode(ciphertext)?;
    let plain = platform::unprotect(&cipher)?;
    String::from_utf8(plain)
        .map_err(|_| AppError::InvalidInput("secret payload is not valid UTF-8".to_string()))
}

/// True when this build can actually seal secrets at rest.
pub fn secret_protection_available() -> bool {
    platform::AVAILABLE
}

/// Startup diagnostic text for a build that cannot seal secrets, or `None`
/// when protection works. It reads the same flag the IPC rejection reads, so
/// the boot path and the per-call error can never disagree.
pub fn startup_diagnostic() -> Option<String> {
    startup_diagnostic_for(secret_protection_available())
}

fn startup_diagnostic_for(protection_available: bool) -> Option<String> {
    if protection_available {
        return None;
    }
    Some(format!(
        "Secret protection is unavailable on this platform ({}), so NEXO cannot seal stored \
         secrets. Your Supabase session is not persisted: it lives in memory for this run and \
         you will need to sign in again after quitting.",
        std::env::consts::OS
    ))
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0F) as usize] as char);
    }
    out
}

fn hex_decode(hex: &str) -> Result<Vec<u8>, AppError> {
    fn nibble(code: u8) -> Result<u8, AppError> {
        match code {
            b'0'..=b'9' => Ok(code - b'0'),
            b'a'..=b'f' => Ok(code - b'a' + 10),
            b'A'..=b'F' => Ok(code - b'A' + 10),
            _ => Err(AppError::InvalidInput("secret payload is not valid hex".to_string())),
        }
    }

    let digits = hex.as_bytes();
    if !digits.len().is_multiple_of(2) {
        return Err(AppError::InvalidInput("secret payload is not valid hex".to_string()));
    }
    let mut out = Vec::with_capacity(digits.len() / 2);
    for pair in digits.chunks_exact(2) {
        out.push((nibble(pair[0])? << 4) | nibble(pair[1])?);
    }
    Ok(out)
}

#[cfg(windows)]
mod platform {
    use super::AppError;
    use std::ffi::c_void;
    use std::ptr;

    /// DPAPI (`CurrentUser`) ships with every supported Windows build, so
    /// protection never reports itself as unavailable there.
    pub(super) const AVAILABLE: bool = true;

    #[repr(C)]
    struct DataBlob {
        cb_data: u32,
        pb_data: *mut u8,
    }

    const CRYPTPROTECT_UI_FORBIDDEN: u32 = 0x1;

    #[link(name = "crypt32")]
    unsafe extern "system" {
        fn CryptProtectData(
            p_data_in: *const DataBlob,
            sz_data_descr: *const u16,
            p_optional_entropy: *const DataBlob,
            pv_reserved: *mut c_void,
            p_prompt_struct: *mut c_void,
            dw_flags: u32,
            p_data_out: *mut DataBlob,
        ) -> i32;
        fn CryptUnprotectData(
            p_data_in: *const DataBlob,
            ppsz_data_descr: *mut *mut u16,
            p_optional_entropy: *const DataBlob,
            pv_reserved: *mut c_void,
            p_prompt_struct: *mut c_void,
            dw_flags: u32,
            p_data_out: *mut DataBlob,
        ) -> i32;
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn LocalFree(hmem: *mut c_void) -> *mut c_void;
    }

    fn failed() -> AppError {
        AppError::InvalidInput("DPAPI operation failed".to_string())
    }

    pub(super) fn protect(plaintext: &[u8]) -> Result<Vec<u8>, AppError> {
        let len = u32::try_from(plaintext.len())
            .map_err(|_| AppError::InvalidInput("secret is too large".to_string()))?;
        if len == 0 {
            return Err(AppError::InvalidInput("cannot protect an empty secret".to_string()));
        }
        // SAFETY: blobs point at live allocations for the duration of the call;
        // the output blob is copied out before LocalFree.
        unsafe {
            let blob_in = DataBlob { cb_data: len, pb_data: plaintext.as_ptr() as *mut u8 };
            let mut blob_out = DataBlob { cb_data: 0, pb_data: ptr::null_mut() };
            let ok = CryptProtectData(
                &blob_in,
                ptr::null(),
                ptr::null(),
                ptr::null_mut(),
                ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut blob_out,
            );
            if ok == 0 || blob_out.pb_data.is_null() {
                return Err(failed());
            }
            let cipher =
                std::slice::from_raw_parts(blob_out.pb_data, blob_out.cb_data as usize).to_vec();
            LocalFree(blob_out.pb_data as *mut c_void);
            Ok(cipher)
        }
    }

    pub(super) fn unprotect(ciphertext: &[u8]) -> Result<Vec<u8>, AppError> {
        let len = u32::try_from(ciphertext.len())
            .map_err(|_| AppError::InvalidInput("secret is too large".to_string()))?;
        if len == 0 {
            return Err(AppError::InvalidInput("cannot unprotect an empty secret".to_string()));
        }
        // SAFETY: same contract as protect. No description string is requested
        // (null out-pointer), so only the data blob needs LocalFree.
        unsafe {
            let blob_in = DataBlob { cb_data: len, pb_data: ciphertext.as_ptr() as *mut u8 };
            let mut blob_out = DataBlob { cb_data: 0, pb_data: ptr::null_mut() };
            let ok = CryptUnprotectData(
                &blob_in,
                ptr::null_mut(),
                ptr::null(),
                ptr::null_mut(),
                ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut blob_out,
            );
            if ok == 0 || blob_out.pb_data.is_null() {
                return Err(failed());
            }
            let plain =
                std::slice::from_raw_parts(blob_out.pb_data, blob_out.cb_data as usize).to_vec();
            LocalFree(blob_out.pb_data as *mut c_void);
            Ok(plain)
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use super::AppError;

    /// No DPAPI outside Windows. The keyring alternative was evaluated and
    /// rejected: it stores the secret itself, so `protect` would have to return
    /// a handle rather than ciphertext, and `secretVault.ts` cannot unseal a
    /// handle. Reporting the capability as missing is the honest option that
    /// keeps the IPC contract unchanged.
    pub(super) const AVAILABLE: bool = false;

    fn unavailable() -> AppError {
        AppError::InvalidInput(format!(
            "secret protection is unavailable on this platform ({}): the Windows DPAPI key \
             store is not present",
            std::env::consts::OS
        ))
    }

    pub(super) fn protect(_plaintext: &[u8]) -> Result<Vec<u8>, AppError> {
        Err(unavailable())
    }

    pub(super) fn unprotect(_ciphertext: &[u8]) -> Result<Vec<u8>, AppError> {
        Err(unavailable())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_round_trip() {
        let bytes = vec![0x00, 0x0f, 0x10, 0xab, 0xcd, 0xef, 0xff];
        assert_eq!(hex_decode(&hex_encode(&bytes)).unwrap(), bytes);
    }

    #[test]
    fn hex_decode_rejects_odd_length() {
        assert!(hex_decode("abc").is_err());
    }

    #[test]
    fn hex_decode_rejects_non_hex() {
        assert!(hex_decode("zz").is_err());
        assert!(hex_decode("0g").is_err());
    }

    #[test]
    fn protect_rejects_empty_input() {
        assert!(protect_secret_inner("").is_err());
    }

    #[test]
    fn unprotect_rejects_non_hex() {
        assert!(unprotect_secret_inner("not-hex!!").is_err());
    }

    #[cfg(windows)]
    #[test]
    fn dpapi_round_trip() {
        let plaintext = "nexo-test-secret-value";
        let ciphertext = protect_secret_inner(plaintext).unwrap();
        assert_ne!(ciphertext, plaintext);
        assert_eq!(unprotect_secret_inner(&ciphertext).unwrap(), plaintext);
    }

    #[cfg(windows)]
    #[test]
    fn dpapi_unprotect_rejects_tampered_ciphertext() {
        let mut ciphertext = protect_secret_inner("tamper-me").unwrap();
        let last = ciphertext.pop().unwrap();
        ciphertext.push(if last == '0' { '1' } else { '0' });
        assert!(unprotect_secret_inner(&ciphertext).is_err());
    }

    #[test]
    fn keyring_names_are_stable() {
        assert_eq!(KEYRING_SERVICE, "nexo-desktop");
        assert_eq!(KEYRING_ACCOUNT, "secret-vault");
    }

    // T3: a build without a sealing backend must never report success that the
    // frontend then writes to disk as a sealed file. Either the round-trip
    // works or the call fails and says why.
    #[test]
    fn protection_availability_matches_platform_capability() {
        if secret_protection_available() {
            let ciphertext = protect_secret_inner("nexo-platform-secret").unwrap();
            assert_ne!(ciphertext, "nexo-platform-secret");
            assert_eq!(unprotect_secret_inner(&ciphertext).unwrap(), "nexo-platform-secret");
        } else {
            let err = protect_secret_inner("nexo-platform-secret").unwrap_err();
            assert!(
                err.to_string().contains("secret protection is unavailable"),
                "unexpected error: {err}"
            );
            assert!(unprotect_secret_inner("00").is_err());
        }
    }

    // Pure decision logic, host-independent: the part of the non-Windows
    // behaviour that can be proven on any host.
    #[test]
    fn startup_diagnostic_for_unprotected_host_is_explicit() {
        let diagnostic = startup_diagnostic_for(false).expect("must produce a diagnostic");
        assert!(diagnostic.contains("not persisted"));
        assert!(diagnostic.contains(std::env::consts::OS));
        assert!(startup_diagnostic_for(true).is_none());
    }

    #[test]
    fn startup_diagnostic_tracks_protection_availability() {
        assert_eq!(startup_diagnostic().is_some(), !secret_protection_available());
    }

    // The IPC surface the frontend banner reads must answer with the same flag
    // the per-call rejection uses, or the banner and the real behaviour drift.
    #[test]
    fn ipc_availability_matches_per_call_path() {
        assert_eq!(secretProtectionAvailable(), secret_protection_available());
        let sealing_works = protect_secret_inner("nexo-ipc-probe").is_ok();
        assert_eq!(secretProtectionAvailable(), sealing_works);
    }
}
