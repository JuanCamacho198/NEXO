package com.nexo.data.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.nexo.domain.model.DeviceInfo
import java.util.UUID

/**
 * Reads the device identity from the Android runtime and maps it into the pure
 * domain [DeviceInfo] model.
 *
 * Lives in the data layer (not `domain/model`) so the project's own `domain`
 * layer carries no `android.*` dependency — enforced by `DomainPurityTest`
 * (SDD android-stack-modernization S3, R7). Behavior is unchanged from the
 * former `com.nexo.domain.model.detectDeviceInfo`.
 */
fun detectDeviceInfo(context: Context): DeviceInfo {
    val hardwareId =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: UUID.randomUUID().toString()

    return DeviceInfo(
        hardwareId = hardwareId,
        name = Build.MODEL,
        os = "Android ${Build.VERSION.RELEASE}",
        type = "mobile",
    )
}
