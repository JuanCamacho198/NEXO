package com.nexo.data.remote.addons

import java.security.MessageDigest

/** addonId = first 16 hex chars of sha256(url). Must match desktop addonId.ts byte-for-byte. */
object AddonId {
    fun fromUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(16)
    }
}
