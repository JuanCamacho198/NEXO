package com.nexo.presentation.viewmodel.reader

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.nexo.domain.translation.TranslationPolicy
import java.util.Locale

/**
 * The language of the real DEVICE, immune to the app's own UI-language override.
 *
 * ## Why this exists (the measured defect)
 *
 * "Follow the device" resolution read `Locale.getDefault()`. `SettingsLanguageScreen`
 * calls `AppCompatDelegate.setApplicationLocales(...)` when the reader changes the
 * app's display language, and that call OVERRIDES `Locale.getDefault()` for the
 * whole process. So the app's "follow the device" target was really "follow the
 * app's UI language": measured on device `en-US` with the app locale set to `[es]`,
 * a Spanish book resolved its source to `es` and its target to `es`, the pair was a
 * self-pair, and the reader got a refusal with no network call — while the phone
 * was English and the translation was perfectly possible.
 *
 * ## Which API is actually immune (MEASURED, not assumed)
 *
 * The first candidate was `Resources.getSystem().configuration`. It is NOT immune.
 * Measured on this project's AVD (Android 15, API 35) with device `en-US` and app
 * locale `[es]`: `Resources.getSystem().configuration.locales` returned
 * `[es, en_US]` — the per-app override had leaked into the "system" resources and
 * the FIRST entry (what a naive reader takes) was still `es`. The candidate would
 * have reproduced the bug it was meant to fix.
 *
 * The API that IS immune: **`LocaleManager.getSystemLocales()`** (framework,
 * Android 13 / API 33+), read through the app's `Context`. It is the platform's own
 * answer to "what locale is this DEVICE set to", managed by the system server
 * independently of any app's per-app locale, and it returned `[en_US]` in the same
 * measurement where the resources-based read returned `[es, en_US]`.
 *
 * On API < 33 there is no `LocaleManager`, and `setApplicationLocales` there is
 * implemented by AppCompat by rebuilding the app's own resources — it does not
 * rewrite the system resources singleton the way API 33+ does. `Resources.getSystem()`
 * is the fallback for those versions only.
 *
 * ## Why the read is split from the decision
 *
 * [languageTag] takes the locale list as an input so the decision is testable in a
 * plain JVM test: `Configuration` and `LocaleList` are Android framework stubs that
 * throw outside Robolectric, and booting Robolectric would start the app's
 * WorkManager and leak its Room thread. The Android-specific acquisition lives in
 * [deviceLocales] and is deliberately the thinnest possible shell over the platform.
 */
internal object DeviceLocale {
    /**
     * The device's language tag (`"en-US"`), or [fallback] when the platform
     * reports nothing usable.
     *
     * @param systemLocales the DEVICE's locale list, most specific first; defaults
     *   to the real device configuration via [deviceLocales].
     * @param fallback used for a blank or missing tag. Defaults to the policy's
     *   own last resort so an unusable device locale still yields a language.
     */
    fun languageTag(
        systemLocales: List<Locale> = deviceLocales(),
        fallback: String = TranslationPolicy.DEFAULT_LANGUAGE,
    ): String = systemLocales.firstOrNull()?.toLanguageTag().orEmpty().ifBlank { fallback }

    /**
     * The device's locale list, from the platform locale service — never the app's.
     *
     * API 33+ has `LocaleManager`, the framework's own per-device locale source,
     * which no per-app override can move. Below 33 it does not exist, and the
     * AppCompat override there does not contaminate the system resources singleton,
     * so `Resources.getSystem()` is a correct fallback for those versions.
     */
    fun deviceLocales(context: Context? = null): List<Locale> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context != null) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.let(::asList)
                ?: resourcesLocales()
        } else {
            resourcesLocales()
        }

    private fun resourcesLocales(): List<Locale> {
        val locales = Resources.getSystem().configuration.locales
        return (0 until locales.size()).map { locales[it] }
    }

    private fun asList(locales: android.os.LocaleList): List<Locale> = (0 until locales.size()).map { locales[it] }
}
