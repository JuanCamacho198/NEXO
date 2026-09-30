package com.nexo.presentation.screen

/** Stable testTag contract for Maestro E2E. Single source for auth + home anchors. */
object AuthTags {
    const val EMAIL = "auth_email_field"
    const val PASSWORD = "auth_password_field"
    const val FULLNAME = "auth_fullname_field"
    const val SIGNIN = "auth_signin_button"
    const val SIGNUP = "auth_signup_button"
    const val SEND_RESET = "auth_send_reset_button"
    const val GOOGLE = "auth_google_button"
    const val FORGOT_LINK = "auth_forgot_link"
    const val REGISTER_LINK = "auth_register_link"
    const val LOGIN_LINK = "auth_login_link"
    const val BACK = "auth_back_button"
    const val ERROR = "auth_error_text"
    const val LOADING = "auth_loading_indicator"
    const val GOOGLE_MOCK = "auth_google_mock_button"
    const val DEV_BYPASS = "auth_dev_bypass"
    const val GOOGLE_DISABLED_REASON = "auth_google_disabled_reason"
}

object HomeTags {
    const val SCREEN_ROOT = "home_screen_root"
    const val GREETING = "home_greeting"
}

object NavTags {
    const val HOME = "bottom_nav_home"
    const val LIBRARY = "bottom_nav_library"
    const val HIGHLIGHTS = "bottom_nav_highlights"
    const val SETTINGS = "bottom_nav_settings"
}
