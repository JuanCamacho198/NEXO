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

use super::map_command_error;
use crate::error::AppError;

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn protectSecret(plaintext: String) -> Result<String, String> {
    protect_secret_inner(&plaintext).map_err(map_command_error)
}

#[allow(non_snake_case)]
#[tauri::command(rename_all = "camelCase")]
pub fn unprotectSecret(ciphertext: String) -> Result<String, String> {
    unprotect_secret_inner(&ciphertext).map_err(map_command_error)
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

    pub(super) fn protect(_plaintext: &[u8]) -> Result<Vec<u8>, AppError> {
        Err(AppError::InvalidInput("secret protection requires Windows DPAPI".to_string()))
    }

    pub(super) fn unprotect(_ciphertext: &[u8]) -> Result<Vec<u8>, AppError> {
        Err(AppError::InvalidInput("secret protection requires Windows DPAPI".to_string()))
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
}
