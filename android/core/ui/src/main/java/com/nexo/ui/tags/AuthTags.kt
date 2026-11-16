package com.nexo.ui.tags

/** Stable testTag contract for Maestro E2E. */
object AuthTags {
    const val EMAIL = "auth_email_field"
    const val PASSWORD = "auth_password_field"
    const val FULLNAME = "auth_fullname_field"
    const val SIGNIN = "auth_signin_button"
    const val SIGNUP = "auth_signup_button"
    const val SEND_RESET = "auth_send_reset_button"
    const val GOOGLE = "auth_google_button"

    /** Toggles the inline email/password form on the login screen. */
    const val EMAIL_TOGGLE = "auth_email_toggle_button"
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
