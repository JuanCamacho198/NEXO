package com.nexo.data.remote.catalog

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Package name + signing-cert SHA-1 that Google needs for a restricted key. */
data class AndroidAppIdentity(
    val packageName: String,
    val certSha1: String,
)

/**
 * Reads this app's own package name and signing-certificate SHA-1 so a
 * Google-API key restricted to "Android apps" can be matched to its
 * package + fingerprint. Returns null on any failure — the caller then omits
 * the identity headers, which is correct for unrestricted/server keys and
 * keeps the fail-closed catalog path intact.
 */
fun androidAppIdentityOrNull(context: Context): AndroidAppIdentity? = try {
    val packageName = context.packageName
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            .signatures
    }
    val cert = signatures?.firstOrNull()?.toByteArray()
    if (packageName.isBlank() || cert == null) {
        null
    } else {
        AndroidAppIdentity(
            packageName = packageName,
            certSha1 = MessageDigest.getInstance("SHA-1")
                .digest(cert)
                .joinToString("") { "%02x".format(it) },
        )
    }
} catch (_: Throwable) {
    null
}
