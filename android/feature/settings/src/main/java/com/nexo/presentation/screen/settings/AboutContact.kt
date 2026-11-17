package com.nexo.presentation.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Where the About screen's "Contact" row sends you.
 *
 * NEXO ships outside any store, so there is no listing page: e-mail is the
 * support channel for users of a released APK, and it is the address the
 * privacy policy names for data requests. Building the intent here (instead of
 * embedding a `mailto:` at the call site) keeps the destination in one place
 * and testable.
 */
object AboutContact {
    const val SUPPORT_EMAIL = "nexosupport@gmail.com"

    /** Issue tracker, kept for bug reports that need a public thread. */
    const val ISSUE_TRACKER_URL = "https://github.com/JuanCamacho198/NEXO/issues"

    /**
     * An intent that opens the support address in a mail app, or null when the
     * device has nothing that can handle it, which is the signal the About row
     * uses to stay inert instead of offering a dead tap.
     */
    fun openSupportMailIntent(context: Context): Intent? {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /**
     * An intent that opens [ISSUE_TRACKER_URL] in a browser, or null when the
     * device has nothing that can handle it.
     */
    fun openIssueTrackerIntent(context: Context): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_TRACKER_URL))
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }
}
