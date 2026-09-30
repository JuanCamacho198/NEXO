package com.nexo.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexo.R
import com.nexo.ui.icons.NexoIcons

sealed class NexoDestination(
    val route: String,
    @param:StringRes val labelRes: Int = -1,
    val icon: ImageVector? = null,
) {
    data object Auth : NexoDestination("auth", R.string.tab_auth, NexoIcons.Person)

    data object AuthRegister : NexoDestination("auth/register")

    data object AuthForgot : NexoDestination("auth/forgot")

    data object OnboardingGoal : NexoDestination("onboarding/goal")

    data object Home : NexoDestination("home", R.string.nav_home, NexoIcons.Home)

    data object Library : NexoDestination("library", R.string.nav_library, NexoIcons.Library)

    data object Discover : NexoDestination("discover", R.string.nav_discover, NexoIcons.Search)

    // Reader and "Ver todo" DiscoverSection are typed `@Serializable` routes:
    // see `ReaderRoute` / `DiscoverSectionRoute` in TypedRoutes.kt.

    data object Highlights : NexoDestination("highlights", R.string.nav_highlights, NexoIcons.Highlights)

    data object Settings : NexoDestination("settings", R.string.nav_settings, NexoIcons.Settings)

    data object Statistics : NexoDestination("statistics", R.string.nav_statistics, NexoIcons.Statistics)

    data object BookDetail : NexoDestination("book_detail/{bookId}", R.string.nav_book_detail, NexoIcons.Book)

    data object BookEdit : NexoDestination("book_edit/{bookId}")

    // Settings nested destinations
    data object SettingsList : NexoDestination("settings/list")

    data object SettingsAccount : NexoDestination("settings/account")

    data object SettingsDataStorage : NexoDestination("settings/data")

    data object SettingsStorage : NexoDestination("settings/storage", R.string.settings_storage_title, NexoIcons.Storage)

    data object SettingsSync : NexoDestination("settings/sync", R.string.settings_sync_title, NexoIcons.CloudSync)

    data object SettingsNotifications : NexoDestination("settings/notifications")

    data object SettingsTheme : NexoDestination("settings/theme")

    data object SettingsLanguage : NexoDestination("settings/language")

    data object SettingsPalette : NexoDestination("settings/palette")

    data object SettingsAbout : NexoDestination("settings/about")

    data object SettingsStatistics : NexoDestination("settings/data/statistics")

    data object SettingsDictionary : NexoDestination(
        "settings/dictionary",
        R.string.settings_dictionary_label,
        NexoIcons.LibraryBooks,
    )

    data object Dictionary : NexoDestination("settings/dictionary", R.string.settings_dictionary_label, NexoIcons.LibraryBooks)

    data object SettingsDevices : NexoDestination("settings/devices")

    data object SettingsDailyGoal : NexoDestination("settings/daily-goal")

    data object SettingsPerformance : NexoDestination("settings/performance")

    data object SettingsAddons : NexoDestination("settings/addons", R.string.settings_addons_title, NexoIcons.LibraryBooks)

    /** U5: legal policy page, reachable from the disclaimer and addon screens. */
    data object SettingsLegal : NexoDestination("settings/legal", R.string.legal_policy_title, NexoIcons.LibraryBooks)

    /** U5: per-addon capability detail; `{addonId}` is the registry id (hex). */
    data object SettingsAddonCapabilities : NexoDestination("settings/addon-capabilities/{addonId}")

    data object LogViewer : NexoDestination("settings/log-viewer")
}
